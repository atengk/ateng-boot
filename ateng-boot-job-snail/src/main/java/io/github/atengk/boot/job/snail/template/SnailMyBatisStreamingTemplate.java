package io.github.atengk.boot.job.snail.template;

import io.github.atengk.boot.core.util.SpringUtil;
import io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate;
import org.apache.ibatis.cursor.Cursor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * SnailJob 联动 MyBatis-Plus 流式游标批处理模板
 *
 * <p>继承自 {@link SnailShardingBatchTemplate}，专门在具备持久层 MyBatis-Plus 依赖的环境下，
 * 对接 {@link StreamingQueryTemplate} 与 MyBatis 原生 {@link Cursor} 流式查询，
 * 提供单线程游标分批拉取与受管多线程并发消费能力，统一沉淀结构化 {@link FailedBatch}
 * 与有界失败收集，并支持 {@link BatchFailureHandler} 与无状态派生视图。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailMyBatisStreamingTemplate extends SnailShardingBatchTemplate {

    private static final Logger log = LoggerFactory.getLogger(SnailMyBatisStreamingTemplate.class);

    private volatile StreamingQueryTemplate streamingQueryTemplate;

    public SnailMyBatisStreamingTemplate() {
        super();
    }

    public SnailMyBatisStreamingTemplate(Executor taskExecutor) {
        super(taskExecutor);
    }

    public SnailMyBatisStreamingTemplate(Executor taskExecutor, int defaultBatchSize, int defaultMaxConcurrency) {
        super(taskExecutor, defaultBatchSize, defaultMaxConcurrency);
    }

    public SnailMyBatisStreamingTemplate(
            Executor taskExecutor,
            int defaultBatchSize,
            int defaultMaxConcurrency,
            boolean defaultFailFast,
            boolean managedPoolEnabled) {
        super(taskExecutor, defaultBatchSize, defaultMaxConcurrency, defaultFailFast, managedPoolEnabled);
    }

    public SnailMyBatisStreamingTemplate(
            Executor taskExecutor,
            int defaultBatchSize,
            int defaultMaxConcurrency,
            StreamingQueryTemplate streamingQueryTemplate) {
        super(taskExecutor, defaultBatchSize, defaultMaxConcurrency);
        this.streamingQueryTemplate = streamingQueryTemplate;
    }

    public SnailMyBatisStreamingTemplate(
            Executor taskExecutor,
            int defaultBatchSize,
            int defaultMaxConcurrency,
            boolean defaultFailFast,
            boolean managedPoolEnabled,
            int progressLogInterval,
            int maxFailedItems,
            StreamingQueryTemplate streamingQueryTemplate,
            BatchFailureHandler<?> defaultFailureHandler) {
        super(taskExecutor, defaultBatchSize, defaultMaxConcurrency, defaultFailFast, managedPoolEnabled,
                progressLogInterval, maxFailedItems, defaultFailureHandler);
        this.streamingQueryTemplate = streamingQueryTemplate;
    }

    protected SnailMyBatisStreamingTemplate(
            Executor taskExecutor,
            int defaultBatchSize,
            int defaultMaxConcurrency,
            boolean defaultFailFast,
            boolean managedPoolEnabled,
            int progressLogInterval,
            int maxFailedItems,
            StreamingQueryTemplate streamingQueryTemplate,
            BatchFailureHandler<?> defaultFailureHandler,
            boolean preserveZeroInterval) {
        super(taskExecutor, defaultBatchSize, defaultMaxConcurrency, defaultFailFast, managedPoolEnabled,
                progressLogInterval, maxFailedItems, defaultFailureHandler, preserveZeroInterval);
        this.streamingQueryTemplate = streamingQueryTemplate;
    }

    @Override
    public SnailMyBatisStreamingTemplate withFailFast(boolean failFast) {
        return new SnailMyBatisStreamingTemplate(
                getTaskExecutor(),
                getDefaultBatchSize(),
                getDefaultMaxConcurrency(),
                failFast,
                isManagedPoolEnabled(),
                getProgressLogInterval(),
                getMaxFailedItems(),
                this.streamingQueryTemplate,
                getDefaultFailureHandler(),
                true
        );
    }

    @Override
    public SnailMyBatisStreamingTemplate withProgressLogInterval(int progressLogInterval) {
        return new SnailMyBatisStreamingTemplate(
                getTaskExecutor(),
                getDefaultBatchSize(),
                getDefaultMaxConcurrency(),
                isFailFast(),
                isManagedPoolEnabled(),
                progressLogInterval,
                getMaxFailedItems(),
                this.streamingQueryTemplate,
                getDefaultFailureHandler(),
                true
        );
    }

    @Override
    public SnailMyBatisStreamingTemplate withMaxConcurrency(int maxConcurrency) {
        int resolved = maxConcurrency > 0 ? maxConcurrency : DEFAULT_MAX_CONCURRENCY;
        return new SnailMyBatisStreamingTemplate(
                getTaskExecutor(),
                getDefaultBatchSize(),
                resolved,
                isFailFast(),
                isManagedPoolEnabled(),
                getProgressLogInterval(),
                getMaxFailedItems(),
                this.streamingQueryTemplate,
                getDefaultFailureHandler(),
                true
        );
    }

    public StreamingQueryTemplate getStreamingQueryTemplate() {
        if (this.streamingQueryTemplate != null) {
            return this.streamingQueryTemplate;
        }
        try {
            this.streamingQueryTemplate = SpringUtil.getBean(StreamingQueryTemplate.class);
        } catch (Throwable ignored) {
        }
        return this.streamingQueryTemplate;
    }

    /**
     * 动态设置 MyBatis-Plus 流式查询模板
     *
     * @param streamingQueryTemplate 流式查询模板
     * @deprecated 直接修改共享单例字段存在并发安全隐患，请通过构造器或方法参数传入 {@link StreamingQueryTemplate}
     */
    @Deprecated(since = "1.2.0")
    public void setStreamingQueryTemplate(StreamingQueryTemplate streamingQueryTemplate) {
        this.streamingQueryTemplate = streamingQueryTemplate;
    }

    // ==================== MyBatis-Plus 流式游标消费 ====================

    public <T> BatchExecutionResult<Integer> executeStreaming(
            Supplier<Cursor<T>> cursorSupplier,
            int batchSize,
            Consumer<List<T>> batchConsumer) {
        return executeStreaming(getStreamingQueryTemplate(), cursorSupplier, batchSize, batchConsumer, null);
    }

    public <T> BatchExecutionResult<Integer> executeStreaming(
            Supplier<Cursor<T>> cursorSupplier,
            int batchSize,
            Consumer<List<T>> batchConsumer,
            BatchFailureHandler<Integer> failureHandler) {
        return executeStreaming(getStreamingQueryTemplate(), cursorSupplier, batchSize, batchConsumer, failureHandler);
    }

    public <T> BatchExecutionResult<Integer> executeStreaming(
            StreamingQueryTemplate customStreamingTemplate,
            Supplier<Cursor<T>> cursorSupplier,
            int batchSize,
            Consumer<List<T>> batchConsumer) {
        return executeStreaming(customStreamingTemplate, cursorSupplier, batchSize, batchConsumer, null);
    }

    /**
     * 联动指定 StreamingQueryTemplate 进行游标流式分批消费（支持显式 {@link BatchFailureHandler}）
     */
    public <T> BatchExecutionResult<Integer> executeStreaming(
            StreamingQueryTemplate customStreamingTemplate,
            Supplier<Cursor<T>> cursorSupplier,
            int batchSize,
            Consumer<List<T>> batchConsumer,
            BatchFailureHandler<Integer> failureHandler) {
        Objects.requireNonNull(cursorSupplier, "cursorSupplier 不能为 null");
        Objects.requireNonNull(batchConsumer, "batchConsumer 不能为 null");
        int effectiveBatchSize = batchSize > 0 ? batchSize : getDefaultBatchSize();

        long startTime = System.nanoTime();
        AtomicLong totalCount = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);
        AtomicInteger batchCount = new AtomicInteger(0);
        List<FailedBatch<Integer>> failedBatches = new ArrayList<>();
        ReentrantLock failedLock = new ReentrantLock();
        AtomicBoolean truncated = new AtomicBoolean(false);
        AtomicLong nextProgressThreshold = createProgressThreshold();

        Consumer<List<T>> dispatcher = batch -> {
            if (batch == null || batch.isEmpty()) {
                return;
            }
            int size = batch.size();
            long processed = totalCount.addAndGet(size);
            int batchIndex = batchCount.incrementAndGet();
            try {
                batchConsumer.accept(Collections.unmodifiableList(batch));
                long currentSuccess = successCount.addAndGet(size);
                emitProgressIfNeeded(processed, currentSuccess, failCount.get(), batchIndex, startTime, nextProgressThreshold);
            } catch (Throwable t) {
                long currentFail = failCount.addAndGet(size);
                FailedBatch<Integer> failed = new FailedBatch<>(batchIndex, batchIndex, null, batchIndex, size, t);
                recordFailedBatch(failedBatches, failedLock, truncated, getMaxFailedItems(), failed);
                notifyFailureHandler(failed, failureHandler);
                emitProgressIfNeeded(processed, successCount.get(), currentFail, batchIndex, startTime, nextProgressThreshold);
                if (isFailFast()) {
                    if (t instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new IllegalStateException("流式游标消费异常", t);
                }
                log.error("[SnailMyBatisStreamingTemplate] 流式游标批处理异常 [批次: {}]: {}", batchIndex, t.getMessage(), t);
            }
        };

        if (customStreamingTemplate != null) {
            customStreamingTemplate.forEachBatch(cursorSupplier, effectiveBatchSize, dispatcher);
        } else {
            try (Cursor<T> cursor = cursorSupplier.get()) {
                if (cursor != null) {
                    List<T> buffer = new ArrayList<>(effectiveBatchSize);
                    for (T item : cursor) {
                        buffer.add(item);
                        if (buffer.size() >= effectiveBatchSize) {
                            dispatcher.accept(buffer);
                            buffer = new ArrayList<>(effectiveBatchSize);
                        }
                    }
                    if (!buffer.isEmpty()) {
                        dispatcher.accept(buffer);
                    }
                }
            } catch (Exception e) {
                if (isFailFast()) {
                    if (e instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new IllegalStateException("MyBatis 游标遍历异常", e);
                }
                int errBatch = batchCount.incrementAndGet();
                FailedBatch<Integer> failed = new FailedBatch<>(errBatch, errBatch, null, errBatch, 0, e);
                recordFailedBatch(failedBatches, failedLock, truncated, getMaxFailedItems(), failed);
                notifyFailureHandler(failed, failureHandler);
                log.error("[SnailMyBatisStreamingTemplate] 游标遍历底层异常: {}", e.getMessage(), e);
            }
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
        return new BatchExecutionResult<>(
                totalCount.get(),
                successCount.get(),
                failCount.get(),
                batchCount.get(),
                duration,
                failedBatches,
                getMaxFailedItems(),
                truncated.get());
    }

    public <T> BatchExecutionResult<Integer> executeStreamingParallel(
            Supplier<Cursor<T>> cursorSupplier,
            int batchSize,
            int maxConcurrency,
            Consumer<List<T>> batchConsumer) {
        return executeStreamingParallel(getStreamingQueryTemplate(), cursorSupplier, batchSize, maxConcurrency, batchConsumer, null);
    }

    public <T> BatchExecutionResult<Integer> executeStreamingParallel(
            Supplier<Cursor<T>> cursorSupplier,
            int batchSize,
            int maxConcurrency,
            Consumer<List<T>> batchConsumer,
            BatchFailureHandler<Integer> failureHandler) {
        return executeStreamingParallel(getStreamingQueryTemplate(), cursorSupplier, batchSize, maxConcurrency, batchConsumer, failureHandler);
    }

    public <T> BatchExecutionResult<Integer> executeStreamingParallel(
            StreamingQueryTemplate customStreamingTemplate,
            Supplier<Cursor<T>> cursorSupplier,
            int batchSize,
            int maxConcurrency,
            Consumer<List<T>> batchConsumer) {
        return executeStreamingParallel(customStreamingTemplate, cursorSupplier, batchSize, maxConcurrency, batchConsumer, null);
    }

    /**
     * 联动指定 StreamingQueryTemplate 进行游标流式分批并发消费（支持显式 {@link BatchFailureHandler}）
     */
    public <T> BatchExecutionResult<Integer> executeStreamingParallel(
            StreamingQueryTemplate customStreamingTemplate,
            Supplier<Cursor<T>> cursorSupplier,
            int batchSize,
            int maxConcurrency,
            Consumer<List<T>> batchConsumer,
            BatchFailureHandler<Integer> failureHandler) {
        Objects.requireNonNull(cursorSupplier, "cursorSupplier 不能为 null");
        Objects.requireNonNull(batchConsumer, "batchConsumer 不能为 null");

        int effectiveBatchSize = batchSize > 0 ? batchSize : getDefaultBatchSize();
        int effectiveConcurrency = maxConcurrency > 0 ? maxConcurrency : getDefaultMaxConcurrency();
        Executor executor = getEffectiveExecutor();

        long startTime = System.nanoTime();
        AtomicLong totalCount = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);
        AtomicInteger batchCount = new AtomicInteger(0);
        List<FailedBatch<Integer>> failedBatches = new ArrayList<>();
        ReentrantLock failedLock = new ReentrantLock();
        AtomicBoolean truncated = new AtomicBoolean(false);
        AtomicLong nextProgressThreshold = createProgressThreshold();

        Semaphore semaphore = new Semaphore(effectiveConcurrency);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        AtomicReference<Throwable> firstError = new AtomicReference<>(null);
        ParentContextSnapshot parentContext = ParentContextSnapshot.capture();

        Consumer<List<T>> dispatcher = batch -> {
            if (batch == null || batch.isEmpty() || (isFailFast() && firstError.get() != null)) {
                return;
            }
            int size = batch.size();
            long processed = totalCount.addAndGet(size);
            int batchIndex = batchCount.incrementAndGet();

            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            List<T> immutableBatch = Collections.unmodifiableList(batch);
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> parentContext.runWithContext(() -> {
                try {
                    batchConsumer.accept(immutableBatch);
                    long currentSuccess = successCount.addAndGet(size);
                    emitProgressIfNeeded(processed, currentSuccess, failCount.get(), batchIndex, startTime, nextProgressThreshold);
                } catch (Throwable t) {
                    long currentFail = failCount.addAndGet(size);
                    FailedBatch<Integer> failed = new FailedBatch<>(batchIndex, batchIndex, null, batchIndex, size, t);
                    recordFailedBatch(failedBatches, failedLock, truncated, getMaxFailedItems(), failed);
                    notifyFailureHandler(failed, failureHandler);
                    firstError.compareAndSet(null, t);
                    log.error("[SnailMyBatisStreamingTemplate] 游标并发切片异常 [批次: {}]: {}", batchIndex, t.getMessage(), t);
                    emitProgressIfNeeded(processed, successCount.get(), currentFail, batchIndex, startTime, nextProgressThreshold);
                } finally {
                    semaphore.release();
                }
            }), executor);

            futures.add(future);
        };

        if (customStreamingTemplate != null) {
            customStreamingTemplate.forEachBatch(cursorSupplier, effectiveBatchSize, dispatcher);
        } else {
            try (Cursor<T> cursor = cursorSupplier.get()) {
                if (cursor != null) {
                    List<T> buffer = new ArrayList<>(effectiveBatchSize);
                    for (T item : cursor) {
                        if (isFailFast() && firstError.get() != null) {
                            break;
                        }
                        buffer.add(item);
                        if (buffer.size() >= effectiveBatchSize) {
                            dispatcher.accept(buffer);
                            buffer = new ArrayList<>(effectiveBatchSize);
                        }
                    }
                    if (!buffer.isEmpty() && (!isFailFast() || firstError.get() == null)) {
                        dispatcher.accept(buffer);
                    }
                }
            } catch (Exception e) {
                int errBatch = batchCount.incrementAndGet();
                FailedBatch<Integer> failed = new FailedBatch<>(errBatch, errBatch, null, errBatch, 0, e);
                recordFailedBatch(failedBatches, failedLock, truncated, getMaxFailedItems(), failed);
                notifyFailureHandler(failed, failureHandler);
                firstError.compareAndSet(null, e);
                log.error("[SnailMyBatisStreamingTemplate] 游标并发流式异常: {}", e.getMessage(), e);
            }
        }

        awaitAllFutures(futures, firstError);

        if (isFailFast() && firstError.get() != null) {
            Throwable t = firstError.get();
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("游标流式并发批处理执行失败: " + t.getMessage(), t);
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
        return new BatchExecutionResult<>(
                totalCount.get(),
                successCount.get(),
                failCount.get(),
                batchCount.get(),
                duration,
                snapshotFailedBatches(failedBatches, failedLock),
                getMaxFailedItems(),
                truncated.get());
    }
}
