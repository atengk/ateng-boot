package io.github.atengk.boot.excel.async;

import io.github.atengk.boot.excel.properties.ExcelAsyncExportProperties;
import io.github.atengk.boot.excel.properties.ExcelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * 内存与本地磁盘异步导出任务中心默认实现
 * <p>
 * 基于并发哈希表与受管线程池执行大报表后台流式写出，支持任务进度感知、错误捕获与过期文件定期清理。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class InMemoryAsyncExportTaskService implements AsyncExportTaskService, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(InMemoryAsyncExportTaskService.class);

    private final Map<String, ExportTask> taskMap = new ConcurrentHashMap<>(128);
    private final Map<String, Future<?>> futureMap = new ConcurrentHashMap<>(128);

    private final Path tempDirPath;
    private final ThreadPoolExecutor executorService;

    public InMemoryAsyncExportTaskService() {
        this(new ExcelProperties());
    }

    public InMemoryAsyncExportTaskService(ExcelProperties excelProperties) {
        ExcelAsyncExportProperties props = (excelProperties != null && excelProperties.getAsyncExport() != null)
                ? excelProperties.getAsyncExport()
                : new ExcelAsyncExportProperties();

        String dir = props.getTempDir();
        if (dir == null || dir.isBlank()) {
            this.tempDirPath = Paths.get(System.getProperty("java.io.tmpdir"), "ateng-excel-async");
        } else {
            this.tempDirPath = Paths.get(dir);
        }

        try {
            Files.createDirectories(this.tempDirPath);
        } catch (IOException e) {
            log.warn("创建异步导出临时目录失败: {}", e.getMessage());
        }

        int core = Math.max(1, props.getCorePoolSize());
        int max = Math.max(core, props.getMaxPoolSize());
        int queueCap = Math.max(10, props.getQueueCapacity());

        this.executorService = new ThreadPoolExecutor(
                core,
                max,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCap),
                new ThreadFactory() {
                    private final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(0);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "ateng-excel-async-" + count.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Override
    public String submit(String taskName, ExportTaskExecutor executor) {
        if (taskName == null || taskName.isBlank()) {
            taskName = "异步报表导出";
        }
        if (executor == null) {
            throw new IllegalArgumentException("ExportTaskExecutor 不可为空");
        }

        String taskId = UUID.randomUUID().toString().replace("-", "");
        File targetFile = tempDirPath.resolve(taskId + ".xlsx").toFile();

        ExportTask task = ExportTask.builder()
                .taskId(taskId)
                .taskName(taskName)
                .status(ExportTaskStatus.PENDING)
                .progress(0)
                .filePath(targetFile.getAbsolutePath())
                .createTime(LocalDateTime.now())
                .build();

        taskMap.put(taskId, task);

        final String finalTaskName = taskName;
        Future<?> future = executorService.submit(() -> {
            task.setStatus(ExportTaskStatus.PROCESSING);
            task.setProgress(10);
            log.info("开始执行异步导出任务 [{}]: {}", taskId, finalTaskName);

            try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                executor.execute(fos);
                if (task.getStatus() == ExportTaskStatus.CANCELLED) {
                    return;
                }
                task.setFileSize(targetFile.length());
                task.setStatus(ExportTaskStatus.SUCCESS);
                task.setProgress(100);
                task.setFinishTime(LocalDateTime.now());
                log.info("异步导出任务完成 [{}]: 文件大小 = {} 字节", taskId, task.getFileSize());
            } catch (Throwable ex) {
                if (task.getStatus() == ExportTaskStatus.CANCELLED) {
                    return;
                }
                task.setStatus(ExportTaskStatus.FAILED);
                task.setErrorMsg(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                task.setFinishTime(LocalDateTime.now());
                log.error("异步导出任务执行失败 [{}]: {}", taskId, ex.getMessage(), ex);
            } finally {
                futureMap.remove(taskId);
            }
        });

        futureMap.put(taskId, future);
        return taskId;
    }

    @Override
    public ExportTask getTask(String taskId) {
        return (taskId != null) ? taskMap.get(taskId) : null;
    }

    @Override
    public boolean cancel(String taskId) {
        if (taskId == null) {
            return false;
        }
        ExportTask task = taskMap.get(taskId);
        if (task == null) {
            return false;
        }
        if (task.getStatus() == ExportTaskStatus.SUCCESS || task.getStatus() == ExportTaskStatus.FAILED) {
            return false;
        }

        Future<?> future = futureMap.remove(taskId);
        if (future != null) {
            future.cancel(true);
        }

        task.setStatus(ExportTaskStatus.CANCELLED);
        task.setFinishTime(LocalDateTime.now());

        // 清理已生成的半成品临时文件
        if (task.getFilePath() != null) {
            try {
                Files.deleteIfExists(Paths.get(task.getFilePath()));
            } catch (IOException ignored) {
            }
        }
        return true;
    }

    @Override
    public void cleanExpiredTasks(Duration retention) {
        if (retention == null || retention.isNegative() || retention.isZero()) {
            retention = Duration.ofHours(24);
        }
        LocalDateTime threshold = LocalDateTime.now().minus(retention);

        taskMap.entrySet().removeIf(entry -> {
            ExportTask task = entry.getValue();
            if (task.getFinishTime() != null && task.getFinishTime().isBefore(threshold)) {
                if (task.getFilePath() != null) {
                    try {
                        Files.deleteIfExists(Paths.get(task.getFilePath()));
                    } catch (IOException ignored) {
                    }
                }
                return true;
            }
            return false;
        });
    }

    @Override
    public void destroy() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}
