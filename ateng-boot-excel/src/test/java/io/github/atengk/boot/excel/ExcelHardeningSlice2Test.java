package io.github.atengk.boot.excel;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import com.sun.net.httpserver.HttpServer;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.excel.annotation.ExcelImage;
import io.github.atengk.boot.excel.async.AsyncExportTaskService;
import io.github.atengk.boot.excel.async.ExportTask;
import io.github.atengk.boot.excel.async.ExportTaskStatus;
import io.github.atengk.boot.excel.async.InMemoryAsyncExportTaskService;
import io.github.atengk.boot.excel.async.RedisAsyncExportTaskService;
import io.github.atengk.boot.excel.async.storage.ExportFileStorage;
import io.github.atengk.boot.excel.async.storage.LocalFileSystemStorage;
import io.github.atengk.boot.excel.exception.ExcelValidationException;
import io.github.atengk.boot.excel.properties.ExcelProperties;
import io.github.atengk.boot.excel.properties.ExcelSecurityProperties;
import io.github.atengk.boot.excel.util.ExcelUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import org.springframework.util.unit.DataSize;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Excel 底座安全与生命周期加固切片 2 对抗性单元测试（#943）
 * <p>
 * 覆盖异步导出受管调度与实时进度上报、Redis ZSet 过期索引原子抢占对称回收、
 * {@link LocalFileSystemStorage} 路径穿越拦截，以及 {@link io.github.atengk.boot.excel.converter.SmartImageConverter} SSRF/OOM/路径安全护栏。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-13
 */
@DisplayName("Excel 加固切片 2：异步调度、分布式回收与多媒体存储安全")
class ExcelHardeningSlice2Test {

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
    @DisplayName("AsyncExportTaskService#updateProgress 应在内存与 Redis 模式实时钳制 0~99 且完成后置为 100")
    void shouldClampAndPersistLiveProgressInMemoryAndRedis() throws Exception {
        ExcelProperties props = new ExcelProperties();
        props.getAsyncExport().setTempDir(tempDir.resolve("mem").toString());
        InMemoryAsyncExportTaskService memoryService = new InMemoryAsyncExportTaskService(props);

        // 1. 验证内存异步任务中心进度钳制与完成归 100
        CountDownLatch memStarted = new CountDownLatch(1);
        CountDownLatch memProceed = new CountDownLatch(1);
        AtomicInteger observedNeg = new AtomicInteger(-1);
        AtomicInteger observedMid = new AtomicInteger(-1);
        AtomicInteger observedOver = new AtomicInteger(-1);

        try {
            String memTaskId = memoryService.submit("内存实时进度测试", out -> {
                memStarted.countDown();
                assertTrue(memProceed.await(5, TimeUnit.SECONDS));
                out.write("done".getBytes(StandardCharsets.UTF_8));
            });

            assertTrue(memStarted.await(5, TimeUnit.SECONDS));

            memoryService.updateProgress(memTaskId, -15);
            observedNeg.set(memoryService.getTask(memTaskId).getProgress());

            memoryService.updateProgress(memTaskId, 68);
            observedMid.set(memoryService.getTask(memTaskId).getProgress());

            memoryService.updateProgress(memTaskId, 200);
            observedOver.set(memoryService.getTask(memTaskId).getProgress());

            memProceed.countDown();

            ExportTask completed = awaitTerminal(memoryService, memTaskId);
            assertNotNull(completed);
            assertEquals(ExportTaskStatus.SUCCESS, completed.getStatus());
            assertEquals(100, completed.getProgress());

            // 终态后禁止回退进度
            memoryService.updateProgress(memTaskId, 30);
            assertEquals(100, memoryService.getTask(memTaskId).getProgress());

            assertEquals(0, observedNeg.get(), "负数进度应被钳制为 0");
            assertEquals(68, observedMid.get());
            assertEquals(99, observedOver.get(), "运行期超过 99 的进度应被钳制为 99");
        } finally {
            memProceed.countDown();
            memoryService.destroy();
        }

        // 2. 验证 Redis 异步任务中心进度落盘与钳制
        FakeRedisBackend fakeRedis = new FakeRedisBackend();
        ExcelProperties redisProps = new ExcelProperties();
        redisProps.getAsyncExport().setTempDir(tempDir.resolve("redis").toString());
        LocalFileSystemStorage storage = new LocalFileSystemStorage(redisProps);
        RedisAsyncExportTaskService redisService = new RedisAsyncExportTaskService(redisProps, fakeRedis.template, storage);

        CountDownLatch redisStarted = new CountDownLatch(1);
        CountDownLatch redisProceed = new CountDownLatch(1);
        try {
            String redisTaskId = redisService.submit("Redis实时进度测试", out -> {
                redisStarted.countDown();
                assertTrue(redisProceed.await(5, TimeUnit.SECONDS));
                out.write("redis-done".getBytes(StandardCharsets.UTF_8));
            });

            assertTrue(redisStarted.await(5, TimeUnit.SECONDS));

            redisService.updateProgress(redisTaskId, -5);
            assertEquals(0, redisService.getTask(redisTaskId).getProgress());

            redisService.updateProgress(redisTaskId, 77);
            assertEquals(77, redisService.getTask(redisTaskId).getProgress());

            redisService.updateProgress(redisTaskId, 120);
            assertEquals(99, redisService.getTask(redisTaskId).getProgress());

            redisProceed.countDown();

            ExportTask completed = awaitTerminal(redisService, redisTaskId);
            assertNotNull(completed);
            assertEquals(ExportTaskStatus.SUCCESS, completed.getStatus());
            assertEquals(100, completed.getProgress());

            redisService.updateProgress(redisTaskId, 10);
            assertEquals(100, redisService.getTask(redisTaskId).getProgress());
        } finally {
            redisProceed.countDown();
            redisService.destroy();
        }
    }

