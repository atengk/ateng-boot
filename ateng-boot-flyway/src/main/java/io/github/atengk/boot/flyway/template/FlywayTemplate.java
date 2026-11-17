package io.github.atengk.boot.flyway.template;

import io.github.atengk.boot.flyway.config.FlywayConfigurationSupport;
import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.event.FlywayMigrationCompletedEvent;
import io.github.atengk.boot.flyway.event.FlywayMigrationFailedEvent;
import io.github.atengk.boot.flyway.exception.FlywayException;
import io.github.atengk.boot.flyway.guard.ProductionSafetyGuard;
import io.github.atengk.boot.flyway.listener.FlywayMigrationListener;
import io.github.atengk.boot.flyway.model.MigrationBaselineResult;
import io.github.atengk.boot.flyway.model.MigrationCleanResult;
import io.github.atengk.boot.flyway.model.MigrationOutcome;
import io.github.atengk.boot.flyway.model.MigrationRepairResult;
import io.github.atengk.boot.flyway.model.MigrationScriptInfo;
import io.github.atengk.boot.flyway.model.MigrationSummary;
import io.github.atengk.boot.flyway.model.MigrationTargetInfo;
import io.github.atengk.boot.flyway.model.MigrationValidationResult;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import io.github.atengk.boot.flyway.security.TransientFlywayExecutor;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.BaselineResult;
import org.flywaydb.core.api.output.CleanResult;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.RepairResult;
import org.flywaydb.core.api.output.ValidateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Flyway 数据库版本化迁移统一对称门面
 * <p>
 * 提供对称的单库与受管目标维度操作能力（迁移、校验、待执行脚本预检、历史探查、基线初始化、修复与受守卫清理），
 * 支持正常模式与隔离式加密 DDL 瞬态连接模式，自动广播迁移领域事件、回调 {@link FlywayMigrationListener}
 * 并对所有异常与摘要执行统一凭据脱敏与保留因果链的 {@link FlywayException} 转译。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class FlywayTemplate {

    private static final Logger log = LoggerFactory.getLogger(FlywayTemplate.class);

    private final Flyway primaryFlyway;
    private final TransientFlywayExecutor transientExecutor;
    private final AtengFlywayProperties properties;
    private final ProductionSafetyGuard safetyGuard;
    private final FlywayMigrationStrategy migrationStrategy;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectProvider<FlywayMigrationListener> listenersProvider;
    private final List<FlywayMigrationListener> manualListeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<MigrationSummary> lastMigrationSummary = new AtomicReference<>();

    /**
     * 构造单目标数据源迁移门面
     *
     * @param primaryFlyway 主目标 Flyway 实例
     * @param properties    ateng-boot-flyway 配置属性
     * @param safetyGuard   生产安全守卫
     */
    public FlywayTemplate(Flyway primaryFlyway,
                          AtengFlywayProperties properties,
                          ProductionSafetyGuard safetyGuard) {
        this(primaryFlyway, properties, safetyGuard, null);
    }

    /**
     * 构造支持自定义迁移策略的单目标数据源迁移门面
     *
     * @param primaryFlyway     主目标 Flyway 实例
     * @param properties        ateng-boot-flyway 配置属性
     * @param safetyGuard       生产安全守卫
     * @param migrationStrategy 可选的 Spring Boot 自定义迁移策略
     */
    public FlywayTemplate(Flyway primaryFlyway,
                          AtengFlywayProperties properties,
                          ProductionSafetyGuard safetyGuard,
                          FlywayMigrationStrategy migrationStrategy) {
        this(Objects.requireNonNull(primaryFlyway, "primaryFlyway 不能为空"),
                null, properties, safetyGuard, migrationStrategy, null, null);
    }

    /**
     * 构造支持隔离瞬态凭据、事件发布与监听回调的完整迁移门面
     *
     * @param primaryFlyway     正常模式主目标 Flyway 实例（隔离模式下为 null）
     * @param transientExecutor 隔离式 DDL 瞬态执行器（正常模式下为 null）
     * @param properties        ateng-boot-flyway 配置属性
     * @param safetyGuard       生产安全守卫
     * @param migrationStrategy 可选的自定义迁移策略
     * @param eventPublisher    Spring 领域事件发布器（可为 null）
     * @param listenersProvider 迁移监听器延迟提供者（可为 null）
     */
    public FlywayTemplate(Flyway primaryFlyway,
                          TransientFlywayExecutor transientExecutor,
                          AtengFlywayProperties properties,
                          ProductionSafetyGuard safetyGuard,
                          FlywayMigrationStrategy migrationStrategy,
                          ApplicationEventPublisher eventPublisher,
                          ObjectProvider<FlywayMigrationListener> listenersProvider) {
        Assert.state(primaryFlyway != null || transientExecutor != null,
                "primaryFlyway 与 transientExecutor 不能同时为空");
        this.primaryFlyway = primaryFlyway;
        this.transientExecutor = transientExecutor;
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.safetyGuard = Objects.requireNonNull(safetyGuard, "safetyGuard 不能为空");
        this.migrationStrategy = migrationStrategy;
        this.eventPublisher = eventPublisher;
        this.listenersProvider = listenersProvider;
    }

    /**
     * 动态注册编程式迁移监听器
     *
     * @param listener 迁移监听器
     */
    public void addMigrationListener(FlywayMigrationListener listener) {
        if (listener != null && !manualListeners.contains(listener)) {
            manualListeners.add(listener);
        }
    }

    /**
     * 注销编程式迁移监听器
     *
     * @param listener 迁移监听器
     */
    public void removeMigrationListener(FlywayMigrationListener listener) {
        if (listener != null) {
            manualListeners.remove(listener);
        }
    }

    /**
     * 对默认主目标数据源执行版本化迁移
     *
     * @return 迁移执行结果
     */
    public MigrationOutcome migrate() {
        return migrate(resolvePrimaryTargetKey());
    }

    /**
     * 对指定目标数据源执行版本化迁移
     *
     * @param targetKey 目标数据源标识
     * @return 迁移执行结果
     */
    public MigrationOutcome migrate(String targetKey) {
        String resolvedKey = requireTargetKey(targetKey);
        Instant start = Instant.now();
        notifyBeforeMigrate(resolvedKey);
        try {
            MigrationOutcome outcome = executeWithFlyway(resolvedKey, flyway -> {
                if (migrationStrategy != null) {
                    int beforeApplied = safeAppliedCount(flyway);
                    migrationStrategy.migrate(flyway);
                    int afterApplied = safeAppliedCount(flyway);
                    int executedCount = Math.max(0, afterApplied - beforeApplied);
                    MigrationInfo current = flyway.info().current();
                    String targetVersion = current != null && current.getVersion() != null
                            ? current.getVersion().getVersion()
                            : null;
                    Instant finishedAt = Instant.now();
                    return MigrationOutcome.builder()
                            .targetKey(resolvedKey)
                            .mode(FlywayExecutionMode.MIGRATE)
                            .success(true)
                            .skipped(false)
                            .migrationsExecuted(executedCount)
                            .targetSchemaVersion(targetVersion)
                            .duration(Duration.between(start, finishedAt))
                            .executedAt(finishedAt)
                            .warnings(Collections.emptyList())
                            .build();
                }

                MigrateResult result = flyway.migrate();
                Instant finishedAt = Instant.now();
                return MigrationOutcome.builder()
                        .targetKey(resolvedKey)
                        .mode(FlywayExecutionMode.MIGRATE)
                        .success(result.success)
                        .skipped(false)
                        .migrationsExecuted(result.migrationsExecuted)
                        .initialSchemaVersion(result.initialSchemaVersion)
                        .targetSchemaVersion(result.targetSchemaVersion)
                        .database(result.database)
                        .duration(Duration.between(start, finishedAt))
                        .executedAt(finishedAt)
                        .warnings(copyList(result.warnings))
                        .build();
            });

            recordSingleSummary(FlywayExecutionMode.MIGRATE, outcome, outcome.getExecutedAt(), outcome.getDuration());
            notifyMigrationSucceeded(outcome);
            publishCompletedEvent(outcome);
            return outcome;
        } catch (FlywayException e) {
            String safeMessage = sanitize(e.getMessage());
            MigrationOutcome failureOutcome = recordSingleFailureSummary(
                    resolvedKey, FlywayExecutionMode.MIGRATE, start, safeMessage);
            notifyMigrationFailed(resolvedKey, failureOutcome, e);
            publishFailedEvent(resolvedKey, FlywayExecutionMode.MIGRATE, safeMessage, failureOutcome, e);
            throw e;
        } catch (Exception e) {
            String safeMessage = sanitize(e.getMessage());
            MigrationOutcome failureOutcome = recordSingleFailureSummary(
                    resolvedKey, FlywayExecutionMode.MIGRATE, start, safeMessage);
            FlywayException translated = new FlywayException(FlywayResultCode.MIGRATION_FAILED,
                    "目标数据源 [" + resolvedKey + "] 迁移执行失败: " + safeMessage, e);
            notifyMigrationFailed(resolvedKey, failureOutcome, translated);
            publishFailedEvent(resolvedKey, FlywayExecutionMode.MIGRATE, safeMessage, failureOutcome, translated);
            throw translated;
        }
    }

    /**
     * 对默认主目标数据源执行一致性校验
     *
     * @return 校验结果视图对象
     */
    public MigrationValidationResult validate() {
        return validate(resolvePrimaryTargetKey());
    }

    /**
     * 对指定目标数据源执行一致性校验
     *
     * @param targetKey 目标数据源标识
     * @return 校验结果视图对象
     */
    public MigrationValidationResult validate(String targetKey) {
        String resolvedKey = requireTargetKey(targetKey);
        Instant start = Instant.now();
        boolean[] failureAlreadyReported = {false};
        try {
            return executeWithFlyway(resolvedKey, flyway -> {
                ValidateResult result = flyway.validateWithResult();
                if (!result.validationSuccessful) {
                    String details = sanitize(result.getAllErrorMessages());
                    MigrationOutcome failureOutcome = recordValidationFailureIfNeeded(resolvedKey, start, details);
                    FlywayException ex = new FlywayException(FlywayResultCode.VALIDATION_FAILED,
                            "目标数据源 [" + resolvedKey + "] 迁移校验失败: " + details);
                    if (failureOutcome != null) {
                        notifyMigrationFailed(resolvedKey, failureOutcome, ex);
                        publishFailedEvent(resolvedKey, FlywayExecutionMode.VALIDATE_ONLY, details, failureOutcome, ex);
                    }
                    failureAlreadyReported[0] = true;
                    throw ex;
                }
                if (shouldRecordValidationSummary()) {
                    Instant finishedAt = Instant.now();
                    Duration elapsed = Duration.between(start, finishedAt);
                    MigrationInfo current = flyway.info().current();
                    String currentVersion = current != null && current.getVersion() != null
                            ? current.getVersion().getVersion()
                            : null;
                    MigrationOutcome outcome = MigrationOutcome.builder()
                            .targetKey(resolvedKey)
                            .mode(FlywayExecutionMode.VALIDATE_ONLY)
                            .success(true)
                            .skipped(false)
                            .migrationsExecuted(0)
                            .targetSchemaVersion(currentVersion)
                            .database(result.database)
                            .duration(elapsed)
                            .executedAt(finishedAt)
                            .warnings(copyList(result.warnings))
                            .build();
                    recordSingleSummary(FlywayExecutionMode.VALIDATE_ONLY, outcome, finishedAt, elapsed);
                }
                return MigrationValidationResult.builder()
                        .targetKey(resolvedKey)
                        .valid(true)
                        .validateCount(result.validateCount)
                        .warnings(copyList(result.warnings))
                        .build();
            });
        } catch (FlywayException e) {
            if (!failureAlreadyReported[0]) {
                String safeMessage = sanitize(e.getMessage());
                MigrationOutcome failureOutcome = recordValidationFailureIfNeeded(resolvedKey, start, safeMessage);
                if (failureOutcome != null) {
                    notifyMigrationFailed(resolvedKey, failureOutcome, e);
                    publishFailedEvent(resolvedKey, FlywayExecutionMode.VALIDATE_ONLY, safeMessage, failureOutcome, e);
                }
            }
            throw e;
        } catch (Exception e) {
            String safeMessage = sanitize(e.getMessage());
            MigrationOutcome failureOutcome = recordValidationFailureIfNeeded(resolvedKey, start, safeMessage);
            FlywayException translated = new FlywayException(FlywayResultCode.VALIDATION_FAILED,
                    "目标数据源 [" + resolvedKey + "] 迁移校验异常: " + safeMessage, e);
            if (failureOutcome != null) {
                notifyMigrationFailed(resolvedKey, failureOutcome, translated);
                publishFailedEvent(resolvedKey, FlywayExecutionMode.VALIDATE_ONLY, safeMessage, failureOutcome, translated);
            }
            throw translated;
        }
    }

    /**
     * 查询默认主目标数据源尚未执行的待迁移脚本列表（可用于 Dry-Run 预检）
     *
     * @return 待迁移脚本列表（永不为 null）
     */
    public List<MigrationScriptInfo> pending() {
        return pending(resolvePrimaryTargetKey());
    }

    /**
     * 查询指定目标数据源尚未执行的待迁移脚本列表
     *
     * @param targetKey 目标数据源标识
     * @return 待迁移脚本列表（永不为 null）
     */
    public List<MigrationScriptInfo> pending(String targetKey) {
        String resolvedKey = requireTargetKey(targetKey);
        try {
            return executeWithFlyway(resolvedKey, flyway -> convertScripts(flyway.info().pending()));
        } catch (FlywayException e) {
            throw e;
        } catch (Exception e) {
            throw new FlywayException(FlywayResultCode.MIGRATION_FAILED,
                    "查询目标数据源 [" + resolvedKey + "] 待迁移脚本失败: " + sanitize(e.getMessage()), e);
        }
    }

    /**
     * 查询默认主目标数据源完整迁移历史与状态视图
     *
     * @return 目标数据源迁移信息视图对象
     */
    public MigrationTargetInfo info() {
        return info(resolvePrimaryTargetKey());
    }

    /**
     * 查询指定目标数据源完整迁移历史与状态视图
     *
     * @param targetKey 目标数据源标识
     * @return 目标数据源迁移信息视图对象
     */
    public MigrationTargetInfo info(String targetKey) {
        String resolvedKey = requireTargetKey(targetKey);
        try {
            return executeWithFlyway(resolvedKey, flyway -> {
                MigrationInfoService infoService = flyway.info();
                MigrationInfo current = infoService.current();
                String currentVersion = current != null && current.getVersion() != null
                        ? current.getVersion().getVersion()
                        : null;
                MigrationInfo[] all = infoService.all();
                int appliedCount = infoService.applied().length;
                int pendingCount = infoService.pending().length;
                int failedCount = 0;
                if (all != null) {
                    for (MigrationInfo item : all) {
                        if (item.getState() != null && item.getState().isFailed()) {
                            failedCount++;
                        }
                    }
                }
                return MigrationTargetInfo.builder()
                        .targetKey(resolvedKey)
                        .currentVersion(currentVersion)
                        .appliedCount(appliedCount)
                        .pendingCount(pendingCount)
                        .failedCount(failedCount)
                        .scripts(convertScripts(all))
                        .build();
            });
        } catch (FlywayException e) {
            throw e;
        } catch (Exception e) {
            throw new FlywayException(FlywayResultCode.MIGRATION_FAILED,
                    "查询目标数据源 [" + resolvedKey + "] 迁移历史信息失败: " + sanitize(e.getMessage()), e);
        }
    }

    /**
     * 对默认主目标数据源执行受控基线初始化
     *
     * @return 基线初始化结果
     */
    public MigrationBaselineResult baseline() {
        return baseline(resolvePrimaryTargetKey());
    }

    /**
     * 对指定目标数据源执行受控基线初始化
     *
     * @param targetKey 目标数据源标识
     * @return 基线初始化结果
     */
    public MigrationBaselineResult baseline(String targetKey) {
        return baseline(targetKey, null, null);
    }

    /**
     * 对指定目标数据源使用指定版本号与描述执行基线初始化
     *
     * @param targetKey   目标数据源标识
     * @param version     基线版本号（为空则继承配置默认值）
     * @param description 基线描述（为空则继承配置默认值）
     * @return 基线初始化结果
     */
    public MigrationBaselineResult baseline(String targetKey, String version, String description) {
        String resolvedKey = requireTargetKey(targetKey);
        try {
            return executeWithFlyway(resolvedKey, baseFlyway -> {
                Flyway runner = baseFlyway;
                if (StringUtils.hasText(version) || StringUtils.hasText(description)) {
                    FluentConfiguration cfg = Flyway.configure().configuration(baseFlyway.getConfiguration());
                    if (StringUtils.hasText(version)) {
                        cfg.baselineVersion(version);
                    }
                    if (StringUtils.hasText(description)) {
                        cfg.baselineDescription(description);
                    }
                    runner = cfg.load();
                }
                BaselineResult result = runner.baseline();
                return MigrationBaselineResult.builder()
                        .targetKey(resolvedKey)
                        .successfullyBaselined(result.successfullyBaselined)
                        .baselineVersion(result.baselineVersion)
                        .warnings(copyList(result.warnings))
                        .build();
            });
        } catch (FlywayException e) {
            throw e;
        } catch (Exception e) {
            throw new FlywayException(FlywayResultCode.BASELINE_FAILED,
                    "目标数据源 [" + resolvedKey + "] 基线初始化失败: " + sanitize(e.getMessage()), e);
        }
    }

    /**
     * 对默认主目标数据源执行历史表修复（对齐 Checksum 与清理失败记录）
     *
     * @return 修复结果
     */
    public MigrationRepairResult repair() {
        return repair(resolvePrimaryTargetKey());
    }

    /**
     * 对指定目标数据源执行历史表修复
     *
     * @param targetKey 目标数据源标识
     * @return 修复结果
     */
    public MigrationRepairResult repair(String targetKey) {
        String resolvedKey = requireTargetKey(targetKey);
        try {
            return executeWithFlyway(resolvedKey, flyway -> {
                RepairResult result = flyway.repair();
                return MigrationRepairResult.builder()
                        .targetKey(resolvedKey)
                        .repairActions(copyList(result.repairActions))
                        .warnings(copyList(result.warnings))
                        .build();
            });
        } catch (FlywayException e) {
            throw e;
        } catch (Exception e) {
            throw new FlywayException(FlywayResultCode.REPAIR_FAILED,
                    "目标数据源 [" + resolvedKey + "] 迁移历史修复失败: " + sanitize(e.getMessage()), e);
        }
    }

    /**
     * 在非受保护环境且显式允许清理时对默认主目标执行清理（受 ProductionSafetyGuard 强熔断）
     *
     * @return 清理结果
     */
    public MigrationCleanResult clean() {
        return clean(resolvePrimaryTargetKey());
    }

    /**
     * 在非受保护环境且显式允许清理时对指定目标执行清理（受 ProductionSafetyGuard 强熔断）
     *
     * @param targetKey 目标数据源标识
     * @return 清理结果
     */
    public MigrationCleanResult clean(String targetKey) {
        String resolvedKey = requireTargetKey(targetKey);
        safetyGuard.assertCleanAllowed(resolvedKey, null);
        try {
            return executeWithFlyway(resolvedKey, flyway -> {
                safetyGuard.assertCleanAllowed(resolvedKey, flyway);
                CleanResult result = flyway.clean();
                return MigrationCleanResult.builder()
                        .targetKey(resolvedKey)
                        .schemasCleaned(copyList(result.schemasCleaned))
                        .schemasDropped(copyList(result.schemasDropped))
                        .warnings(copyList(result.warnings))
                        .build();
            });
        } catch (FlywayException e) {
            throw e;
        } catch (Exception e) {
            throw new FlywayException(FlywayResultCode.MIGRATION_FAILED,
                    "目标数据源 [" + resolvedKey + "] 清理操作失败: " + sanitize(e.getMessage()), e);
        }
    }

    /**
     * 记录 DISABLED 模式下启动跳过的摘要快照（供启动初始化器调用）
     */
    public void recordSkippedStartupSummary() {
        String primaryKey = resolvePrimaryTargetKey();
        Instant now = Instant.now();
        MigrationOutcome outcome = MigrationOutcome.builder()
                .targetKey(primaryKey)
                .mode(FlywayExecutionMode.DISABLED)
                .success(true)
                .skipped(true)
                .migrationsExecuted(0)
                .duration(Duration.ZERO)
                .executedAt(now)
                .warnings(Collections.emptyList())
                .build();
        lastMigrationSummary.set(MigrationSummary.builder()
                .mode(FlywayExecutionMode.DISABLED)
                .executedAt(now)
                .totalDuration(Duration.ZERO)
                .totalTargets(1)
                .successCount(0)
                .failedCount(0)
                .skippedCount(1)
                .outcomes(List.of(outcome))
                .build());
    }

    /**
     * 获取最近一次迁移或启动校验的全局汇总摘要快照
     *
     * @return 最近一次摘要快照；若尚未执行过任何迁移或校验则返回 null
     */
    public MigrationSummary getLastMigrationSummary() {
        return lastMigrationSummary.get();
    }

    private String resolvePrimaryTargetKey() {
        return StringUtils.hasText(properties.getPrimaryTargetKey())
                ? properties.getPrimaryTargetKey().trim()
                : "default";
    }

    private String requireTargetKey(String targetKey) {
        Assert.hasText(targetKey, "目标数据源标识 (targetKey) 不能为空");
        return targetKey.trim();
    }

    private <T> T executeWithFlyway(String resolvedKey, Function<Flyway, T> action) {
        String primaryKey = resolvePrimaryTargetKey();
        if (!Objects.equals(primaryKey, resolvedKey)) {
            throw new FlywayException(FlywayResultCode.TARGET_NOT_FOUND,
                    "未找到指定迁移目标数据源: [" + resolvedKey + "]");
        }
        if (transientExecutor != null) {
            return transientExecutor.execute(resolvedKey, action);
        }
        return action.apply(primaryFlyway);
    }

    private int safeAppliedCount(Flyway flyway) {
        MigrationInfo[] applied = flyway.info().applied();
        return applied != null ? applied.length : 0;
    }

    private boolean shouldRecordValidationSummary() {
        return properties.getMode() == FlywayExecutionMode.VALIDATE_ONLY || lastMigrationSummary.get() == null;
    }

    private MigrationOutcome recordValidationFailureIfNeeded(String resolvedKey, Instant start, String sanitizedError) {
        if (shouldRecordValidationSummary()) {
            return recordSingleFailureSummary(resolvedKey, FlywayExecutionMode.VALIDATE_ONLY, start, sanitizedError);
        }
        return null;
    }

    private String sanitize(String rawMessage) {
        return FlywayConfigurationSupport.sanitizeSensitiveText(rawMessage);
    }

    private void recordSingleSummary(FlywayExecutionMode mode,
                                     MigrationOutcome outcome,
                                     Instant finishedAt,
                                     Duration elapsed) {
        lastMigrationSummary.set(MigrationSummary.builder()
                .mode(mode)
                .executedAt(finishedAt)
                .totalDuration(elapsed)
                .totalTargets(1)
                .successCount(outcome.isSuccess() ? 1 : 0)
                .failedCount(outcome.isSuccess() ? 0 : 1)
                .skippedCount(outcome.isSkipped() ? 1 : 0)
                .outcomes(List.of(outcome))
                .build());
    }

    private MigrationOutcome recordSingleFailureSummary(String targetKey,
                                                        FlywayExecutionMode mode,
                                                        Instant start,
                                                        String errorMessage) {
        Instant finishedAt = Instant.now();
        Duration elapsed = Duration.between(start, finishedAt);
        MigrationOutcome outcome = MigrationOutcome.builder()
                .targetKey(targetKey)
                .mode(mode)
                .success(false)
                .skipped(false)
                .migrationsExecuted(0)
                .duration(elapsed)
                .executedAt(finishedAt)
                .errorMessage(errorMessage)
                .warnings(Collections.emptyList())
                .build();
        recordSingleSummary(mode, outcome, finishedAt, elapsed);
        return outcome;
    }

    private List<FlywayMigrationListener> resolveListeners() {
        Set<FlywayMigrationListener> combined = new LinkedHashSet<>();
        if (listenersProvider != null) {
            listenersProvider.orderedStream().forEach(combined::add);
        }
        combined.addAll(manualListeners);
        return new ArrayList<>(combined);
    }

    private void notifyBeforeMigrate(String targetKey) {
        for (FlywayMigrationListener listener : resolveListeners()) {
            try {
                listener.onBeforeMigrate(targetKey);
            } catch (Exception e) {
                log.warn("FlywayMigrationListener#onBeforeMigrate 执行异常 [target={}]: {}",
                        targetKey, sanitize(e.getMessage()));
            }
        }
    }

    private void notifyMigrationSucceeded(MigrationOutcome outcome) {
        for (FlywayMigrationListener listener : resolveListeners()) {
            try {
                listener.onMigrationSucceeded(outcome);
            } catch (Exception e) {
                log.warn("FlywayMigrationListener#onMigrationSucceeded 执行异常 [target={}]: {}",
                        outcome.getTargetKey(), sanitize(e.getMessage()));
            }
        }
    }

    private void notifyMigrationFailed(String targetKey, MigrationOutcome outcome, Throwable cause) {
        for (FlywayMigrationListener listener : resolveListeners()) {
            try {
                listener.onMigrationFailed(targetKey, outcome, cause);
            } catch (Exception e) {
                log.warn("FlywayMigrationListener#onMigrationFailed 执行异常 [target={}]: {}",
                        targetKey, sanitize(e.getMessage()));
            }
        }
    }

    private void publishCompletedEvent(MigrationOutcome outcome) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new FlywayMigrationCompletedEvent(this, outcome));
        }
    }

    private void publishFailedEvent(String targetKey,
                                    FlywayExecutionMode mode,
                                    String safeMessage,
                                    MigrationOutcome outcome,
                                    Throwable cause) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new FlywayMigrationFailedEvent(
                    this, targetKey, mode, safeMessage, outcome, cause));
        }
    }

    private List<MigrationScriptInfo> convertScripts(MigrationInfo[] infos) {
        if (infos == null || infos.length == 0) {
            return Collections.emptyList();
        }
        List<MigrationScriptInfo> list = new ArrayList<>(infos.length);
        for (MigrationInfo info : infos) {
            if (info == null) {
                continue;
            }
            MigrationState state = info.getState();
            list.add(MigrationScriptInfo.builder()
                    .version(info.getVersion() != null ? info.getVersion().getVersion() : null)
                    .description(info.getDescription())
                    .type(info.getType() != null ? info.getType().name() : null)
                    .state(state != null ? state.name() : null)
                    .script(info.getScript())
                    .checksum(info.getChecksum())
                    .installedOn(info.getInstalledOn() != null ? info.getInstalledOn().toInstant() : null)
                    .executionTimeMs(info.getExecutionTime())
                    .installedBy(info.getInstalledBy())
                    .build());
        }
        return Collections.unmodifiableList(list);
    }

    private List<String> copyList(List<String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return source.stream()
                .filter(Objects::nonNull)
                .toList();
    }
}
