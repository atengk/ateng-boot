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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.util.Assert;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
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

    private static final RedisScript<Long> CAS_TIMEOUT_STATUS_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('get', KEYS[1])\n" +
            "if not current then return 0 end\n" +
            "if string.find(current, '\"PROCESSING\"') then\n" +
            "    redis.call('set', KEYS[1], ARGV[1], 'EX', ARGV[2])\n" +
            "    return 1\n" +
            "end\n" +
            "return 0\n",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final ExportFileStorage fileStorage;
    private final Map<String, Future<?>> localFutureMap = new ConcurrentHashMap<>(128);

    private final Path tempDirPath;
    private final ThreadPoolExecutor executorService;
    private final ScheduledExecutorService heartbeatScheduler;
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

        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("ateng-excel-redis-heartbeat-")
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

                // 启动执行期租约自动心跳续期
                long intervalSeconds = Math.max(5, Math.min(60, leaseTimeout.toSeconds() / 3));
                ScheduledFuture<?> heartbeatFuture = heartbeatScheduler.scheduleWithFixedDelay(
                        () -> refreshLease(taskId),
                        intervalSeconds,
                        intervalSeconds,
                        TimeUnit.SECONDS
                );

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
                    heartbeatFuture.cancel(false);
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

        // 执行租约超时探测（死任务 / 宕机节点自恢复），通过 CAS 脚本保证原子流转
        if (task.getStatus() == ExportTaskStatus.PROCESSING) {
            if (task.getLeaseExpireTime() != null && LocalDateTime.now().isAfter(task.getLeaseExpireTime())) {
                task.setStatus(ExportTaskStatus.FAILED);
                task.setErrorMsg("导出节点异常离线或任务执行超时（租约超期）");
                task.setFinishTime(LocalDateTime.now());
                String failedJson = JsonUtil.toJsonString(task);
                Long updated = null;
                try {
                    updated = redisTemplate.execute(
                            CAS_TIMEOUT_STATUS_SCRIPT,
                            Collections.singletonList(key),
                            failedJson,
                            String.valueOf(ttl.toSeconds())
                    );
                } catch (Throwable ex) {
                    log.debug("Redis CAS 脚本执行异常，将降级执行直接保存: {}", ex.getMessage());
                }
                if (updated != null && updated == 1L) {
                    log.warn("检测到分布式异步导出任务 [{}] 执行租约超期，已原子流转为 FAILED 状态", taskId);
                } else if (updated == null) {
                    // 降级兼容：如不支持 Lua 或单元测试 Mock 环境下，安全执行回写
                    saveTask(task);
                    log.warn("检测到分布式异步导出任务 [{}] 执行租约超期，已流转为 FAILED 状态 (降级回写)", taskId);
                } else {
                    // updated == 0 说明已被其他并发节点原子流转或已被正常完成，重新拉取最新真实状态
                    String latestJson = redisTemplate.opsForValue().get(key);
                    if (latestJson != null && !latestJson.isBlank()) {
                        task = JsonUtil.parseObject(latestJson, ExportTask.class);
                    }
                }
            }
        }
        return task;
    }

    private void refreshLease(String taskId) {
        try {
            ExportTask task = getTask(taskId);
            if (task != null && task.getStatus() == ExportTaskStatus.PROCESSING) {
                task.setLeaseExpireTime(LocalDateTime.now().plus(leaseTimeout));
                saveTask(task);
                log.debug("分布式异步导出任务 [{}] 租约心跳续期成功", taskId);
            }
        } catch (Throwable ex) {
            log.warn("分布式异步导出任务 [{}] 租约心跳续期异常: {}", taskId, ex.getMessage());
        }
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
        heartbeatScheduler.shutdownNow();
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
