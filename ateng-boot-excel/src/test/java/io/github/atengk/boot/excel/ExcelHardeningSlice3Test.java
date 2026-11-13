package io.github.atengk.boot.excel;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.excel.async.ExportTask;
import io.github.atengk.boot.excel.async.ExportTaskStatus;
import io.github.atengk.boot.excel.async.InMemoryAsyncExportTaskService;
import io.github.atengk.boot.excel.async.storage.LocalFileSystemStorage;
import io.github.atengk.boot.excel.exception.ExcelHeaderValidationException;
import io.github.atengk.boot.excel.exception.ExcelValidationException;
import io.github.atengk.boot.excel.model.DemoUser;
import io.github.atengk.boot.excel.model.ExcelErrorRow;
import io.github.atengk.boot.excel.model.FillItemVO;
import io.github.atengk.boot.excel.model.ImportUserVO;
import io.github.atengk.boot.excel.properties.ExcelProperties;
import io.github.atengk.boot.excel.streaming.StreamingPageExportTemplate;
import io.github.atengk.boot.excel.util.ExcelSecurityUtil;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.excel.util.ExcelWebUtil;
import io.github.atengk.boot.excel.enums.HeaderValidationStrategy;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.event.AnalysisEventListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Excel 读写加密、多 Sheet 校验与 Web 对称门面切片 3 对抗性单元测试（#944）
 *
 * @author Ateng
 * @since 2026-11-13
 */
