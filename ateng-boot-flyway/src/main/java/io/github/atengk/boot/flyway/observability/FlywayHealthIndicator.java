package io.github.atengk.boot.flyway.observability;

import io.github.atengk.boot.flyway.config.FlywayConfigurationSupport;
import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import io.github.atengk.boot.flyway.listener.FlywayMigrationListener;
import io.github.atengk.boot.flyway.model.MigrationOutcome;
import io.github.atengk.boot.flyway.model.MigrationSummary;
import io.github.atengk.boot.flyway.model.MigrationTargetInfo;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓存感知型 Flyway 数据库迁移健康探针
 * <p>
 * 基于 {@link FlywayTemplate} 最近一次执行摘要快照与可配置缓存窗口 {@code healthCacheTtl} 暴露健康状态，
 * 采用 {@link java.util.concurrent.locks.ReentrantLock} 防止并发健康探查击穿数据库且严格避免 JDK 21+ 虚拟线程 Carrier Pinning；
 * 隔离凭据模式下按需走瞬态连接并在缓存周期内零额外数据库查询，异常信息全量脱敏。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class FlywayHealthIndicator implements HealthIndicator, FlywayMigrationListener {

    private final FlywayTemplate flywayTemplate;
    private final ObservabilityTtlCache<Health> healthCache;
    private final AtomicLong databaseProbeCount = new AtomicLong(0);

    /**
     * 构造缓存感知 Flyway 健康探针
     *
     * @param flywayTemplate 对称迁移门面
     * @param properties     ateng-boot-flyway 配置属性
     */
    public FlywayHealthIndicator(FlywayTemplate flywayTemplate, AtengFlywayProperties properties) {
        this.flywayTemplate = Objects.requireNonNull(flywayTemplate, "flywayTemplate 不能为空");
        this.healthCache = new ObservabilityTtlCache<>(
                Objects.requireNonNull(properties, "properties 不能为空"),
                flywayTemplate::getSummaryVersion);
    }

    @Override
    public Health health() {
        return healthCache.getOrCompute(this::evaluateHealth);
    }

    @Override
    public void onMigrationSucceeded(MigrationOutcome outcome) {
        invalidateCache();
    }

    @Override
    public void onMigrationFailed(String targetKey, MigrationOutcome outcome, Throwable cause) {
        invalidateCache();
    }

    /**
     * 主动失效当前健康缓存快照，触发下一次探查刷新
     */
    public void invalidateCache() {
        healthCache.invalidate();
    }

    /**
     * 获取累计实际触发底层数据库探查的次数（主要用于缓存窗口测试与指标核验）
     *
     * @return 数据库探查次数
     */
    public long getDatabaseProbeCount() {
        return databaseProbeCount.get();
    }

    private Health evaluateHealth() {
        MigrationSummary summary = flywayTemplate.getLastMigrationSummary();
        if (summary != null && summary.getFailedCount() > 0) {
            return buildDownFromSummary(summary);
        }
        if (summary != null && summary.getMode() == FlywayExecutionMode.DISABLED && summary.getSkippedCount() > 0) {
            return Health.up()
                    .withDetail("mode", FlywayExecutionMode.DISABLED.name())
                    .withDetail("skipped", true)
                    .withDetail("skippedCount", summary.getSkippedCount())
                    .withDetail("executedAt", summary.getExecutedAt().toString())
                    .build();
        }

        try {
            databaseProbeCount.incrementAndGet();
            MigrationTargetInfo info = flywayTemplate.info();
            Health.Builder builder = info.getFailedCount() > 0 ? Health.down() : Health.up();
            builder.withDetail("target", info.getTargetKey())
                    .withDetail("currentVersion", info.getCurrentVersion() != null ? info.getCurrentVersion() : "none")
                    .withDetail("appliedCount", info.getAppliedCount())
                    .withDetail("pendingCount", info.getPendingCount())
                    .withDetail("failedCount", info.getFailedCount());
            if (summary != null) {
                builder.withDetail("mode", summary.getMode().name())
                        .withDetail("totalTargets", summary.getTotalTargets())
                        .withDetail("successCount", summary.getSuccessCount());
            }
            return builder.build();
        } catch (Exception e) {
            String safeError = FlywayConfigurationSupport.sanitizeSensitiveText(e.getMessage());
            if (!StringUtils.hasText(safeError)) {
                safeError = e.getClass().getSimpleName();
            }
            return Health.down()
                    .withDetail("error", safeError)
                    .build();
        }
    }

    private Health buildDownFromSummary(MigrationSummary summary) {
        String errorDetail = summary.getOutcomes().stream()
                .filter(outcome -> !outcome.isSuccess() && StringUtils.hasText(outcome.getErrorMessage()))
                .map(outcome -> FlywayConfigurationSupport.sanitizeSensitiveText(outcome.getErrorMessage()))
                .findFirst()
                .orElse("数据库迁移失败");
        return Health.down()
                .withDetail("mode", summary.getMode().name())
                .withDetail("totalTargets", summary.getTotalTargets())
                .withDetail("successCount", summary.getSuccessCount())
                .withDetail("failedCount", summary.getFailedCount())
                .withDetail("error", errorDetail)
                .build();
    }
}
