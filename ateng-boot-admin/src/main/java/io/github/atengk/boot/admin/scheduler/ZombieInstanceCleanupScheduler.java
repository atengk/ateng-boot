package io.github.atengk.boot.admin.scheduler;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import de.codecentric.boot.admin.server.services.InstanceRegistry;
import io.github.atengk.boot.admin.cluster.AdminClusterCoordinator;
import io.github.atengk.boot.admin.eventstore.AtengInMemoryEventStore;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.core.util.IdUtil;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 容器云离线僵尸实例自动清理调度器
 * <p>
 * 周期性扫描实例持久层仓库，自动识别因容器 Pod 突发被杀导致长期处于 {@code OFFLINE} 或 {@code UNKNOWN} 状态的僵尸实例，
 * 在可选集群互斥锁协调下执行安全注销并联动清理事件仓库与通知器残留状态；全程使用 {@link ReentrantLock} 杜绝虚拟线程 Pinning。
 *
 * @author Ateng
 * @since 2026-11-02
 */
public class ZombieInstanceCleanupScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ZombieInstanceCleanupScheduler.class);

    private static final String CLEANUP_LOCK_KEY = "zombie-cleanup";

    private final InstanceRepository repository;
    private final InstanceRegistry registry;
    private final AtengAdminServerProperties properties;
    private final boolean internalTaskScheduler;
    private final AdminClusterCoordinator clusterCoordinator;
    private final AtengInMemoryEventStore eventStore;
    private final Consumer<InstanceId> notifierStatePurger;
    private final String nodeOwnerId = IdUtil.fastSimpleUUID();
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    private ThreadPoolTaskScheduler taskScheduler;
    private ScheduledFuture<?> scheduledFuture;
    private volatile boolean running = false;

    public ZombieInstanceCleanupScheduler(InstanceRepository repository,
                                          InstanceRegistry registry,
                                          AtengAdminServerProperties properties) {
        this(repository, registry, properties, null, null, null, null);
    }

    public ZombieInstanceCleanupScheduler(InstanceRepository repository,
                                          InstanceRegistry registry,
                                          AtengAdminServerProperties properties,
                                          ThreadPoolTaskScheduler taskScheduler) {
        this(repository, registry, properties, taskScheduler, null, null, null);
    }

    /**
     * 全功能构造僵尸清理调度器（注入独立线程池、集群协调器、事件仓库与通知器状态清理钩子）
     *
     * @param repository          实例仓库
     * @param registry            SBA 注册表
     * @param properties          配置属性
     * @param taskScheduler       受管独立清理线程池
     * @param clusterCoordinator  集群互斥锁协调器（可选）
     * @param eventStore          有界内存事件仓库（可选，用于驱逐后释放事件历史）
     * @param notifierStatePurger 通知器实例残留状态清理回调（可选）
     */
    public ZombieInstanceCleanupScheduler(InstanceRepository repository,
                                          InstanceRegistry registry,
                                          AtengAdminServerProperties properties,
                                          ThreadPoolTaskScheduler taskScheduler,
                                          AdminClusterCoordinator clusterCoordinator,
                                          AtengInMemoryEventStore eventStore,
                                          Consumer<InstanceId> notifierStatePurger) {
        this.repository = repository;
        this.registry = registry;
        this.properties = properties;
        this.clusterCoordinator = clusterCoordinator;
        this.eventStore = eventStore;
        this.notifierStatePurger = notifierStatePurger;
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
    public void start() {
        lifecycleLock.lock();
        try {
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

            // 首发调度优化：计算初始延迟（若周期小于 10 秒则以周期为准，否则 10 秒后执行首发清理）
            Duration initialDelay = interval.compareTo(Duration.ofSeconds(10)) < 0 ? interval : Duration.ofSeconds(10);
            Instant startTime = Instant.now().plus(initialDelay);
            this.scheduledFuture = this.taskScheduler.scheduleWithFixedDelay(this::cleanZombies, startTime, interval);
            this.running = true;

            Duration threshold = this.properties != null && this.properties.getCleanup() != null
                    ? this.properties.getCleanup().getZombieThreshold()
                    : Duration.ofHours(24);
            log.info("ZombieInstanceCleanupScheduler started successfully with interval={} and threshold={}.", interval, threshold);
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public void stop() {
        lifecycleLock.lock();
        try {
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
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        if (callback != null) {
            callback.run();
        }
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
     * 执行离线僵尸实例清理扫描（支持集群互斥防重与驱逐后事件/通知状态联动释放）
     *
     * @return 本次成功驱逐并注销的僵尸实例数量
     */
    public int cleanZombies() {
        if (this.repository == null) {
            return 0;
        }
        Duration lockTtl = this.properties != null && this.properties.getCluster() != null
                ? this.properties.getCluster().getCleanupLockTtl()
                : Duration.ofSeconds(30);
        if (this.clusterCoordinator != null
                && !this.clusterCoordinator.tryAcquireLock(CLEANUP_LOCK_KEY, this.nodeOwnerId, lockTtl)) {
            log.debug("Cluster cleanup lock held by peer node, skipping current zombie sweep.");
            return 0;
        }

        try {
            return executeZombieSweep();
        } finally {
            if (this.clusterCoordinator != null) {
                this.clusterCoordinator.releaseLock(CLEANUP_LOCK_KEY, this.nodeOwnerId);
            }
        }
    }

    private int executeZombieSweep() {
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
                purgeResidualState(id);
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

    private void purgeResidualState(InstanceId id) {
        if (id == null) {
            return;
        }
        if (this.eventStore != null) {
            try {
                this.eventStore.evict(id);
            } catch (Exception ex) {
                log.debug("Failed to evict event history for zombie instance [{}]: {}", id, ex.getMessage());
            }
        }
        if (this.notifierStatePurger != null) {
            try {
                this.notifierStatePurger.accept(id);
            } catch (Exception ex) {
                log.debug("Failed to purge notifier state for zombie instance [{}]: {}", id, ex.getMessage());
            }
        }
    }
}
