package io.github.atengk.boot.admin.scheduler;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import de.codecentric.boot.admin.server.services.InstanceRegistry;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * 容器云离线僵尸实例自动清理调度器
 * <p>
 * 周期性扫描实例持久层仓库，自动识别因容器 Pod 突发被杀（OOMKilled、驱逐等）导致未发出下线通知而长期处于 OFFLINE 或 UNKNOWN 状态的僵尸实例，
 * 达到配置的超时阈值后自动触发下线注销，防御实例元数据在看板与内存中无限期泄漏堆积。
 *
 * @author Ateng
 * @since 2026-11-02
 */
public class ZombieInstanceCleanupScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ZombieInstanceCleanupScheduler.class);

    private final InstanceRepository repository;
    private final InstanceRegistry registry;
    private final AtengAdminServerProperties properties;
    private final boolean internalTaskScheduler;

    private ThreadPoolTaskScheduler taskScheduler;
    private ScheduledFuture<?> scheduledFuture;
    private volatile boolean running = false;

    public ZombieInstanceCleanupScheduler(InstanceRepository repository,
                                         InstanceRegistry registry,
                                         AtengAdminServerProperties properties) {
        this(repository, registry, properties, null);
    }

    public ZombieInstanceCleanupScheduler(InstanceRepository repository,
                                         InstanceRegistry registry,
                                         AtengAdminServerProperties properties,
                                         ThreadPoolTaskScheduler taskScheduler) {
        this.repository = repository;
        this.registry = registry;
        this.properties = properties;
        if (taskScheduler != null) {
            this.taskScheduler = taskScheduler;
            this.internalTaskScheduler = false;
        } else {
            this.taskScheduler = null;
            this.internalTaskScheduler = true;
        }
    }

    private ThreadPoolTaskScheduler createDefaultTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("sba-zombie-cleanup-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public synchronized void start() {
        if (this.running) {
            return;
        }
        if (this.properties != null && this.properties.getCleanup() != null && !this.properties.getCleanup().isEnabled()) {
            log.debug("ZombieInstanceCleanupScheduler is disabled by configuration, skipping start.");
            return;
        }

        Duration interval = this.properties != null && this.properties.getCleanup() != null
                ? this.properties.getCleanup().getInterval()
                : Duration.ofHours(1);
        if (interval == null || interval.isNegative() || interval.isZero()) {
            interval = Duration.ofHours(1);
        }

        if (this.internalTaskScheduler && (this.taskScheduler == null || !this.taskScheduler.isRunning())) {
            this.taskScheduler = createDefaultTaskScheduler();
        }

        // 首发调度优化：计算初始延迟（若周期小于 10 秒则以周期为准，否则 10 秒后执行首发清理，防御长周期下服务启动无即席扫描）
        Duration initialDelay = interval.compareTo(Duration.ofSeconds(10)) < 0 ? interval : Duration.ofSeconds(10);
        Instant startTime = Instant.now().plus(initialDelay);
        this.scheduledFuture = this.taskScheduler.scheduleWithFixedDelay(this::cleanZombies, startTime, interval);
        this.running = true;

        Duration threshold = this.properties != null && this.properties.getCleanup() != null
                ? this.properties.getCleanup().getZombieThreshold()
                : Duration.ofHours(24);
        log.info("ZombieInstanceCleanupScheduler started successfully with interval={} and threshold={}.", interval, threshold);
    }

    @Override
    public synchronized void stop() {
        if (!this.running) {
            return;
        }
        if (this.scheduledFuture != null) {
            this.scheduledFuture.cancel(false);
            this.scheduledFuture = null;
        }
        if (this.taskScheduler != null && this.internalTaskScheduler) {
            this.taskScheduler.destroy();
            this.taskScheduler = null;
        }
        this.running = false;
        log.info("ZombieInstanceCleanupScheduler stopped successfully.");
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /**
     * 执行离线僵尸实例清理扫描
     *
     * @return 本次成功驱逐并注销的僵尸实例数量
     */
    public int cleanZombies() {
        if (this.repository == null) {
            return 0;
        }
        Duration configuredThreshold = this.properties != null && this.properties.getCleanup() != null
                ? this.properties.getCleanup().getZombieThreshold()
                : Duration.ofHours(24);
        final Duration threshold = (configuredThreshold == null || configuredThreshold.isNegative())
                ? Duration.ofHours(24)
                : configuredThreshold;

        Instant now = Instant.now();
        List<Instance> candidates;
        try {
            Flux<Instance> instancesFlux = this.repository.findAll();
            if (instancesFlux == null) {
                return 0;
            }
            candidates = instancesFlux
                    .filter(Instance::isRegistered)
                    .filter(instance -> {
                        StatusInfo statusInfo = instance.getStatusInfo();
                        return statusInfo != null && (statusInfo.isOffline() || statusInfo.isUnknown());
                    })
                    .filter(instance -> {
                        Instant statusTimestamp = instance.getStatusTimestamp();
                        return statusTimestamp != null && Duration.between(statusTimestamp, now).compareTo(threshold) >= 0;
                    })
                    .collectList()
                    .block();
        } catch (Exception ex) {
            log.error("Failed to query candidate zombie instances from repository: {}", ex.getMessage(), ex);
            return 0;
        }

        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }

        int evictedCount = 0;
        for (Instance instance : candidates) {
            try {
                InstanceId id = instance.getId();
                String name = instance.getRegistration() != null ? instance.getRegistration().getName() : "unknown";
                log.info("Evicting zombie instance: id={}, name={}, status={}, statusTimestamp={}",
                        id, name, instance.getStatusInfo().getStatus(), instance.getStatusTimestamp());

                if (this.registry != null) {
                    this.registry.deregister(id).block();
                } else {
                    this.repository.computeIfPresent(id, (k, inst) -> Mono.just(inst.deregister())).block();
                }
                evictedCount++;
            } catch (Exception ex) {
                log.error("Failed to evict zombie instance [{}]: {}", instance.getId(), ex.getMessage(), ex);
            }
        }
        if (evictedCount > 0) {
            log.info("ZombieInstanceCleanupScheduler evicted {} zombie instance(s).", evictedCount);
        }
        return evictedCount;
    }
}