    @Test
    @DisplayName("Redis 过期索引应写入 expire-index 且延长 1h 元数据 TTL，多节点并发 cleanExpiredTasks 仅原子抢占触发一次存储删除")
    void shouldRegisterExpireIndexAndReclaimStorageAtomicallyAcrossNodes() throws Exception {
        FakeRedisBackend fakeRedis = new FakeRedisBackend();
        Path sharedStorageDir = tempDir.resolve("shared-storage");

        // 1. 构造长保留期任务 T1 与短保留期任务 T2
        ExcelProperties longProps = new ExcelProperties();
        longProps.getAsyncExport().setTempDir(sharedStorageDir.toString());
        longProps.getAsyncExport().setRetentionPeriod(Duration.ofHours(2));
        longProps.getAsyncExport().getRedis().setTtl(Duration.ofHours(3));

        ExcelProperties shortProps = new ExcelProperties();
        shortProps.getAsyncExport().setTempDir(sharedStorageDir.toString());
        shortProps.getAsyncExport().setRetentionPeriod(Duration.ofMillis(40));
        shortProps.getAsyncExport().getRedis().setTtl(Duration.ofHours(3));

        AtomicInteger t2DeleteCount = new AtomicInteger(0);
        LocalFileSystemStorage delegateStorage = new LocalFileSystemStorage(longProps);
        final String[] t2IdHolder = new String[1];
        ExportFileStorage countingStorage = new ExportFileStorage() {
            @Override
            public String store(String taskId, File tempFile, String fileName) throws IOException {
                return delegateStorage.store(taskId, tempFile, fileName);
            }

            @Override
            public InputStream getInputStream(String taskId, String storageKey) throws IOException {
                return delegateStorage.getInputStream(taskId, storageKey);
            }

            @Override
            public String getDownloadUrl(String taskId, String storageKey) {
                return delegateStorage.getDownloadUrl(taskId, storageKey);
            }

            @Override
            public void delete(String taskId, String storageKey) {
                if (taskId != null && taskId.equals(t2IdHolder[0])) {
                    t2DeleteCount.incrementAndGet();
                }
                delegateStorage.delete(taskId, storageKey);
            }
        };

        RedisAsyncExportTaskService nodeA = new RedisAsyncExportTaskService(longProps, fakeRedis.template, countingStorage);
        RedisAsyncExportTaskService nodeB = new RedisAsyncExportTaskService(shortProps, fakeRedis.template, countingStorage);
        try {
            String t1 = nodeA.submit("未过期账单", out -> out.write("t1-payload".getBytes(StandardCharsets.UTF_8)));
            String t2 = nodeB.submit("即将过期账单", out -> out.write("t2-payload".getBytes(StandardCharsets.UTF_8)));
            t2IdHolder[0] = t2;

            ExportTask task1 = awaitTerminal(nodeA, t1);
            ExportTask task2 = awaitTerminal(nodeB, t2);
            assertNotNull(task1);
            assertNotNull(task2);
            assertEquals(ExportTaskStatus.SUCCESS, task1.getStatus());
            assertEquals(ExportTaskStatus.SUCCESS, task2.getStatus());

            String keyPrefix = longProps.getAsyncExport().getRedis().getKeyPrefix();
            // 验证元数据 TTL 延长 1 小时宽限期（3h + 1h = 4h）
            assertEquals(Duration.ofHours(4), fakeRedis.ttlStore.get(keyPrefix + t1), "完成后元数据 TTL 应延长 1 小时宽限期");
            // 验证 ZSet 过期索引存在 taskId|storageKey 条目
            String m1 = t1 + "|" + task1.getStorageKey();
            String m2 = t2 + "|" + task2.getStorageKey();
            assertTrue(fakeRedis.zsetStore.containsKey(m1));
            assertTrue(fakeRedis.zsetStore.containsKey(m2));
            assertEquals(keyPrefix + "expire-index", fakeRedis.lastZSetKey);

            // 等待 T2 越过 40ms 过期线
            Thread.sleep(90);

            // 模拟两节点并发竞争执行 cleanExpiredTasks（各自使用其配置 TTL，T1 未过期、T2 已到期）
            runConcurrently(
                    () -> nodeA.cleanExpiredTasks(null),
                    () -> nodeB.cleanExpiredTasks(null)
            );

            // 断言 T2 被原子抢占且仅删除一次存储文件并清除元数据与索引；T1 保留完好
            assertEquals(1, t2DeleteCount.get(), "多节点并发清理只允许原子抢占成功者执行一次 ExportFileStorage#delete");
            assertFalse(fakeRedis.kvStore.containsKey(keyPrefix + t2), "过期任务元数据 Key 应同步删除");
            assertFalse(fakeRedis.zsetStore.containsKey(m2), "过期索引条目应被 ZREM 移除");
            try (InputStream in2 = delegateStorage.getInputStream(t2, task2.getStorageKey())) {
                assertNull(in2, "过期物理文件应已被回收删除");
            }

            assertTrue(fakeRedis.kvStore.containsKey(keyPrefix + t1), "未过期任务元数据应保留");
            assertTrue(fakeRedis.zsetStore.containsKey(m1), "未过期任务索引应保留");
            try (InputStream in1 = delegateStorage.getInputStream(t1, task1.getStorageKey())) {
                assertNotNull(in1, "未过期物理文件不应被误删");
            }
        } finally {
            nodeA.destroy();
            nodeB.destroy();
        }
    }

