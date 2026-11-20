package io.github.atengk.boot.job.snail.template;

import com.aizuda.snailjob.common.log.SnailJobLog;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder.TenantSnapshot;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.job.snail.context.SnailJobContext;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import io.github.atengk.boot.job.snail.model.ShardingContext;
import io.github.atengk.boot.job.snail.support.SnailJobInternalSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * SnailJob 分片流式批处理模板与大数据防 OOM 核心执行门面
 *
 * <p>面向千万级大表、分布式集群广播分片及 MapReduce 切片计算场景，提供基于主键/游标步长流式切片拉取与
 * MyBatis-Plus 流式游标对接能力。内置无状态不可变派生视图（{@link #withFailFast(boolean)}、
 * {@link #withProgressLogInterval(int)}、{@link #withMaxConcurrency(int)}）、
 * {@link BatchFailureHandler} 函数式失败回调 SPI、基于 {@link ReentrantLock} 的有界失败收集（防 OOM）
 * 以及信号量并发背压与四维/SnailJob 全链路上下文透传。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailShardingBatchTemplate {

    private static final Logger log = LoggerFactory.getLogger(SnailShardingBatchTemplate.class);

    public static final int DEFAULT_BATCH_SIZE = 1000;
    public static final int DEFAULT_MAX_CONCURRENCY = 8;
    public static final int DEFAULT_PROGRESS_LOG_INTERVAL = 5000;

    private final Executor taskExecutor;
    private final int defaultBatchSize;
    private final int defaultMaxConcurrency;
    private final boolean defaultFailFast;
    private final boolean managedPoolEnabled;
    private final int progressLogInterval;
    private final int maxFailedItems;
    private final BatchFailureHandler<?> defaultFailureHandler;

    public SnailShardingBatchTemplate() {
        this(null, DEFAULT_BATCH_SIZE, DEFAULT_MAX_CONCURRENCY, true, true, DEFAULT_PROGRESS_LOG_INTERVAL,
                BatchExecutionResult.MAX_FAILED_ITEMS, null);
    }

    public SnailShardingBatchTemplate(Executor taskExecutor) {
        this(taskExecutor, DEFAULT_BATCH_SIZE, DEFAULT_MAX_CONCURRENCY, true, true, DEFAULT_PROGRESS_LOG_INTERVAL,
                BatchExecutionResult.MAX_FAILED_ITEMS, null);
    }

    public SnailShardingBatchTemplate(Executor taskExecutor, int defaultBatchSize, int defaultMaxConcurrency) {
        this(taskExecutor, defaultBatchSize, defaultMaxConcurrency, true, true, DEFAULT_PROGRESS_LOG_INTERVAL,
                BatchExecutionResult.MAX_FAILED_ITEMS, null);
    }

    public SnailShardingBatchTemplate(
            Executor taskExecutor,
            int defaultBatchSize,
            int defaultMaxConcurrency,
            boolean defaultFailFast) {
        this(taskExecutor, defaultBatchSize, defaultMaxConcurrency, defaultFailFast, true, DEFAULT_PROGRESS_LOG_INTERVAL,
                BatchExecutionResult.MAX_FAILED_ITEMS, null);
    }

    public SnailShardingBatchTemplate(
            Executor taskExecutor,
            int defaultBatchSize,
            int defaultMaxConcurrency,
            boolean defaultFailFast,
            boolean managedPoolEnabled) {
        this(taskExecutor, defaultBatchSize, defaultMaxConcurrency, defaultFailFast, managedPoolEnabled,
                DEFAULT_PROGRESS_LOG_INTERVAL, BatchExecutionResult.MAX_FAILED_ITEMS, null);
    }

    /**
     * 构造全特性分片批处理模板实例
     *
     * @param taskExecutor          受管并发线程池（null 时视 {@code managedPoolEnabled} 决定是否回捞 Spring {@code taskExecutor}）
     * @param defaultBatchSize      默认分片拉取大小
     * @param defaultMaxConcurrency 默认最大并发度
     * @param defaultFailFast       默认快速失败策略
     * @param managedPoolEnabled    是否允许回捞 Spring 受管线程池
     * @param progressLogInterval   进度心跳输出间隔条数（{@code <= 0} 关闭）
     * @param maxFailedItems        允许收集的最大失败批次条目数
     * @param defaultFailureHandler 全局默认失败切片回调 SPI（可为 null）
     */
    public SnailShardingBatchTemplate(
            Executor taskExecutor,
            int defaultBatchSize,
            int defaultMaxConcurrency,
            boolean defaultFailFast,
            boolean managedPoolEnabled,
            int progressLogInterval,
            int maxFailedItems,
            BatchFailureHandler<?> defaultFailureHandler) {
        this.taskExecutor = taskExecutor;
        this.defaultBatchSize = defaultBatchSize > 0 ? defaultBatchSize : DEFAULT_BATCH_SIZE;
        this.defaultMaxConcurrency = defaultMaxConcurrency > 0 ? defaultMaxConcurrency : DEFAULT_MAX_CONCURRENCY;
        this.defaultFailFast = defaultFailFast;
        this.managedPoolEnabled = managedPoolEnabled;
        this.progressLogInterval = progressLogInterval;
        this.maxFailedItems = maxFailedItems > 0 ? maxFailedItems : BatchExecutionResult.MAX_FAILED_ITEMS;
        this.defaultFailureHandler = defaultFailureHandler;
    }

    /**
     * 派生无状态不可变视图使用的受保护拷贝构造器
     */
    protected SnailShardingBatchTemplate(
            Executor taskExecutor,
            int defaultBatchSize,
            int defaultMaxConcurrency,
            boolean defaultFailFast,
            boolean managedPoolEnabled,
            int progressLogInterval,
            int maxFailedItems,
            BatchFailureHandler<?> defaultFailureHandler,
            boolean preserveZeroInterval) {
        this.taskExecutor = taskExecutor;
        this.defaultBatchSize = defaultBatchSize;
        this.defaultMaxConcurrency = defaultMaxConcurrency;
        this.defaultFailFast = defaultFailFast;
        this.managedPoolEnabled = managedPoolEnabled;
        this.progressLogInterval = preserveZeroInterval ? progressLogInterval
                : (progressLogInterval > 0 ? progressLogInterval : DEFAULT_PROGRESS_LOG_INTERVAL);
        this.maxFailedItems = maxFailedItems > 0 ? maxFailedItems : BatchExecutionResult.MAX_FAILED_ITEMS;
        this.defaultFailureHandler = defaultFailureHandler;
    }

    protected Executor getTaskExecutor() {
        return taskExecutor;
    }

    protected boolean isManagedPoolEnabled() {
        return managedPoolEnabled;
    }

    public int getDefaultBatchSize() {
        return defaultBatchSize;
    }

    public int getDefaultMaxConcurrency() {
        return defaultMaxConcurrency;
    }

    public boolean isFailFast() {
        return defaultFailFast;
    }

    public int getProgressLogInterval() {
        return progressLogInterval;
    }

    public int getMaxFailedItems() {
        return maxFailedItems;
    }

    public BatchFailureHandler<?> getDefaultFailureHandler() {
        return defaultFailureHandler;
    }

    /**
     * 基于当前模板派生携带指定 {@code failFast} 策略的无状态不可变视图实例
     *
     * @param failFast 目标快速失败策略
     * @return 具备独立 {@code failFast} 策略的派生视图实例
     */
    public SnailShardingBatchTemplate withFailFast(boolean failFast) {
        return new SnailShardingBatchTemplate(
                this.taskExecutor,
                this.defaultBatchSize,
                this.defaultMaxConcurrency,
                failFast,
                this.managedPoolEnabled,
                this.progressLogInterval,
                this.maxFailedItems,
                this.defaultFailureHandler,
                true
        );
    }

    /**
     * 基于当前模板派生携带指定 {@code progressLogInterval} 步长的无状态不可变视图实例
     *
     * @param progressLogInterval 进度心跳步长（{@code <= 0} 时关闭进度日志）
     * @return 具备独立进度步长的派生视图实例
     */
    public SnailShardingBatchTemplate withProgressLogInterval(int progressLogInterval) {
        return new SnailShardingBatchTemplate(
                this.taskExecutor,
                this.defaultBatchSize,
                this.defaultMaxConcurrency,
                this.defaultFailFast,
                this.managedPoolEnabled,
                progressLogInterval,
                this.maxFailedItems,
                this.defaultFailureHandler,
                true
        );
    }

    /**
     * 基于当前模板派生携带指定 {@code maxConcurrency} 并发度的无状态不可变视图实例
     *
     * @param maxConcurrency 默认最大并发度
     * @return 具备独立并发度设置的派生视图实例
     */
    public SnailShardingBatchTemplate withMaxConcurrency(int maxConcurrency) {
        int resolved = maxConcurrency > 0 ? maxConcurrency : DEFAULT_MAX_CONCURRENCY;
        return new SnailShardingBatchTemplate(
                this.taskExecutor,
                this.defaultBatchSize,
                resolved,
                this.defaultFailFast,
                this.managedPoolEnabled,
                this.progressLogInterval,
                this.maxFailedItems,
                this.defaultFailureHandler,
                true
        );
    }

    public Executor getEffectiveExecutor() {
        Executor resolved = SnailJobInternalSupport.resolveManagedExecutor(this.taskExecutor, this.managedPoolEnabled);
        // 脱管单测、禁用受管线程池或未注入线程池时在调用方当前线程顺滑执行，严禁回退 ForkJoinPool.commonPool()
        return resolved != null ? resolved : Runnable::run;
    }

    // ==================== 1. 基于游标/步长流式分页 (单线程顺序消费) ====================

    public <T, ID extends Comparable<ID>> BatchExecutionResult<ID> executeStep(
            BiFunction<ID, Integer, List<T>> queryFunction,
            Function<T, ID> idExtractor,
            Consumer<List<T>> batchConsumer) {
        return executeStep(null, this.defaultBatchSize, this.defaultFailFast, queryFunction, idExtractor, batchConsumer, null);
    }

    public <T, ID extends Comparable<ID>> BatchExecutionResult<ID> executeStep(
            ID initialId,
            int batchSize,
            BiFunction<ID, Integer, List<T>> queryFunction,
            Function<T, ID> idExtractor,
            Consumer<List<T>> batchConsumer) {
        return executeStep(initialId, batchSize, this.defaultFailFast, queryFunction, idExtractor, batchConsumer, null);
    }

    public <T, ID extends Comparable<ID>> BatchExecutionResult<ID> executeStep(
            ID initialId,
            int batchSize,
            boolean failFast,
            BiFunction<ID, Integer, List<T>> queryFunction,
            Function<T, ID> idExtractor,
            Consumer<List<T>> batchConsumer) {
        return executeStep(initialId, batchSize, failFast, queryFunction, idExtractor, batchConsumer, null);
    }

    /**
     * 基于游标步长流式分批拉取并消费（支持显式 {@link BatchFailureHandler} 回调覆盖）
     *
     * @param initialId      初始游标 ID
     * @param batchSize      每批拉取条数
     * @param failFast       是否快速失败
     * @param queryFunction  游标查询函数
     * @param idExtractor    实体主键提取器
     * @param batchConsumer  分批消费逻辑
     * @param failureHandler 调用处显式失败回调（为 null 时回退全局默认 SPI，不重复触发）
     * @param <T>            实体类型
     * @param <ID>           游标 ID 类型
     * @return 批处理执行汇总统计
     */
    public <T, ID extends Comparable<ID>> BatchExecutionResult<ID> executeStep(
            ID initialId,
            int batchSize,
            boolean failFast,
            BiFunction<ID, Integer, List<T>> queryFunction,
            Function<T, ID> idExtractor,
            Consumer<List<T>> batchConsumer,
            BatchFailureHandler<ID> failureHandler) {
        Objects.requireNonNull(queryFunction, "queryFunction 不能为 null");
        Objects.requireNonNull(idExtractor, "idExtractor 不能为 null");
        Objects.requireNonNull(batchConsumer, "batchConsumer 不能为 null");
        int effectiveBatchSize = batchSize > 0 ? batchSize : this.defaultBatchSize;

        long startTime = System.nanoTime();
        ID lastId = initialId;
        long totalCount = 0;
        long successCount = 0;
        long failCount = 0;
        int batchCount = 0;
        List<FailedBatch<ID>> failedBatches = new ArrayList<>();
        ReentrantLock failedLock = new ReentrantLock();
        AtomicBoolean truncated = new AtomicBoolean(false);
        AtomicLong nextProgressThreshold = createProgressThreshold();

        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                log.warn("[SnailShardingBatchTemplate] 批处理线程检测到中断信号，提前终止流式批拉取 [当前游标 ID: {}]", lastId);
                break;
            }

            List<T> chunk = queryFunction.apply(lastId, effectiveBatchSize);
            if (chunk == null || chunk.isEmpty()) {
                break;
            }

            int chunkSize = chunk.size();
            totalCount += chunkSize;
            batchCount++;

            ID chunkStartId = idExtractor.apply(chunk.get(0));
            ID chunkEndId = idExtractor.apply(chunk.get(chunkSize - 1));

            try {
                batchConsumer.accept(Collections.unmodifiableList(chunk));
                successCount += chunkSize;
            } catch (Throwable t) {
                failCount += chunkSize;
                FailedBatch<ID> failed = new FailedBatch<>(chunkStartId, chunkEndId, null, batchCount, chunkSize, t);
                recordFailedBatch(failedBatches, failedLock, truncated, this.maxFailedItems, failed);
                notifyFailureHandler(failed, failureHandler);
                if (failFast) {
                    if (t instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new IllegalStateException("游标流式批处理发生未受检异常", t);
                }
                log.error("[SnailShardingBatchTemplate] 游标流式批处理执行异常 [批次: {}, 当前游标 ID: {}]: {}", batchCount, lastId, t.getMessage(), t);
            }

            emitProgressIfNeeded(totalCount, successCount, failCount, batchCount, startTime, nextProgressThreshold);

            lastId = chunkEndId;
            if (chunkSize < effectiveBatchSize) {
                break;
            }
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
        return new BatchExecutionResult<>(
                totalCount, successCount, failCount, batchCount, duration, failedBatches, this.maxFailedItems, truncated.get());
    }

    public <T, ID extends Comparable<ID>> BatchExecutionResult<ID> executeStepForEach(
            ID initialId,
            int batchSize,
            BiFunction<ID, Integer, List<T>> queryFunction,
            Function<T, ID> idExtractor,
            Consumer<T> itemConsumer) {
        Objects.requireNonNull(itemConsumer, "itemConsumer 不能为 null");
        return executeStep(initialId, batchSize, queryFunction, idExtractor, batch -> {
            for (T item : batch) {
                itemConsumer.accept(item);
            }
        });
    }

    // ==================== 2. 结合 ShardingContext 分片自动路由过滤 ====================

    public <T> BatchExecutionResult<Long> executeShardedStep(
            BiFunction<Long, Integer, List<T>> queryFunction,
            ToLongFunction<T> idExtractor,
            Consumer<List<T>> batchConsumer) {
        return executeShardedStep(ShardingContext.current(), null, this.defaultBatchSize, this.defaultFailFast,
                queryFunction, idExtractor, batchConsumer, null);
    }

    public <T> BatchExecutionResult<Long> executeShardedStep(
            ShardingContext sharding,
            Long initialId,
            int batchSize,
            BiFunction<Long, Integer, List<T>> queryFunction,
            ToLongFunction<T> idExtractor,
            Consumer<List<T>> batchConsumer) {
        return executeShardedStep(sharding, initialId, batchSize, this.defaultFailFast,
                queryFunction, idExtractor, batchConsumer, null);
    }

    public <T> BatchExecutionResult<Long> executeShardedStep(
            ShardingContext sharding,
            Long initialId,
            int batchSize,
            boolean failFast,
            BiFunction<Long, Integer, List<T>> queryFunction,
            ToLongFunction<T> idExtractor,
            Consumer<List<T>> batchConsumer) {
        return executeShardedStep(sharding, initialId, batchSize, failFast, queryFunction, idExtractor, batchConsumer, null);
    }

    /**
     * 带有指定分片匹配的步长流式批处理（支持快速失败与显式 {@link BatchFailureHandler} 回调）
     */
    public <T> BatchExecutionResult<Long> executeShardedStep(
            ShardingContext sharding,
            Long initialId,
            int batchSize,
            boolean failFast,
            BiFunction<Long, Integer, List<T>> queryFunction,
            ToLongFunction<T> idExtractor,
            Consumer<List<T>> batchConsumer,
            BatchFailureHandler<Long> failureHandler) {
        Objects.requireNonNull(queryFunction, "queryFunction 不能为 null");
        Objects.requireNonNull(idExtractor, "idExtractor 不能为 null");
        Objects.requireNonNull(batchConsumer, "batchConsumer 不能为 null");
        int effectiveBatchSize = batchSize > 0 ? batchSize : this.defaultBatchSize;

        long startTime = System.nanoTime();
        Long lastId = initialId;
        long totalCount = 0;
        long successCount = 0;
        long failCount = 0;
        int batchCount = 0;
        List<FailedBatch<Long>> failedBatches = new ArrayList<>();
        ReentrantLock failedLock = new ReentrantLock();
        AtomicBoolean truncated = new AtomicBoolean(false);
        AtomicLong nextProgressThreshold = createProgressThreshold();

        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                log.warn("[SnailShardingBatchTemplate] 分片批处理线程检测到中断信号，提前终止流式批拉取 [当前游标 ID: {}]", lastId);
                break;
            }

            List<T> chunk = queryFunction.apply(lastId, effectiveBatchSize);
            if (chunk == null || chunk.isEmpty()) {
                break;
            }

            int chunkSize = chunk.size();
            List<T> matchedChunk = filterMatchedChunk(chunk, sharding, idExtractor);

            if (!matchedChunk.isEmpty()) {
                int matchedSize = matchedChunk.size();
                totalCount += matchedSize;
                batchCount++;

                Long chunkStartId = idExtractor.applyAsLong(matchedChunk.get(0));
                Long chunkEndId = idExtractor.applyAsLong(matchedChunk.get(matchedSize - 1));
                Integer shardIndex = (sharding != null) ? sharding.getShardIndex() : null;

                try {
                    batchConsumer.accept(Collections.unmodifiableList(matchedChunk));
                    successCount += matchedSize;
                } catch (Throwable t) {
                    failCount += matchedSize;
                    FailedBatch<Long> failed = new FailedBatch<>(chunkStartId, chunkEndId, shardIndex, batchCount, matchedSize, t);
                    recordFailedBatch(failedBatches, failedLock, truncated, this.maxFailedItems, failed);
                    notifyFailureHandler(failed, failureHandler);
                    if (failFast) {
                        if (t instanceof RuntimeException re) {
                            throw re;
                        }
                        throw new IllegalStateException("分片流式批处理发生未受检异常", t);
                    }
                    log.error("[SnailShardingBatchTemplate] 分片流式批处理执行异常 [批次: {}, 当前游标 ID: {}]: {}", batchCount, lastId, t.getMessage(), t);
                }
                emitProgressIfNeeded(totalCount, successCount, failCount, batchCount, startTime, nextProgressThreshold);
            }

            lastId = idExtractor.applyAsLong(chunk.get(chunkSize - 1));
            if (chunkSize < effectiveBatchSize) {
                break;
            }
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
        return new BatchExecutionResult<>(
                totalCount, successCount, failCount, batchCount, duration, failedBatches, this.maxFailedItems, truncated.get());
    }

    public <T> BatchExecutionResult<Long> executeShardedStepForEach(
            ShardingContext sharding,
            Long initialId,
            int batchSize,
            BiFunction<Long, Integer, List<T>> queryFunction,
            ToLongFunction<T> idExtractor,
            Consumer<T> itemConsumer) {
        Objects.requireNonNull(itemConsumer, "itemConsumer 不能为 null");
        return executeShardedStep(sharding, initialId, batchSize, queryFunction, idExtractor, batch -> {
            for (T item : batch) {
                itemConsumer.accept(item);
            }
        });
    }

    // ==================== 3. 并发切片批处理 (带背压流量保护与四维上下文透传) ====================

    public <T, ID extends Comparable<ID>> BatchExecutionResult<ID> executeStepParallel(
            ID initialId,
            BiFunction<ID, Integer, List<T>> queryFunction,
            Function<T, ID> idExtractor,
            Consumer<List<T>> batchConsumer) {
        return executeStepParallel(initialId, this.defaultBatchSize, this.defaultMaxConcurrency, this.defaultFailFast,
                queryFunction, idExtractor, batchConsumer, null);
    }

    public <T, ID extends Comparable<ID>> BatchExecutionResult<ID> executeStepParallel(
            ID initialId,
            int batchSize,
            int maxConcurrency,
            BiFunction<ID, Integer, List<T>> queryFunction,
            Function<T, ID> idExtractor,
            Consumer<List<T>> batchConsumer) {
        return executeStepParallel(initialId, batchSize, maxConcurrency, this.defaultFailFast,
                queryFunction, idExtractor, batchConsumer, null);
    }

    public <T, ID extends Comparable<ID>> BatchExecutionResult<ID> executeStepParallel(
            ID initialId,
            int batchSize,
            int maxConcurrency,
            boolean failFast,
            BiFunction<ID, Integer, List<T>> queryFunction,
            Function<T, ID> idExtractor,
            Consumer<List<T>> batchConsumer) {
        return executeStepParallel(initialId, batchSize, maxConcurrency, failFast,
                queryFunction, idExtractor, batchConsumer, null);
    }

    /**
     * 基于游标步长拉取 + 受管线程池并发消费（支持快速失败与显式 {@link BatchFailureHandler} 回调）
     */
    public <T, ID extends Comparable<ID>> BatchExecutionResult<ID> executeStepParallel(
            ID initialId,
            int batchSize,
            int maxConcurrency,
            boolean failFast,
            BiFunction<ID, Integer, List<T>> queryFunction,
            Function<T, ID> idExtractor,
            Consumer<List<T>> batchConsumer,
            BatchFailureHandler<ID> failureHandler) {
        Objects.requireNonNull(queryFunction, "queryFunction 不能为 null");
        Objects.requireNonNull(idExtractor, "idExtractor 不能为 null");
        Objects.requireNonNull(batchConsumer, "batchConsumer 不能为 null");

        int effectiveBatchSize = batchSize > 0 ? batchSize : this.defaultBatchSize;
        int effectiveConcurrency = maxConcurrency > 0 ? maxConcurrency : this.defaultMaxConcurrency;
        Executor executor = getEffectiveExecutor();

        long startTime = System.nanoTime();
        ID lastId = initialId;
        AtomicLong totalCount = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);
        AtomicInteger batchCount = new AtomicInteger(0);
        List<FailedBatch<ID>> failedBatches = new ArrayList<>();
        ReentrantLock failedLock = new ReentrantLock();
        AtomicBoolean truncated = new AtomicBoolean(false);
        AtomicLong nextProgressThreshold = createProgressThreshold();

        Semaphore semaphore = new Semaphore(effectiveConcurrency);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        AtomicReference<Throwable> firstError = new AtomicReference<>(null);
        ParentContextSnapshot parentContext = ParentContextSnapshot.capture();

        while (true) {
            if (Thread.currentThread().isInterrupted() || (failFast && firstError.get() != null)) {
                log.warn("[SnailShardingBatchTemplate] 并发批处理检测到中断或前置失败，提前终止拉取 [游标 ID: {}]", lastId);
                break;
            }

            List<T> chunk = queryFunction.apply(lastId, effectiveBatchSize);
            if (chunk == null || chunk.isEmpty()) {
                break;
            }

            int chunkSize = chunk.size();
            long processed = totalCount.addAndGet(chunkSize);
            int currentBatch = batchCount.incrementAndGet();

            ID chunkStartId = idExtractor.apply(chunk.get(0));
            ID chunkEndId = idExtractor.apply(chunk.get(chunkSize - 1));

            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[SnailShardingBatchTemplate] 等待并发许可被中断", e);
                break;
            }

            List<T> immutableChunk = Collections.unmodifiableList(chunk);
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> parentContext.runWithContext(() -> {
                try {
                    batchConsumer.accept(immutableChunk);
                    long currentSuccess = successCount.addAndGet(chunkSize);
                    emitProgressIfNeeded(processed, currentSuccess, failCount.get(), currentBatch, startTime, nextProgressThreshold);
                } catch (Throwable t) {
                    long currentFail = failCount.addAndGet(chunkSize);
                    FailedBatch<ID> failed = new FailedBatch<>(chunkStartId, chunkEndId, null, currentBatch, chunkSize, t);
                    recordFailedBatch(failedBatches, failedLock, truncated, this.maxFailedItems, failed);
                    notifyFailureHandler(failed, failureHandler);
                    firstError.compareAndSet(null, t);
                    log.error("[SnailShardingBatchTemplate] 并发批处理子任务执行异常 [批次: {}]: {}", currentBatch, t.getMessage(), t);
                    emitProgressIfNeeded(processed, successCount.get(), currentFail, currentBatch, startTime, nextProgressThreshold);
                } finally {
                    semaphore.release();
                }
            }), executor);

            futures.add(future);

            lastId = chunkEndId;
            if (chunkSize < effectiveBatchSize) {
                break;
            }
        }

        awaitAllFutures(futures, firstError);

        if (failFast && firstError.get() != null) {
            Throwable t = firstError.get();
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("并发分片批处理执行失败: " + t.getMessage(), t);
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
        return new BatchExecutionResult<>(
                totalCount.get(),
                successCount.get(),
                failCount.get(),
                batchCount.get(),
                duration,
                snapshotFailedBatches(failedBatches, failedLock),
                this.maxFailedItems,
                truncated.get());
    }

    public <T> BatchExecutionResult<Long> executeShardedStepParallel(
            ShardingContext sharding,
            Long initialId,
            int batchSize,
            int maxConcurrency,
            BiFunction<Long, Integer, List<T>> queryFunction,
            ToLongFunction<T> idExtractor,
            Consumer<List<T>> batchConsumer) {
        return executeShardedStepParallel(sharding, initialId, batchSize, maxConcurrency, this.defaultFailFast,
                queryFunction, idExtractor, batchConsumer, null);
    }

    public <T> BatchExecutionResult<Long> executeShardedStepParallel(
            ShardingContext sharding,
            Long initialId,
            int batchSize,
            int maxConcurrency,
            boolean failFast,
            BiFunction<Long, Integer, List<T>> queryFunction,
            ToLongFunction<T> idExtractor,
            Consumer<List<T>> batchConsumer) {
        return executeShardedStepParallel(sharding, initialId, batchSize, maxConcurrency, failFast,
                queryFunction, idExtractor, batchConsumer, null);
    }

    /**
     * 带有分片过滤的并发批处理（支持快速失败与显式 {@link BatchFailureHandler} 回调）
     */
    public <T> BatchExecutionResult<Long> executeShardedStepParallel(
            ShardingContext sharding,
            Long initialId,
            int batchSize,
            int maxConcurrency,
            boolean failFast,
            BiFunction<Long, Integer, List<T>> queryFunction,
            ToLongFunction<T> idExtractor,
            Consumer<List<T>> batchConsumer,
            BatchFailureHandler<Long> failureHandler) {
        Objects.requireNonNull(queryFunction, "queryFunction 不能为 null");
        Objects.requireNonNull(idExtractor, "idExtractor 不能为 null");
        Objects.requireNonNull(batchConsumer, "batchConsumer 不能为 null");

        int effectiveBatchSize = batchSize > 0 ? batchSize : this.defaultBatchSize;
        int effectiveConcurrency = maxConcurrency > 0 ? maxConcurrency : this.defaultMaxConcurrency;
        Executor executor = getEffectiveExecutor();

        long startTime = System.nanoTime();
        Long lastId = initialId;
        AtomicLong totalCount = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);
        AtomicInteger batchCount = new AtomicInteger(0);
        List<FailedBatch<Long>> failedBatches = new ArrayList<>();
        ReentrantLock failedLock = new ReentrantLock();
        AtomicBoolean truncated = new AtomicBoolean(false);
        AtomicLong nextProgressThreshold = createProgressThreshold();

        Semaphore semaphore = new Semaphore(effectiveConcurrency);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        AtomicReference<Throwable> firstError = new AtomicReference<>(null);
        ParentContextSnapshot parentContext = ParentContextSnapshot.capture();

        while (true) {
            if (Thread.currentThread().isInterrupted() || (failFast && firstError.get() != null)) {
                log.warn("[SnailShardingBatchTemplate] 分片并发批处理提前终止 [游标 ID: {}]", lastId);
                break;
            }

            List<T> chunk = queryFunction.apply(lastId, effectiveBatchSize);
            if (chunk == null || chunk.isEmpty()) {
                break;
            }

            int chunkSize = chunk.size();
            List<T> matchedChunk = filterMatchedChunk(chunk, sharding, idExtractor);

            if (!matchedChunk.isEmpty()) {
                int matchedSize = matchedChunk.size();
                long processed = totalCount.addAndGet(matchedSize);
                int currentBatch = batchCount.incrementAndGet();

                Long chunkStartId = idExtractor.applyAsLong(matchedChunk.get(0));
                Long chunkEndId = idExtractor.applyAsLong(matchedChunk.get(matchedSize - 1));
                Integer shardIndex = (sharding != null) ? sharding.getShardIndex() : null;

                try {
                    semaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                List<T> immutableChunk = Collections.unmodifiableList(matchedChunk);
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> parentContext.runWithContext(() -> {
                    try {
                        batchConsumer.accept(immutableChunk);
                        long currentSuccess = successCount.addAndGet(matchedSize);
                        emitProgressIfNeeded(processed, currentSuccess, failCount.get(), currentBatch, startTime, nextProgressThreshold);
                    } catch (Throwable t) {
                        long currentFail = failCount.addAndGet(matchedSize);
                        FailedBatch<Long> failed = new FailedBatch<>(chunkStartId, chunkEndId, shardIndex, currentBatch, matchedSize, t);
                        recordFailedBatch(failedBatches, failedLock, truncated, this.maxFailedItems, failed);
                        notifyFailureHandler(failed, failureHandler);
                        firstError.compareAndSet(null, t);
                        log.error("[SnailShardingBatchTemplate] 分片并发切片执行异常 [批次: {}]: {}", currentBatch, t.getMessage(), t);
                        emitProgressIfNeeded(processed, successCount.get(), currentFail, currentBatch, startTime, nextProgressThreshold);
                    } finally {
                        semaphore.release();
                    }
                }), executor);

                futures.add(future);
            }

            lastId = idExtractor.applyAsLong(chunk.get(chunkSize - 1));
            if (chunkSize < effectiveBatchSize) {
                break;
            }
        }

        awaitAllFutures(futures, firstError);

        if (failFast && firstError.get() != null) {
            Throwable t = firstError.get();
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("分片并发批处理执行失败: " + t.getMessage(), t);
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
        return new BatchExecutionResult<>(
                totalCount.get(),
                successCount.get(),
                failCount.get(),
                batchCount.get(),
                duration,
                snapshotFailedBatches(failedBatches, failedLock),
                this.maxFailedItems,
                truncated.get());
    }

    // ==================== 4. 共享受保护协同原语 (消除逻辑重复与 synchronized Pinning) ====================

    protected AtomicLong createProgressThreshold() {
        return this.progressLogInterval > 0 ? new AtomicLong(this.progressLogInterval) : null;
    }

    protected void emitProgressIfNeeded(
            long totalProcessed,
            long successCount,
            long failCount,
            int batchCount,
            long startNanos,
            AtomicLong nextThreshold) {
        if (nextThreshold == null || this.progressLogInterval <= 0) {
            return;
        }
        long expected = nextThreshold.get();
        if (totalProcessed < expected) {
            return;
        }
        long advanced = ((totalProcessed / this.progressLogInterval) + 1) * (long) this.progressLogInterval;
        if (!nextThreshold.compareAndSet(expected, advanced)) {
            return;
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        double throughput = elapsedMillis > 0 ? (totalProcessed * 1000.0 / elapsedMillis) : totalProcessed;
        String msg = String.format(
                "[PROGRESS] 批处理流式进度打卡: 已处理 %d 条 (成功: %d, 失败: %d, 批次: %d, 耗时: %dms, 吞吐量: %.1f 条/秒)",
                totalProcessed, successCount, failCount, batchCount, elapsedMillis, throughput);
        log.info("{}", msg);
        try {
            SnailJobLog.LOCAL.info(msg);
        } catch (Throwable ignored) {
        }
    }

    protected static <K> void recordFailedBatch(
            List<FailedBatch<K>> failedBatches,
            ReentrantLock lock,
            AtomicBoolean truncated,
            int maxItems,
            FailedBatch<K> failed) {
        if (failed == null) {
            return;
        }
        int limit = maxItems > 0 ? maxItems : BatchExecutionResult.MAX_FAILED_ITEMS;
        lock.lock();
        try {
            if (failedBatches.size() < limit) {
                failedBatches.add(failed);
            } else {
                truncated.set(true);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    protected static <K> List<FailedBatch<K>> snapshotFailedBatches(
            List<FailedBatch<K>> failedBatches,
            ReentrantLock lock) {
        lock.lock();
        try {
            return failedBatches.isEmpty() ? Collections.emptyList() : new ArrayList<>(failedBatches);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected <K> void notifyFailureHandler(FailedBatch<K> failed, BatchFailureHandler<K> explicitHandler) {
        if (failed == null) {
            return;
        }
        BatchFailureHandler<K> target = explicitHandler != null
                ? explicitHandler
                : (BatchFailureHandler<K>) this.defaultFailureHandler;
        if (target == null) {
            return;
        }
        try {
            target.onFailure(failed);
        } catch (Throwable ex) {
            log.warn("[SnailShardingBatchTemplate] BatchFailureHandler 回调执行异常，已隔离防止影响主链路 [Batch: {}]: {}",
                    failed.batchIndex(), ex.getMessage(), ex);
        }
    }

    protected static void awaitAllFutures(List<CompletableFuture<Void>> futures, AtomicReference<Throwable> firstError) {
        if (futures == null || futures.isEmpty()) {
            return;
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(1, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("[SnailShardingBatchTemplate] 等待并发切片完成异常: {}", e.getMessage(), e);
            firstError.compareAndSet(null, e);
        }
    }

    private <T> List<T> filterMatchedChunk(List<T> chunk, ShardingContext sharding, ToLongFunction<T> idExtractor) {
        List<T> matched = new ArrayList<>(chunk.size());
        for (T item : chunk) {
            long id = idExtractor.applyAsLong(item);
            if (sharding == null || sharding.match(id)) {
                matched.add(item);
            }
        }
        return matched;
    }

    /**
     * 父线程四维上下文与 SnailJob 调度上下文快照器（供受管并发子任务无损透传与终态清理）
     */
    protected static final class ParentContextSnapshot {

        private final Map<String, String> mdcContext;
        private final String tenantId;
        private final boolean ignoreTenant;
        private final String userId;
        private final SnailJobContext jobContext;

        private ParentContextSnapshot(
                Map<String, String> mdcContext,
                String tenantId,
                boolean ignoreTenant,
                String userId,
                SnailJobContext jobContext) {
            this.mdcContext = mdcContext;
            this.tenantId = tenantId;
            this.ignoreTenant = ignoreTenant;
            this.userId = userId;
            this.jobContext = jobContext;
        }

        public static ParentContextSnapshot capture() {
            return new ParentContextSnapshot(
                    MDC.getCopyOfContextMap(),
                    TenantContextHolder.getTenantId(),
                    TenantContextHolder.isIgnoreTenant(),
                    UserContextHolder.getExplicit(),
                    SnailJobContextHolder.get()
            );
        }

        public void runWithContext(Runnable task) {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            TenantSnapshot previousTenant = TenantContextHolder.capturePrevious();
            String previousUser = UserContextHolder.getExplicit();
            SnailJobContext previousJob = SnailJobContextHolder.get();

            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            } else {
                MDC.clear();
            }
            TenantContextHolder.setTenantId(tenantId);
            TenantContextHolder.setIgnoreTenant(ignoreTenant);
            if (userId != null) {
                UserContextHolder.set(userId);
            } else {
                UserContextHolder.clear();
            }
            if (jobContext != null) {
                SnailJobContextHolder.set(jobContext);
            } else {
                SnailJobContextHolder.remove();
            }

            try {
                task.run();
            } finally {
                if (previousJob != null) {
                    SnailJobContextHolder.set(previousJob);
                } else {
                    SnailJobContextHolder.remove();
                }
                TenantContextHolder.restore(previousTenant);
                if (previousUser != null) {
                    UserContextHolder.set(previousUser);
                } else {
                    UserContextHolder.clear();
                }
                if (previousMdc != null) {
                    MDC.setContextMap(previousMdc);
                } else {
                    MDC.clear();
                }
            }
        }
    }
}
