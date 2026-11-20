package io.github.atengk.boot.job.snail.template;

import com.aizuda.snailjob.common.log.SnailJobLog;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.snail.support.SnailJobInternalSupport;
import io.github.atengk.boot.job.snail.template.SnailShardingBatchTemplate.ParentContextSnapshot;
import io.github.atengk.boot.job.snail.tenant.TenantListProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * SnailJob 多租户隔离遍历执行模板
 *
 * <p>为分布式调度任务提供安全、沙箱化的租户级顺序与并发执行能力（对应 {@link io.github.atengk.boot.job.snail.enums.JobTenantMode#ITERATE} 模式）。
 * 支持通过 {@link #executeForEachTenantWithResult} 获取结构化 {@link BatchExecutionResult}，
 * 以及通过 {@link #executeForEachTenantParallel} 基于 {@link Semaphore} 背压与 Core 受管线程池并发遍历多租户，
 * 自动透传四维与 {@code SnailJobContext} 全链路上下文并严格还原现场，支持 {@link BatchFailureHandler} 与 {@code REQUIRES_NEW} 事务沙箱。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class TenantJobTemplate {

    private static final Logger log = LoggerFactory.getLogger(TenantJobTemplate.class);

    public static final int DEFAULT_MAX_CONCURRENCY = SnailShardingBatchTemplate.DEFAULT_MAX_CONCURRENCY;

    private final TenantListProvider tenantListProvider;
    private final ObjectProvider<PlatformTransactionManager> transactionManagerProvider;
    private final Executor taskExecutor;
    private final int defaultMaxConcurrency;
    private final boolean managedPoolEnabled;
    private final int maxFailedItems;
    private final BatchFailureHandler<?> defaultFailureHandler;

    public TenantJobTemplate() {
        this(null, null, null, DEFAULT_MAX_CONCURRENCY, true, BatchExecutionResult.MAX_FAILED_ITEMS, null);
    }

    public TenantJobTemplate(TenantListProvider tenantListProvider) {
        this(tenantListProvider, null, null, DEFAULT_MAX_CONCURRENCY, true, BatchExecutionResult.MAX_FAILED_ITEMS, null);
    }

    public TenantJobTemplate(
            TenantListProvider tenantListProvider,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider) {
        this(tenantListProvider, transactionManagerProvider, null, DEFAULT_MAX_CONCURRENCY, true,
                BatchExecutionResult.MAX_FAILED_ITEMS, null);
    }

    public TenantJobTemplate(
            TenantListProvider tenantListProvider,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
            Executor taskExecutor,
            int defaultMaxConcurrency,
            boolean managedPoolEnabled,
            int maxFailedItems,
            BatchFailureHandler<?> defaultFailureHandler) {
        this.tenantListProvider = tenantListProvider;
        this.transactionManagerProvider = transactionManagerProvider;
        this.taskExecutor = taskExecutor;
        this.defaultMaxConcurrency = defaultMaxConcurrency > 0 ? defaultMaxConcurrency : DEFAULT_MAX_CONCURRENCY;
        this.managedPoolEnabled = managedPoolEnabled;
        this.maxFailedItems = maxFailedItems > 0 ? maxFailedItems : BatchExecutionResult.MAX_FAILED_ITEMS;
        this.defaultFailureHandler = defaultFailureHandler;
    }

    public int getDefaultMaxConcurrency() {
        return defaultMaxConcurrency;
    }

    public Executor getEffectiveExecutor() {
        Executor resolved = SnailJobInternalSupport.resolveManagedExecutor(this.taskExecutor, this.managedPoolEnabled);
        return resolved != null ? resolved : Runnable::run;
    }

    // ==================== 1. 顺序单线程租户遍历 ====================

    public BatchExecutionResult<String> executeForEachTenant(Consumer<String> action) {
        return executeForEachTenantWithResult(resolveTenantIds(), false, action, null);
    }

    public BatchExecutionResult<String> executeForEachTenant(boolean failFast, Consumer<String> action) {
        return executeForEachTenantWithResult(resolveTenantIds(), failFast, action, null);
    }

    public BatchExecutionResult<String> executeForEachTenant(Collection<String> tenantIds, Consumer<String> action) {
        return executeForEachTenantWithResult(tenantIds, false, action, null);
    }

    public BatchExecutionResult<String> executeForEachTenant(Collection<String> tenantIds, boolean failFast, Consumer<String> action) {
        return executeForEachTenantWithResult(tenantIds, failFast, action, null);
    }

    public BatchExecutionResult<String> executeForEachTenantWithResult(Consumer<String> action) {
        return executeForEachTenantWithResult(resolveTenantIds(), false, action, null);
    }

    public BatchExecutionResult<String> executeForEachTenantWithResult(boolean failFast, Consumer<String> action) {
        return executeForEachTenantWithResult(resolveTenantIds(), failFast, action, null);
    }

    public BatchExecutionResult<String> executeForEachTenantWithResult(Collection<String> tenantIds, Consumer<String> action) {
        return executeForEachTenantWithResult(tenantIds, false, action, null);
    }

    public BatchExecutionResult<String> executeForEachTenantWithResult(Collection<String> tenantIds, boolean failFast, Consumer<String> action) {
        return executeForEachTenantWithResult(tenantIds, failFast, action, null);
    }

    /**
     * 指定租户集合执行顺序遍历并返回结构化结果（支持显式 {@link BatchFailureHandler} 回调覆盖）
     */
    public BatchExecutionResult<String> executeForEachTenantWithResult(
            Collection<String> tenantIds,
            boolean failFast,
            Consumer<String> action,
            BatchFailureHandler<String> failureHandler) {
        Objects.requireNonNull(action, "action 不能为 null");
        List<String> validTenantIds = filterValidTenantIds(tenantIds);
        if (validTenantIds.isEmpty()) {
            log.info("[TenantJobTemplate] 无有效租户标识，跳过租户遍历执行");
            return new BatchExecutionResult<>(0, 0, 0, 0, Duration.ZERO);
        }

        long startTime = System.nanoTime();
        long total = validTenantIds.size();
        long successCount = 0;
        long failCount = 0;
        List<FailedBatch<String>> failedBatches = new ArrayList<>();
        ReentrantLock failedLock = new ReentrantLock();
        AtomicBoolean truncated = new AtomicBoolean(false);

        log.info("[TenantJobTemplate] 开始执行多租户遍历任务，共 {} 个租户，failFast={}", total, failFast);
        logSnailLocal("[TENANT-JOB] 开始执行多租户遍历任务 (租户数: {}, failFast: {})", total, failFast);

        int currentIndex = 0;
        for (String tenantId : validTenantIds) {
            currentIndex++;
            log.info("[TenantJobTemplate] [{}/{}] 正在执行租户 [{}] 的调度任务...", currentIndex, total, tenantId);
            try {
                runInTenantSandbox(tenantId, action);
                successCount++;
            } catch (Throwable t) {
                failCount++;
                FailedBatch<String> failed = new FailedBatch<>(tenantId, tenantId, null, currentIndex, 1, t);
                SnailShardingBatchTemplate.recordFailedBatch(failedBatches, failedLock, truncated, this.maxFailedItems, failed);
                notifyFailureHandler(failed, failureHandler);

                if (failFast) {
                    throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                            "多租户遍历任务在租户 [" + tenantId + "] 快速失败中断: " + t.getMessage(), t);
                }

                log.error("[TenantJobTemplate] 租户 [{}] 调度任务执行失败: {}", tenantId, t.getMessage(), t);
                logSnailLocalError("[TENANT-JOB] 租户 [{}] 执行失败: {}", tenantId, t.getMessage(), t);
            }
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
        logSummary("多租户遍历任务", total, successCount, failCount, duration, failedBatches);
        return new BatchExecutionResult<>(
                total, successCount, failCount, (int) total, duration, failedBatches, this.maxFailedItems, truncated.get());
    }

    // ==================== 2. 并发多线程租户遍历 (Semaphore 背压与全链路上下文透传) ====================

    public BatchExecutionResult<String> executeForEachTenantParallel(Consumer<String> action) {
        return executeForEachTenantParallel(resolveTenantIds(), this.defaultMaxConcurrency, false, action, null);
    }

    public BatchExecutionResult<String> executeForEachTenantParallel(int maxConcurrency, Consumer<String> action) {
        return executeForEachTenantParallel(resolveTenantIds(), maxConcurrency, false, action, null);
    }

    public BatchExecutionResult<String> executeForEachTenantParallel(int maxConcurrency, boolean failFast, Consumer<String> action) {
        return executeForEachTenantParallel(resolveTenantIds(), maxConcurrency, failFast, action, null);
    }

    public BatchExecutionResult<String> executeForEachTenantParallel(Collection<String> tenantIds, Consumer<String> action) {
        return executeForEachTenantParallel(tenantIds, this.defaultMaxConcurrency, false, action, null);
    }

    public BatchExecutionResult<String> executeForEachTenantParallel(Collection<String> tenantIds, int maxConcurrency, Consumer<String> action) {
        return executeForEachTenantParallel(tenantIds, maxConcurrency, false, action, null);
    }

    public BatchExecutionResult<String> executeForEachTenantParallel(
            Collection<String> tenantIds,
            int maxConcurrency,
            boolean failFast,
            Consumer<String> action) {
        return executeForEachTenantParallel(tenantIds, maxConcurrency, failFast, action, null);
    }

    /**
     * 指定租户集合执行受管并发沙箱化遍历处理（支持 {@link Semaphore} 背压、四维/SnailJobContext 透传与失败回调 SPI）
     */
    public BatchExecutionResult<String> executeForEachTenantParallel(
            Collection<String> tenantIds,
            int maxConcurrency,
            boolean failFast,
            Consumer<String> action,
            BatchFailureHandler<String> failureHandler) {
        Objects.requireNonNull(action, "action 不能为 null");
        List<String> validTenantIds = filterValidTenantIds(tenantIds);
        if (validTenantIds.isEmpty()) {
            log.info("[TenantJobTemplate] 无有效租户标识，跳过租户并发执行");
            return new BatchExecutionResult<>(0, 0, 0, 0, Duration.ZERO);
        }

        int effectiveConcurrency = maxConcurrency > 0 ? maxConcurrency : this.defaultMaxConcurrency;
        Executor executor = getEffectiveExecutor();
        long startTime = System.nanoTime();
        long total = validTenantIds.size();

        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);
        List<FailedBatch<String>> failedBatches = new ArrayList<>();
        ReentrantLock failedLock = new ReentrantLock();
        AtomicBoolean truncated = new AtomicBoolean(false);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>(validTenantIds.size());
        Semaphore semaphore = new Semaphore(effectiveConcurrency);
        ParentContextSnapshot parentContext = ParentContextSnapshot.capture();

        log.info("[TenantJobTemplate] 开始并发执行多租户任务，共 {} 个租户，并发度: {}, failFast={}",
                total, effectiveConcurrency, failFast);
        logSnailLocal("[TENANT-JOB] 开始并发执行多租户任务 (租户数: {}, 并发度: {}, failFast: {})",
                total, effectiveConcurrency, failFast);

        int index = 0;
        for (String tenantId : validTenantIds) {
            index++;
            final int currentIndex = index;

            if (failFast && firstError.get() != null) {
                log.warn("[TenantJobTemplate] 检测到租户执行失败，快速失败熔断后续调度 [当前租户: {}]", tenantId);
                break;
            }

            try {
                if (!semaphore.tryAcquire(5, TimeUnit.MINUTES)) {
                    throw new IllegalStateException("并发多租户执行获取背压信号量超时 (时限: 5分钟)");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("并发多租户执行在等待背压信号量时被中断", ie);
            }

            CompletableFuture<Void> future;
            try {
                future = CompletableFuture.runAsync(() -> parentContext.runWithContext(() -> {
                    try {
                        log.info("[TenantJobTemplate] [{}/{}] 正在并发执行租户 [{}] 的调度任务...", currentIndex, total, tenantId);
                        runInTenantSandbox(tenantId, action);
                        successCount.incrementAndGet();
                    } catch (Throwable t) {
                        failCount.incrementAndGet();
                        FailedBatch<String> failed = new FailedBatch<>(tenantId, tenantId, null, currentIndex, 1, t);
                        SnailShardingBatchTemplate.recordFailedBatch(
                                failedBatches, failedLock, truncated, this.maxFailedItems, failed);
                        notifyFailureHandler(failed, failureHandler);
                        firstError.compareAndSet(null, t);

                        if (failFast) {
                            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                                    "多租户并行任务在租户 [" + tenantId + "] 快速失败中断: " + t.getMessage(), t);
                        }
                        log.error("[TenantJobTemplate] 租户 [{}] 并发调度任务执行失败: {}", tenantId, t.getMessage(), t);
                        logSnailLocalError("[TENANT-JOB] 租户 [{}] 并发执行失败: {}", tenantId, t.getMessage(), t);
                    } finally {
                        semaphore.release();
                    }
                }), executor);
            } catch (Throwable submitError) {
                semaphore.release();
                throw submitError;
            }
            futures.add(future);
        }

        SnailShardingBatchTemplate.awaitAllFutures(futures, firstError);

        if (failFast && firstError.get() != null) {
            Throwable t = firstError.get();
            if (t instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "多租户并发遍历执行失败: " + t.getMessage(), t);
        }

        List<FailedBatch<String>> snapshot = SnailShardingBatchTemplate.snapshotFailedBatches(failedBatches, failedLock);
        Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
        logSummary("多租户并发遍历任务", total, successCount.get(), failCount.get(), duration, snapshot);
        return new BatchExecutionResult<>(
                total,
                successCount.get(),
                failCount.get(),
                (int) total,
                duration,
                snapshot,
                this.maxFailedItems,
                truncated.get());
    }

    /**
     * 在隔离租户沙箱中执行任务，若存在受管事务管理器，则自动包装为独立的 PROPAGATION_REQUIRES_NEW 事务沙箱
     */
    protected void runInTenantSandbox(String tenantId, Consumer<String> action) {
        TenantContextHolder.runWithTenant(tenantId, () -> {
            PlatformTransactionManager txManager = (transactionManagerProvider != null)
                    ? transactionManagerProvider.getIfAvailable() : null;
            if (txManager != null) {
                TransactionTemplate txTemplate = new TransactionTemplate(txManager);
                txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                txTemplate.executeWithoutResult(status -> action.accept(tenantId));
            } else {
                action.accept(tenantId);
            }
        });
    }

    protected List<String> resolveTenantIds() {
        if (tenantListProvider == null) {
            return Collections.emptyList();
        }
        List<String> list = tenantListProvider.getTenantIds();
        return list != null ? list : Collections.emptyList();
    }

    private List<String> filterValidTenantIds(Collection<String> tenantIds) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> valid = new ArrayList<>(tenantIds.size());
        for (String tenantId : tenantIds) {
            if (tenantId != null && !tenantId.isBlank()) {
                valid.add(tenantId);
            }
        }
        return valid;
    }

    @SuppressWarnings("unchecked")
    private void notifyFailureHandler(FailedBatch<String> failed, BatchFailureHandler<String> explicitHandler) {
        if (failed == null) {
            return;
        }
        BatchFailureHandler<String> target = explicitHandler != null
                ? explicitHandler
                : (BatchFailureHandler<String>) this.defaultFailureHandler;
        if (target == null) {
            return;
        }
        try {
            target.onFailure(failed);
        } catch (Throwable ex) {
            log.warn("[TenantJobTemplate] BatchFailureHandler 回调执行异常，已隔离 [Tenant: {}]: {}",
                    failed.cursorStart(), ex.getMessage(), ex);
        }
    }

    private void logSummary(
            String label,
            long total,
            long successCount,
            long failCount,
            Duration duration,
            List<FailedBatch<String>> failedBatches) {
        if (failCount > 0) {
            List<String> failedTenantIds = failedBatches.stream().map(FailedBatch::cursorStart).toList();
            log.warn("[TenantJobTemplate] {}执行完毕，总数: {}, 成功: {}, 失败: {}, 耗时: {}ms, 失败租户明细: {}",
                    label, total, successCount, failCount, duration.toMillis(), failedTenantIds);
            logSnailLocalError("[TENANT-JOB] {}完成 (总数: {}, 成功: {}, 失败: {}, 耗时: {}ms, 失败租户: {})",
                    label, total, successCount, failCount, duration.toMillis(), failedTenantIds);
        } else {
            log.info("[TenantJobTemplate] {}执行完毕，总数: {}, 成功: {}, 失败: {}, 耗时: {}ms",
                    label, total, successCount, failCount, duration.toMillis());
            logSnailLocal("[TENANT-JOB] {}完成 (总数: {}, 成功: {}, 失败: {}, 耗时: {}ms)",
                    label, total, successCount, failCount, duration.toMillis());
        }
    }

    private static void logSnailLocal(String format, Object... args) {
        try {
            SnailJobLog.LOCAL.info(format, args);
        } catch (Throwable ignored) {
        }
    }

    private static void logSnailLocalError(String format, Object... args) {
        try {
            SnailJobLog.LOCAL.error(format, args);
        } catch (Throwable ignored) {
        }
    }
}