    @Test
    @DisplayName("ExcelSecurityProperties 默认安全护栏配置与 LocalFileSystemStorage 路径穿越阻断生效")
    void shouldProvideSecurityDefaultsAndBlockStoragePathTraversal() throws Exception {
        // 1. 验证 ExcelSecurityProperties 新配置默认值
        ExcelSecurityProperties securityProps = new ExcelSecurityProperties();
        assertEquals(DataSize.ofMegabytes(10), securityProps.getMaxImageSize());
        assertEquals(Duration.ofSeconds(3), securityProps.getImageConnectTimeout());
        assertEquals(Duration.ofSeconds(10), securityProps.getImageReadTimeout());
        assertTrue(securityProps.isBlockInternalImageUrls());
        assertTrue(securityProps.isAllowLocalImageFile());
        assertNull(securityProps.getAllowedImageBaseDir());

        // 2. 准备受管存储根目录及其外部越界文件
        Path storageBaseDir = tempDir.resolve("managed-storage");
        Path outsideSecret = tempDir.resolve("outside-secret.xlsx");
        java.nio.file.Files.writeString(outsideSecret, "TOP_SECRET", StandardCharsets.UTF_8);

        ExcelProperties properties = new ExcelProperties();
        properties.getAsyncExport().setTempDir(storageBaseDir.toString());
        LocalFileSystemStorage storage = new LocalFileSystemStorage(properties);

        // 恶意相对路径穿越、绝对越界路径、空字符与恶意 taskId 均应抛出 ExcelValidationException
        assertThrows(ExcelValidationException.class, () -> storage.getInputStream("t1", "../outside-secret.xlsx"));
        assertThrows(ExcelValidationException.class, () -> storage.getInputStream("t1", "sub/../../outside-secret.xlsx"));
        assertThrows(ExcelValidationException.class, () -> storage.getInputStream("t1", "/etc/passwd"));
        assertThrows(ExcelValidationException.class, () -> storage.getInputStream("t1", outsideSecret.toAbsolutePath().toString()));
        assertThrows(ExcelValidationException.class, () -> storage.getInputStream("t1", "bad\0key.xlsx"));
        assertThrows(ExcelValidationException.class, () -> storage.delete("t1", "../outside-secret.xlsx"));
        assertThrows(ExcelValidationException.class, () -> storage.delete("../outside-secret", null));
        assertTrue(java.nio.file.Files.exists(outsideSecret), "越界外部文件严禁被 delete 误删");

        Path tmpPayload = tempDir.resolve("payload.tmp");
        java.nio.file.Files.writeString(tmpPayload, "ok-content", StandardCharsets.UTF_8);
        assertThrows(ExcelValidationException.class, () -> storage.store("../escape-task", tmpPayload.toFile(), "x.xlsx"));

        // 合法相对子路径与合法 stored key 应正常访问
        String storedKey = storage.store("valid-task-1", tmpPayload.toFile(), "valid.xlsx");
        try (InputStream in = storage.getInputStream("valid-task-1", storedKey)) {
            assertNotNull(in);
            assertEquals("ok-content", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        storage.delete("valid-task-1", storedKey);
        assertNull(storage.getInputStream("valid-task-1", storedKey));
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageSecurityVO {
        @ExcelProperty("名称")
        private String title;

        @ExcelProperty("封面图")
        @ExcelImage(rowHeight = 60)
        private String imageSource;
    }

    private static byte[] createSmallPng(int width, int height, Color color) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    @Test
    @DisplayName("SmartImageConverter 默认拦截 SSRF 内网/环回/云元数据地址与非 HTTP(S) 协议")
    void shouldBlockSsrfUrlsAndNonHttpProtocolsByDefault() throws Exception {
        AtomicInteger serverHits = new AtomicInteger(0);
        byte[] tinyPng = createSmallPng(4, 4, Color.BLUE);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/img.png", exchange -> {
            serverHits.incrementAndGet();
            exchange.sendResponseHeaders(200, tinyPng.length);
            exchange.getResponseBody().write(tinyPng);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            ExcelUtil.initProperties(new ExcelProperties());

            List<ImageSecurityVO> ssrfSamples = List.of(
                    new ImageSecurityVO("loopback-ip", "http://127.0.0.1:" + port + "/img.png"),
                    new ImageSecurityVO("loopback-host", "http://localhost:" + port + "/img.png"),
                    new ImageSecurityVO("ipv6-loopback", "http://[::1]:" + port + "/img.png"),
                    new ImageSecurityVO("cloud-meta", "http://169.254.169.254/latest/meta-data/"),
                    new ImageSecurityVO("private-10", "http://10.0.0.1/img.png"),
                    new ImageSecurityVO("private-192", "http://192.168.1.10/img.png"),
                    new ImageSecurityVO("file-scheme", "file:///etc/passwd"),
                    new ImageSecurityVO("ftp-scheme", "ftp://example.com/img.png")
            );

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ExcelUtil.export(out, ssrfSamples, ImageSecurityVO.class);

            assertEquals(0, serverHits.get(), "开启 blockInternalImageUrls 后绝不允许对环回测试服务器发起实际 HTTP 连接");
            try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
                assertTrue(wb.getAllPictures().isEmpty(), "被 SSRF 拦截的 URL 不应嵌入任何图片数据");
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("SmartImageConverter 强制执行 maxImageSize 有界读取、Duration 超时及重定向协议拦截")
    void shouldEnforceMaxImageSizeTimeoutAndRedirectProtocolCheck() throws Exception {
        byte[] okPng = createSmallPng(4, 4, Color.GREEN);
        byte[] oversizedPng = new byte[okPng.length + 512];
        System.arraycopy(okPng, 0, oversizedPng, 0, okPng.length);

        ExecutorService serverPool = new ThreadPoolExecutor(
                4, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new NamedThreadFactory("test-img-http-")
        );
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(serverPool);
        server.createContext("/ok.png", exchange -> {
            exchange.sendResponseHeaders(200, okPng.length);
            exchange.getResponseBody().write(okPng);
            exchange.close();
        });
        server.createContext("/big.png", exchange -> {
            exchange.sendResponseHeaders(200, oversizedPng.length);
            exchange.getResponseBody().write(oversizedPng);
            exchange.close();
        });
        server.createContext("/chunked-big.png", exchange -> {
            // Chunked 传输不带 Content-Length，验证 FileUtil.readNBytesSafe 流式有界拦截
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(oversizedPng);
            exchange.close();
        });
        server.createContext("/slow.png", exchange -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, okPng.length);
            exchange.getResponseBody().write(okPng);
            exchange.close();
        });
        server.createContext("/redirect-file", exchange -> {
            exchange.getResponseHeaders().add("Location", "file:///etc/passwd");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            ExcelProperties props = new ExcelProperties();
            props.getSecurity().setBlockInternalImageUrls(false);
            props.getSecurity().setMaxImageSize(DataSize.ofBytes(okPng.length + 32L));
            props.getSecurity().setImageConnectTimeout(Duration.ofMillis(800));
            props.getSecurity().setImageReadTimeout(Duration.ofMillis(400));
            ExcelUtil.initProperties(props);

            List<ImageSecurityVO> rows = List.of(
                    new ImageSecurityVO("正常小图", "http://127.0.0.1:" + port + "/ok.png"),
                    new ImageSecurityVO("超限大图", "http://127.0.0.1:" + port + "/big.png"),
                    new ImageSecurityVO("分块无头超限大图", "http://127.0.0.1:" + port + "/chunked-big.png"),
                    new ImageSecurityVO("读取超时图", "http://127.0.0.1:" + port + "/slow.png"),
                    new ImageSecurityVO("恶意重定向", "http://127.0.0.1:" + port + "/redirect-file")
            );

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ExcelUtil.export(out, rows, ImageSecurityVO.class);

            try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
                assertEquals(1, wb.getAllPictures().size(), "仅合法未超限且未超时的图片应成功内嵌");
            }
        } finally {
            server.stop(0);
            serverPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("SmartImageConverter 阻断本地图片路径穿越、系统敏感目录并强制校验 allowedImageBaseDir")
    void shouldSanitizeLocalImagePathsAndEnforceAllowedBaseDir() throws Exception {
        byte[] png = createSmallPng(6, 6, Color.RED);
        Path allowedDir = Files.createDirectories(tempDir.resolve("allowed-images"));
        Path outsideDir = Files.createDirectories(tempDir.resolve("outside-images"));
        Path allowedImg = allowedDir.resolve("safe.png");
        Path outsideImg = outsideDir.resolve("outside.png");
        Files.write(allowedImg, png);
        Files.write(outsideImg, png);

        // 1. 开启基目录白名单后：仅允许 allowedDir 内路径，拦截外部文件、.. 穿越及系统敏感目录
        ExcelProperties props = new ExcelProperties();
        props.getSecurity().setAllowedImageBaseDir(allowedDir.toAbsolutePath().toString());
        ExcelUtil.initProperties(props);

        String traversalIntoAllowed = outsideDir.toAbsolutePath() + "/../allowed-images/safe.png";
        List<ImageSecurityVO> rows = List.of(
                new ImageSecurityVO("合规基目录图片", allowedImg.toAbsolutePath().toString()),
                new ImageSecurityVO("带..穿越路径", traversalIntoAllowed),
                new ImageSecurityVO("基目录外图片", outsideImg.toAbsolutePath().toString()),
                new ImageSecurityVO("Linux敏感路径", "/etc/passwd"),
                new ImageSecurityVO("Windows敏感路径", "C:\\Windows\\win.ini")
        );

        ByteArrayOutputStream out1 = new ByteArrayOutputStream();
        ExcelUtil.export(out1, rows, ImageSecurityVO.class);
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out1.toByteArray()))) {
            assertEquals(1, wb.getAllPictures().size(), "仅基目录内且不含穿越段的图片允许加载");
        }

        // 2. 不配置 allowedImageBaseDir 时，仍允许合法临时路径但强制阻断 .. 穿越与系统敏感目录
        ExcelProperties defaultLocalProps = new ExcelProperties();
        ExcelUtil.initProperties(defaultLocalProps);
        ByteArrayOutputStream outDefault = new ByteArrayOutputStream();
        ExcelUtil.export(outDefault, List.of(
                new ImageSecurityVO("合法本地图片", outsideImg.toAbsolutePath().toString()),
                new ImageSecurityVO("无基目录..穿越", traversalIntoAllowed),
                new ImageSecurityVO("Linux敏感路径", "/etc/passwd"),
                new ImageSecurityVO("Windows敏感路径", "C:\\Windows\\win.ini")
        ), ImageSecurityVO.class);
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(outDefault.toByteArray()))) {
            assertEquals(1, wb.getAllPictures().size(), "未配置基目录时也必须拦截 .. 穿越与系统敏感目录");
        }

