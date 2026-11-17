package io.github.atengk.boot.flyway.observability;

import io.github.atengk.boot.flyway.config.FlywayConfigurationSupport;
import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import io.github.atengk.boot.flyway.listener.FlywayMigrationListener;
import io.github.atengk.boot.flyway.model.MigrationOutcome;
import io.github.atengk.boot.flyway.model.MigrationScriptInfo;
import io.github.atengk.boot.flyway.model.MigrationSummary;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Flyway Micrometer 迁移可观测指标绑定器
 * <p>
 * 暴露迁移成功/失败次数（{@value #METRIC_MIGRATIONS_TOTAL}）、实际执行脚本总数（{@value #METRIC_MIGRATIONS_EXECUTED}）、
 * 迁移耗时（{@value #METRIC_MIGRATION_DURATION}）、待执行迁移脚本数（{@value #METRIC_MIGRATIONS_PENDING}）
 * 以及最近一次成功/失败目标数 Gauge 指标；
 * 通过有界缓冲队列兼容启动迁移早于 {@link MeterRegistry} 就绪的装配时序，并对待执行脚本探查实施 TTL 缓存以防抓取风暴。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class FlywayMetricsBinder implements MeterBinder, FlywayMigrationListener {

    private static final Logger log = LoggerFactory.getLogger(FlywayMetricsBinder.class);

    public static final String METRIC_MIGRATIONS_TOTAL = "ateng.flyway.migrations.total";
    public static final String METRIC_MIGRATIONS_EXECUTED = "ateng.flyway.migrations.executed";
    public static final String METRIC_MIGRATION_DURATION = "ateng.flyway.migration.duration";
    public static final String METRIC_MIGRATIONS_PENDING = "ateng.flyway.migrations.pending";
    public static final String METRIC_FAILED_TARGETS = "ateng.flyway.failed.targets";
    public static final String METRIC_SUCCESS_TARGETS = "ateng.flyway.success.targets";

    static final int MAX_PENDING_OUTCOMES = 1024;

    private static final String TAG_TARGET = "target";
    private static final String TAG_OUTCOME = "outcome";

    private final FlywayTemplate flywayTemplate;
    private final AtengFlywayProperties properties;
    private final Queue<MigrationOutcome> pendingOutcomes = new ConcurrentLinkedQueue<>();
    private final Set<MeterRegistry> boundRegistries = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ReentrantLock bindLock = new ReentrantLock();
    private final ReentrantLock pendingCacheLock = new ReentrantLock();
    private final AtomicBoolean queueOverflowWarned = new AtomicBoolean(false);
    private final AtomicLong pendingProbeCount = new AtomicLong(0);

    private volatile CachedPendingSnapshot cachedPending;

    /**
     * 构造 Flyway Micrometer 度量绑定器（使用默认缓存窗口配置）
     *
     * @param flywayTemplate 对称迁移门面
     */
    public FlywayMetricsBinder(FlywayTemplate flywayTemplate) {
        this(flywayTemplate, new AtengFlywayProperties());
    }

    /**
     * 构造 Flyway Micrometer 度量绑定器
     *
     * @param flywayTemplate 对称迁移门面
     * @param properties     ateng-boot-flyway 配置属性
     */
    public FlywayMetricsBinder(FlywayTemplate flywayTemplate, AtengFlywayProperties properties) {
        this.flywayTemplate = Objects.requireNonNull(flywayTemplate, "flywayTemplate 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
    }

    @Override
    public void bindTo(MeterRegistry meterRegistry) {
        Objects.requireNonNull(meterRegistry, "meterRegistry 不能为空");
        bindLock.lock();
        try {
            if (!boundRegistries.add(meterRegistry)) {
                return;
            }

            Gauge.builder(METRIC_FAILED_TARGETS, flywayTemplate, template -> {
                MigrationSummary summary = template.getLastMigrationSummary();
                return summary != null ? summary.getFailedCount() : 0;
            }).description("最近一次迁移摘要中失败的目标数据源数量").register(meterRegistry);

            Gauge.builder(METRIC_SUCCESS_TARGETS, flywayTemplate, template -> {
                MigrationSummary summary = template.getLastMigrationSummary();
                return summary != null ? summary.getSuccessCount() : 0;
            }).description("最近一次迁移摘要中成功的目标数据源数量").register(meterRegistry);

            Gauge.builder(METRIC_MIGRATIONS_PENDING, this, FlywayMetricsBinder::resolvePendingCount)
                    .description("当前缓存窗口内待执行的 Flyway 迁移脚本数量")
                    .register(meterRegistry);

            for (MigrationOutcome buffered : snapshotBufferedOutcomes()) {
                recordOutcome(meterRegistry, buffered);
            }
        } finally {
            if (bindLock.isHeldByCurrentThread()) {
                bindLock.unlock();
            }
        }
    }

    @Override
    public void onMigrationSucceeded(MigrationOutcome outcome) {
        invalidatePendingCache();
        handleOutcome(outcome);
    }

    @Override
    public void onMigrationFailed(String targetKey, MigrationOutcome outcome, Throwable cause) {
        invalidatePendingCache();
        if (outcome != null) {
            handleOutcome(outcome);
            return;
        }
        handleOutcome(MigrationOutcome.builder()
                .targetKey(targetKey != null ? targetKey : "default")
                .mode(FlywayExecutionMode.MIGRATE)
                .success(false)
                .skipped(false)
                .migrationsExecuted(0)
                .duration(Duration.ZERO)
                .executedAt(Instant.now())
                .build());
    }

    /**
     * 主动失效待执行脚本指标缓存
     */
    public void invalidatePendingCache() {
        this.cachedPending = null;
    }

    /**
     * 获取累计实际触发底层 pending 探查的次数（主要用于抓取缓存测试核验）
     *
     * @return 底层 pending 查询次数
     */
    public long getPendingProbeCount() {
        return pendingProbeCount.get();
    }

    private double resolvePendingCount() {
        Duration ttl = resolveCacheTtl();
        CachedPendingSnapshot current = this.cachedPending;
        Instant now = Instant.now();
        if (isCacheValid(current, ttl, now)) {
            return current.count();
        }
        pendingCacheLock.lock();
        try {
            current = this.cachedPending;
            now = Instant.now();
            if (isCacheValid(current, ttl, now)) {
                return current.count();
            }
            int count = queryPendingSafely();
            this.cachedPending = new CachedPendingSnapshot(count, Instant.now());
            return count;
        } finally {
            if (pendingCacheLock.isHeldByCurrentThread()) {
                pendingCacheLock.unlock();
            }
        }
    }

    private int queryPendingSafely() {
        try {
            pendingProbeCount.incrementAndGet();
            List<MigrationScriptInfo> pending = flywayTemplate.pending();
            return pending != null ? pending.size() : 0;
        } catch (Exception e) {
            log.debug("采集 Flyway 待执行脚本指标失败，将返回 0: {}",
                    FlywayConfigurationSupport.sanitizeSensitiveText(e.getMessage()));
            return 0;
        }
    }

    private Duration resolveCacheTtl() {
        AtengFlywayProperties.ObservabilityProperties obs = properties.getObservability();
        return obs != null && obs.getHealthCacheTtl() != null
                ? obs.getHealthCacheTtl()
                : Duration.ofSeconds(30);
    }

    private boolean isCacheValid(CachedPendingSnapshot snapshot, Duration ttl, Instant now) {
        if (snapshot == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return false;
        }
        return now.isBefore(snapshot.cachedAt().plus(ttl));
    }

    private List<MigrationOutcome> snapshotBufferedOutcomes() {
        if (pendingOutcomes.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(pendingOutcomes);
    }

    private void handleOutcome(MigrationOutcome outcome) {
        if (outcome == null || outcome.isSkipped()) {
            return;
        }
        bindLock.lock();
        try {
            if (boundRegistries.isEmpty()) {
                enqueueBounded(outcome);
                return;
            }
            for (MeterRegistry registry : boundRegistries) {
                recordOutcome(registry, outcome);
            }
        } finally {
            if (bindLock.isHeldByCurrentThread()) {
                bindLock.unlock();
            }
        }
    }

    private void enqueueBounded(MigrationOutcome outcome) {
        while (pendingOutcomes.size() >= MAX_PENDING_OUTCOMES) {
            pendingOutcomes.poll();
            if (queueOverflowWarned.compareAndSet(false, true)) {
                log.warn("FlywayMetricsBinder 预绑定事件缓冲队列达到上限 {}，将滚动丢弃最早事件以防内存积压", MAX_PENDING_OUTCOMES);
            }
        }
        pendingOutcomes.offer(outcome);
    }

    private void recordOutcome(MeterRegistry meterRegistry, MigrationOutcome outcome) {
        String target = outcome.getTargetKey() != null ? outcome.getTargetKey() : "default";
        String outcomeTag = outcome.isSuccess() ? "success" : "failure";

        Counter.builder(METRIC_MIGRATIONS_TOTAL)
                .tag(TAG_TARGET, target)
                .tag(TAG_OUTCOME, outcomeTag)
                .description("Flyway 迁移执行总次数")
                .register(meterRegistry)
                .increment();

        if (outcome.getMigrationsExecuted() > 0) {
            Counter.builder(METRIC_MIGRATIONS_EXECUTED)
                    .tag(TAG_TARGET, target)
                    .description("Flyway 累计实际执行的版本化脚本数")
                    .register(meterRegistry)
                    .increment(outcome.getMigrationsExecuted());
        }

        Duration duration = outcome.getDuration();
        if (duration != null && !duration.isNegative()) {
            Timer.builder(METRIC_MIGRATION_DURATION)
                    .tag(TAG_TARGET, target)
                    .tag(TAG_OUTCOME, outcomeTag)
                    .description("Flyway 迁移执行耗时")
                    .register(meterRegistry)
                    .record(duration.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private record CachedPendingSnapshot(int count, Instant cachedAt) {
    }
}
