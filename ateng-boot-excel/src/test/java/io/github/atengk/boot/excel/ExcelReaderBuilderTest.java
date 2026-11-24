package io.github.atengk.boot.excel;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.excel.builder.ExcelBuilder;
import io.github.atengk.boot.excel.builder.ExcelReaderBuilder;
import io.github.atengk.boot.excel.model.DemoUser;
import io.github.atengk.boot.excel.model.EnumAndDictUserVO;
import io.github.atengk.boot.excel.model.ExcelMultiSheetResult;
import io.github.atengk.boot.excel.model.ExcelRowAware;
import io.github.atengk.boot.excel.model.ExcelRowData;
import io.github.atengk.boot.excel.model.ImportUserVO;
import io.github.atengk.boot.excel.model.UserGenderEnum;
import io.github.atengk.boot.excel.model.UserStatusEnum;
import io.github.atengk.boot.excel.util.ExcelUtil;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelIgnore;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.read.listener.ReadListener;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异构多工作表流式读取构建器 ExcelReaderBuilder 单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
class ExcelReaderBuilderTest {

    private byte[] createMultiSheetWorkbook() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelBuilder builder = ExcelUtil.builder(out)) {
            // Sheet 0: 用户表 (DemoUser)
            List<DemoUser> users = List.of(
                    DemoUser.builder().id(1L).username("张三").age(28).balance(new BigDecimal("100.50")).createTime(new Date()).build(),
                    DemoUser.builder().id(2L).username("李四").age(32).balance(new BigDecimal("888.88")).createTime(new Date()).build()
            );
            builder.sheet("用户表", DemoUser.class, users);

            // Sheet 1: 导入人员表 (ImportUserVO)
            List<ImportUserVO> importUsers = List.of(
                    ImportUserVO.builder().name("王五").phone("13800138001").age(25).email("wangwu@test.com").build(),
                    ImportUserVO.builder().name("赵六").phone("13800138002").age(30).email("zhaoliu@test.com").build(),
                    ImportUserVO.builder().name("钱七").phone("13800138003").age(35).email("qianqi@test.com").build()
            );
            builder.sheet("导入人员表", ImportUserVO.class, importUsers);
            builder.finish();
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("测试 多工作表按索引与名称双向寻址聚合同步读取")
    void testMultiSheetAggregatedReadByIndexAndName() {
        byte[] excelBytes = createMultiSheetWorkbook();

        ExcelMultiSheetResult result = ExcelUtil.reader(new ByteArrayInputStream(excelBytes))
                .sheet(0, DemoUser.class)
                .sheet("导入人员表", ImportUserVO.class)
                .read();

        assertNotNull(result);
        assertEquals(2, result.getSheetCount());
        assertEquals(5, result.getTotalRowCount());
        assertFalse(result.isEmpty());

        // 验证 Sheet 0 数据
        assertEquals(2, result.getRowCount(0));
        assertEquals(2, result.getRowCount("用户表"));
        List<DemoUser> usersByIndex = result.getSheetData(0, DemoUser.class);
        assertEquals(2, usersByIndex.size());
        assertEquals("张三", usersByIndex.get(0).getUsername());
        assertEquals("李四", usersByIndex.get(1).getUsername());

        // 验证 Sheet 0 通过名称亦可透明获取
        List<DemoUser> usersByName = result.getSheetData("用户表", DemoUser.class);
        assertEquals(2, usersByName.size());
        assertEquals("张三", usersByName.get(0).getUsername());

        // 验证 Sheet 1 数据
        assertEquals(3, result.getRowCount(1));
        assertEquals(3, result.getRowCount("导入人员表"));
        List<ImportUserVO> importsByName = result.getSheetData("导入人员表", ImportUserVO.class);
        assertEquals(3, importsByName.size());
        assertEquals("王五", importsByName.get(0).getName());
        assertEquals("赵六", importsByName.get(1).getName());
        assertEquals("钱七", importsByName.get(2).getName());

        // 验证 Sheet 1 通过索引亦可透明获取
        List<ImportUserVO> importsByIndex = result.getSheetData(1, ImportUserVO.class);
        assertEquals(3, importsByIndex.size());

        // 验证空安全保底返回：查询不存在的工作表绝不返回 null
        List<DemoUser> nonExistentByIndex = result.getSheetData(999, DemoUser.class);
        assertNotNull(nonExistentByIndex);
        assertTrue(nonExistentByIndex.isEmpty());

        List<ImportUserVO> nonExistentByName = result.getSheetData("不存在的Sheet", ImportUserVO.class);
        assertNotNull(nonExistentByName);
        assertTrue(nonExistentByName.isEmpty());
    }

    @Test
    @DisplayName("测试 多工作表流式分批回调消费（常数级内存占用）")
    void testMultiSheetStreamingBatchConsumption() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelBuilder builder = ExcelUtil.builder(out)) {
            List<DemoUser> users = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                users.add(DemoUser.builder().id((long) i).username("用户" + i).age(20 + i).balance(BigDecimal.valueOf(100 * i)).createTime(new Date()).build());
            }
            builder.sheet("用户表", DemoUser.class, users);

            List<ImportUserVO> importUsers = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                importUsers.add(ImportUserVO.builder().name("人员" + i).phone("1380013800" + i).age(20 + i).email("user" + i + "@test.com").build());
            }
            builder.sheet("人员表", ImportUserVO.class, importUsers);
            builder.finish();
        }

        List<List<DemoUser>> userBatches = new ArrayList<>();
        List<List<ImportUserVO>> importBatches = new ArrayList<>();

        ExcelMultiSheetResult result = ExcelUtil.reader(new ByteArrayInputStream(out.toByteArray()))
                .sheet(0, DemoUser.class, 2, userBatches::add)
                .sheet("人员表", ImportUserVO.class, 3, importBatches::add)
                .read();

        // 验证 Sheet 0 分批（每批 2 条，共 5 条：2, 2, 1）
        assertEquals(3, userBatches.size());
        assertEquals(2, userBatches.get(0).size());
        assertEquals(2, userBatches.get(1).size());
        assertEquals(1, userBatches.get(2).size());
        assertEquals("用户1", userBatches.get(0).get(0).getUsername());

        // 验证 Sheet 1 分批（每批 3 条，共 5 条：3, 2）
        assertEquals(2, importBatches.size());
        assertEquals(3, importBatches.get(0).size());
        assertEquals(2, importBatches.get(1).size());
        assertEquals("人员1", importBatches.get(0).get(0).getName());

        // 验证统计行数
        assertEquals(5, result.getRowCount(0));
        assertEquals(5, result.getRowCount("人员表"));
        assertEquals(10, result.getTotalRowCount());
    }

    @Test
    @DisplayName("测试 混合模式：同一工作簿同时支持分批流式消费与聚合同步返回")
    void testMixedSyncAndStreamingBatchSheets() {
        byte[] excelBytes = createMultiSheetWorkbook();

        List<DemoUser> streamedUsers = new ArrayList<>();

        ExcelMultiSheetResult result = ExcelUtil.reader(new ByteArrayInputStream(excelBytes))
                // Sheet 0 使用流式回调消费
                .sheet(0, DemoUser.class, 10, streamedUsers::addAll)
                // Sheet 1 使用聚合返回
                .sheet("导入人员表", ImportUserVO.class)
                .read();

        // Sheet 0 通过回调消费
        assertEquals(2, streamedUsers.size());
        assertEquals("张三", streamedUsers.get(0).getUsername());

        // Sheet 1 在结果容器中聚合存储
        List<ImportUserVO> importList = result.getSheetData("导入人员表", ImportUserVO.class);
        assertEquals(3, importList.size());
        assertEquals("王五", importList.get(0).getName());

        assertEquals(5, result.getTotalRowCount());
    }

    @Test
    @DisplayName("测试 单 Sheet 最大同步行数熔断防卫（maxRows 触发 BAD_REQUEST）")
    void testSingleSheetRowLimitFailFastDefense() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelBuilder builder = ExcelUtil.builder(out)) {
            List<DemoUser> users = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                users.add(DemoUser.builder().id((long) i).username("用户" + i).age(20 + i).balance(BigDecimal.valueOf(100 * i)).createTime(new Date()).build());
            }
            builder.sheet("大数据表", DemoUser.class, users);
            builder.finish();
        }

        ExcelReaderBuilder readerBuilder = ExcelUtil.reader(new ByteArrayInputStream(out.toByteArray()))
                .maxRows(5)
                .sheet(0, DemoUser.class);

        BusinessException exception = assertThrows(BusinessException.class, readerBuilder::read);
        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), exception.getCode());
        assertTrue(exception.getMessage().contains("数据行数超出同步读取安全上限"));
    }

    @Test
    @DisplayName("测试 全表累计最大同步行数熔断防卫（maxTotalRows 触发 BAD_REQUEST）")
    void testTotalRowLimitFailFastDefense() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelBuilder builder = ExcelUtil.builder(out)) {
            List<DemoUser> users1 = List.of(
                    DemoUser.builder().id(1L).username("A").age(20).balance(BigDecimal.TEN).createTime(new Date()).build(),
                    DemoUser.builder().id(2L).username("B").age(21).balance(BigDecimal.TEN).createTime(new Date()).build(),
                    DemoUser.builder().id(3L).username("C").age(22).balance(BigDecimal.TEN).createTime(new Date()).build()
            );
            builder.sheet("表1", DemoUser.class, users1);

            List<DemoUser> users2 = List.of(
                    DemoUser.builder().id(4L).username("D").age(23).balance(BigDecimal.TEN).createTime(new Date()).build(),
                    DemoUser.builder().id(5L).username("E").age(24).balance(BigDecimal.TEN).createTime(new Date()).build(),
                    DemoUser.builder().id(6L).username("F").age(25).balance(BigDecimal.TEN).createTime(new Date()).build()
            );
            builder.sheet("表2", DemoUser.class, users2);
            builder.finish();
        }

        // 单表上限为 5（未超出），但全表累计上限设为 4（已超出，共 6 条）
        ExcelReaderBuilder readerBuilder = ExcelUtil.reader(new ByteArrayInputStream(out.toByteArray()))
                .maxRows(5)
                .maxTotalRows(4)
                .sheet(0, DemoUser.class)
                .sheet(1, DemoUser.class);

        BusinessException exception = assertThrows(BusinessException.class, readerBuilder::read);
        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), exception.getCode());
        assertTrue(exception.getMessage().contains("多工作表累计数据行数超出同步读取安全上限"));
    }

    @Test
    @DisplayName("测试 确定性生命周期与资源清理：读取完成后输入流被可靠闭合")
    void testAutoCloseStreamAndDeterministicCleanup() {
        byte[] excelBytes = createMultiSheetWorkbook();
        AtomicBoolean streamClosed = new AtomicBoolean(false);

        InputStream monitoredStream = new FilterInputStream(new ByteArrayInputStream(excelBytes)) {
            @Override
            public void close() throws IOException {
                streamClosed.set(true);
                super.close();
            }
        };

        ExcelUtil.reader(monitoredStream)
                .sheet(0, DemoUser.class)
                .read();

        assertTrue(streamClosed.get(), "读取完成后 underlying InputStream 必须被自动闭合");
    }

    @Test
    @DisplayName("测试 try-with-resources 语法生命周期：即使未调用 read 也能通过 close 释放流")
    void testTryWithResourcesClosesStream() {
        byte[] excelBytes = createMultiSheetWorkbook();
        AtomicBoolean streamClosed = new AtomicBoolean(false);

        InputStream monitoredStream = new FilterInputStream(new ByteArrayInputStream(excelBytes)) {
            @Override
            public void close() throws IOException {
                streamClosed.set(true);
                super.close();
            }
        };

        try (ExcelReaderBuilder builder = ExcelUtil.reader(monitoredStream)) {
            builder.sheet(0, DemoUser.class);
            // 模拟业务逻辑在此处发生其他判断提前返回，未调用 builder.read()
        }

        assertTrue(streamClosed.get(), "try-with-resources 退出时必须确定性调用 close() 释放流");
    }

    @Test
    @DisplayName("测试 自定义 ReadListener 注册与回调支持")
    void testCustomReadListener() {
        byte[] excelBytes = createMultiSheetWorkbook();
        List<String> invokedUsernames = new ArrayList<>();

        ReadListener<DemoUser> customListener = new ReadListener<>() {
            @Override
            public void invoke(DemoUser data, AnalysisContext context) {
                invokedUsernames.add(data.getUsername());
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
            }
        };

        ExcelUtil.reader(new ByteArrayInputStream(excelBytes))
                .sheet(0, DemoUser.class, customListener)
                .read();

        assertEquals(2, invokedUsernames.size());
        assertEquals("张三", invokedUsernames.get(0));
        assertEquals("李四", invokedUsernames.get(1));
    }

    @Test
    @DisplayName("测试 防御性断言：非法入参快速失败 Fail-Fast")
    void testDefensiveValidation() {
        // 1. null InputStream
        assertThrows(IllegalArgumentException.class, () -> ExcelUtil.reader(null));

        // 2. 负数 sheetNo
        assertThrows(IllegalArgumentException.class, () ->
                ExcelUtil.reader(new ByteArrayInputStream(new byte[0])).sheet(-1, DemoUser.class));

        // 3. 空白 sheetName 与空 sheetNo
        assertThrows(IllegalArgumentException.class, () ->
                ExcelUtil.reader(new ByteArrayInputStream(new byte[0])).sheet("   ", DemoUser.class));

        // 4. null clazz
        assertThrows(IllegalArgumentException.class, () ->
                ExcelUtil.reader(new ByteArrayInputStream(new byte[0])).sheet(0, null));

        // 5. 非法 batchSize <= 0
        assertThrows(IllegalArgumentException.class, () ->
                ExcelUtil.reader(new ByteArrayInputStream(new byte[0])).sheet(0, DemoUser.class, 0, batch -> {}));

        // 6. 未配置任何 Sheet 时直接读取
        ExcelReaderBuilder emptyBuilder = ExcelUtil.reader(new ByteArrayInputStream(new byte[0]));
        assertThrows(IllegalStateException.class, emptyBuilder::read);
    }

    @Test
    @DisplayName("测试 多工作表读取时 @ExcelEnum 枚举双向转换正确生效")
    void testMultiSheetEnumConversion() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelBuilder builder = ExcelUtil.builder(out)) {
            List<EnumAndDictUserVO> enumUsers = List.of(
                    new EnumAndDictUserVO(1L, "小红", UserGenderEnum.FEMALE, UserStatusEnum.ENABLE, 2, null, null),
                    new EnumAndDictUserVO(2L, "小明", UserGenderEnum.MALE, UserStatusEnum.DISABLE, 1, null, null)
            );
            builder.sheet("枚举用户表", EnumAndDictUserVO.class, enumUsers);
            builder.finish();
        }

        ExcelMultiSheetResult result = ExcelUtil.reader(new ByteArrayInputStream(out.toByteArray()))
                .sheet("枚举用户表", EnumAndDictUserVO.class)
                .read();

        List<EnumAndDictUserVO> data = result.getSheetData("枚举用户表", EnumAndDictUserVO.class);
        assertEquals(2, data.size());
        assertEquals(UserGenderEnum.FEMALE, data.get(0).getGender());
        assertEquals(UserStatusEnum.ENABLE, data.get(0).getStatus());
        assertEquals(UserGenderEnum.MALE, data.get(1).getGender());
        assertEquals(UserStatusEnum.DISABLE, data.get(1).getStatus());
    }

    @Test
    @DisplayName("测试 多工作表行数统计精准性：别名与多维度查询不覆盖为0且总行数不虚增")
    void testMultiSheetRowCountPrecisionAndNoAliasZeroOverwrite() {
        byte[] excelBytes = createMultiSheetWorkbook();

        // 1. 同步聚合读取：验证总行数严格等于 5 (2 + 3)，且跨索引与表名查询一致
        ExcelMultiSheetResult aggResult = ExcelUtil.reader(new ByteArrayInputStream(excelBytes))
                .sheet(0, DemoUser.class)
                .sheet("导入人员表", ImportUserVO.class)
                .read();

        assertEquals(5, aggResult.getTotalRowCount(), "总行数必须严格等于 5，禁止多别名绑定导致重复虚增");
        assertEquals(2, aggResult.getRowCount(0), "按索引 0 查询行数应为 2");
        assertEquals(2, aggResult.getRowCount("用户表"), "按实际表名查询行数应为 2");
        assertEquals(3, aggResult.getRowCount(1), "按索引 1 查询行数应为 3");
        assertEquals(3, aggResult.getRowCount("导入人员表"), "按定义表名查询行数应为 3");

        // 2. 流式分批读取：验证流式回调下行数不被别名置 0 且总行数准确
        List<DemoUser> streamedUsers = new ArrayList<>();
        ExcelMultiSheetResult streamResult = ExcelUtil.reader(new ByteArrayInputStream(excelBytes))
                .sheet("用户表", DemoUser.class, 10, streamedUsers::addAll)
                .sheet(1, ImportUserVO.class, 10, batch -> {})
                .read();

        assertEquals(5, streamResult.getTotalRowCount(), "流式分批读取总行数必须准确等于 5");
        assertEquals(2, streamResult.getRowCount(0), "流式分批下按索引 0 查询绝对不能被覆盖为 0");
        assertEquals(2, streamResult.getRowCount("用户表"), "流式分批下按表名查询必须为真实行数 2");
        assertEquals(3, streamResult.getRowCount(1), "流式分批下按索引 1 查询必须为真实行数 3");
        assertEquals(3, streamResult.getRowCount("导入人员表"), "流式分批下按实际表名查询必须为 3");
    }

    @Data
    public static class RowTrackingSheetItem implements ExcelRowAware {
        @ExcelProperty("编码")
        @NotBlank(message = "编码不能为空")
        private String code;

        @ExcelProperty("名称")
        private String title;

        @ExcelIgnore
        private Integer rowIndex;
    }

    @Test
    @DisplayName("测试 多工作表物理行号追踪：校验聚合、非校验聚合、跳空行与流式消费常量内存边界")
    void testMultiSheetRowRecordsTrackingAndRowAwareBackfill() throws Exception {
        byte[] workbookBytes;
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // Sheet 0 (校验聚合): 第 1 行表头；第 2 行有效；第 3 行为空行；第 4 行非法(编码为空)；第 5 行有效
            Sheet s0 = wb.createSheet("校验表");
            Row s0Head = s0.createRow(0);
            s0Head.createCell(0).setCellValue("编码");
            s0Head.createCell(1).setCellValue("名称");
            Row s0r2 = s0.createRow(1);
            s0r2.createCell(0).setCellValue("C001");
            s0r2.createCell(1).setCellValue("合法行2");
            // row 2 (物理第 3 行) 留空
            Row s0r4 = s0.createRow(3);
            s0r4.createCell(0).setCellValue("");
            s0r4.createCell(1).setCellValue("非法行4");
            Row s0r5 = s0.createRow(4);
            s0r5.createCell(0).setCellValue("C003");
            s0r5.createCell(1).setCellValue("合法行5");

            // Sheet 1 (非校验聚合): 第 1 行表头；第 2 行有效；第 3 行留空；第 4 行有效
            Sheet s1 = wb.createSheet("普通表");
            Row s1Head = s1.createRow(0);
            s1Head.createCell(0).setCellValue("编码");
            s1Head.createCell(1).setCellValue("名称");
            Row s1r2 = s1.createRow(1);
            s1r2.createCell(0).setCellValue("N001");
            s1r2.createCell(1).setCellValue("普通行2");
            Row s1r4 = s1.createRow(3);
            s1r4.createCell(0).setCellValue("N002");
            s1r4.createCell(1).setCellValue("普通行4");

            // Sheet 2 (全错误行): 第 1 行表头；第 2 行非法
            Sheet s2 = wb.createSheet("全错表");
            Row s2Head = s2.createRow(0);
            s2Head.createCell(0).setCellValue("编码");
            s2Head.createCell(1).setCellValue("名称");
            Row s2r2 = s2.createRow(1);
            s2r2.createCell(0).setCellValue("");
            s2r2.createCell(1).setCellValue("必错");

            wb.write(out);
            workbookBytes = out.toByteArray();
        }

        // 1. 聚合读取模式：混合校验 Sheet + 非校验 Sheet + 全错 Sheet
        ExcelMultiSheetResult aggResult = ExcelUtil.reader(new ByteArrayInputStream(workbookBytes))
                .sheet(0, RowTrackingSheetItem.class, true)
                .sheet(1, RowTrackingSheetItem.class, false)
                .sheet(2, RowTrackingSheetItem.class, true)
                .read();

        // Sheet 0 (校验聚合): 仅第 2、5 行合法，物理行号必须严格为 2 和 5，且 ExcelRowAware 同步回填
        List<ExcelRowData<RowTrackingSheetItem>> s0RecordsByIndex = aggResult.getSheetRowRecords(0, RowTrackingSheetItem.class);
        List<ExcelRowData<RowTrackingSheetItem>> s0RecordsByName = aggResult.getSheetRowRecords("校验表", RowTrackingSheetItem.class);
        assertEquals(2, s0RecordsByIndex.size());
        assertEquals(s0RecordsByIndex, s0RecordsByName);
        assertEquals(2, s0RecordsByIndex.get(0).rowIndex());
        assertEquals("C001", s0RecordsByIndex.get(0).data().getCode());
        assertEquals(2, s0RecordsByIndex.get(0).data().getRowIndex());
        assertEquals(5, s0RecordsByIndex.get(1).rowIndex());
        assertEquals("C003", s0RecordsByIndex.get(1).data().getCode());
        assertEquals(5, s0RecordsByIndex.get(1).data().getRowIndex());
        assertEquals(1, aggResult.getSheetErrors(0).size());
        assertEquals(4, aggResult.getSheetErrors(0).get(0).getRowIndex());

        // Sheet 1 (非校验聚合): 跳过空行 3，物理行号必须为 2 和 4
        List<ExcelRowData<RowTrackingSheetItem>> s1Records = aggResult.getSheetRowRecords("普通表");
        assertEquals(2, s1Records.size());
        assertEquals(2, s1Records.get(0).rowIndex());
        assertEquals(2, s1Records.get(0).data().getRowIndex());
        assertEquals(4, s1Records.get(1).rowIndex());
        assertEquals(4, s1Records.get(1).data().getRowIndex());

        // Sheet 2 (全错表) 与不存在的表：保底返回不可变空列表（绝不返回 null）
        List<ExcelRowData<RowTrackingSheetItem>> s2Records = aggResult.getSheetRowRecords(2);
        assertNotNull(s2Records);
        assertTrue(s2Records.isEmpty());
        assertNotNull(aggResult.getSheetRowRecords(999));
        assertTrue(aggResult.getSheetRowRecords(999).isEmpty());
        assertNotNull(aggResult.getSheetRowRecords("不存在"));
        assertTrue(aggResult.getSheetRowRecords("不存在").isEmpty());
        assertTrue(aggResult.getIndexedRowRecords().containsKey(0));
        assertTrue(aggResult.getNamedRowRecords().containsKey("普通表"));

        // 2. 流式分批模式：ExcelRowAware 回填物理行号，但结果容器不滞留 rowRecords 守住 O(1) 内存
        List<RowTrackingSheetItem> validatedStreamed = new ArrayList<>();
        List<RowTrackingSheetItem> plainStreamed = new ArrayList<>();
        ExcelMultiSheetResult streamResult = ExcelUtil.reader(new ByteArrayInputStream(workbookBytes))
                .sheet(0, RowTrackingSheetItem.class, 10, validatedStreamed::addAll, true)
                .sheet(1, RowTrackingSheetItem.class, 10, plainStreamed::addAll, false)
                .read();

        assertEquals(2, validatedStreamed.size());
        assertEquals(2, validatedStreamed.get(0).getRowIndex());
        assertEquals(5, validatedStreamed.get(1).getRowIndex());
        assertEquals(2, plainStreamed.size());
        assertEquals(2, plainStreamed.get(0).getRowIndex());
        assertEquals(4, plainStreamed.get(1).getRowIndex());
        assertTrue(streamResult.getSheetRowRecords(0).isEmpty());
        assertTrue(streamResult.getSheetRowRecords(1).isEmpty());
    }
}
