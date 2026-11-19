package io.github.atengk.boot.flyway.template;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.util.AsyncUtil;
import io.github.atengk.boot.flyway.config.FlywayConfigurationSupport;
import io.github.atengk.boot.flyway.enums.BatchExecutionPolicy;
import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.enums.MigrationTargetKind;
import io.github.atengk.boot.flyway.exception.FlywayException;
import io.github.atengk.boot.flyway.guard.ProductionSafetyGuard;
import io.github.atengk.boot.flyway.listener.FlywayMigrationListener;
import io.github.atengk.boot.flyway.model.BatchMigrationResult;
import io.github.atengk.boot.flyway.model.BatchOperationResult;
import io.github.atengk.boot.flyway.model.MigrationBaselineResult;
import io.github.atengk.boot.flyway.model.MigrationCleanResult;
import io.github.atengk.boot.flyway.model.MigrationOutcome;
import io.github.atengk.boot.flyway.model.MigrationRepairResult;
import io.github.atengk.boot.flyway.model.MigrationScriptInfo;
import io.github.atengk.boot.flyway.model.MigrationSummary;
import io.github.atengk.boot.flyway.model.MigrationTarget;
import io.github.atengk.boot.flyway.model.MigrationTargetInfo;
import io.github.atengk.boot.flyway.model.MigrationValidationResult;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import io.github.atengk.boot.flyway.security.TransientFlywayExecutor;
import io.github.atengk.boot.flyway.spi.DefaultFlywayLockProvider;
import io.github.atengk.boot.flyway.spi.FlywayLockProvider;
import io.github.atengk.boot.flyway.spi.FlywayTargetResolver;
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
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Flyway 数据库版本化迁移统一对称门面
 * <p>
 * 提供对称的单库与多目标数据源维度操作能力（{@code migrate}、{@code validate}、{@code pending}、
 * {@code info}、{@code baseline}、{@code repair}、{@code clean}，以及 {@code migrateTargets}、
 * {@code validateTargets}、{@code infoAll}），支持动态多数据源物理主库解包、静态多库隔离与
 * 隔离式加密 DDL 瞬态连接执行，统一集成 {@link FlywayLockProvider}、领域事件广播及全链路脱敏。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class FlywayTemplate implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(FlywayTemplate.class);

    private final Flyway primaryFlyway;
    private final TransientFlywayExecutor primaryTransientExecutor;
    private final TransientFlywayExecutor targetBuilderSupport;
    private final FlywayTargetResolver targetResolver;
    private final FlywayLockProvider lockProvider;
    private final AtengFlywayProperties properties;
    private final ProductionSafetyGuard safetyGuard;
    private final FlywayMigrationStrategy migrationStrategy;
    private final FlywayExecutionNotifier notifier;
    private final Executor taskExecutor;
    private final AtomicReference<MigrationSummary> lastMigrationSummary = new AtomicReference<>();
    private final AtomicLong summaryVersion = new AtomicLong(0);
    private final AtomicReference<CompletableFuture<BatchMigrationResult>> startupTenantMigrationFuture = new AtomicReference<>();
    private final AtomicReference<FlywayBatchExecutor.AsyncBatchHandle> startupTenantBatchHandle = new AtomicReference<>();

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
                null, null, null, new DefaultFlywayLockProvider(),
                properties, safetyGuard, migrationStrategy, null, null);
    }

    /**
     * 构造支持隔离瞬态凭据、事件发布与监听回调的单目标迁移门面
     *
     * @param primaryFlyway            正常模式主目标 Flyway 实例（隔离模式下为 null）
     * @param primaryTransientExecutor 隔离式 DDL 瞬态执行器（正常模式下为 null）
     * @param properties               ateng-boot-flyway 配置属性
     * @param safetyGuard              生产安全守卫
     * @param migrationStrategy        可选的自定义迁移策略
     * @param eventPublisher           Spring 领域事件发布器（可为 null）
     * @param listenersProvider        迁移监听器延迟提供者（可为 null）
     */
    public FlywayTemplate(Flyway primaryFlyway,
                          TransientFlywayExecutor primaryTransientExecutor,
                          AtengFlywayProperties properties,
                          ProductionSafetyGuard safetyGuard,
                          FlywayMigrationStrategy migrationStrategy,
                          ApplicationEventPublisher eventPublisher,
                          ObjectProvider<FlywayMigrationListener> listenersProvider) {
        this(primaryFlyway, primaryTransientExecutor, primaryTransientExecutor, null,
                new DefaultFlywayLockProvider(), properties, safetyGuard,
                migrationStrategy, eventPublisher, listenersProvider);
    }

    /**
     * 构造完整多数据源协同迁移门面（支持 L1/L2 目标路由、并发锁 SPI、瞬态加密连接与可观测闭环）
     *
     * @param primaryFlyway            正常模式主目标 Flyway 实例（隔离模式下可为 null）
     * @param primaryTransientExecutor 主目标瞬态执行器（非全局隔离模式下为 null）
     * @param targetBuilderSupport     多目标配置与瞬态凭据执行支撑（可为 null）
     * @param targetResolver           迁移目标路由器（可为 null，回退单目标模式）
     * @param lockProvider             并发迁移互斥锁提供者（为空时使用默认实现）
     * @param properties               ateng-boot-flyway 配置属性
     * @param safetyGuard              生产安全守卫
     * @param migrationStrategy        可选的自定义迁移策略
     * @param eventPublisher           Spring 领域事件发布器（可为 null）
     * @param listenersProvider        迁移监听器延迟提供者（可为 null）
     */
    public FlywayTemplate(Flyway primaryFlyway,
                          TransientFlywayExecutor primaryTransientExecutor,
                          TransientFlywayExecutor targetBuilderSupport,
                          FlywayTargetResolver targetResolver,
                          FlywayLockProvider lockProvider,
                          AtengFlywayProperties properties,
                          ProductionSafetyGuard safetyGuard,
                          FlywayMigrationStrategy migrationStrategy,
                          ApplicationEventPublisher eventPublisher,
                          ObjectProvider<FlywayMigrationListener> listenersProvider) {
        this(primaryFlyway, primaryTransientExecutor, targetBuilderSupport, targetResolver,
                lockProvider, properties, safetyGuard, migrationStrategy, eventPublisher, listenersProvider, null);
    }

    /**
     * 构造支持受管线程池注入的完整多数据源协同迁移门面（L1/L2/L3 全场景）
     *
     * @param primaryFlyway            正常模式主目标 Flyway 实例（隔离模式下可为 null）
     * @param primaryTransientExecutor 主目标瞬态执行器（非全局隔离模式下为 null）
     * @param targetBuilderSupport     多目标配置与瞬态凭据执行支撑（可为 null）
     * @param targetResolver           迁移目标路由器（可为 null，回退单目标模式）
     * @param lockProvider             并发迁移互斥锁提供者（为空时使用默认实现）
     * @param properties               ateng-boot-flyway 配置属性
     * @param safetyGuard              生产安全守卫
     * @param migrationStrategy        可选的自定义迁移策略
     * @param eventPublisher           Spring 领域事件发布器（可为 null）
     * @param listenersProvider        迁移监听器延迟提供者（可为 null）
     * @param taskExecutor             受管异步线程池（为空时回退至 Core {@link AsyncUtil#getExecutor()}）
     */
    public FlywayTemplate(Flyway primaryFlyway,
                          TransientFlywayExecutor primaryTransientExecutor,
                          TransientFlywayExecutor targetBuilderSupport,
                          FlywayTargetResolver targetResolver,
                          FlywayLockProvider lockProvider,
                          AtengFlywayProperties properties,
                          ProductionSafetyGuard safetyGuard,
                          FlywayMigrationStrategy migrationStrategy,
                          ApplicationEventPublisher eventPublisher,
                          ObjectProvider<FlywayMigrationListener> listenersProvider,
                          Executor taskExecutor) {
        Assert.state(primaryFlyway != null || primaryTransientExecutor != null || targetResolver != null,
                "primaryFlyway、primaryTransientExecutor 与 targetResolver 不能同时为空");
        this.primaryFlyway = primaryFlyway;
        this.primaryTransientExecutor = primaryTransientExecutor;
        this.targetBuilderSupport = targetBuilderSupport;
        this.targetResolver = targetResolver;
        this.lockProvider = lockProvider != null ? lockProvider : new DefaultFlywayLockProvider();
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.safetyGuard = Objects.requireNonNull(safetyGuard, "safetyGuard 不能为空");
        this.migrationStrategy = migrationStrategy;
        this.notifier = new FlywayExecutionNotifier(this, eventPublisher, listenersProvider);
        this.taskExecutor = taskExecutor;
    }

    /**
     * 动态注册编程式迁移监听器
     *
     * @param listener 迁移监听器
     */
    public void addMigrationListener(FlywayMigrationListener listener) {
        notifier.addListener(listener);
    }

    /**
     * 注销编程式迁移监听器
     *
     * @param listener 迁移监听器
     */
    public void removeMigrationListener(FlywayMigrationListener listener) {
        notifier.removeListener(listener);
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
        MigrationOutcome outcome = executeSingleMigration(resolvedKey, true);
        recordSingleSummary(FlywayExecutionMode.MIGRATE, outcome, outcome.getExecutedAt(), outcome.getDuration());
        return outcome;
    }

    /**
     * 按配置容错策略对全部受管控目标数据源执行批量版本化迁移
     *
     * @return 批量迁移聚合回执
     */
    public BatchMigrationResult migrateTargets() {
        return migrateTargets(resolveStaticFailurePolicy());
    }

    /**
     * 按指定容错策略对全部受管控目标数据源执行批量版本化迁移
     *
     * @param policy 批量执行容错策略（FAIL_FAST / FAIL_SAFE）
     * @return 批量迁移聚合回执
     */
    public BatchMigrationResult migrateTargets(BatchExecutionPolicy policy) {
        return executeBatchTargets(null, FlywayExecutionMode.MIGRATE, policy);
    }

    /**
     * 按默认静态失败策略对指定目标数据源集合执行批量版本化迁移
     *
     * @param targetKeys 目标数据源标识集合
     * @return 批量迁移聚合回执
     */
    public BatchMigrationResult migrateTargets(Collection<String> targetKeys) {
        return migrateTargets(targetKeys, resolveStaticFailurePolicy());
    }

    /**
     * 按指定容错策略对指定目标数据源集合执行批量版本化迁移
     *
     * @param targetKeys 目标数据源标识集合
     * @param policy     批量执行容错策略
     * @return 批量迁移聚合回执
     */
    public BatchMigrationResult migrateTargets(Collection<String> targetKeys, BatchExecutionPolicy policy) {
        Assert.notEmpty(targetKeys, "目标数据源集合 (targetKeys) 不能为空");
        return executeBatchTargets(targetKeys, FlywayExecutionMode.MIGRATE, policy);
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
        return executeSingleValidation(resolvedKey, shouldRecordValidationSummary());
    }

    /**
     * 按配置容错策略对全部受管控目标数据源执行只读一致性校验
     *
     * @return 批量校验聚合回执
     */
    public BatchMigrationResult validateTargets() {
        return validateTargets(resolveStaticFailurePolicy());
    }

    /**
     * 按指定容错策略对全部受管控目标数据源执行只读一致性校验
     *
     * @param policy 批量执行容错策略
     * @return 批量校验聚合回执
     */
    public BatchMigrationResult validateTargets(BatchExecutionPolicy policy) {
        return executeBatchTargets(null, FlywayExecutionMode.VALIDATE_ONLY, policy);
    }

    /**
     * 按默认静态失败策略对指定目标数据源集合执行只读一致性校验
     *
     * @param targetKeys 目标数据源标识集合
     * @return 批量校验聚合回执
     */
    public BatchMigrationResult validateTargets(Collection<String> targetKeys) {
        return validateTargets(targetKeys, resolveStaticFailurePolicy());
    }

    /**
     * 按指定容错策略对指定目标数据源集合执行只读一致性校验
     *
     * @param targetKeys 目标数据源标识集合
     * @param policy     批量执行容错策略
     * @return 批量校验聚合回执
     */
    public BatchMigrationResult validateTargets(Collection<String> targetKeys, BatchExecutionPolicy policy) {
        Assert.notEmpty(targetKeys, "目标数据源集合 (targetKeys) 不能为空");
        return executeBatchTargets(targetKeys, FlywayExecutionMode.VALIDATE_ONLY, policy);
    }

    /**
     * 按配置容错策略与有界并发度对全部 SaaS 物理租户库执行批量版本化迁移
     *
     * @return 全租户批量迁移聚合回执
     */
    public BatchMigrationResult migrateAllTenants() {
        return migrateAllTenants(resolveTenantFailurePolicy());
    }

    /**
     * 按指定容错策略与配置有界并发度对全部 SaaS 物理租户库执行批量版本化迁移
     *
     * @param policy 批量执行容错策略（FAIL_FAST / FAIL_SAFE）
     * @return 全租户批量迁移聚合回执
     */
    public BatchMigrationResult migrateAllTenants(BatchExecutionPolicy policy) {
        BatchExecutionPolicy effectivePolicy = policy != null ? policy : resolveTenantFailurePolicy();
        List<String> tenantKeys = resolveTenantTargetKeys();
        return executeBatchList(tenantKeys, FlywayExecutionMode.MIGRATE, effectivePolicy,
                resolveTenantConcurrency(), true);
    }

    /**
     * 按配置容错策略与有界并发度对全部 SaaS 物理租户库执行批量一致性校验
     *
     * @return 全租户批量校验聚合回执
     */
    public BatchMigrationResult validateAllTenants() {
        return validateAllTenants(resolveTenantFailurePolicy());
    }

    /**
     * 按指定容错策略与配置有界并发度对全部 SaaS 物理租户库执行批量一致性校验
     *
     * @param policy 批量执行容错策略（FAIL_FAST / FAIL_SAFE）
     * @return 全租户批量校验聚合回执
     */
    public BatchMigrationResult validateAllTenants(BatchExecutionPolicy policy) {
        BatchExecutionPolicy effectivePolicy = policy != null ? policy : resolveTenantFailurePolicy();
        List<String> tenantKeys = resolveTenantTargetKeys();
        return executeBatchList(tenantKeys, FlywayExecutionMode.VALIDATE_ONLY, effectivePolicy,
                resolveTenantConcurrency(), true);
    }

    /**
     * 获取启动期异步租户迁移任务句柄（未开启异步启动或尚未调度时返回 null）
     *
     * @return 异步租户批量迁移任务 Future，可为 null
     */
    public CompletableFuture<BatchMigrationResult> getStartupTenantMigrationFuture() {
        return startupTenantMigrationFuture.get();
    }

    /**
     * 容器关闭时协作式取消尚未完结的启动期异步租户迁移任务，防止停机期间访问已关闭连接池
     */
    @Override
    public void destroy() {
        CompletableFuture<BatchMigrationResult> exposedFuture = startupTenantMigrationFuture.get();
        FlywayBatchExecutor.AsyncBatchHandle handle = startupTenantBatchHandle.getAndSet(null);
        boolean activeExposed = exposedFuture != null && !exposedFuture.isDone();
        boolean activeHandle = handle != null && !handle.future().isDone();
        if (activeExposed || activeHandle) {
            log.info("Spring 容器正在关闭，协作式取消尚未完结的启动期异步租户迁移任务");
        }
        if (activeExposed) {
            exposedFuture.cancel(true);
        }
        if (activeHandle) {
            handle.cancelAll(java.time.Duration.ofSeconds(2));
        }
    }

    /**
     * 启动初始化调度入口：先同步完成静态核心目标，再按需同步或异步执行全租户批量迁移与摘要合并
     *
     * @param mode 启动执行模式（MIGRATE / VALIDATE_ONLY）
     * @return 启动阶段批处理回执
     */
    public BatchMigrationResult executeStartupTargets(FlywayExecutionMode mode) {
        BatchExecutionPolicy staticPolicy = resolveStaticFailurePolicy();
        List<String> staticKeys = resolveStaticTargetKeys();
        BatchMigrationResult staticResult = executeBatchList(staticKeys, mode, staticPolicy, 1, true);

        AtengFlywayProperties.TenantMigrationProperties tenantProps = properties.getTenant();
        if (tenantProps == null || !tenantProps.isEnabled() || !tenantProps.isMigrateOnStartup()) {
            return staticResult;
        }
        List<String> tenantKeys = resolveTenantTargetKeys();
        if (tenantKeys.isEmpty()) {
            return staticResult;
        }

        BatchExecutionPolicy tenantPolicy = resolveTenantFailurePolicy();
        int concurrency = resolveTenantConcurrency();

        if (tenantProps.isAsyncStartup()) {
            FlywayBatchExecutor.AsyncBatchHandle handle = FlywayBatchExecutor.launchAsync(
                    tenantKeys, mode, tenantPolicy, concurrency, resolveTaskExecutor(),
                    this::executeBatchItem, this::sanitize);
            startupTenantBatchHandle.set(handle);
            CompletableFuture<BatchMigrationResult> asyncFuture = handle.future()
                    .whenComplete((tenantResult, ex) -> {
                        startupTenantBatchHandle.compareAndSet(handle, null);
                        if (tenantResult != null) {
                            BatchMigrationResult combined = FlywayBatchExecutor.mergeBatchResults(
                                    mode, tenantPolicy, staticResult, tenantResult);
                            commitSummary(FlywayBatchExecutor.toSummary(combined));
                        } else if (ex != null) {
                            Throwable unwrapped = (ex instanceof CompletionException && ex.getCause() != null)
                                    ? ex.getCause() : ex;
                            if (unwrapped instanceof FlywayBatchExecutor.BatchFailFastException failFast) {
                                BatchMigrationResult combined = FlywayBatchExecutor.mergeBatchResults(
                                        mode, tenantPolicy, staticResult, failFast.getPartialResult());
                                commitSummary(FlywayBatchExecutor.toSummary(combined));
                            }
                            log.error("启动期异步租户批量执行异常: {}", sanitize(unwrapped.getMessage()), unwrapped);
                        }
                    });
            startupTenantMigrationFuture.set(asyncFuture);
            return staticResult;
        }

        BatchMigrationResult tenantResult = executeBatchList(tenantKeys, mode, tenantPolicy, concurrency, false);
        BatchMigrationResult combined = FlywayBatchExecutor.mergeBatchResults(mode, tenantPolicy, staticResult, tenantResult);
        commitSummary(FlywayBatchExecutor.toSummary(combined));
        return combined;
    }

    /**
     * 执行运行期动态挂载数据源的增量迁移或校验，并在保留模式失败或成功时增量合并全局摘要快照
     *
     * @param targetKey         动态挂载数据源标识
     * @param mode              当前执行模式（MIGRATE / VALIDATE_ONLY）
     * @param rollbackOnFailure 失败时是否即将摘除数据源（为 true 时不在全局摘要中滞留失败状态）
     * @return 执行结果
     */
    public MigrationOutcome executeDynamicMountTarget(String targetKey,
                                                      FlywayExecutionMode mode,
                                                      boolean rollbackOnFailure) {
        String resolvedKey = requireTargetKey(targetKey);
        FlywayExecutionMode effectiveMode = mode != null ? mode : FlywayExecutionMode.MIGRATE;
        Instant start = Instant.now();
        try {
            MigrationOutcome outcome = executeBatchItem(resolvedKey, effectiveMode);
            updateSummary(existing -> FlywayBatchExecutor.upsertSummaryOutcome(existing, effectiveMode, outcome));
            return outcome;
        } catch (FlywayException e) {
            if (!rollbackOnFailure) {
                MigrationOutcome failureOutcome = buildFailureOutcome(resolvedKey, effectiveMode, start, sanitize(e.getMessage()));
                updateSummary(existing -> FlywayBatchExecutor.upsertSummaryOutcome(existing, effectiveMode, failureOutcome));
            }
            throw e;
        } catch (Exception e) {
            String safeMessage = sanitize(e.getMessage());
            if (!rollbackOnFailure) {
                MigrationOutcome failureOutcome = buildFailureOutcome(resolvedKey, effectiveMode, start, safeMessage);
                updateSummary(existing -> FlywayBatchExecutor.upsertSummaryOutcome(existing, effectiveMode, failureOutcome));
            }
            FlywayResultCode code = effectiveMode == FlywayExecutionMode.VALIDATE_ONLY
                    ? FlywayResultCode.VALIDATION_FAILED
                    : FlywayResultCode.MIGRATION_FAILED;
            throw new FlywayException(code,
                    "动态挂载数据源 [" + resolvedKey + "] 执行失败: " + safeMessage, e);
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
        return executeOperation(resolvedKey, false, FlywayResultCode.MIGRATION_FAILED,
                "查询目标数据源 [" + resolvedKey + "] 待迁移脚本失败",
                flyway -> convertScripts(flyway.info().pending()));
    }

    /**
     * 按配置容错策略对全部受管控目标数据源执行批量待迁移脚本预检
     *
     * @return 泛型批量预检回执
     */
    public BatchOperationResult<List<MigrationScriptInfo>> pendingTargets() {
        return pendingTargets(resolveStaticFailurePolicy());
    }

    /**
     * 按指定容错策略对全部受管控目标数据源执行批量待迁移脚本预检
     *
     * @param policy 批量执行容错策略
     * @return 泛型批量预检回执
     */
    public BatchOperationResult<List<MigrationScriptInfo>> pendingTargets(BatchExecutionPolicy policy) {
        return executeGenericTargets(null, policy, FlywayResultCode.MIGRATION_FAILED, this::pending);
    }

    /**
     * 按默认静态失败策略对指定目标数据源集合执行批量待迁移脚本预检
     *
     * @param targetKeys 目标数据源标识集合
     * @return 泛型批量预检回执
     */
    public BatchOperationResult<List<MigrationScriptInfo>> pendingTargets(Collection<String> targetKeys) {
        return pendingTargets(targetKeys, resolveStaticFailurePolicy());
    }

    /**
     * 按指定容错策略对指定目标数据源集合执行批量待迁移脚本预检
     *
     * @param targetKeys 目标数据源标识集合
     * @param policy     批量执行容错策略
     * @return 泛型批量预检回执
     */
    public BatchOperationResult<List<MigrationScriptInfo>> pendingTargets(Collection<String> targetKeys,
                                                                        BatchExecutionPolicy policy) {
        Assert.notEmpty(targetKeys, "目标数据源集合 (targetKeys) 不能为空");
        return executeGenericTargets(targetKeys, policy, FlywayResultCode.MIGRATION_FAILED, this::pending);
    }

    /**
     * 按租户容错策略与有界并发度对全部 SaaS 物理租户库执行批量待迁移脚本预检
     *
     * @return 全租户批量预检回执
     */
    public BatchOperationResult<List<MigrationScriptInfo>> pendingAllTenants() {
        return pendingAllTenants(resolveTenantFailurePolicy());
    }

    /**
     * 按指定容错策略与配置有界并发度对全部 SaaS 物理租户库执行批量待迁移脚本预检
     *
     * @param policy 批量执行容错策略
     * @return 全租户批量预检回执
     */
    public BatchOperationResult<List<MigrationScriptInfo>> pendingAllTenants(BatchExecutionPolicy policy) {
        return executeGenericTenants(policy, FlywayResultCode.MIGRATION_FAILED, this::pending);
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
        return executeOperation(resolvedKey, false, FlywayResultCode.MIGRATION_FAILED,
                "查询目标数据源 [" + resolvedKey + "] 迁移历史信息失败",
                flyway -> {
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
    }

    /**
     * 聚合查询全部受管控目标数据源的迁移历史与状态视图
     *
     * @return 目标迁移状态列表（永不为 null）
     */
    public List<MigrationTargetInfo> infoAll() {
        List<String> keys = resolveOrderedTargetKeys();
        if (keys.isEmpty()) {
            return Collections.emptyList();
        }
        return infoAll(keys);
    }

    /**
     * 聚合查询指定目标数据源集合的迁移历史与状态视图
     *
     * @param targetKeys 目标数据源标识集合
     * @return 目标迁移状态列表（永不为 null）
     */
    public List<MigrationTargetInfo> infoAll(Collection<String> targetKeys) {
        if (CollectionUtils.isEmpty(targetKeys)) {
            return Collections.emptyList();
        }
        List<String> keys = resolveBatchKeys(targetKeys);
        List<MigrationTargetInfo> list = new ArrayList<>(keys.size());
        for (String key : keys) {
            list.add(info(key));
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * 按指定容错策略聚合探查全部受管控目标数据源迁移状态并输出泛型回执
     *
     * @param policy 批量执行容错策略
     * @return 泛型批量状态聚合回执
     */
    public BatchOperationResult<MigrationTargetInfo> infoAll(BatchExecutionPolicy policy) {
        return infoTargets(policy);
    }

    /**
     * 按指定容错策略聚合探查指定目标数据源集合迁移状态并输出泛型回执
     *
     * @param targetKeys 目标数据源标识集合
     * @param policy     批量执行容错策略
     * @return 泛型批量状态聚合回执
     */
    public BatchOperationResult<MigrationTargetInfo> infoAll(Collection<String> targetKeys,
                                                             BatchExecutionPolicy policy) {
        return infoTargets(targetKeys, policy);
    }

    /**
     * 按配置容错策略对全部受管控目标数据源执行历史状态聚合探查
     *
     * @return 泛型批量状态聚合回执
     */
    public BatchOperationResult<MigrationTargetInfo> infoTargets() {
        return infoTargets(resolveStaticFailurePolicy());
    }

    /**
     * 按指定容错策略对全部受管控目标数据源执行历史状态聚合探查
     *
     * @param policy 批量执行容错策略
     * @return 泛型批量状态聚合回执
     */
    public BatchOperationResult<MigrationTargetInfo> infoTargets(BatchExecutionPolicy policy) {
        return executeGenericTargets(null, policy, FlywayResultCode.MIGRATION_FAILED, this::info);
    }

    /**
     * 按默认静态失败策略对指定目标数据源集合执行历史状态聚合探查
     *
     * @param targetKeys 目标数据源标识集合
     * @return 泛型批量状态聚合回执
     */
    public BatchOperationResult<MigrationTargetInfo> infoTargets(Collection<String> targetKeys) {
        return infoTargets(targetKeys, resolveStaticFailurePolicy());
    }

    /**
     * 按指定容错策略对指定目标数据源集合执行历史状态聚合探查
     *
     * @param targetKeys 目标数据源标识集合
     * @param policy     批量执行容错策略
     * @return 泛型批量状态聚合回执
     */
    public BatchOperationResult<MigrationTargetInfo> infoTargets(Collection<String> targetKeys,
                                                                 BatchExecutionPolicy policy) {
        Assert.notEmpty(targetKeys, "目标数据源集合 (targetKeys) 不能为空");
        return executeGenericTargets(targetKeys, policy, FlywayResultCode.MIGRATION_FAILED, this::info);
    }

    /**
     * 按租户容错策略与有界并发度对全部 SaaS 物理租户库执行历史状态聚合探查
     *
     * @return 全租户批量状态聚合回执
     */
    public BatchOperationResult<MigrationTargetInfo> infoAllTenants() {
        return infoAllTenants(resolveTenantFailurePolicy());
    }

    /**
     * 按指定容错策略与配置有界并发度对全部 SaaS 物理租户库执行历史状态聚合探查
     *
     * @param policy 批量执行容错策略
     * @return 全租户批量状态聚合回执
     */
    public BatchOperationResult<MigrationTargetInfo> infoAllTenants(BatchExecutionPolicy policy) {
        return executeGenericTenants(policy, FlywayResultCode.MIGRATION_FAILED, this::info);
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
        Instant start = Instant.now();
        return executeOperation(resolvedKey, true, FlywayResultCode.BASELINE_FAILED,
                "目标数据源 [" + resolvedKey + "] 基线初始化失败",
                baseFlyway -> {
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
                    List<String> warnings = copyList(result.warnings);
                    recordGovernanceSummaryOutcome(resolvedKey, runner.info(), result.baselineVersion,
                            result.database, start, 0, null, warnings);
                    return MigrationBaselineResult.builder()
                            .targetKey(resolvedKey)
                            .successfullyBaselined(result.successfullyBaselined)
                            .baselineVersion(result.baselineVersion)
                            .warnings(warnings)
                            .build();
                });
    }

    /**
     * 按配置容错策略对全部受管控目标数据源执行批量基线初始化
     *
     * @return 泛型批量基线初始化回执
     */
    public BatchOperationResult<MigrationBaselineResult> baselineTargets() {
        return baselineTargets(null, null, resolveStaticFailurePolicy());
    }

    /**
     * 按指定容错策略对全部受管控目标数据源执行批量基线初始化
     *
     * @param policy 批量执行容错策略
     * @return 泛型批量基线初始化回执
     */
    public BatchOperationResult<MigrationBaselineResult> baselineTargets(BatchExecutionPolicy policy) {
        return baselineTargets(null, null, policy);
    }

    /**
     * 按默认静态失败策略对全部受管控目标数据源使用指定版本号与描述执行批量基线初始化
     *
     * @param version     基线版本号（为空则继承配置默认值）
     * @param description 基线描述（为空则继承配置默认值）
     * @return 泛型批量基线初始化回执
     */
    public BatchOperationResult<MigrationBaselineResult> baselineTargets(String version, String description) {
        return baselineTargets(version, description, resolveStaticFailurePolicy());
    }

    /**
     * 按指定容错策略对全部受管控目标数据源使用指定版本号与描述执行批量基线初始化
     *
     * @param version     基线版本号（为空则继承配置默认值）
     * @param description 基线描述（为空则继承配置默认值）
     * @param policy      批量执行容错策略
     * @return 泛型批量基线初始化回执
     */
    public BatchOperationResult<MigrationBaselineResult> baselineTargets(String version,
                                                                         String description,
                                                                         BatchExecutionPolicy policy) {
        return executeGenericTargets(null, policy, FlywayResultCode.BASELINE_FAILED,
                key -> baseline(key, version, description));
    }

    /**
     * 按默认静态失败策略对指定目标数据源集合执行批量基线初始化
     *
     * @param targetKeys 目标数据源标识集合
     * @return 泛型批量基线初始化回执
     */
    public BatchOperationResult<MigrationBaselineResult> baselineTargets(Collection<String> targetKeys) {
        return baselineTargets(targetKeys, null, null, resolveStaticFailurePolicy());
    }

    /**
     * 按指定容错策略对指定目标数据源集合执行批量基线初始化
     *
     * @param targetKeys 目标数据源标识集合
     * @param policy     批量执行容错策略
     * @return 泛型批量基线初始化回执
     */
    public BatchOperationResult<MigrationBaselineResult> baselineTargets(Collection<String> targetKeys,
                                                                         BatchExecutionPolicy policy) {
        return baselineTargets(targetKeys, null, null, policy);
    }

    /**
     * 按默认静态失败策略对指定目标数据源集合使用指定版本号与描述执行批量基线初始化
     *
     * @param targetKeys  目标数据源标识集合
     * @param version     基线版本号（为空则继承配置默认值）
     * @param description 基线描述（为空则继承配置默认值）
     * @return 泛型批量基线初始化回执
     */
    public BatchOperationResult<MigrationBaselineResult> baselineTargets(Collection<String> targetKeys,
                                                                         String version,
                                                                         String description) {
        return baselineTargets(targetKeys, version, description, resolveStaticFailurePolicy());
    }

    /**
     * 按指定容错策略对指定目标数据源集合使用指定版本号与描述执行批量基线初始化
     *
     * @param targetKeys  目标数据源标识集合
     * @param version     基线版本号（为空则继承配置默认值）
     * @param description 基线描述（为空则继承配置默认值）
     * @param policy      批量执行容错策略
     * @return 泛型批量基线初始化回执
     */
    public BatchOperationResult<MigrationBaselineResult> baselineTargets(Collection<String> targetKeys,
                                                                         String version,
                                                                         String description,
                                                                         BatchExecutionPolicy policy) {
        Assert.notEmpty(targetKeys, "目标数据源集合 (targetKeys) 不能为空");
        return executeGenericTargets(targetKeys, policy, FlywayResultCode.BASELINE_FAILED,
                key -> baseline(key, version, description));
    }

    /**
     * 按租户容错策略与有界并发度对全部 SaaS 物理租户库执行批量基线初始化
     *
     * @return 全租户批量基线初始化回执
     */
    public BatchOperationResult<MigrationBaselineResult> baselineAllTenants() {
        return baselineAllTenants(null, null, resolveTenantFailurePolicy());
    }

    /**
     * 按指定容错策略与配置有界并发度对全部 SaaS 物理租户库执行批量基线初始化
     *
     * @param policy 批量执行容错策略
     * @return 全租户批量基线初始化回执
     */
    public BatchOperationResult<MigrationBaselineResult> baselineAllTenants(BatchExecutionPolicy policy) {
        return baselineAllTenants(null, null, policy);
    }

    /**
     * 按租户容错策略与有界并发度对全部 SaaS 物理租户库使用指定版本号与描述执行批量基线初始化
     *
     * @param version     基线版本号（为空则继承配置默认值）
     * @param description 基线描述（为空则继承配置默认值）
     * @return 全租户批量基线初始化回执
     */
    public BatchOperationResult<MigrationBaselineResult> baselineAllTenants(String version, String description) {
        return baselineAllTenants(version, description, resolveTenantFailurePolicy());
    }

    /**
     * 按指定容错策略与配置有界并发度对全部 SaaS 物理租户库使用指定版本号与描述执行批量基线初始化
     *
     * @param version     基线版本号（为空则继承配置默认值）
     * @param description 基线描述（为空则继承配置默认值）
     * @param policy      批量执行容错策略
     * @return 全租户批量基线初始化回执
     */
    public BatchOperationResult<MigrationBaselineResult> baselineAllTenants(String version,
                                                                            String description,
                                                                            BatchExecutionPolicy policy) {
        return executeGenericTenants(policy, FlywayResultCode.BASELINE_FAILED,
                key -> baseline(key, version, description));
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
        Instant start = Instant.now();
        return executeOperation(resolvedKey, true, FlywayResultCode.REPAIR_FAILED,
                "目标数据源 [" + resolvedKey + "] 迁移历史修复失败",
                flyway -> {
                    RepairResult result = flyway.repair();
                    MigrationInfoService infoService = flyway.info();
                    int remainingFailed = countFailedMigrations(infoService.all());
                    List<String> warnings = copyList(result.warnings);
                    String errorMessage = remainingFailed > 0
                            ? "修复后仍存在 " + remainingFailed + " 条失败迁移记录"
                            : null;
                    recordGovernanceSummaryOutcome(resolvedKey, infoService, null,
                            result.database, start, remainingFailed, errorMessage, warnings);
                    return MigrationRepairResult.builder()
                            .targetKey(resolvedKey)
                            .repairActions(copyList(result.repairActions))
                            .warnings(warnings)
                            .build();
                });
    }

    /**
     * 按配置容错策略对全部受管控目标数据源执行批量历史表修复
     *
     * @return 泛型批量修复回执
     */
    public BatchOperationResult<MigrationRepairResult> repairTargets() {
        return repairTargets(resolveStaticFailurePolicy());
    }

    /**
     * 按指定容错策略对全部受管控目标数据源执行批量历史表修复
     *
     * @param policy 批量执行容错策略
     * @return 泛型批量修复回执
     */
    public BatchOperationResult<MigrationRepairResult> repairTargets(BatchExecutionPolicy policy) {
        return executeGenericTargets(null, policy, FlywayResultCode.REPAIR_FAILED, this::repair);
    }

    /**
     * 按默认静态失败策略对指定目标数据源集合执行批量历史表修复
     *
     * @param targetKeys 目标数据源标识集合
     * @return 泛型批量修复回执
     */
    public BatchOperationResult<MigrationRepairResult> repairTargets(Collection<String> targetKeys) {
        return repairTargets(targetKeys, resolveStaticFailurePolicy());
    }

    /**
     * 按指定容错策略对指定目标数据源集合执行批量历史表修复
     *
     * @param targetKeys 目标数据源标识集合
     * @param policy     批量执行容错策略
     * @return 泛型批量修复回执
     */
    public BatchOperationResult<MigrationRepairResult> repairTargets(Collection<String> targetKeys,
                                                                       BatchExecutionPolicy policy) {
        Assert.notEmpty(targetKeys, "目标数据源集合 (targetKeys) 不能为空");
        return executeGenericTargets(targetKeys, policy, FlywayResultCode.REPAIR_FAILED, this::repair);
    }

    /**
     * 按租户容错策略与有界并发度对全部 SaaS 物理租户库执行批量历史表修复
     *
     * @return 全租户批量修复回执
     */
    public BatchOperationResult<MigrationRepairResult> repairAllTenants() {
        return repairAllTenants(resolveTenantFailurePolicy());
    }

    /**
     * 按指定容错策略与配置有界并发度对全部 SaaS 物理租户库执行批量历史表修复
     *
     * @param policy 批量执行容错策略
     * @return 全租户批量修复回执
     */
    public BatchOperationResult<MigrationRepairResult> repairAllTenants(BatchExecutionPolicy policy) {
        return executeGenericTenants(policy, FlywayResultCode.REPAIR_FAILED, this::repair);
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
        return executeOperation(resolvedKey, true, FlywayResultCode.MIGRATION_FAILED,
                "目标数据源 [" + resolvedKey + "] 清理操作失败",
                flyway -> {
                    safetyGuard.assertCleanAllowed(resolvedKey, flyway);
                    CleanResult result = flyway.clean();
                    removeTargetFromSummary(resolvedKey, true);
                    return MigrationCleanResult.builder()
                            .targetKey(resolvedKey)
                            .schemasCleaned(copyList(result.schemasCleaned))
                            .schemasDropped(copyList(result.schemasDropped))
                            .warnings(copyList(result.warnings))
                            .build();
                });
    }

    /**
     * 记录 DISABLED 模式下启动跳过的摘要快照（供启动初始化器调用，覆盖全量受管目标）
     */
    public void recordSkippedStartupSummary() {
        List<String> keys = resolveOrderedTargetKeys();
        if (keys.isEmpty()) {
            keys = List.of(resolvePrimaryTargetKey());
        }
        Instant now = Instant.now();
        List<MigrationOutcome> outcomes = new ArrayList<>(keys.size());
        for (String key : keys) {
            outcomes.add(MigrationOutcome.builder()
                    .targetKey(key)
                    .mode(FlywayExecutionMode.DISABLED)
                    .success(true)
                    .skipped(true)
                    .migrationsExecuted(0)
                    .duration(Duration.ZERO)
                    .executedAt(now)
                    .warnings(Collections.emptyList())
                    .build());
        }
        commitSummary(MigrationSummary.builder()
                .mode(FlywayExecutionMode.DISABLED)
                .executedAt(now)
                .totalDuration(Duration.ZERO)
                .totalTargets(outcomes.size())
                .successCount(0)
                .failedCount(0)
                .skippedCount(outcomes.size())
                .outcomes(Collections.unmodifiableList(outcomes))
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

    /**
     * 从全局汇总摘要快照中移除已摘除目标数据源记录并递增摘要版本号
     *
     * @param targetKey 目标数据源标识
     * @return true 表示摘要中原本存在该目标并已清理，false 表示摘要中无该目标记录
     */
    public boolean removeTargetFromSummary(String targetKey) {
        return removeTargetFromSummary(targetKey, false);
    }

    private boolean removeTargetFromSummary(String targetKey, boolean alwaysIncrementVersion) {
        if (!StringUtils.hasText(targetKey)) {
            return false;
        }
        String normalizedKey = targetKey.trim();
        AtomicBoolean removed = new AtomicBoolean(false);
        lastMigrationSummary.updateAndGet(existing -> {
            if (existing == null || CollectionUtils.isEmpty(existing.getOutcomes())) {
                return existing;
            }
            MigrationSummary updated = FlywayBatchExecutor.removeSummaryOutcome(existing, normalizedKey);
            if (updated != existing) {
                removed.set(true);
            }
            return updated;
        });
        if (removed.get() || alwaysIncrementVersion) {
            summaryVersion.incrementAndGet();
        }
        return removed.get();
    }

    /**
     * 获取全局汇总摘要单调递增版本号（用于可观测缓存防竞态失效校验）
     *
     * @return 摘要快照版本号
     */
    public long getSummaryVersion() {
        return summaryVersion.get();
    }

    /**
     * 获取当前仍处于打开状态的隔离式瞬态连接池数量（用于防泄漏审计，正常结束应为 0）
     *
     * @return 活跃瞬态连接池数量
     */
    public int getActiveTransientPoolCount() {
        TransientFlywayExecutor runner = targetBuilderSupport != null ? targetBuilderSupport : primaryTransientExecutor;
        return runner != null ? runner.getActiveTransientPoolCount() : 0;
    }

    /**
     * 获取累计已确定性关闭的隔离式瞬态连接池数量
     *
     * @return 累计已关闭瞬态连接池数量
     */
    public long getClosedTransientPoolCount() {
        TransientFlywayExecutor runner = targetBuilderSupport != null ? targetBuilderSupport : primaryTransientExecutor;
        return runner != null ? runner.getClosedTransientPoolCount() : 0L;
    }

    /**
     * 基于指定迁移目标（含临时隔离 DDL 凭据）在瞬态连接作用域内执行受管 {@link Flyway} 操作
     *
     * @param target 迁移目标描述符
     * @param action 基于 Flyway 实例的业务操作回调
     * @param <T>    返回值类型
     * @return 操作执行结果
     */
    public <T> T executeTransient(MigrationTarget target, Function<Flyway, T> action) {
        Objects.requireNonNull(target, "target 不能为空");
        Objects.requireNonNull(action, "action 不能为空");
        TransientFlywayExecutor runner = targetBuilderSupport != null ? targetBuilderSupport : primaryTransientExecutor;
        if (runner == null) {
            throw new FlywayException(FlywayResultCode.CONFIG_INVALID,
                    "当前环境未装配瞬态 Flyway 执行器，无法执行隔离凭据迁移操作");
        }
        return runner.execute(target, action);
    }

    private <T> BatchOperationResult<T> executeGenericTargets(Collection<String> explicitKeys,
                                                               BatchExecutionPolicy policy,
                                                               FlywayResultCode fallbackCode,
                                                               Function<String, T> action) {
        BatchExecutionPolicy effectivePolicy = policy != null ? policy : resolveStaticFailurePolicy();
        List<String> targetKeys = resolveBatchKeys(explicitKeys);
        return executeGenericList(targetKeys, effectivePolicy, 1, fallbackCode, action);
    }

    private <T> BatchOperationResult<T> executeGenericTenants(BatchExecutionPolicy policy,
                                                               FlywayResultCode fallbackCode,
                                                               Function<String, T> action) {
        BatchExecutionPolicy effectivePolicy = policy != null ? policy : resolveTenantFailurePolicy();
        List<String> tenantKeys = resolveTenantTargetKeys();
        return executeGenericList(tenantKeys, effectivePolicy, resolveTenantConcurrency(), fallbackCode, action);
    }

    private <T> BatchOperationResult<T> executeGenericList(List<String> targetKeys,
                                                            BatchExecutionPolicy effectivePolicy,
                                                            int concurrency,
                                                            FlywayResultCode fallbackCode,
                                                            Function<String, T> action) {
        if (CollectionUtils.isEmpty(targetKeys)) {
            return FlywayBatchExecutor.emptyGenericResult(effectivePolicy);
        }
        List<String> normalizedKeys = targetKeys.stream().map(this::requireTargetKey).toList();
        return FlywayBatchExecutor.executeGenericBatch(
                normalizedKeys,
                effectivePolicy,
                concurrency,
                resolveBatchTimeout(),
                resolveTaskExecutor(),
                fallbackCode,
                action,
                this::sanitize
        );
    }

    private BatchMigrationResult executeBatchTargets(Collection<String> explicitKeys,
                                                     FlywayExecutionMode mode,
                                                     BatchExecutionPolicy policy) {
        BatchExecutionPolicy effectivePolicy = policy != null ? policy : resolveStaticFailurePolicy();
        List<String> targetKeys = resolveBatchKeys(explicitKeys);
        return executeBatchList(targetKeys, mode, effectivePolicy, 1, true);
    }

    private BatchMigrationResult executeBatchList(List<String> targetKeys,
                                                  FlywayExecutionMode mode,
                                                  BatchExecutionPolicy effectivePolicy,
                                                  int concurrency,
                                                  boolean recordSummary) {
        if (CollectionUtils.isEmpty(targetKeys)) {
            BatchMigrationResult empty = FlywayBatchExecutor.emptyResult(mode, effectivePolicy);
            if (recordSummary) {
                commitSummary(FlywayBatchExecutor.toSummary(empty));
            }
            return empty;
        }
        List<String> normalizedKeys = targetKeys.stream().map(this::requireTargetKey).toList();
        if (concurrency <= 1 || normalizedKeys.size() <= 1) {
            return FlywayBatchExecutor.executeSerially(
                    normalizedKeys, mode, effectivePolicy, this::executeBatchItem,
                    this::sanitize, this::commitSummary, recordSummary);
        }
        return FlywayBatchExecutor.executeConcurrently(
                normalizedKeys, mode, effectivePolicy, concurrency, resolveBatchTimeout(),
                resolveTaskExecutor(), this::executeBatchItem, this::sanitize,
                this::commitSummary, recordSummary);
    }

    private MigrationOutcome executeBatchItem(String key, FlywayExecutionMode mode) {
        Instant itemStart = Instant.now();
        if (mode == FlywayExecutionMode.VALIDATE_ONLY) {
            MigrationValidationResult val = executeSingleValidation(key, false);
            Instant finishedAt = Instant.now();
            return MigrationOutcome.builder()
                    .targetKey(key)
                    .mode(FlywayExecutionMode.VALIDATE_ONLY)
                    .success(val.isValid())
                    .skipped(false)
                    .migrationsExecuted(0)
                    .duration(Duration.between(itemStart, finishedAt))
                    .executedAt(finishedAt)
                    .warnings(val.getWarnings())
                    .build();
        }
        return executeSingleMigration(key, false);
    }

    private MigrationOutcome executeSingleMigration(String resolvedKey, boolean recordAsSingleSummary) {
        Instant start = Instant.now();
        notifier.notifyBeforeMigrate(resolvedKey);
        try {
            MigrationOutcome outcome = executeWithFlyway(resolvedKey, true, flyway -> {
                safetyGuard.assertPendingSqlSafe(resolvedKey, flyway);
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

            if (recordAsSingleSummary) {
                recordSingleSummary(FlywayExecutionMode.MIGRATE, outcome, outcome.getExecutedAt(), outcome.getDuration());
            }
            notifier.notifySucceeded(outcome);
            return outcome;
        } catch (FlywayException e) {
            reportSingleFailure(resolvedKey, FlywayExecutionMode.MIGRATE, start, sanitize(e.getMessage()), e, recordAsSingleSummary);
            throw e;
        } catch (Exception e) {
            String safeMessage = sanitize(e.getMessage());
            FlywayException translated = new FlywayException(FlywayResultCode.MIGRATION_FAILED,
                    "目标数据源 [" + resolvedKey + "] 迁移执行失败: " + safeMessage, e);
            reportSingleFailure(resolvedKey, FlywayExecutionMode.MIGRATE, start, safeMessage, translated, recordAsSingleSummary);
            throw translated;
        }
    }

    private MigrationValidationResult executeSingleValidation(String resolvedKey, boolean recordSummary) {
        Instant start = Instant.now();
        boolean[] failureAlreadyReported = {false};
        try {
            return executeWithFlyway(resolvedKey, true, flyway -> {
                ValidateResult result = flyway.validateWithResult();
                if (!result.validationSuccessful) {
                    String details = sanitize(result.getAllErrorMessages());
                    FlywayException ex = new FlywayException(FlywayResultCode.VALIDATION_FAILED,
                            "目标数据源 [" + resolvedKey + "] 迁移校验失败: " + details);
                    reportSingleFailure(resolvedKey, FlywayExecutionMode.VALIDATE_ONLY, start, details, ex, recordSummary);
                    failureAlreadyReported[0] = true;
                    throw ex;
                }
                if (recordSummary) {
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
                reportSingleFailure(resolvedKey, FlywayExecutionMode.VALIDATE_ONLY, start, sanitize(e.getMessage()), e, recordSummary);
            }
            throw e;
        } catch (Exception e) {
            String safeMessage = sanitize(e.getMessage());
            FlywayException translated = new FlywayException(FlywayResultCode.VALIDATION_FAILED,
                    "目标数据源 [" + resolvedKey + "] 迁移校验异常: " + safeMessage, e);
            reportSingleFailure(resolvedKey, FlywayExecutionMode.VALIDATE_ONLY, start, safeMessage, translated, recordSummary);
            throw translated;
        }
    }

    private String resolvePrimaryTargetKey() {
        if (targetResolver != null) {
            for (MigrationTarget target : targetResolver.resolveTargets()) {
                if (target != null && target.getKind() == MigrationTargetKind.PRIMARY
                        && StringUtils.hasText(target.getTargetKey())) {
                    return target.getTargetKey().trim();
                }
            }
        }
        return StringUtils.hasText(properties.getPrimaryTargetKey())
                ? properties.getPrimaryTargetKey().trim()
                : "default";
    }

    private List<String> resolveOrderedTargetKeys() {
        if (targetResolver == null) {
            return List.of(resolvePrimaryTargetKey());
        }
        return filterTargetKeys(target -> true, Collections.emptyList());
    }

    private List<String> resolveStaticTargetKeys() {
        if (targetResolver == null) {
            return List.of(resolvePrimaryTargetKey());
        }
        return filterTargetKeys(target -> target.getKind() != MigrationTargetKind.TENANT,
                Collections.emptyList());
    }

    private List<String> resolveTenantTargetKeys() {
        if (targetResolver == null) {
            return Collections.emptyList();
        }
        return extractTargetKeys(targetResolver.resolveTenantTargets(), target -> true);
    }

    private List<String> filterTargetKeys(Predicate<MigrationTarget> filter, List<String> emptyFallback) {
        List<MigrationTarget> targets = targetResolver.resolveTargets();
        if (CollectionUtils.isEmpty(targets)) {
            return emptyFallback;
        }
        return extractTargetKeys(targets, filter);
    }

    private List<String> extractTargetKeys(List<MigrationTarget> targets, Predicate<MigrationTarget> filter) {
        if (CollectionUtils.isEmpty(targets)) {
            return Collections.emptyList();
        }
        List<String> keys = new ArrayList<>(targets.size());
        for (MigrationTarget target : targets) {
            if (target != null && filter.test(target) && StringUtils.hasText(target.getTargetKey())) {
                keys.add(target.getTargetKey().trim());
            }
        }
        return Collections.unmodifiableList(keys);
    }

    private List<String> resolveBatchKeys(Collection<String> explicitKeys) {
        if (explicitKeys == null) {
            return resolveOrderedTargetKeys();
        }
        Set<String> deduplicated = new LinkedHashSet<>();
        for (String key : explicitKeys) {
            deduplicated.add(requireTargetKey(key));
        }
        return new ArrayList<>(deduplicated);
    }

    private BatchExecutionPolicy resolveStaticFailurePolicy() {
        return properties.getStaticFailurePolicy() != null
                ? properties.getStaticFailurePolicy()
                : BatchExecutionPolicy.FAIL_FAST;
    }

    private BatchExecutionPolicy resolveTenantFailurePolicy() {
        AtengFlywayProperties.TenantMigrationProperties tenantProps = properties.getTenant();
        return tenantProps != null && tenantProps.getFailurePolicy() != null
                ? tenantProps.getFailurePolicy()
                : BatchExecutionPolicy.FAIL_SAFE;
    }

    private int resolveTenantConcurrency() {
        AtengFlywayProperties.TenantMigrationProperties tenantProps = properties.getTenant();
        if (tenantProps == null || tenantProps.getConcurrency() <= 1) {
            return 1;
        }
        return tenantProps.getConcurrency();
    }

    private Duration resolveBatchTimeout() {
        AtengFlywayProperties.TenantMigrationProperties tenantProps = properties.getTenant();
        Duration configured = tenantProps != null ? tenantProps.getBatchTimeout() : null;
        if (configured != null && !configured.isNegative() && !configured.isZero()) {
            return configured;
        }
        return Duration.ofMinutes(10);
    }

    private Executor resolveTaskExecutor() {
        if (taskExecutor != null) {
            return taskExecutor;
        }
        return AsyncUtil.getExecutor();
    }

    private String requireTargetKey(String targetKey) {
        Assert.hasText(targetKey, "目标数据源标识 (targetKey) 不能为空");
        return targetKey.trim();
    }

    private <T> T executeOperation(String resolvedKey,
                                   boolean acquireLock,
                                   FlywayResultCode failureCode,
                                   String failurePrefix,
                                   Function<Flyway, T> action) {
        try {
            return executeWithFlyway(resolvedKey, acquireLock, action);
        } catch (FlywayException e) {
            throw e;
        } catch (Exception e) {
            throw new FlywayException(failureCode, failurePrefix + ": " + sanitize(e.getMessage()), e);
        }
    }

    private <T> T executeWithFlyway(String resolvedKey, boolean acquireLock, Function<Flyway, T> action) {
        Supplier<T> invocation = () -> invokeResolvedTarget(resolvedKey, action);
        if (!acquireLock) {
            return invocation.get();
        }
        Duration lockTimeout = properties.getLockWaitTimeout() != null
                ? properties.getLockWaitTimeout()
                : Duration.ofSeconds(30);
        return lockProvider.executeWithLock(resolvedKey, lockTimeout, invocation);
    }

    private <T> T invokeResolvedTarget(String resolvedKey, Function<Flyway, T> action) {
        MigrationTarget resolvedTarget = targetResolver != null ? targetResolver.resolveTarget(resolvedKey) : null;
        if (resolvedTarget != null) {
            boolean hasIsolatedCred = resolvedTarget.getCredential() != null
                    && resolvedTarget.getCredential().isConfigured();
            if (hasIsolatedCred) {
                TransientFlywayExecutor runner = primaryTransientExecutor != null
                        ? primaryTransientExecutor
                        : targetBuilderSupport;
                if (runner == null) {
                    throw new FlywayException(FlywayResultCode.CONFIG_INVALID,
                            "目标 [" + resolvedKey + "] 配置了隔离 DDL 凭据但未装配瞬态执行器");
                }
                return runner.execute(resolvedTarget, action);
            }
            if (resolvedTarget.getKind() == MigrationTargetKind.PRIMARY
                    && primaryFlyway != null
                    && resolvedTarget.getMigrationConfig() == null
                    && !hasContextPlaceholderOverride()) {
                return action.apply(primaryFlyway);
            }
            if (targetBuilderSupport != null) {
                Flyway targetFlyway = targetBuilderSupport.buildFlyway(resolvedTarget);
                return action.apply(targetFlyway);
            }
            if (resolvedTarget.getKind() == MigrationTargetKind.PRIMARY && primaryFlyway != null) {
                return action.apply(primaryFlyway);
            }
        }

        String primaryKey = resolvePrimaryTargetKey();
        if (Objects.equals(primaryKey, resolvedKey)
                && (targetResolver == null || targetResolver.resolveTargets().isEmpty())) {
            if (primaryTransientExecutor != null) {
                return primaryTransientExecutor.execute(resolvedKey, action);
            }
            if (primaryFlyway != null) {
                return action.apply(primaryFlyway);
            }
        }
        throw new FlywayException(FlywayResultCode.TARGET_NOT_FOUND,
                "未找到指定迁移目标数据源: [" + resolvedKey + "]");
    }

    private boolean hasContextPlaceholderOverride() {
        return StringUtils.hasText(io.github.atengk.boot.core.context.TenantContextHolder.getTenantId());
    }

    private int safeAppliedCount(Flyway flyway) {
        MigrationInfo[] applied = flyway.info().applied();
        return applied != null ? applied.length : 0;
    }

    private boolean shouldRecordValidationSummary() {
        return properties.getMode() == FlywayExecutionMode.VALIDATE_ONLY || lastMigrationSummary.get() == null;
    }

    private String sanitize(String rawMessage) {
        return FlywayConfigurationSupport.sanitizeSensitiveText(rawMessage);
    }

    private void commitSummary(MigrationSummary summary) {
        lastMigrationSummary.set(summary);
        summaryVersion.incrementAndGet();
    }

    private void updateSummary(UnaryOperator<MigrationSummary> updater) {
        lastMigrationSummary.updateAndGet(updater);
        summaryVersion.incrementAndGet();
    }

    private void recordGovernanceSummaryOutcome(String resolvedKey,
                                                MigrationInfoService infoService,
                                                String fallbackVersion,
                                                String database,
                                                Instant start,
                                                int remainingFailed,
                                                String errorMessage,
                                                List<String> warnings) {
        Instant finishedAt = Instant.now();
        MigrationInfo current = infoService != null ? infoService.current() : null;
        String resolvedVersion = current != null && current.getVersion() != null
                ? current.getVersion().getVersion()
                : fallbackVersion;
        MigrationOutcome outcome = MigrationOutcome.builder()
                .targetKey(resolvedKey)
                .mode(FlywayExecutionMode.MIGRATE)
                .success(remainingFailed == 0)
                .skipped(false)
                .migrationsExecuted(0)
                .targetSchemaVersion(resolvedVersion)
                .database(database)
                .duration(Duration.between(start, finishedAt))
                .executedAt(finishedAt)
                .errorMessage(errorMessage)
                .warnings(warnings)
                .build();
        recordSingleSummary(FlywayExecutionMode.MIGRATE, outcome, finishedAt, outcome.getDuration());
    }

    private int countFailedMigrations(MigrationInfo[] infos) {
        if (infos == null || infos.length == 0) {
            return 0;
        }
        int failed = 0;
        for (MigrationInfo info : infos) {
            if (info != null && info.getState() != null && info.getState().isFailed()) {
                failed++;
            }
        }
        return failed;
    }

    private void recordSingleSummary(FlywayExecutionMode mode,
                                     MigrationOutcome outcome,
                                     Instant finishedAt,
                                     Duration elapsed) {
        updateSummary(existing -> {
            if (existing != null && !CollectionUtils.isEmpty(existing.getOutcomes())) {
                return FlywayBatchExecutor.upsertSummaryOutcome(existing, mode, outcome);
            }
            return MigrationSummary.builder()
                    .mode(mode)
                    .executedAt(finishedAt)
                    .totalDuration(elapsed)
                    .totalTargets(1)
                    .successCount(outcome.isSuccess() ? 1 : 0)
                    .failedCount(outcome.isSuccess() ? 0 : 1)
                    .skippedCount(outcome.isSkipped() ? 1 : 0)
                    .outcomes(List.of(outcome))
                    .build();
        });
    }

    private MigrationOutcome buildFailureOutcome(String targetKey,
                                                 FlywayExecutionMode mode,
                                                 Instant start,
                                                 String errorMessage) {
        Instant finishedAt = Instant.now();
        return MigrationOutcome.builder()
                .targetKey(targetKey)
                .mode(mode)
                .success(false)
                .skipped(false)
                .migrationsExecuted(0)
                .duration(Duration.between(start, finishedAt))
                .executedAt(finishedAt)
                .errorMessage(errorMessage)
                .warnings(Collections.emptyList())
                .build();
    }

    private MigrationOutcome recordSingleFailureSummary(String targetKey,
                                                        FlywayExecutionMode mode,
                                                        Instant start,
                                                        String errorMessage) {
        MigrationOutcome outcome = buildFailureOutcome(targetKey, mode, start, errorMessage);
        recordSingleSummary(mode, outcome, outcome.getExecutedAt(), outcome.getDuration());
        return outcome;
    }

    private void reportSingleFailure(String resolvedKey,
                                     FlywayExecutionMode mode,
                                     Instant start,
                                     String safeMessage,
                                     Throwable cause,
                                     boolean recordSummary) {
        MigrationOutcome failureOutcome = recordSummary
                ? recordSingleFailureSummary(resolvedKey, mode, start, safeMessage)
                : buildFailureOutcome(resolvedKey, mode, start, safeMessage);
        notifier.notifyFailed(resolvedKey, mode, safeMessage, failureOutcome, cause);
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
