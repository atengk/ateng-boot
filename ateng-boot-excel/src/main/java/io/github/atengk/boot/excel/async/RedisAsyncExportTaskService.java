package io.github.atengk.boot.excel.async;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.excel.async.storage.ExportFileStorage;
import io.github.atengk.boot.excel.async.storage.LocalFileSystemStorage;
import io.github.atengk.boot.excel.properties.ExcelAsyncExportProperties;
import io.github.atengk.boot.excel.properties.ExcelAsyncExportRedisProperties;
import io.github.atengk.boot.excel.properties.ExcelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.Assert;

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
 * 基于 Redis 的分布式异步海量 Excel 导出任务中心实现
 * <p>
 * 采用“接收节点受管线程池本地执行并透传四维上下文 + Redis 集中状态机与进度同步”架构。
 * 彻底解决微服务多实例负载均衡分流时的轮询 404 痛点；引入执行租约与心跳检测，节点宕机超期后查询自动流转为 FAILED；
 * 配合 {@link ExportFileStorage} 解耦大文件持久化与预签名下载直链交付。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-20
 */
public class RedisAsyncExportTaskService implements AsyncExportTaskService, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RedisAsyncExportTaskService.class);

    private final StringRedisTemplate redisTemplate;
    private final ExportFileStorage fileStorage;
    private final Map<String, Future<?>> localFutureMap = new ConcurrentHashMap<>(128);

    private final Path tempDirPath;
    private final ThreadPoolExecutor executorService;
    private final String keyPrefix;
    private final Duration ttl;
    private final Duration leaseTimeout;

    public RedisAsyncExportTaskService(ExcelProperties excelProperties, StringRedisTemplate redisTemplate) {
        this(excelProperties, redisTemplate, new LocalFileSystemStorage(excelProperties));
    }

    public RedisAsyncExportTaskService(ExcelProperties excelProperties,
                                      StringRedisTemplate redisTemplate,
                                      ExportFileStorage fileStorage) {
        Assert.notNull(redisTemplate, "StringRedisTemplate 不可为空");
        Assert.notNull(fileStorage, "ExportFileStorage 不可为空");
        this.redisTemplate = redisTemplate;
        this.fileStorage = fileStorage;

        ExcelAsyncExportProperties props = (excelProperties != null && excelProperties.getAsyncExport() != null)
                ? excelProperties.getAsyncExport()
                : new ExcelAsyncExportProperties();
        ExcelAsyncExportRedisProperties redisProps = props.getRedis();

        this.keyPrefix = redisProps.getKeyPrefix();
        this.ttl = (redisProps.getTtl() != null) ? redisProps.getTtl() : Duration.ofHours(24);
        this.leaseTimeout = (props.getLeaseTimeout() != null) ? props.getLeaseTimeout() : Duration.ofMinutes(30);

        String dir = props.getTempDir();
        if (dir == null || dir.isBlank()) {
            this.tempDirPath = Paths.get(System.getProperty("java.io.tmpdir"), "ateng-excel-async-temp");
        } else {
            this.tempDirPath = Paths.get(dir, "temp");
        }

        try {
            Files.createDirectories(this.tempDirPath);
        } catch (IOException e) {
            log.warn("创建 Redis 异步导出本地临时目录失败: {}", e.getMessage());
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
                new NamedThreadFactory("ateng-excel-redis-async-"),
                new ThreadPoolExecutor.AbortPolicy()
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
        File tempFile = tempDirPath.resolve(taskId + ".tmp").toFile();

        ExportTask task = ExportTask.builder()
                .taskId(taskId)
                .taskName(taskName)
                .status(ExportTaskStatus.PENDING)
                .progress(0)
                .createTime(LocalDateTime.now())
                .leaseExpireTime(LocalDateTime.now().plus(leaseTimeout))
                .build();

        saveTask(task);

        final String finalTaskName = taskName;

        Future<?> future;
        try {
            future = executorService.submit(AsyncContext.wrap(() -> {
                Thread.interrupted();
                task.setStatus(ExportTaskStatus.PROCESSING);
                task.setProgress(10);
                task.setLeaseExpireTime(LocalDateTime.now().plus(leaseTimeout));
                saveTask(task);
                log.info("开始执行分布式异步导出任务 [{}]: {}", taskId, finalTaskName);

                try {
                    try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                        executor.execute(fos);
                    }

                    // 检查是否已被取消
                    ExportTask latestTask = getTask(taskId);
                    if (latestTask != null && latestTask.getStatus() == ExportTaskStatus.CANCELLED) {
                        return;
                    }

                    long fileSize = tempFile.length();
                    // 保存至持久化存储介质并获取下载契约
                    String storageKey = fileStorage.store(taskId, tempFile, finalTaskName + ".xlsx");
                    task.setStorageKey(storageKey);
                    task.setFilePath(storageKey);
                    task.setDownloadUrl(fileStorage.getDownloadUrl(taskId, storageKey));
                    task.setFileSize(fileSize);
                    task.setStatus(ExportTaskStatus.SUCCESS);
                    task.setProgress(100);
                    task.setFinishTime(LocalDateTime.now());
                    saveTask(task);
                    log.info("分布式异步导出任务完成 [{}]: 文件大小 = {} 字节", taskId, task.getFileSize());
                } catch (Throwable ex) {
                    if (task.getStatus() == ExportTaskStatus.CANCELLED || Thread.currentThread().isInterrupted() || ex instanceof InterruptedException) {
                        log.info("分布式异步导出任务已被取消或中断 [{}]", taskId);
                        return;
                    }
                    ExportTask latestTask = getTask(taskId);
                    if (latestTask != null && latestTask.getStatus() == ExportTaskStatus.CANCELLED) {
                        return;
                    }
                    task.setStatus(ExportTaskStatus.FAILED);
                    task.setErrorMsg(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                    task.setFinishTime(LocalDateTime.now());
                    saveTask(task);
                    log.error("分布式异步导出任务执行失败 [{}]: {}", taskId, ex.getMessage(), ex);
                } finally {
                    localFutureMap.remove(taskId);
                    if (tempFile.exists() && task.getStatus() != ExportTaskStatus.SUCCESS) {
                        tempFile.delete();
                    }
                    Thread.interrupted();
                }
            }));
        } catch (RejectedExecutionException rex) {
            if (tempFile.exists()) {
                tempFile.delete();
            }
            task.setStatus(ExportTaskStatus.FAILED);
            task.setErrorMsg("异步导出任务队列已满，请稍后重试");
            task.setFinishTime(LocalDateTime.now());
            saveTask(task);
            log.warn("异步导出任务队列已满，拒绝排队 [{}]: {}", taskId, taskName);
            throw new BusinessException(CommonResultCode.TOO_MANY_REQUESTS.getCode(), "异步导出任务队列已满，请稍后重试");
        }

        localFutureMap.put(taskId, future);
        return taskId;
    }

    @Override
    public ExportTask getTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        String key = keyPrefix + taskId;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return null;
        }

        ExportTask task = JsonUtil.parseObject(json, ExportTask.class);
        if (task == null) {
            return null;
        }

        // 执行租约超时探测（死任务 / 宕机节点自恢复）
        if (task.getStatus() == ExportTaskStatus.PROCESSING) {
            if (task.getLeaseExpireTime() != null && LocalDateTime.now().isAfter(task.getLeaseExpireTime())) {
                task.setStatus(ExportTaskStatus.FAILED);
                task.setErrorMsg("导出节点异常离线或任务执行超时（租约超期）");
                task.setFinishTime(LocalDateTime.now());
                saveTask(task);
                log.warn("检测到分布式异步导出任务 [{}] 执行租约超期，已原子流转为 FAILED 状态", taskId);
            }
        }
        return task;
    }

    @Override
    public boolean cancel(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        ExportTask task = getTask(taskId);
        if (task == null) {
            return false;
        }
        if (task.getStatus() == ExportTaskStatus.SUCCESS || task.getStatus() == ExportTaskStatus.FAILED) {
            return false;
        }

        task.setStatus(ExportTaskStatus.CANCELLED);
        task.setFinishTime(LocalDateTime.now());
        saveTask(task);

        Future<?> future = localFutureMap.remove(taskId);
        if (future != null) {
            future.cancel(true);
        }

        if (task.getStorageKey() != null) {
            fileStorage.delete(taskId, task.getStorageKey());
        }
        return true;
    }

    @Override
    public void cleanExpiredTasks(Duration retention) {
        // Redis 端任务根据 TTL 自动由 Redis 引擎安全过期清理
        // 此处防御性清理本地临时遗留的未闭合 tmp 文件
        try (var stream = Files.list(tempDirPath)) {
            stream.filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            var lastModified = Files.getLastModifiedTime(path).toInstant();
                            if (lastModified.plus(retention).isBefore(java.time.Instant.now())) {
                                Files.deleteIfExists(path);
                            }
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private void saveTask(ExportTask task) {
        if (task == null || task.getTaskId() == null) {
            return;
        }
        String key = keyPrefix + task.getTaskId();
        String json = JsonUtil.toJsonString(task);
        redisTemplate.opsForValue().set(key, json, ttl);
    }

    @Override
    public void destroy() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
