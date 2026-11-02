package io.github.atengk.boot.job.xxl.template;

import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.context.XxlJobHelper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder.TenantSnapshot;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.SpringUtil;
import io.github.atengk.boot.job.xxl.tenant.TenantListProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * XXL-Job 多租户隔离遍历执行模板
 *
 * <p>为分布式调度任务提供安全、沙箱化的租户级批量执行能力（对应 {@link io.github.atengk.boot.job.xxl.enums.JobTenantMode#ITERATE} 模式）。
 * 自动在每个有效租户上下文中封装调用 {@link TenantContextHolder#runWithTenant(String, Runnable)}，
 * 确保各租户数据操作完全隔离，并在异常或完成时确定性恢复原始现场，彻底杜绝租户数据串写与越权。</p>
 *
 * <p>同时支持单线程顺序遍历与受管多线程并发遍历（带基于信号量的背压流量保护），
 * 并自动将父线程的四维上下文（MDC traceId、操作人、多租户）与 XXL-Job 日志镜像句柄透传至并发子任务，
 * 并在子线程执行终态严格清理现场，杜绝线程复用污染与上下文泄漏。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class TenantJobTemplate {

    private static final Logger log = LoggerFactory.getLogger(TenantJobTemplate.class);

    public static final int DEFAULT_MAX_CONCURRENCY = 8;

    private final TenantListProvider tenantListProvider;
    private final Executor taskExecutor;
    private final int defaultMaxConcurrency;

    public TenantJobTemplate(TenantListProvider tenantListProvider) {
        this(tenantListProvider, null, DEFAULT_MAX_CONCURRENCY);
    }

    public TenantJobTemplate(TenantListProvider tenantListProvider, Executor taskExecutor) {
        this(tenantListProvider, taskExecutor, DEFAULT_MAX_CONCURRENCY);
    }

    public TenantJobTemplate(TenantListProvider tenantListProvider, Executor taskExecutor, int defaultMaxConcurrency) {
        this.tenantListProvider = tenantListProvider;
        this.taskExecutor = taskExecutor;
        this.defaultMaxConcurrency = defaultMaxConcurrency > 0 ? defaultMaxConcurrency : DEFAULT_MAX_CONCURRENCY;
    }

    public TenantListProvider getTenantListProvider() {
        return tenantListProvider;
    }

    public Executor getTaskExecutor() {
        return taskExecutor;
    }

    public int getDefaultMaxConcurrency() {
        return defaultMaxConcurrency;
    }

    // ==================== 1. 顺序单线程租户遍历 ====================

    /**
     * 从注册的 {@link TenantListProvider} 获取有效租户列表并执行租户遍历处理（容错继续模式）
     *
     * @param action 租户执行回调动作
     * @return 批处理执行统计结果
     */
    public BatchExecutionResult executeForEachTenant(Consumer<String> action) {
        return executeForEachTenant(resolveTenantIds(), false, action);
    }

    /**
     * 从注册的 {@link TenantListProvider} 获取有效租户列表并执行租户遍历处理
     *
     * @param failFast 是否快速失败
     * @param action   租户执行回调动作
     * @return 批处理执行统计结果
     */
    public BatchExecutionResult executeForEachTenant(boolean failFast, Consumer<String> action) {
        return executeForEachTenant(resolveTenantIds(), failFast, action);
    }

    /**
     * 指定租户集合执行遍历处理（容错继续模式）
     *
     * @param tenantIds 租户标识集合
     * @param action    租户执行回调动作
     * @return 批处理执行统计结果
     */
    public BatchExecutionResult executeForEachTenant(Collection<String> tenantIds, Consumer<String> action) {
        return executeForEachTenant(tenantIds, false, action);
    }

    /**
     * 指定租户集合执行遍历处理
     *
     * @param tenantIds 租户标识集合
     * @param failFast  是否快速失败（true 表示任一租户报错即中断，false 表示记录异常并继续处理其他租户）
     * @param action    租户执行回调动作
     * @return 批处理执行统计结果
     */
    public BatchExecutionResult executeForEachTenant(Collection<String> tenantIds, boolean failFast, Consumer<String> action) {
        Objects.requireNonNull(action, "action 不能为空");
        if (tenantIds == null || tenantIds.isEmpty()) {
            log.info("[TenantJobTemplate] 无有效租户标识，跳过租户遍历执行");
            return new BatchExecutionResult(0, 0, 0, 0, Duration.ZERO);
        }

        long startTime = System.currentTimeMillis();
        long total = tenantIds.size();
        long successCount = 0;
        long failCount = 0;
        List<String> failedTenants = new ArrayList<>();
        Throwable firstError = null;

        log.info("[TenantJobTemplate] 开始执行多租户遍历任务，共 {} 个租户，failFast={}", total, failFast);
        try {
            XxlJobHelper.log("<br>----------- [TENANT-JOB] 开始执行多租户遍历任务 (租户数: " + total + ", failFast: " + failFast + ") -----------");
        } catch (Throwable ignored) {
        }

        int currentIndex = 0;
        for (String tenantId : tenantIds) {
            currentIndex++;
            if (tenantId == null || tenantId.isBlank()) {
                continue;
            }

            log.info("[TenantJobTemplate] [{}/{}] 正在执行租户 [{}] 的调度任务...", currentIndex, total, tenantId);
            try {
                TenantContextHolder.runWithTenant(tenantId, () -> action.accept(tenantId));
                successCount++;
            } catch (Throwable t) {
                failCount++;
                if (failedTenants.size() < BatchExecutionResult.MAX_FAILED_ITEMS) {
                    failedTenants.add(tenantId);
                }
                try {
                    XxlJobHelper.log("<font color=\"red\">[TENANT-JOB] 租户 [" + tenantId + "] 执行失败: " + t.getMessage() + "</font>");
                } catch (Throwable ignored) {
                }

                if (firstError == null) {
                    firstError = t;
                }
                if (failFast) {
                    Duration duration = Duration.ofMillis(System.currentTimeMillis() - startTime);
                    throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                            "多租户遍历任务在租户 [" + tenantId + "] 快速失败中断: " + t.getMessage(), t);
                }
                log.error("[TenantJobTemplate] 租户 [{}] 调度任务执行失败: {}", tenantId, t.getMessage(), t);
            }
        }

        Duration duration = Duration.ofMillis(System.currentTimeMillis() - startTime);
        log.info("[TenantJobTemplate] 多租户遍历任务执行完毕，总数: {}, 成功: {}, 失败: {}, 耗时: {}ms",
                total, successCount, failCount, duration.toMillis());
        try {
            String summaryColor = (failCount == 0) ? "green" : "red";
            XxlJobHelper.log("<br><font color=\"" + summaryColor + "\">----------- [TENANT-JOB] 租户遍历完成 (总数: " +
                    total + ", 成功: " + successCount + ", 失败: " + failCount + ", 耗时: " + duration.toMillis() + "ms) -----------</font>");
            if (failCount > 0) {
                XxlJobHelper.handleFail("多租户遍历存在失败租户: " + failedTenants);
            }
        } catch (Throwable ignored) {
        }

        boolean truncated = failCount > BatchExecutionResult.MAX_FAILED_ITEMS;
        return new BatchExecutionResult(total, successCount, failCount, (int) total, duration, failedTenants, truncated);
    }

    // ==================== 2. 并发多线程租户遍历 (带背压与沙箱隔离) ====================

    /**
     * 从注册的 {@link TenantListProvider} 获取有效租户列表并执行并发遍历处理（默认并发度，容错继续模式）
     *
     * @param action 租户执行回调动作
     * @return 批处理执行统计结果
     */
    public BatchExecutionResult executeForEachTenantParallel(Consumer<String> action) {
        return executeForEachTenantParallel(resolveTenantIds(), this.defaultMaxConcurrency, false, action);
    }

    /**
     * 从注册的 {@link TenantListProvider} 获取有效租户列表并执行并发遍历处理（容错继续模式）
     *
     * @param maxConcurrency 最大在途并发度（背压阈值）
     * @param action         租户执行回调动作
     * @return 批处理执行统计结果
     */
    public BatchExecutionResult executeForEachTenantParallel(int maxConcurrency, Consumer<String> action) {
        return executeForEachTenantParallel(resolveTenantIds(), maxConcurrency, false, action);
    }

    /**
     * 从注册的 {@link TenantListProvider} 获取有效租户列表并执行并发遍历处理
     *
     * @param maxConcurrency 最大在途并发度（背压阈值）
     * @param failFast       是否快速失败
     * @param action         租户执行回调动作
     * @return 批处理执行统计结果
     */
    public BatchExecutionResult executeForEachTenantParallel(int maxConcurrency, boolean failFast, Consumer<String> action) {
        return executeForEachTenantParallel(resolveTenantIds(), maxConcurrency, failFast, action);
    }

    /**
     * 指定租户集合执行并发遍历处理（默认并发度，容错继续模式）
     *
     * @param tenantIds 租户标识集合
     * @param action    租户执行回调动作
     * @return 批处理执行统计结果
     */
    public BatchExecutionResult executeForEachTenantParallel(Collection<String> tenantIds, Consumer<String> action) {
        return executeForEachTenantParallel(tenantIds, this.defaultMaxConcurrency, false, action);
    }

    /**
     * 指定租户集合执行并发遍历处理（容错继续模式）
     *
     * @param tenantIds      租户标识集合
     * @param maxConcurrency 最大在途并发度（背压阈值）
     * @param action         租户执行回调动作
     * @return 批处理执行统计结果
     */
    public BatchExecutionResult executeForEachTenantParallel(Collection<String> tenantIds, int maxConcurrency, Consumer<String> action) {
        return executeForEachTenantParallel(tenantIds, maxConcurrency, false, action);
    }

    /**
     * 指定租户集合执行受管并发沙箱化遍历处理
     *
     * <p>采用信号量实施背压节流，防止租户过多导致线程池饱和；
     * 每个租户子任务在独立的 {@link TenantContextHolder#runWithTenant(String, Runnable)} 沙箱中执行，
     * 并全自动透传父线程的 MDC、操作人与 XXL-Job 日志镜像句柄，终态严格清理现场。</p>
     *
     * @param tenantIds      租户标识集合
     * @param maxConcurrency 最大在途并发度（背压阈值）
     * @param failFast       是否快速失败（true 表示任一租户报错即熔断中断，false 表示收集异常并继续并发处理其余租户）
     * @param action         租户执行回调动作
     * @return 批处理执行统计结果
     */
    public BatchExecutionResult executeForEachTenantParallel(
            Collection<String> tenantIds,
            int maxConcurrency,
            boolean failFast,
            Consumer<String> action) {
        Objects.requireNonNull(action, "action 不能为空");
        if (tenantIds == null || tenantIds.isEmpty()) {
            log.info("[TenantJobTemplate] 无有效租户标识，跳过租户并发执行");
            return new BatchExecutionResult(0, 0, 0, 0, Duration.ZERO);
        }

        List<String> validTenantIds = new ArrayList<>();
        for (String tId : tenantIds) {
            if (tId != null && !tId.isBlank()) {
                validTenantIds.add(tId);
            }
        }
        if (validTenantIds.isEmpty()) {
            log.info("[TenantJobTemplate] 筛选后无非空租户标识，跳过租户并发执行");
            return new BatchExecutionResult(0, 0, 0, 0, Duration.ZERO);
        }

        int effectiveConcurrency = maxConcurrency > 0 ? maxConcurrency : this.defaultMaxConcurrency;
        long startTime = System.currentTimeMillis();
        long total = validTenantIds.size();
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);
        List<String> failedTenants = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>(validTenantIds.size());
        Semaphore semaphore = new Semaphore(effectiveConcurrency);
        Executor executor = getEffectiveExecutor();

        Map<String, String> parentMdc = MDC.getCopyOfContextMap();
        String parentUser = UserContextHolder.getExplicit();
        XxlJobContext parentJobContext = XxlJobContext.getXxlJobContext();

        log.info("[TenantJobTemplate] 开始并发执行多租户任务，共 {} 个租户，并发度: {}, failFast={}", total, effectiveConcurrency, failFast);
        try {
            XxlJobHelper.log("<br>----------- [TENANT-JOB] 开始并发执行多租户任务 (租户数: " + total + ", 并发度: " + effectiveConcurrency + ", failFast: " + failFast + ") -----------");
        } catch (Throwable ignored) {
        }

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
                future = CompletableFuture.runAsync(() -> {
                    Map<String, String> prevMdc = MDC.getCopyOfContextMap();
                    String prevUser = UserContextHolder.getExplicit();
                    XxlJobContext prevJobContext = XxlJobContext.getXxlJobContext();
                    TenantSnapshot prevTenantSnapshot = TenantContextHolder.capturePrevious();

                    try {
                        if (parentMdc != null) {
                            MDC.setContextMap(parentMdc);
                        } else {
                            MDC.clear();
                        }
                        if (parentUser != null) {
                            UserContextHolder.set(parentUser);
                        } else {
                            UserContextHolder.clear();
                        }
                        if (parentJobContext != null) {
                            XxlJobContext.setXxlJobContext(parentJobContext);
                        }

                        log.info("[TenantJobTemplate] [{}/{}] 正在并发执行租户 [{}] 的调度任务...", currentIndex, total, tenantId);

                        TenantContextHolder.runWithTenant(tenantId, () -> action.accept(tenantId));
                        successCount.incrementAndGet();
                    } catch (Throwable t) {
                        failCount.incrementAndGet();
                        synchronized (failedTenants) {
                            if (failedTenants.size() < BatchExecutionResult.MAX_FAILED_ITEMS) {
                                failedTenants.add(tenantId);
                            }
                        }
                        firstError.compareAndSet(null, t);
                        try {
                            XxlJobHelper.log("<font color=\"red\">[TENANT-JOB] 租户 [" + tenantId + "] 执行失败: " + t.getMessage() + "</font>");
                        } catch (Throwable ignored) {
                        }

                        if (failFast) {
                            if (t instanceof RuntimeException re) {
                                throw re;
                            }
                            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                                    "多租户并行任务在租户 [" + tenantId + "] 快速失败中断: " + t.getMessage(), t);
                        }
                        log.error("[TenantJobTemplate] 租户 [{}] 并发调度任务执行失败: {}", tenantId, t.getMessage(), t);
                    } finally {
                        semaphore.release();

                        // 终态现场清理，防止线程池复用污染
                        TenantContextHolder.restore(prevTenantSnapshot);
                        if (prevMdc != null) {
                            MDC.setContextMap(prevMdc);
                        } else {
                            MDC.clear();
                        }
                        if (prevUser != null) {
                            UserContextHolder.set(prevUser);
                        } else {
                            UserContextHolder.clear();
                        }
                        XxlJobContext.setXxlJobContext(prevJobContext);
                    }
                }, executor);
            } catch (Throwable t) {
                semaphore.release();
                throw t;
            }
            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Throwable ex) {
            if (failFast) {
                Throwable cause = firstError.get() != null ? firstError.get() : (ex.getCause() != null ? ex.getCause() : ex);
                if (cause instanceof BusinessException be) {
                    throw be;
                }
                throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                        "多租户并发遍历执行失败: " + cause.getMessage(), cause);
            }
        }

        if (failFast && firstError.get() != null) {
            Throwable t = firstError.get();
            if (t instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "多租户并发遍历执行失败: " + t.getMessage(), t);
        }

        Duration duration = Duration.ofMillis(System.currentTimeMillis() - startTime);
        log.info("[TenantJobTemplate] 多租户并发遍历任务执行完毕，总数: {}, 成功: {}, 失败: {}, 耗时: {}ms",
                total, successCount.get(), failCount.get(), duration.toMillis());
        try {
            String summaryColor = (failCount.get() == 0) ? "green" : "red";
            XxlJobHelper.log("<br><font color=\"" + summaryColor + "\">----------- [TENANT-JOB] 租户并发遍历完成 (总数: " +
                    total + ", 成功: " + successCount.get() + ", 失败: " + failCount.get() + ", 耗时: " + duration.toMillis() + "ms) -----------</font>");
            if (failCount.get() > 0) {
                XxlJobHelper.handleFail("多租户并发遍历存在失败租户: " + failedTenants);
            }
        } catch (Throwable ignored) {
        }

        boolean truncated = failCount.get() > BatchExecutionResult.MAX_FAILED_ITEMS;
        return new BatchExecutionResult(total, successCount.get(), failCount.get(), (int) total, duration, failedTenants, truncated);
    }

    /**
     * 获取租户列表，无 Provider 时保底返回空列表
     */
    protected List<String> resolveTenantIds() {
        if (tenantListProvider == null) {
            return Collections.emptyList();
        }
        List<String> list = tenantListProvider.getTenantIds();
        return list != null ? list : Collections.emptyList();
    }

    /**
     * 获取当前生效的线程池执行器
     */
    protected Executor getEffectiveExecutor() {
        if (this.taskExecutor != null) {
            return this.taskExecutor;
        }
        try {
            Executor springExecutor = SpringUtil.getBean("taskExecutor", Executor.class);
            if (springExecutor != null) {
                return springExecutor;
            }
        } catch (Throwable ignored) {
        }
        return ForkJoinPool.commonPool();
    }

}
