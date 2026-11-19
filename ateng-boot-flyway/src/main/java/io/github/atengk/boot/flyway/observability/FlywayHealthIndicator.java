package io.github.atengk.boot.flyway.observability;

import io.github.atengk.boot.flyway.config.FlywayConfigurationSupport;
import io.github.atengk.boot.flyway.enums.BatchExecutionPolicy;
import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import io.github.atengk.boot.flyway.enums.ObservabilityTargetScope;
import io.github.atengk.boot.flyway.listener.FlywayMigrationListener;
import io.github.atengk.boot.flyway.model.BatchOperationResult;
import io.github.atengk.boot.flyway.model.MigrationOutcome;
import io.github.atengk.boot.flyway.model.MigrationSummary;
import io.github.atengk.boot.flyway.model.MigrationTargetInfo;
import io.github.atengk.boot.flyway.model.TargetFailure;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓存感知型多目标 Flyway 数据库迁移健康探针
 * <p>
 * 基于 {@link FlywayTemplate} 最近一次执行摘要快照、可配置作用域 {@link ObservabilityTargetScope}
 * 与缓存窗口 {@code healthCacheTtl} 暴露健康状态；采用 {@link java.util.concurrent.locks.ReentrantLock}
 * 与单调递增 {@code summaryVersion} 联动防缓存击穿与竞态，单目标查询异常或超时按目标独立隔离记录而不掩盖其余库状态。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class FlywayHealthIndicator implements HealthIndicator, FlywayMigrationListener {

    private static final String TARGET_STATUS_UP = "UP";
    private static final String TARGET_STATUS_DOWN = "DOWN";
    private static final String TARGET_STATUS_ERROR = "ERROR";

    private final FlywayTemplate flywayTemplate;
    private final AtengFlywayProperties properties;
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
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.healthCache = new ObservabilityTtlCache<>(this.properties, flywayTemplate::getSummaryVersion);
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
        AtengFlywayProperties.ObservabilityProperties obs = properties.getObservability();
        ObservabilityTargetScope scope = ObservabilityTtlCache.resolveTargetScope(properties);
        boolean failOnPending = obs != null && obs.isFailOnPending();

        MigrationSummary summary = flywayTemplate.getLastMigrationSummary();
        if (summary != null && summary.getFailedCount() > 0) {
            return buildDownFromSummary(summary, scope, failOnPending);
        }

        if (summary != null && summary.getMode() == FlywayExecutionMode.DISABLED
                && summary.getSkippedCount() > 0 && !failOnPending) {
            return Health.up()
                    .withDetail("mode", FlywayExecutionMode.DISABLED.name())
                    .withDetail("scope", scope.getCode())
                    .withDetail("skipped", true)
                    .withDetail("skippedCount", summary.getSkippedCount())
                    .withDetail("executedAt", summary.getExecutedAt().toString())
                    .build();
        }

        List<String> scopeKeys = flywayTemplate.resolveObservableTargetKeys(scope);
        if (scopeKeys.isEmpty()) {
            Health.Builder emptyBuilder = Health.up()
                    .withDetail("scope", scope.getCode())
                    .withDetail("targetCount", 0)
                    .withDetail("appliedCount", 0)
                    .withDetail("pendingCount", 0)
                    .withDetail("failedCount", 0)
                    .withDetail("failOnPending", failOnPending)
                    .withDetail("targets", Collections.emptyMap());
            appendSummaryDetails(emptyBuilder, summary);
            return emptyBuilder.build();
        }

        try {
            databaseProbeCount.incrementAndGet();
            BatchOperationResult<MigrationTargetInfo> batchInfo =
                    flywayTemplate.infoTargets(scopeKeys, BatchExecutionPolicy.FAIL_SAFE);
            return buildMultiTargetHealth(scope, failOnPending, scopeKeys, batchInfo, summary);
        } catch (Exception e) {
            String safeError = FlywayConfigurationSupport.sanitizeSensitiveText(e.getMessage());
            if (!StringUtils.hasText(safeError)) {
                safeError = e.getClass().getSimpleName();
            }
            return Health.down()
                    .withDetail("scope", scope.getCode())
                    .withDetail("error", safeError)
                    .build();
        }
    }

    private Health buildMultiTargetHealth(ObservabilityTargetScope scope,
                                        boolean failOnPending,
                                        List<String> scopeKeys,
                                        BatchOperationResult<MigrationTargetInfo> batchInfo,
                                        MigrationSummary summary) {
        Map<String, TargetFailure> failureMap = new LinkedHashMap<>();
        for (TargetFailure failure : batchInfo.getFailures()) {
            failureMap.put(failure.getTargetKey(), failure);
        }

        Map<String, Object> targetDetails = new LinkedHashMap<>(scopeKeys.size());
        int totalApplied = 0;
        int totalPending = 0;
        int totalFailed = 0;

        for (String key : scopeKeys) {
            MigrationTargetInfo info = batchInfo.getResults().get(key);
            if (info != null) {
                totalApplied += info.getAppliedCount();
                totalPending += info.getPendingCount();
                totalFailed += info.getFailedCount();
                boolean targetBlocked = info.getFailedCount() > 0 || (failOnPending && info.getPendingCount() > 0);
                targetDetails.put(key, buildSuccessTargetEntry(key, info, targetBlocked));
            } else {
                totalFailed++;
                targetDetails.put(key, buildErrorTargetEntry(key, failureMap.get(key)));
            }
        }

        boolean isDown = totalFailed > 0 || batchInfo.hasFailures() || (failOnPending && totalPending > 0);
        String primaryKey = scopeKeys.get(0);
        MigrationTargetInfo primaryInfo = batchInfo.getResults().get(primaryKey);

        Health.Builder builder = isDown ? Health.down() : Health.up();
        builder.withDetail("scope", scope.getCode())
                .withDetail("target", primaryKey)
                .withDetail("currentVersion",
                        primaryInfo != null && primaryInfo.getCurrentVersion() != null
                                ? primaryInfo.getCurrentVersion()
                                : "none")
                .withDetail("targetCount", scopeKeys.size())
                .withDetail("appliedCount", totalApplied)
                .withDetail("pendingCount", totalPending)
                .withDetail("failedCount", totalFailed)
                .withDetail("failOnPending", failOnPending)
                .withDetail("targets", Collections.unmodifiableMap(targetDetails));

        if (batchInfo.hasFailures()) {
            builder.withDetail("errors", batchInfo.getFailures().stream()
                    .map(f -> f.getTargetKey() + ": " + f.getErrorMessage())
                    .toList());
        } else if (failOnPending && totalPending > 0) {
            builder.withDetail("error", "存在 " + totalPending + " 个待执行迁移脚本，fail-on-pending=true 阻断就绪");
        }
        appendSummaryDetails(builder, summary);
        return builder.build();
    }

    private Map<String, Object> buildSuccessTargetEntry(String key, MigrationTargetInfo info, boolean blocked) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", flywayTemplate.resolveTargetKind(key).getCode());
        detail.put("status", blocked ? TARGET_STATUS_DOWN : TARGET_STATUS_UP);
        detail.put("currentVersion", info.getCurrentVersion() != null ? info.getCurrentVersion() : "none");
        detail.put("appliedCount", info.getAppliedCount());
        detail.put("pendingCount", info.getPendingCount());
        detail.put("failedCount", info.getFailedCount());
        return Collections.unmodifiableMap(detail);
    }

    private Map<String, Object> buildErrorTargetEntry(String key, TargetFailure failure) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", flywayTemplate.resolveTargetKind(key).getCode());
        detail.put("status", TARGET_STATUS_ERROR);
        if (failure != null) {
            if (failure.getErrorCode() != null) {
                detail.put("errorCode", failure.getErrorCode());
            }
            detail.put("error", failure.getErrorMessage());
        } else {
            detail.put("error", "探测目标迁移状态失败");
        }
        return Collections.unmodifiableMap(detail);
    }

    private void appendSummaryDetails(Health.Builder builder, MigrationSummary summary) {
        if (summary == null) {
            return;
        }
        if (summary.getMode() != null) {
            builder.withDetail("mode", summary.getMode().name());
        }
        builder.withDetail("totalTargets", summary.getTotalTargets())
                .withDetail("successCount", summary.getSuccessCount());
        if (summary.getMode() == FlywayExecutionMode.DISABLED) {
            builder.withDetail("skipped", summary.getSkippedCount() > 0)
                    .withDetail("skippedCount", summary.getSkippedCount());
        }
    }

    private Health buildDownFromSummary(MigrationSummary summary,
                                        ObservabilityTargetScope scope,
                                        boolean failOnPending) {
        String errorDetail = summary.getOutcomes().stream()
                .filter(outcome -> !outcome.isSuccess() && StringUtils.hasText(outcome.getErrorMessage()))
                .map(outcome -> FlywayConfigurationSupport.sanitizeSensitiveText(outcome.getErrorMessage()))
                .findFirst()
                .orElse("数据库迁移失败");
        List<String> failedTargets = summary.getOutcomes().stream()
                .filter(outcome -> !outcome.isSuccess() && StringUtils.hasText(outcome.getTargetKey()))
                .map(MigrationOutcome::getTargetKey)
                .toList();
        return Health.down()
                .withDetail("scope", scope.getCode())
                .withDetail("failOnPending", failOnPending)
                .withDetail("mode", summary.getMode().name())
                .withDetail("totalTargets", summary.getTotalTargets())
                .withDetail("successCount", summary.getSuccessCount())
                .withDetail("failedCount", summary.getFailedCount())
                .withDetail("failedTargets", failedTargets)
                .withDetail("error", errorDetail)
                .build();
    }
}
