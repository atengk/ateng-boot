package io.github.atengk.boot.excel.async;

import io.github.atengk.boot.excel.properties.ExcelAsyncExportProperties;
import io.github.atengk.boot.excel.properties.ExcelProperties;
import io.github.atengk.boot.excel.util.ExcelUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 异步导出任务中心与内存调度服务测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
class AsyncExportTaskServiceTest {

    private InMemoryAsyncExportTaskService taskService;
    private Path tempStorageDir;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskUserVO {
        @ExcelProperty("姓名")
        private String name;

        @ExcelProperty("年龄")
        private Integer age;
    }

    @BeforeEach
    void setUp() throws IOException {
        tempStorageDir = Paths.get(System.getProperty("java.io.tmpdir"), "ateng-excel-async-test");
        Files.createDirectories(tempStorageDir);

        ExcelProperties properties = new ExcelProperties();
        ExcelAsyncExportProperties asyncProps = properties.getAsyncExport();
        asyncProps.setTempDir(tempStorageDir.toString());
        asyncProps.setCorePoolSize(2);
        asyncProps.setMaxPoolSize(4);
        asyncProps.setQueueCapacity(20);

        taskService = new InMemoryAsyncExportTaskService(properties);
    }

    @AfterEach
    void tearDown() {
        if (taskService != null) {
            taskService.destroy();
        }
    }

    @Test
    @DisplayName("测试异步导出任务正常提交、进度感知与成功完成")
    void testNormalAsyncExport() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        String taskId = taskService.submit("全量用户导出", out -> {
            ExcelUtil.export(out, Collections.singletonList(new TaskUserVO("张三", 28)), TaskUserVO.class);
            latch.countDown();
        });

        assertNotNull(taskId);
        assertTrue(latch.await(10, TimeUnit.SECONDS), "异步导出应在 10 秒内执行完毕");

        long deadline = System.currentTimeMillis() + 2000;
        ExportTask task = taskService.getTask(taskId);
        while ((task == null || task.getStatus() == ExportTaskStatus.PENDING || task.getStatus() == ExportTaskStatus.PROCESSING) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            task = taskService.getTask(taskId);
        }

        assertNotNull(task);
        assertEquals(ExportTaskStatus.SUCCESS, task.getStatus(), "任务状态应为 SUCCESS");
        assertEquals(100, task.getProgress(), "完成进度应为 100%");
        assertNotNull(task.getFilePath(), "产物文件路径不应为空");
        assertTrue(new File(task.getFilePath()).exists(), "导出的产物 Excel 文件应真实存在");
        assertTrue(task.getFileSize() > 0, "文件大小应大于 0");
        assertNotNull(task.getFinishTime(), "完成时间不应为空");
    }

    @Test
    @DisplayName("测试异步导出执行中异常捕获与失败状态持久化")
    void testFailedAsyncExport() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        String taskId = taskService.submit("故障导出任务", out -> {
            latch.countDown();
            throw new RuntimeException("模拟数据库查询超时异常");
        });

        assertNotNull(taskId);
        assertTrue(latch.await(15, TimeUnit.SECONDS));

        long deadline = System.currentTimeMillis() + 5000;
        ExportTask task = taskService.getTask(taskId);
        while ((task == null || task.getStatus() == ExportTaskStatus.PENDING || task.getStatus() == ExportTaskStatus.PROCESSING) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            task = taskService.getTask(taskId);
        }

        assertNotNull(task);
        assertEquals(ExportTaskStatus.FAILED, task.getStatus(), "任务状态应标记为 FAILED");
        assertNotNull(task.getErrorMsg());
        assertTrue(task.getErrorMsg().contains("模拟数据库查询超时异常"), "应记录具体的失败原因堆栈信息");
    }

    @Test
    @DisplayName("测试异步导出任务取消与状态变更")
    void testCancelAsyncExport() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);

        String taskId = taskService.submit("长耗时导出任务", out -> {
            startLatch.countDown();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(startLatch.await(15, TimeUnit.SECONDS));

        boolean cancelled = taskService.cancel(taskId);
        assertTrue(cancelled, "任务取消方法应返回 true");

        ExportTask task = taskService.getTask(taskId);
        assertNotNull(task);
        assertEquals(ExportTaskStatus.CANCELLED, task.getStatus(), "任务状态应变更为 CANCELLED");
    }

    @Test
    @DisplayName("测试过期导出任务与物理文件自动清理")
    void testCleanExpiredTasks() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        String taskId = taskService.submit("待清理任务", out -> {
            ExcelUtil.export(out, Collections.singletonList(new TaskUserVO("李四", 30)), TaskUserVO.class);
            latch.countDown();
        });

        assertTrue(latch.await(15, TimeUnit.SECONDS));

        long deadline = System.currentTimeMillis() + 5000;
        ExportTask task = taskService.getTask(taskId);
        while ((task == null || task.getStatus() == ExportTaskStatus.PENDING || task.getStatus() == ExportTaskStatus.PROCESSING) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            task = taskService.getTask(taskId);
        }

        assertNotNull(task);
        assertEquals(ExportTaskStatus.SUCCESS, task.getStatus());
        String filePath = task.getFilePath();
        assertTrue(new File(filePath).exists());

        // 人工模拟将完成时间修改为 24 小时之前
        task.setFinishTime(LocalDateTime.now().minusHours(24));

        // 触发清理保留周期 1 小时之前的任务
        taskService.cleanExpiredTasks(Duration.ofHours(1));

        // 验证内存记录与物理文件均已清理
        assertNull(taskService.getTask(taskId), "过期任务应从内存映射中移除");
        assertFalse(new File(filePath).exists(), "过期任务对应的物理 Excel 文件应被同步删除");
    }
}
