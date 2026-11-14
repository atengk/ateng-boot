package io.github.atengk.boot.storage.cleaner;

import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.factory.StorageDriverFactory;
import io.github.atengk.boot.storage.properties.StorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/**
 * 孤儿分片碎片生命周期治理受管守护组件
 * 周期性扫描并清理 Local 驱动 `.chunks` 目录超时碎片与 S3 未决 Multipart 会话，并在应用下线时优雅关闭
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Slf4j
public class StorageChunkCleaner implements DisposableBean {

    private final StorageProperties properties;
    private final ObjectProvider<StorageDriverFactory> driverFactoryProvider;
    private final ObjectProvider<StorageDriver> defaultDriverProvider;
    private final TaskScheduler taskScheduler;
    private final ThreadPoolTaskScheduler ownedTaskScheduler;
    private final ScheduledFuture<?> scheduledFuture;

    /**
     * 构造孤儿分片清理器（缺省创建 Spring 受管单线程调度器）
     *
     * @param properties            存储配置属性
     * @param driverFactoryProvider 存储驱动工厂提供者
     * @param defaultDriverProvider 默认存储驱动提供者
     */
    public StorageChunkCleaner(
            StorageProperties properties,
            ObjectProvider<StorageDriverFactory> driverFactoryProvider,
            ObjectProvider<StorageDriver> defaultDriverProvider) {
        this(properties, driverFactoryProvider, defaultDriverProvider, null);
    }

    /**
     * 构造孤儿分片清理器（优先复用 Spring 容器受管 TaskScheduler）
     *
     * @param properties            存储配置属性
     * @param driverFactoryProvider 存储驱动工厂提供者
     * @param defaultDriverProvider 默认存储驱动提供者
     * @param taskSchedulerProvider Spring 任务调度器提供者
     */
    public StorageChunkCleaner(
            StorageProperties properties,
            ObjectProvider<StorageDriverFactory> driverFactoryProvider,
            ObjectProvider<StorageDriver> defaultDriverProvider,
            ObjectProvider<TaskScheduler> taskSchedulerProvider) {
        this.properties = properties;
        this.driverFactoryProvider = driverFactoryProvider;
        this.defaultDriverProvider = defaultDriverProvider;

        TaskScheduler externalScheduler = taskSchedulerProvider != null ? taskSchedulerProvider.getIfAvailable() : null;
        if (externalScheduler != null) {
            this.taskScheduler = externalScheduler;
            this.ownedTaskScheduler = null;
        } else {
            ThreadPoolTaskScheduler fallback = new ThreadPoolTaskScheduler();
            fallback.setPoolSize(1);
            fallback.setThreadNamePrefix("storage-chunk-cleaner-");
            fallback.setDaemon(true);
            fallback.setWaitForTasksToCompleteOnShutdown(false);
            fallback.initialize();
            this.taskScheduler = fallback;
            this.ownedTaskScheduler = fallback;
        }

        Duration interval = (properties != null && properties.getChunkCleaner() != null && properties.getChunkCleaner().getInterval() != null)
                ? properties.getChunkCleaner().getInterval()
                : Duration.ofHours(2);

        long initialDelaySeconds = Math.max(10, Math.min(300, interval.toSeconds()));
        long periodSeconds = Math.max(60, interval.toSeconds());

        Instant startTime = Instant.now().plusSeconds(initialDelaySeconds);
        Duration delay = Duration.ofSeconds(periodSeconds);
        this.scheduledFuture = this.taskScheduler.scheduleWithFixedDelay(this::cleanAllSafely, startTime, delay);
        log.info("存储孤儿分片定时清理器启动成功: initialDelay={}s, period={}s, managedScheduler={}",
                initialDelaySeconds, periodSeconds, externalScheduler != null ? "external" : "internal-ThreadPoolTaskScheduler");
    }

    /**
     * 手动触发一次针对所有已注册存储源的孤儿分片清理扫描
     *
     * @return 本次扫描清理的孤儿分片会话总数
     */
    public int triggerClean() {
        return cleanAllSafely();
    }

    /**
     * 安全执行所有存储源的超时分片碎片清理
     */
    private int cleanAllSafely() {
        int totalCleaned = 0;
        try {
            Duration maxAge = (properties != null && properties.getChunkCleaner() != null && properties.getChunkCleaner().getMaxAge() != null)
                    ? properties.getChunkCleaner().getMaxAge()
                    : Duration.ofHours(24);

            StorageDriverFactory factory = driverFactoryProvider != null ? driverFactoryProvider.getIfAvailable() : null;
            if (factory != null && !factory.getAllDrivers().isEmpty()) {
                for (Map.Entry<String, StorageDriver> entry : factory.getAllDrivers().entrySet()) {
                    try {
                        int cleaned = entry.getValue().cleanExpiredChunkSessions(maxAge);
                        totalCleaned += cleaned;
                        if (cleaned > 0) {
                            log.info("存储源 [{}] 清理超时孤儿分片会话完成: cleanedCount={}", entry.getKey(), cleaned);
                        }
                    } catch (Exception e) {
                        log.warn("存储源 [{}] 清理孤儿分片会话异常: {}", entry.getKey(), e.getMessage());
                    }
                }
            } else if (defaultDriverProvider != null) {
                StorageDriver defaultDriver = defaultDriverProvider.getIfAvailable();
                if (defaultDriver != null) {
                    int cleaned = defaultDriver.cleanExpiredChunkSessions(maxAge);
                    totalCleaned += cleaned;
                    if (cleaned > 0) {
                        log.info("默认存储驱动清理超时孤儿分片会话完成: cleanedCount={}", cleaned);
                    }
                }
            }
        } catch (Throwable t) {
            log.warn("执行后台孤儿分片碎片定时清理异常: {}", t.getMessage());
        }
        return totalCleaned;
    }

    @Override
    public void destroy() {
        if (this.scheduledFuture != null) {
            this.scheduledFuture.cancel(false);
        }
        if (this.ownedTaskScheduler != null) {
            this.ownedTaskScheduler.shutdown();
        }
    }
}
