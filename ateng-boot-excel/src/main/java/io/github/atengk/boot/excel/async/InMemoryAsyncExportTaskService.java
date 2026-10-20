package io.github.atengk.boot.excel.async;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.excel.async.storage.ExportFileStorage;
import io.github.atengk.boot.excel.async.storage.LocalFileSystemStorage;
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
 * 基于并发哈希表与受管线程池执行大报表后台流式写出，支持全链路四维上下文透传、进度感知、错误捕获与过期文件定期清理。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class InMemoryAsyncExportTaskService implements AsyncExportTaskService, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(InMemoryAsyncExportTaskService.class);

    private final Map<String, ExportTask> taskMap = new ConcurrentHashMap<>(128);
    private final Map<String, Future<?>> futureMap = new ConcurrentHashMap<>(128);

    private final ExportFileStorage fileStorage;
    private final Path tempDirPath;
    private final ThreadPoolExecutor executorService;
    private final ScheduledExecutorService cleanerExecutor;

    public InMemoryAsyncExportTaskService() {
        this(new ExcelProperties(), null);
    }

    public InMemoryAsyncExportTaskService(ExcelProperties excelProperties) {
        this(excelProperties, null);
    }

    public InMemoryAsyncExportTaskService(ExcelProperties excelProperties, ExportFileStorage fileStorage) {
        this.fileStorage = (fileStorage != null) ? fileStorage : new LocalFileSystemStorage(excelProperties);
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
        int queueCap = Math.max(1, props.getQueueCapacity());

        this.executorService = new ThreadPoolExecutor(
                core,
                max,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCap),
                new NamedThreadFactory("ateng-excel-async-"),
                new ThreadPoolExecutor.AbortPolicy()
        );

        Duration retention = props.getRetentionPeriod() != null ? props.getRetentionPeriod() : Duration.ofHours(Math.max(1, props.getRetentionHours()));
        this.cleanerExecutor = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("ateng-excel-cleaner-")
        );
        this.cleanerExecutor.scheduleAtFixedRate(
                () -> cleanExpiredTasks(retention),
                1, 1, TimeUnit.HOURS
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

        // 捕获调用方线程的四维上下文（MDC、User、TenantId、IgnoreTenant）以透传给后台执行线程并严格自恢复
        final String finalTaskName = taskName;

        Future<?> future;
        try {
            future = executorService.submit(AsyncContext.wrap(() -> {
                // 防御性清除线程可能残留的中断标志，确保复用线程初始状态纯净
                Thread.interrupted();
                task.setStatus(ExportTaskStatus.PROCESSING);
                task.setProgress(10);
                log.info("开始执行异步导出任务 [{}]: {}", taskId, finalTaskName);

                try {
                    try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                        executor.execute(fos);
                    }
                    if (task.getStatus() == ExportTaskStatus.CANCELLED) {
                        return;
                    }
                    long fileSize = targetFile.length();
                    String storageKey = fileStorage.store(taskId, targetFile, finalTaskName + ".xlsx");
                    task.setStorageKey(storageKey);
                    task.setFilePath(storageKey);
                    task.setDownloadUrl(fileStorage.getDownloadUrl(taskId, storageKey));
                    task.setFileSize(fileSize);
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
                    // 任务执行结束后清理中断状态，避免对后续复用该线程的任务产生副作用
                    Thread.interrupted();
                }
            }));
        } catch (RejectedExecutionException rex) {
            taskMap.remove(taskId);
            if (targetFile.exists()) {
                targetFile.delete();
            }
            log.warn("异步导出任务队列已满，拒绝排队 [{}]: {}", taskId, taskName);
            throw new BusinessException(CommonResultCode.TOO_MANY_REQUESTS.getCode(), "异步导出任务队列已满，请稍后重试");
        }

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

        if (task.getStorageKey() != null) {
            fileStorage.delete(taskId, task.getStorageKey());
        }

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
        if (cleanerExecutor != null && !cleanerExecutor.isShutdown()) {
            cleanerExecutor.shutdownNow();
        }
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