@DisplayName("Excel 能力完备性切片 3：流式/构建器/填充/动态加密与多表校验及 Web 对称门面")
class ExcelHardeningSlice3Test {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        ExcelUtil.initProperties(new ExcelProperties());
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("切片 1：StreamingPageExportTemplate 应支持 ReentrantLock 并发安全、进度回调与 AES-256 整工作簿加密及零临时文件残留")
    void shouldSupportStreamingProgressEncryptionAndZeroTempLeak() throws Exception {
        Path managedTmp = Files.createDirectories(tempDir.resolve("managed-tmp"));
        Path storageRoot = Files.createDirectories(tempDir.resolve("storage-root"));

        ExcelProperties props = new ExcelProperties();
        props.setTempDir(managedTmp.toString());
        props.getAsyncExport().setTempDir(storageRoot.toString());
        ExcelUtil.initProperties(props);

        // 1. 分页流式 + 实时进度回调 + AES-256 加密
        List<Integer> progressTimeline = new CopyOnWriteArrayList<>();
        ByteArrayOutputStream encryptedOut = new ByteArrayOutputStream();
        try (StreamingPageExportTemplate<DemoUser> template = ExcelUtil.streaming(encryptedOut, DemoUser.class)
                .encrypt("Stream#2026")
                .onProgress(progressTimeline::add)) {
            long total = template.exportByPage(page -> {
                if (page > 2) {
                    return PageResult.of(List.of(), 4L, page, 2);
                }
                List<DemoUser> rows = List.of(
                        DemoUser.builder().id((long) page * 2 - 1).username("user-" + page + "-a").age(20 + page).build(),
                        DemoUser.builder().id((long) page * 2).username("user-" + page + "-b").age(30 + page).build()
                );
                return PageResult.of(rows, 4L, page, 2);
            });
            assertEquals(4L, total);
        }

        assertFalse(progressTimeline.isEmpty());
        assertEquals(100, progressTimeline.get(progressTimeline.size() - 1));
        assertTrue(progressTimeline.stream().anyMatch(p -> p > 0 && p < 100), "导出过程应上报中间进度 (1~99)");

        byte[] encryptedBytes = encryptedOut.toByteArray();
        assertTrue(ExcelSecurityUtil.isEncryptedWorkbook(new ByteArrayInputStream(encryptedBytes)), "流式输出必须为 OLE2 AES-256 加密工作簿");
        assertThrows(ExcelValidationException.class,
                () -> ExcelUtil.read(new ByteArrayInputStream(encryptedBytes), DemoUser.class, "wrong-pwd"));

        List<DemoUser> decrypted = ExcelUtil.read(new ByteArrayInputStream(encryptedBytes), DemoUser.class, "Stream#2026");
        assertEquals(4, decrypted.size());
        assertEquals("user-1-a", decrypted.get(0).getUsername());
        assertEquals("user-2-b", decrypted.get(3).getUsername());

        try (var stream = Files.list(managedTmp)) {
            assertEquals(0, stream.count(), "加密完成后临时明文缓冲文件必须 100% 释放");
        }

        // 2. 多线程 ReentrantLock 并发 writeBatch 压测 + onProgressDetail + 写后 encrypt 状态防护 + 异常清理
        List<String> detailEvents = new CopyOnWriteArrayList<>();
        ByteArrayOutputStream concurrentOut = new ByteArrayOutputStream();
        try (StreamingPageExportTemplate<DemoUser> concurrentTpl = ExcelUtil.streaming(concurrentOut, DemoUser.class)
                .expectedTotalRows(20)
                .encrypt("Concurrent#2026")
                .onProgressDetail((pct, rows) -> detailEvents.add(pct + ":" + rows))) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch startGate = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < 2; t++) {
                    final int threadIdx = t;
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        assertTrue(startGate.await(3, TimeUnit.SECONDS));
                        for (int b = 0; b < 5; b++) {
                            long baseId = threadIdx * 100L + b * 2L;
                            concurrentTpl.writeBatch(List.of(
                                    DemoUser.builder().id(baseId + 1).username("t" + threadIdx + "-a" + b).age(20).build(),
                                    DemoUser.builder().id(baseId + 2).username("t" + threadIdx + "-b" + b).age(21).build()
                            ));
                        }
                        return null;
                    }));
                }
                assertTrue(ready.await(3, TimeUnit.SECONDS));
                startGate.countDown();
                for (Future<?> f : futures) {
                    f.get(5, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
            assertThrows(IllegalStateException.class, () -> concurrentTpl.encrypt("LatePwd"),
                    "开始写入后不可再调用 encrypt(password)");
            assertEquals(20L, concurrentTpl.getTotalExportedCount());
        }
        assertFalse(detailEvents.isEmpty());
        assertEquals("100:20", detailEvents.get(detailEvents.size() - 1));
        assertEquals(20, ExcelUtil.read(new ByteArrayInputStream(concurrentOut.toByteArray()), DemoUser.class, "Concurrent#2026").size());

        // 加密流式导出中途抛异常时零临时文件残留
        ByteArrayOutputStream failedStreamOut = new ByteArrayOutputStream();
        assertThrows(RuntimeException.class, () -> {
            try (StreamingPageExportTemplate<DemoUser> failingTpl = ExcelUtil.streaming(failedStreamOut, DemoUser.class)
                    .encrypt("FailStream#1")) {
                failingTpl.exportByPage(page -> {
                    if (page == 1) {
                        return PageResult.of(List.of(DemoUser.builder().id(1L).username("ok").age(20).build()), 2L, 1, 1);
                    }
                    throw new IllegalStateException("模拟分页查询故障");
                });
            }
        });
        try (var stream = Files.list(managedTmp)) {
            assertEquals(0, stream.count(), "加密流式导出异常中止后不得遗留明文临时文件");
        }

        // 3. 绑定异步导出任务服务实时驱动进度
        LocalFileSystemStorage storage = new LocalFileSystemStorage(props);
        InMemoryAsyncExportTaskService taskService = new InMemoryAsyncExportTaskService(props, storage);
        try {
            CountDownLatch taskStarted = new CountDownLatch(1);
            CountDownLatch allowFinish = new CountDownLatch(1);
            AtomicReference<String> taskIdRef = new AtomicReference<>();

            String taskId = taskService.submit("流式进度联调导出", out -> {
                String resolvedId = waitForNonNull(taskIdRef);
                taskStarted.countDown();
                try (StreamingPageExportTemplate<DemoUser> template = ExcelUtil.streaming(out, DemoUser.class)
                        .expectedTotalRows(4)
                        .onProgress(taskService, resolvedId)) {
                    template.writeBatch(List.of(DemoUser.builder().id(1L).username("p1").age(18).build()));
                    assertTrue(allowFinish.await(5, TimeUnit.SECONDS));
                    template.writeBatch(List.of(
                            DemoUser.builder().id(2L).username("p2").age(19).build(),
                            DemoUser.builder().id(3L).username("p3").age(20).build(),
                            DemoUser.builder().id(4L).username("p4").age(21).build()
                    ));
                    template.finish();
                }
            });
            taskIdRef.set(taskId);
            assertTrue(taskStarted.await(3, TimeUnit.SECONDS));

            long deadline = System.currentTimeMillis() + 3000;
            ExportTask midTask = taskService.getTask(taskId);
            while ((midTask == null || midTask.getProgress() < 25) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
                midTask = taskService.getTask(taskId);
            }
            assertNotNull(midTask);
            assertTrue(midTask.getProgress() >= 25 && midTask.getProgress() < 100,
                    "进行中任务应通过 StreamingPageExportTemplate 上报真实进度: " + midTask.getProgress());

            allowFinish.countDown();
            ExportTask doneTask = awaitTerminal(taskService, taskId);
            assertEquals(ExportTaskStatus.SUCCESS, doneTask.getStatus());
            assertEquals(100, doneTask.getProgress());
        } finally {
            taskService.destroy();
        }
    }

    @Test
    @DisplayName("切片 2：ExcelBuilder 延迟初始化、多 Sheet 链式 AES-256 加密与异常/结束零临时文件残留")
    void shouldSupportExcelBuilderLazyInitMultiSheetEncryptionAndCleanup() throws Exception {
        Path managedTmp = Files.createDirectories(tempDir.resolve("builder-tmp"));
        ExcelProperties props = new ExcelProperties();
        props.setTempDir(managedTmp.toString());
        ExcelUtil.initProperties(props);

        // 1. 通过 ExcelUtil.builder(out, password) 构造并混合实体 Sheet + 动态表头 Sheet + 写后 encrypt 状态防护
        ByteArrayOutputStream multiEncOut = new ByteArrayOutputStream();
        try (var builder = ExcelUtil.builder(multiEncOut, "Multi#2026")) {
            builder.sheet("员工表", DemoUser.class, List.of(
                            DemoUser.builder().id(1L).username("alice").age(25).build(),
                            DemoUser.builder().id(2L).username("bob").age(28).build()))
                    .sheetDynamic("指标表", List.of("指标名", "数值"), List.of(List.of("QPS", "9999")));
            assertThrows(IllegalStateException.class, () -> builder.encrypt("LatePwd"),
                    "写入工作表后不可再调用 encrypt(password)");
        }

        byte[] multiEncBytes = multiEncOut.toByteArray();
        assertTrue(ExcelSecurityUtil.isEncryptedWorkbook(new ByteArrayInputStream(multiEncBytes)));
        assertThrows(ExcelValidationException.class,
                () -> ExcelUtil.read(new ByteArrayInputStream(multiEncBytes), DemoUser.class, "wrong"));

        List<DemoUser> sheet0Rows = ExcelUtil.read(new ByteArrayInputStream(multiEncBytes), DemoUser.class, "Multi#2026");
        assertEquals(2, sheet0Rows.size());
        assertEquals("alice", sheet0Rows.get(0).getUsername());

        // 2. 通过无密码构造后链式 .encrypt(...)，以及 Web ExcelWebUtil.builder(response, fileName, password)
        ByteArrayOutputStream chainEncOut = new ByteArrayOutputStream();
        try (var builder = ExcelUtil.builder(chainEncOut).encrypt("Chain#999")) {
            builder.sheet("SheetA", DemoUser.class, List.of(DemoUser.builder().id(3L).username("carol").age(31).build()));
        }
        List<DemoUser> chainRows = ExcelUtil.read(new ByteArrayInputStream(chainEncOut.toByteArray()), DemoUser.class, "Chain#999");
        assertEquals(1, chainRows.size());
        assertEquals("carol", chainRows.get(0).getUsername());

        MockHttpServletResponse webBuilderResp = new MockHttpServletResponse();
        try (var webBuilder = ExcelWebUtil.builder(webBuilderResp, "web-multi", "WebBuilder#2026")) {
            assertNotNull(webBuilder);
            webBuilder.sheet("WebSheet", DemoUser.class, List.of(DemoUser.builder().id(9L).username("web-u").age(29).build()));
        }
        List<DemoUser> webRows = ExcelUtil.read(new ByteArrayInputStream(webBuilderResp.getContentAsByteArray()), DemoUser.class, "WebBuilder#2026");
        assertEquals(1, webRows.size());
        assertEquals("web-u", webRows.get(0).getUsername());

        // 3. 异常中止时也不应遗留临时文件
        ByteArrayOutputStream abortedOut = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class, () -> {
            try (var builder = ExcelUtil.builder(abortedOut, "Abort#123")) {
                builder.sheet("Ok", DemoUser.class, List.of(DemoUser.builder().id(4L).username("dave").age(22).build()));
                builder.sheet("Invalid", (Class<DemoUser>) null, List.of());
            }
        });

        try (var stream = Files.list(managedTmp)) {
            assertEquals(0, stream.count(), "无论正常结束还是异常中止，ExcelBuilder 均不可遗留临时文件");
        }
    }

    @Test
    @DisplayName("切片 3：ExcelUtil.fill 与 ExcelUtil.exportDynamic 全量重载对称支持 AES-256 密码加密")
    void shouldSupportAes256EncryptionForFillAndExportDynamicOverloads() throws Exception {
        Path managedTmp = Files.createDirectories(tempDir.resolve("slice3-tmp"));
        ExcelProperties props = new ExcelProperties();
        props.setTempDir(managedTmp.toString());
        ExcelUtil.initProperties(props);

        // 1. 构造含单值占位符与循环列表占位符的内存模板
        byte[] templateBytes;
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook tplWb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             ByteArrayOutputStream tplOut = new ByteArrayOutputStream()) {
            var sheet = tplWb.createSheet("Report");
            var titleRow = sheet.createRow(0);
            titleRow.createCell(0).setCellValue("{reportTitle}");
            var listHeader = sheet.createRow(1);
            listHeader.createCell(0).setCellValue("名称");
            listHeader.createCell(1).setCellValue("金额");
            var tplRow = sheet.createRow(2);
            tplRow.createCell(0).setCellValue("{.name}");
            tplRow.createCell(1).setCellValue("{.amount}");
            tplWb.write(tplOut);
            templateBytes = tplOut.toByteArray();
        }

        // 2. ExcelUtil.fill 加密写出（单对象 + 列表复合填充）
        ByteArrayOutputStream fillEncOut = new ByteArrayOutputStream();
        ExcelUtil.fill(
                fillEncOut,
                new ByteArrayInputStream(templateBytes),
                "Report",
                java.util.Map.of("reportTitle", "月度加密报表"),
                List.of(
                        io.github.atengk.boot.excel.model.FillItemVO.builder().name("云服务").amount(128.5).build(),
                        io.github.atengk.boot.excel.model.FillItemVO.builder().name("对象存储").amount(64.0).build()
                ),
                null,
                "Fill#2026"
        );
        byte[] fillEncBytes = fillEncOut.toByteArray();
        assertTrue(ExcelSecurityUtil.isEncryptedWorkbook(new ByteArrayInputStream(fillEncBytes)));
        assertThrows(ExcelValidationException.class,
                () -> ExcelSecurityUtil.decryptWorkbook(new ByteArrayInputStream(fillEncBytes), new ByteArrayOutputStream(), "bad"));

        ByteArrayOutputStream fillDecOut = new ByteArrayOutputStream();
        ExcelSecurityUtil.decryptWorkbook(new ByteArrayInputStream(fillEncBytes), fillDecOut, "Fill#2026");
        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(fillDecOut.toByteArray()))) {
            var s = wb.getSheet("Report");
            assertEquals("月度加密报表", s.getRow(0).getCell(0).getStringCellValue());
            assertEquals("云服务", s.getRow(2).getCell(0).getStringCellValue());
            assertEquals(64.0, s.getRow(3).getCell(1).getNumericCellValue());
        }

        // 3. ExcelUtil.exportDynamic 加密写出（列表模式与 Map 显式列键映射模式）
        ByteArrayOutputStream dynEncOut = new ByteArrayOutputStream();
        ExcelUtil.exportDynamic(
                dynEncOut,
                "明细",
                List.of("部门", "预算"),
                List.of("dept", "budget"),
                List.of(java.util.Map.of("dept", "研发部", "budget", "500W")),
                "Dyn#2026"
        );
        byte[] dynEncBytes = dynEncOut.toByteArray();
        assertTrue(ExcelSecurityUtil.isEncryptedWorkbook(new ByteArrayInputStream(dynEncBytes)));
        ByteArrayOutputStream dynDecOut = new ByteArrayOutputStream();
        ExcelSecurityUtil.decryptWorkbook(new ByteArrayInputStream(dynEncBytes), dynDecOut, "Dyn#2026");
        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(dynDecOut.toByteArray()))) {
            var s = wb.getSheet("明细");
            assertEquals("部门", s.getRow(0).getCell(0).getStringCellValue());
            assertEquals("研发部", s.getRow(1).getCell(0).getStringCellValue());
            assertEquals("500W", s.getRow(1).getCell(1).getStringCellValue());
        }

        try (var stream = Files.list(managedTmp)) {
            assertEquals(0, stream.count(), "fill/exportDynamic 加密完成后不得残留临时文件");
        }
    }

    @Test
    @DisplayName("切片 4：ExcelReaderBuilder 多 Sheet 加密读取校验聚合、failFast 中断、表头策略与按 Sheet 独立开关")
    void shouldValidateEncryptedMultiSheetAggregateAndFailFast() {
        List<io.github.atengk.boot.excel.model.ImportUserVO> sheet0Data = List.of(
                io.github.atengk.boot.excel.model.ImportUserVO.builder()
                        .name("张三").phone("13800138000").age(25).email("zhangsan@example.com").build(),
                io.github.atengk.boot.excel.model.ImportUserVO.builder()
                        .name("").phone("123").age(15).email("bad-email").build()
        );
        List<io.github.atengk.boot.excel.model.ImportUserVO> sheet1Data = List.of(
                io.github.atengk.boot.excel.model.ImportUserVO.builder()
                        .name("李四").phone("13900139000").age(28).email("lisi@example.com").build(),
                io.github.atengk.boot.excel.model.ImportUserVO.builder()
                        .name("王五").phone("13700137000").age(22).email("invalid-mail").build()
        );

        ByteArrayOutputStream multiOut = new ByteArrayOutputStream();
        try (var builder = ExcelUtil.builder(multiOut, "ReadVal#2026")) {
            builder.sheet("有效与失效用户", io.github.atengk.boot.excel.model.ImportUserVO.class, sheet0Data)
                    .sheet("待审用户", io.github.atengk.boot.excel.model.ImportUserVO.class, sheet1Data);
        }
        byte[] multiBytes = multiOut.toByteArray();

        // 1. 默认聚合模式：跨两个 Sheet 收集校验错误而不中断，合法数据与错误列表按索引/名称双向可查
        var aggregateResult = ExcelUtil.reader(new ByteArrayInputStream(multiBytes), "ReadVal#2026")
                .validate()
                .sheet(0, io.github.atengk.boot.excel.model.ImportUserVO.class)
                .sheet("待审用户", io.github.atengk.boot.excel.model.ImportUserVO.class)
                .read();

        assertTrue(aggregateResult.hasErrors(), "多 Sheet 聚合校验应捕获所有错误行");
        assertEquals(1, aggregateResult.getSheetData(0, io.github.atengk.boot.excel.model.ImportUserVO.class).size());
        assertEquals(1, aggregateResult.getSheetData("待审用户", io.github.atengk.boot.excel.model.ImportUserVO.class).size());

        var s0Errors = aggregateResult.getSheetErrors(0);
        var s0NamedErrors = aggregateResult.getSheetErrors("有效与失效用户");
        var s1Errors = aggregateResult.getSheetErrors("待审用户");
        assertFalse(s0Errors.isEmpty());
        assertEquals(s0Errors.size(), s0NamedErrors.size());
        assertEquals(1, s1Errors.size());
        assertEquals(s0Errors.size() + s1Errors.size(), aggregateResult.getAllErrors().size());

        // 2. 显式开启 failFast(true)：遇到首个行级错误即抛出 ExcelValidationException
        assertThrows(ExcelValidationException.class, () ->
                ExcelUtil.reader(new ByteArrayInputStream(multiBytes), "ReadVal#2026")
                        .validate()
                        .failFast(true)
                        .sheet(0, io.github.atengk.boot.excel.model.ImportUserVO.class)
                        .sheet(1, io.github.atengk.boot.excel.model.ImportUserVO.class)
                        .read()
        );

        // 3. STRICT 表头契约校验：模型不匹配时立即抛出 ExcelHeaderValidationException
        assertThrows(io.github.atengk.boot.excel.exception.ExcelHeaderValidationException.class, () ->
                ExcelUtil.reader(new ByteArrayInputStream(multiBytes), "ReadVal#2026")
                        .validate()
                        .headerStrategy(io.github.atengk.boot.excel.enums.HeaderValidationStrategy.STRICT)
                        .sheet(0, DemoUser.class)
                        .read()
        );

        // 4. 按 Sheet 独立开关校验：Sheet 0 开启校验、Sheet 1 关闭校验
        var perSheetResult = ExcelUtil.reader(new ByteArrayInputStream(multiBytes), "ReadVal#2026")
                .sheet(0, io.github.atengk.boot.excel.model.ImportUserVO.class, true)
                .sheet("待审用户", io.github.atengk.boot.excel.model.ImportUserVO.class, false)
                .read();

        assertEquals(1, perSheetResult.getSheetData(0, ImportUserVO.class).size());
        assertFalse(perSheetResult.getSheetErrors(0).isEmpty());
        assertEquals(2, perSheetResult.getSheetData("待审用户", ImportUserVO.class).size());
        assertTrue(perSheetResult.getSheetErrors("待审用户").isEmpty());

        // 5. GAP-2 回归：未开启 validate()（validate=false）但开启表头策略 STRICT 读取合法工作簿时，必须读取全部数据行（不可截断为 0）
        var headerOnlyResult = ExcelUtil.reader(new ByteArrayInputStream(multiBytes), "ReadVal#2026")
                .headerStrategy(HeaderValidationStrategy.STRICT)
                .sheet(0, ImportUserVO.class)
                .sheet("待审用户", ImportUserVO.class)
                .read();
        assertEquals(2, headerOnlyResult.getSheetData(0, ImportUserVO.class).size(),
                "仅开启表头校验策略但未开启行级校验时，合法表头工作表必须正常读取全量数据行");
        assertEquals(2, headerOnlyResult.getSheetData("待审用户", ImportUserVO.class).size());
        assertFalse(headerOnlyResult.hasErrors());

        // 6. 自定义 ReadListener 配合表头策略 STRICT 时也必须放行全部数据行
        AtomicInteger customCount = new AtomicInteger(0);
        ExcelUtil.reader(new ByteArrayInputStream(multiBytes), "ReadVal#2026")
                .headerStrategy(HeaderValidationStrategy.STRICT)
                .sheet(0, ImportUserVO.class, new AnalysisEventListener<ImportUserVO>() {
                    @Override
                    public void invoke(ImportUserVO data, AnalysisContext context) {
                        customCount.incrementAndGet();
                    }

                    @Override
                    public void doAfterAllAnalysed(AnalysisContext context) {
                    }
                })
                .read();
        assertEquals(2, customCount.get(), "自定义监听器搭配表头契约校验时，表头通过后必须继续完整解析数据行");

        // 7. 流式分批多 Sheet 校验（ValidatedStreamingBatchListener）与错误行数熔断标记
        List<ImportUserVO> streamedValid = new ArrayList<>();
        var streamingValResult = ExcelUtil.reader(new ByteArrayInputStream(multiBytes), "ReadVal#2026")
                .maxErrorRows(1)
                .sheet(0, ImportUserVO.class, 1, streamedValid::addAll, true)
                .read();
        assertEquals(1, streamedValid.size());
        assertEquals(1, streamingValResult.getRowCount(0));
        assertFalse(streamingValResult.getSheetErrors(0).isEmpty());
        assertTrue(streamingValResult.isCircuitBroken(), "达到 maxErrorRows 阈值时结果对象应记录熔断状态");

        assertThrows(ExcelValidationException.class, () ->
                ExcelUtil.reader(new ByteArrayInputStream(multiBytes), "ReadVal#2026")
                        .sheet(0, ImportUserVO.class, 1, batch -> {}, true, true, HeaderValidationStrategy.STRICT)
                        .read()
        );
    }

    @Test
    @DisplayName("切片 5：ExcelWebUtil 对称门面 toResponseEntityFailedRows/Fill/Streaming/ByCursor 验证")
    void shouldBuildSymmetricResponseEntityStreams() throws Exception {
        Path managedTmp = Files.createDirectories(tempDir.resolve("slice5-tmp"));
        ExcelProperties props = new ExcelProperties();
        props.setTempDir(managedTmp.toString());
        ExcelUtil.initProperties(props);

        // 1. toResponseEntityFailedRows（3 参与 4 参含差错实体列表重载）
        ImportUserVO badUser = ImportUserVO.builder()
                .name("坏数据").phone("123").age(12).email("bad").build();
        List<ExcelErrorRow> errors = List.of(
                ExcelErrorRow.builder()
                        .rowIndex(2)
                        .columnName("邮箱")
                        .fieldName("email")
                        .message("邮箱格式不合法")
                        .rowData(badUser)
                        .build()
        );
        var failedResp = ExcelWebUtil.toResponseEntityFailedRows("failed-users", ImportUserVO.class, errors);
        assertEquals(HttpStatus.OK, failedResp.getStatusCode());
        assertNotNull(failedResp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        ByteArrayOutputStream failedOut = new ByteArrayOutputStream();
        failedResp.getBody().writeTo(failedOut);
        List<ImportUserVO> readFailed = ExcelUtil.read(new ByteArrayInputStream(failedOut.toByteArray()), ImportUserVO.class);
        assertEquals(1, readFailed.size());
        assertEquals("坏数据", readFailed.get(0).getName());

        var failedExplicitResp = ExcelWebUtil.toResponseEntityFailedRows(
                "failed-users-explicit", ImportUserVO.class, List.of(badUser), errors);
        ByteArrayOutputStream failedExplicitOut = new ByteArrayOutputStream();
        failedExplicitResp.getBody().writeTo(failedExplicitOut);
        assertEquals(1, ExcelUtil.read(new ByteArrayInputStream(failedExplicitOut.toByteArray()), ImportUserVO.class).size());

        // 2. toResponseEntityFill（含 5 参/7 参 AES-256 加密与 MockHttpServletResponse 加密填充重载）
        byte[] templateBytes;
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook tplWb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             ByteArrayOutputStream tplOut = new ByteArrayOutputStream()) {
            var sheet = tplWb.createSheet("Summary");
            sheet.createRow(0).createCell(0).setCellValue("{title}");
            var hdr = sheet.createRow(1);
            hdr.createCell(0).setCellValue("名称");
            hdr.createCell(1).setCellValue("金额");
            var row = sheet.createRow(2);
            row.createCell(0).setCellValue("{.name}");
            row.createCell(1).setCellValue("{.amount}");
            tplWb.write(tplOut);
            templateBytes = tplOut.toByteArray();
        }
        var fillResp = ExcelWebUtil.toResponseEntityFill(
                "monthly-fill",
                "Summary",
                new ByteArrayInputStream(templateBytes),
                java.util.Map.of("title", "Web填充报表"),
                List.of(FillItemVO.builder().name("算力节点").amount(99.5).build()),
                null,
                "WebFill#2026"
        );
        assertEquals(HttpStatus.OK, fillResp.getStatusCode());
        ByteArrayOutputStream fillOut = new ByteArrayOutputStream();
        fillResp.getBody().writeTo(fillOut);
        byte[] fillEncBytes = fillOut.toByteArray();
        assertTrue(ExcelSecurityUtil.isEncryptedWorkbook(new ByteArrayInputStream(fillEncBytes)));
        ByteArrayOutputStream fillDecOut = new ByteArrayOutputStream();
        ExcelSecurityUtil.decryptWorkbook(new ByteArrayInputStream(fillEncBytes), fillDecOut, "WebFill#2026");
        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(fillDecOut.toByteArray()))) {
            var s = wb.getSheet("Summary");
            assertEquals("Web填充报表", s.getRow(0).getCell(0).getStringCellValue());
            assertEquals("算力节点", s.getRow(2).getCell(0).getStringCellValue());
        }

        // 5 参 toResponseEntityFill 与 HttpServletResponse 加密 fill/exportDynamic
        var fill5Resp = ExcelWebUtil.toResponseEntityFill(
                "fill5", new ByteArrayInputStream(templateBytes), java.util.Map.of("title", "单表Web加密"), null, "Fill5#2026");
        ByteArrayOutputStream fill5Out = new ByteArrayOutputStream();
        fill5Resp.getBody().writeTo(fill5Out);
        assertTrue(ExcelSecurityUtil.isEncryptedWorkbook(new ByteArrayInputStream(fill5Out.toByteArray())));

        MockHttpServletResponse mockFillResp = new MockHttpServletResponse();
        ExcelUtil.fill(mockFillResp, "resp-fill", new ByteArrayInputStream(templateBytes),
                java.util.Map.of("title", "Resp加密填充"), null, "RespFill#2026");
        assertTrue(ExcelSecurityUtil.isEncryptedWorkbook(new ByteArrayInputStream(mockFillResp.getContentAsByteArray())));

        // 3. toResponseEntityDynamic（4 参带密码重载）与 toResponseEntityStreaming（PageResult 分页拉取）
        var dynResp = ExcelWebUtil.toResponseEntityDynamic(
                "dyn-enc", List.of("列A", "列B"), List.of(List.of("v1", "v2")), "DynResp#2026");
        ByteArrayOutputStream dynRespOut = new ByteArrayOutputStream();
        dynResp.getBody().writeTo(dynRespOut);
        assertTrue(ExcelSecurityUtil.isEncryptedWorkbook(new ByteArrayInputStream(dynRespOut.toByteArray())));

        var streamResp = ExcelWebUtil.toResponseEntityStreaming(
                "paged-users",
                "用户页",
                DemoUser.class,
                pageNum -> {
                    if (pageNum == 1) {
                        return PageResult.of(List.of(DemoUser.builder().id(1L).username("u1").age(20).build()), 2L, 1, 1);
                    }
                    if (pageNum == 2) {
                        return PageResult.of(List.of(DemoUser.builder().id(2L).username("u2").age(21).build()), 2L, 2, 1);
                    }
                    return PageResult.empty();
                }
        );
        assertEquals(HttpStatus.OK, streamResp.getStatusCode());
        ByteArrayOutputStream streamOut = new ByteArrayOutputStream();
        streamResp.getBody().writeTo(streamOut);
        List<DemoUser> streamedUsers = ExcelUtil.read(new ByteArrayInputStream(streamOut.toByteArray()), DemoUser.class);
        assertEquals(2, streamedUsers.size());
        assertEquals("u2", streamedUsers.get(1).getUsername());

        // 4. toResponseEntityStreamingByCursor（游标驱动流式导出）
        var cursorResp = ExcelWebUtil.toResponseEntityStreamingByCursor(
                "cursor-users",
                DemoUser.class,
                0L,
                1,
                (cursor, limit) -> {
                    if (cursor == 0L) {
                        return List.of(DemoUser.builder().id(10L).username("c10").age(30).build());
                    }
                    if (cursor == 10L) {
                        return List.of(DemoUser.builder().id(20L).username("c20").age(31).build());
                    }
                    return List.of();
                },
                DemoUser::getId
        );
        assertEquals(HttpStatus.OK, cursorResp.getStatusCode());
        ByteArrayOutputStream cursorOut = new ByteArrayOutputStream();
        cursorResp.getBody().writeTo(cursorOut);
        List<DemoUser> cursorUsers = ExcelUtil.read(new ByteArrayInputStream(cursorOut.toByteArray()), DemoUser.class);
        assertEquals(2, cursorUsers.size());
        assertEquals(20L, cursorUsers.get(1).getId());

        // 5. 流式写出中途出错时，临时文件仍必须 100% 清理
        var brokenFillResp = ExcelWebUtil.toResponseEntityFill(
                "broken-fill", new ByteArrayInputStream(templateBytes), java.util.Map.of("title", "中断"), null, "Broken#2026");
        assertThrows(Exception.class, () -> brokenFillResp.getBody().writeTo(new OutputStream() {
            @Override
            public void write(int b) throws java.io.IOException {
                throw new java.io.IOException("模拟客户端提前断开写出流");
            }
        }));
        try (var stream = Files.list(managedTmp)) {
            assertEquals(0, stream.count(), "ResponseEntity 流式加密写出完成或中途中断后，临时目录不得遗留任何临时文件");
        }
    }

    private static String waitForNonNull(AtomicReference<String> ref) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (ref.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        return ref.get();
    }

    private static ExportTask awaitTerminal(InMemoryAsyncExportTaskService service, String taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        ExportTask task = service.getTask(taskId);
        while ((task == null || task.getStatus() == ExportTaskStatus.PENDING || task.getStatus() == ExportTaskStatus.PROCESSING)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
            task = service.getTask(taskId);
        }
        return task;
    }
}