        // 3. 全局关闭本地文件图片加载后：所有本地路径均应拦截
        props.getSecurity().setAllowLocalImageFile(false);
        ExcelUtil.initProperties(props);
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        ExcelUtil.export(out2, List.of(new ImageSecurityVO("禁止本地加载", allowedImg.toAbsolutePath().toString())), ImageSecurityVO.class);
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out2.toByteArray()))) {
            assertTrue(wb.getAllPictures().isEmpty(), "allowLocalImageFile=false 时不应加载任何本地文件图片");
        }
    }

    private static ExportTask awaitTerminal(AsyncExportTaskService service, String taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        ExportTask task = service.getTask(taskId);
        while ((task == null || task.getStatus() == ExportTaskStatus.PENDING || task.getStatus() == ExportTaskStatus.PROCESSING)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
            task = service.getTask(taskId);
        }
        return task;
    }

    private static void runConcurrently(Runnable firstTask, Runnable secondTask) throws InterruptedException {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);
        ExecutorService pool = new ThreadPoolExecutor(
                2, 2, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new NamedThreadFactory("test-concurrent-runner-")
        );
        try {
            for (Runnable action : List.of(firstTask, secondTask)) {
                pool.submit(() -> {
                    try {
                        assertTrue(startGate.await(3, TimeUnit.SECONDS));
                        action.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @SuppressWarnings("unchecked")
    private static class FakeRedisBackend {
        final Map<String, String> kvStore = new ConcurrentHashMap<>();
        final Map<String, Duration> ttlStore = new ConcurrentHashMap<>();
        final Map<String, Double> zsetStore = new ConcurrentHashMap<>();
        volatile String lastZSetKey;
        final StringRedisTemplate template;

        FakeRedisBackend() {
            template = mock(StringRedisTemplate.class);
            ValueOperations<String, String> valueOps = mock(ValueOperations.class);
            ZSetOperations<String, String> zsetOps = mock(ZSetOperations.class);

            when(template.opsForValue()).thenReturn(valueOps);
            when(template.opsForZSet()).thenReturn(zsetOps);

            when(valueOps.get(anyString())).thenAnswer(inv -> kvStore.get(inv.getArgument(0, String.class)));
            doAnswer(inv -> {
                String k = inv.getArgument(0, String.class);
                String v = inv.getArgument(1, String.class);
                Duration d = inv.getArgument(2, Duration.class);
                kvStore.put(k, v);
                if (d != null) {
                    ttlStore.put(k, d);
                }
                return null;
            }).when(valueOps).set(anyString(), anyString(), any(Duration.class));

            when(template.delete(anyString())).thenAnswer(inv -> kvStore.remove(inv.getArgument(0, String.class)) != null);

            when(zsetOps.add(anyString(), anyString(), anyDouble())).thenAnswer(inv -> {
                lastZSetKey = inv.getArgument(0, String.class);
                String member = inv.getArgument(1, String.class);
                Double score = inv.getArgument(2, Double.class);
                zsetStore.put(member, score);
                return Boolean.TRUE;
            });

            when(zsetOps.rangeByScore(anyString(), anyDouble(), anyDouble())).thenAnswer(inv -> {
                double min = inv.getArgument(1, Double.class);
                double max = inv.getArgument(2, Double.class);
                Set<String> matched = new LinkedHashSet<>();
                for (Map.Entry<String, Double> entry : zsetStore.entrySet()) {
                    if (entry.getValue() >= min && entry.getValue() <= max) {
                        matched.add(entry.getKey());
                    }
                }
                return matched;
            });

            when(zsetOps.remove(anyString(), any(Object[].class))).thenAnswer(inv -> {
                long count = 0;
                for (int i = 1; i < inv.getArguments().length; i++) {
                    Object arg = inv.getArgument(i);
                    if (arg instanceof String s && zsetStore.remove(s) != null) {
                        count++;
                    }
                }
                return count;
            });

            when(template.execute(
                    org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                    org.mockito.ArgumentMatchers.anyList(),
                    any(),
                    any())).thenAnswer(inv -> {
                List<String> keys = inv.getArgument(1);
                String newJson = inv.getArgument(2);
                String key = keys.get(0);
                String current = kvStore.get(key);
                if (current != null && current.contains("\"PROCESSING\"")) {
                    kvStore.put(key, newJson);
                    return 1L;
                }
                return 0L;
            });
        }
    }
}
