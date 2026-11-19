package io.github.atengk.boot.job.xxl.template;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * XXL-Job 联动 MyBatis-Plus 流式游标批处理模板
 *
 * <p>继承自 {@link XxlShardingBatchTemplate}，专门在具备持久层 MyBatis-Plus 依赖的环境下，
 * 对接 {@link StreamingQueryTemplate} 与 MyBatis 原生 {@link Cursor} 流式查询，
 * 提供单线程游标分批拉取与受管多线程并发消费能力。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class XxlMyBatisStreamingTemplate extends XxlShardingBatchTemplate {

    private static final Logger log = LoggerFactory.getLogger(XxlMyBatisStreamingTemplate.class);

    private volatile StreamingQueryTemplate streamingQueryTemplate;

    public XxlMyBatisStreamingTemplate() {
        super();
    }

    public XxlMyBatisStreamingTemplate(Executor taskExecutor) {
        super(taskExecutor);
    }

    public XxlMyBatisStreamingTemplate(Executor taskExecutor, int defaultBatchSize, int defaultMaxConcurrency) {
        super(taskExecutor, defaultBatchSize, defaultMaxConcurrency);
    }

    public XxlMyBatisStreamingTemplate(Executor taskExecutor, int defaultBatchSize, int defaultMaxConcurrency, int progressLogInterval) {
        super(taskExecutor, defaultBatchSize, defaultMaxConcurrency, progressLogInterval);
    }

    /**
     * 构造具备全局默认失败切片回调的 MyBatis 流式游标批处理模板
     *
     * @param taskExecutor          受管并发线程池
     * @param defaultBatchSize      默认切片大小
     * @param defaultMaxConcurrency 默认最大并发切片数
     * @param progressLogInterval   默认进度日志打卡步长
     * @param defaultFailureHandler 全局默认失败切片回调 SPI（可为 null）
     */
    public XxlMyBatisStreamingTemplate(
            Executor taskExecutor,
            int defaultBatchSize,
            int defaultMaxConcurrency,
            int progressLogInterval,
            BatchFailureHandler<?> defaultFailureHandler) {
        super(taskExecutor, defaultBatchSize, defaultMaxConcurrency, progressLogInterval, defaultFailureHandler);
    }

    public XxlMyBatisStreamingTemplate(Executor taskExecutor, int defaultBatchSize, int defaultMaxConcurrency, StreamingQueryTemplate streamingQueryTemplate) {
        super(taskExecutor, defaultBatchSize, defaultMaxConcurrency);
        this.streamingQueryTemplate = streamingQueryTemplate;
    }

    /**
     * 构造绑定指定 {@link StreamingQueryTemplate} 与全局默认 {@link BatchFailureHandler} 的流式批处理模板
     *
     * @param taskExecutor           受管并发线程池
     * @param defaultBatchSize       默认切片大小
     * @param defaultMaxConcurrency  默认最大并发切片数
     * @param progressLogInterval    默认进度日志打卡步长
     * @param streamingQueryTemplate MyBatis-Plus 游标流式查询模板
     * @param defaultFailureHandler  全局默认失败切片回调 SPI（可为 null）
     */
    public XxlMyBatisStreamingTemplate(
            Executor taskExecutor,
            int defaultBatchSize,
            int defaultMaxConcurrency,
            int progressLogInterval,
            StreamingQueryTemplate streamingQueryTemplate,
            BatchFailureHandler<?> defaultFailureHandler) {
        super(taskExecutor, defaultBatchSize, defaultMaxConcurrency, progressLogInterval, defaultFailureHandler);
        this.streamingQueryTemplate = streamingQueryTemplate;
    }

    /**
     * 内部轻量派生视图构造器，完整保留策略快照及流式查询模板引用
     */
    protected XxlMyBatisStreamingTemplate(
            Executor taskExecutor,
            int defaultBatchSize,
            int defaultMaxConcurrency,
            int progressLogInterval,
            boolean failFast,
            StreamingQueryTemplate streamingQueryTemplate,
            BatchFailureHandler<?> defaultFailureHandler) {
        super(taskExecutor, defaultBatchSize, defaultMaxConcurrency, progressLogInterval, failFast, defaultFailureHandler);
        this.streamingQueryTemplate = streamingQueryTemplate;
    }

    @Override
    public XxlMyBatisStreamingTemplate withFailFast(boolean failFast) {
        return new XxlMyBatisStreamingTemplate(
                getTaskExecutor(),
                getDefaultBatchSize(),
                getDefaultMaxConcurrency(),
                getProgressLogInterval(),
                failFast,
                this.streamingQueryTemplate,
                getDefaultFailureHandler()
        );
    }

    @Override
    public XxlMyBatisStreamingTemplate withProgressLogInterval(int progressLogInterval) {
        return new XxlMyBatisStreamingTemplate(
                getTaskExecutor(),
                getDefaultBatchSize(),
                getDefaultMaxConcurrency(),
                progressLogInterval,
                isFailFast(),
                this.streamingQueryTemplate,
                getDefaultFailureHandler()
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

    public void setStreamingQueryTemplate(StreamingQueryTemplate streamingQueryTemplate) {
        this.streamingQueryTemplate = streamingQueryTemplate;
    }

    // ==================== MyBatis-Plus 流式游标消费 ====================

    /**
     * 联动 MyBatis-Plus 流式游标分批流式消费（顺序消费）
     *
     * @param cursorSupplier 游标提供者
     * @param batchSize      分批大小
     * @param batchConsumer  分批消费逻辑
     * @param <T>            实体类型
     * @return 批处理执行汇总统计
     */
    public <T> BatchExecutionResult executeStreaming(
            Supplier<Cursor<T>> cursorSupplier,
            int batchSize,
            Consumer<List<T>> batchConsumer) {
        return executeStreaming(getStreamingQueryTemplate(), cursorSupplier, batchSize, batchConsumer, null);
    }

    /**
     * 联动 MyBatis-Plus 流式游标分批流式消费（带失败补偿回调）
     *
     * @param cursorSupplier 游标提供者
     * @param batchSize      分批大小
     * @param batchConsumer  分批消费逻辑
     * @param failureHandler 失败切片补偿回调
     * @param <T>            实体类型
     * @return 批处理执行汇总统计
     */
    public <T> BatchExecutionResult executeStreaming(
            Supplier<Cursor<T>> cursorSupplier,
            int batchSize,
            Consumer<List<T>> batchConsumer,
            Consumer<FailedBatch<T>> failureHandler) {
        return executeStreaming(getStreamingQueryTemplate(), cursorSupplier, batchSize, batchConsumer, failureHandler);
    }

    /**
     * 联动指定 StreamingQueryTemplate 进行游标流式分批消费（顺序消费）
     *
     * @param streamingQueryTemplate MyBatis-Plus 游标流式模板
     * @param cursorSupplier         游标提供者
     * @param batchSize              分批大小
     * @param batchConsumer          分批消费逻辑
     * @param <T>                    实体类型
     * @return 批处理执行汇总统计
     */
    public <T> BatchExecutionResult executeStreaming(
            StreamingQueryTemplate streamingQueryTemplate,
            Supplier<Cursor<T>> cursorSupplier,
            int batchSize,
            Consumer<List<T>> batchConsumer) {
        return executeStreaming(streamingQueryTemplate, cursorSupplier, batchSize, batchConsumer, null);
    }

    /**
     * 联动指定 StreamingQueryTemplate 进行游标流式分批消费（支持失败补偿回调）
     *
     * @param streamingQueryTemplate MyBatis-Plus 游标流式模板
     * @param cursorSupplier         游标提供者
     * @param batchSize              分批大小
     * @param batchConsumer          分批消费逻辑
     * @param failureHandler         失败切片补偿回调（可为 null）
     * @param <T>                    实体类型
     * @return 批处理执行汇总统计
     */
    public <T> BatchExecutionResult executeStreaming(
            StreamingQueryTemplate streamingQueryTemplate,
            Supplier<Cursor<T>> cursorSupplier,
            int batchSize,
            Consumer<List<T>> batchConsumer,
            Consumer<FailedBatch<T>> failureHandler) {
        Objects.requireNonNull(cursorSupplier, "cursorSupplier 不能为 null");
        Objects.requireNonNull(batchConsumer, "batchConsumer 不能为 null");
        int effectiveBatchSize = batchSize > 0 ? batchSize : getDefaultBatchSize();

        long startTime = System.nanoTime();
        AtomicLong totalCount = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);
        AtomicInteger batchCount = new AtomicInteger(0);
        List<Object> failedItems = new ArrayList<>();
        boolean[] truncatedFailures = new boolean[]{false};
        AtomicLong lastReportedCount = new AtomicLong(0);

        Consumer<List<T>> dispatcher = batch -> {
            if (batch == null || batch.isEmpty()) {
                return;
            }
            int size = batch.size();
            long currentTotal = totalCount.addAndGet(size);
            int batchIndex = batchCount.incrementAndGet();
            try {
                batchConsumer.accept(Collections.unmodifiableList(batch));
                successCount.addAndGet(size);
            } catch (Throwable t) {
                failCount.addAndGet(size);

                if (XxlShardingBatchTemplate.collectFailedItems(failedItems, batch)) {
                    truncatedFailures[0] = true;
                }

                handleFailedBatch(failureHandler, new FailedBatch<>(batch, batchIndex, null, t), "流式游标失败回调执行异常");

                if (isFailFast()) {
                    if (t instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new IllegalStateException("流式游标消费异常", t);
                }
                log.error("流式游标批处理异常 [批次: {}]: {}", batchIndex, t.getMessage(), t);
            }

            int interval = getProgressLogInterval();
            if (interval > 0 && (currentTotal - lastReportedCount.get() >= interval)) {
                lastReportedCount.set(currentTotal);
                reportProgress(currentTotal, successCount.get(), failCount.get(), batchCount.get(), startTime);
            }
        };

        if (streamingQueryTemplate != null) {
            streamingQueryTemplate.forEachBatch(cursorSupplier, effectiveBatchSize, dispatcher);
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
            } catch (Exception ex) {
                throw new IllegalStateException("游标流式处理执行失败: " + ex.getMessage(), ex);
            }
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
        return new BatchExecutionResult(totalCount.get(), successCount.get(), failCount.get(), batchCount.get(), duration, failedItems, truncatedFailures[0]);
    }

    /**
     * 联动 MyBatis-Plus 流式游标并发批处理
     */
    public <T> BatchExecutionResult executeStreamingParallel(
            Supplier<Cursor<T>> cursorSupplier,
            int batchSize,
            int maxConcurrency,
            Consumer<List<T>> batchConsumer) {
        return executeStreamingParallel(getStreamingQueryTemplate(), cursorSupplier, batchSize, maxConcurrency, batchConsumer, null);
    }

    /**
     * 联动 MyBatis-Plus 流式游标并发批处理（带失败补偿回调）
     */
    public <T> BatchExecutionResult executeStreamingParallel(
            Supplier<Cursor<T>> cursorSupplier,
            int batchSize,
            int maxConcurrency,
            Consumer<List<T>> batchConsumer,
            Consumer<FailedBatch<T>> failureHandler) {
        return executeStreamingParallel(getStreamingQueryTemplate(), cursorSupplier, batchSize, maxConcurrency, batchConsumer, failureHandler);
    }

    /**
     * 联动指定 StreamingQueryTemplate 游标流式拉取 + 受管线程池并发消费
     */
    public <T> BatchExecutionResult executeStreamingParallel(
            StreamingQueryTemplate streamingQueryTemplate,
            Supplier<Cursor<T>> cursorSupplier,
            int batchSize,
            int maxConcurrency,
            Consumer<List<T>> batchConsumer) {
        return executeStreamingParallel(streamingQueryTemplate, cursorSupplier, batchSize, maxConcurrency, batchConsumer, null);
    }

    /**
     * 联动指定 StreamingQueryTemplate 游标流式拉取 + 受管线程池并发消费（支持失败补偿回调）
     */
    public <T> BatchExecutionResult executeStreamingParallel(
            StreamingQueryTemplate streamingQueryTemplate,
            Supplier<Cursor<T>> cursorSupplier,
            int batchSize,
            int maxConcurrency,
            Consumer<List<T>> batchConsumer,
            Consumer<FailedBatch<T>> failureHandler) {
        Objects.requireNonNull(cursorSupplier, "cursorSupplier 不能为 null");
        Objects.requireNonNull(batchConsumer, "batchConsumer 不能为 null");
        int effectiveBatchSize = batchSize > 0 ? batchSize : getDefaultBatchSize();
        int effectiveConcurrency = maxConcurrency > 0 ? maxConcurrency : getDefaultMaxConcurrency();

        long startTime = System.nanoTime();
        boolean effectiveFailFast = isFailFast();
        Executor executor = getEffectiveExecutor();
        Semaphore semaphore = new Semaphore(effectiveConcurrency);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        TaskSnapshot snapshot = TaskSnapshot.capture();

        AtomicLong totalCount = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);
        AtomicInteger batchCount = new AtomicInteger(0);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        List<Object> failedItems = new ArrayList<>();
        ReentrantLock failedItemsLock = new ReentrantLock();
        AtomicBoolean truncatedFailures = new AtomicBoolean(false);
        AtomicLong processedCount = new AtomicLong(0);
        AtomicLong lastReportedCount = new AtomicLong(0);

        Consumer<List<T>> parallelDispatcher = chunk -> {
            if (chunk == null || chunk.isEmpty()) {
                return;
            }
            if (effectiveFailFast && firstError.get() != null) {
                return;
            }
            int chunkSize = chunk.size();
            int batchIndex = batchCount.incrementAndGet();
            totalCount.addAndGet(chunkSize);
            List<T> immutableBatch = Collections.unmodifiableList(new ArrayList<>(chunk));

            try {
                if (!semaphore.tryAcquire(5, TimeUnit.MINUTES)) {
                    throw new IllegalStateException("并行流式调度获取背压信号量超时 (时限: 5分钟)");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("并行流式调度在获取背压信号量时被中断", ie);
            }

            CompletableFuture<Void> future;
            try {
                future = CompletableFuture.runAsync(() -> {
                    try {
                        snapshot.run(() -> batchConsumer.accept(immutableBatch));
                        successCount.addAndGet(chunkSize);
                    } catch (Throwable t) {
                        failCount.addAndGet(chunkSize);
                        firstError.compareAndSet(null, t);

                        recordFailedItems(failedItems, failedItemsLock, truncatedFailures, immutableBatch);

                        handleFailedBatch(failureHandler, new FailedBatch<>(immutableBatch, batchIndex, null, t), "并发流式游标失败回调执行异常");

                        if (!effectiveFailFast) {
                            log.error("并行流式游标消费异常 [批次: {}]: {}", batchIndex, t.getMessage(), t);
                        }
                        if (effectiveFailFast) {
                            if (t instanceof RuntimeException re) {
                                throw re;
                            }
                            throw new IllegalStateException("并发切片消费异常", t);
                        }
                    } finally {
                        semaphore.release();
                        long currentProcessed = processedCount.addAndGet(chunkSize);
                        checkAndReportProgress(currentProcessed, lastReportedCount, successCount.get(), failCount.get(), batchCount.get(), startTime);
                    }
                }, executor);
            } catch (Throwable t) {
                semaphore.release();
                throw t;
            }
            futures.add(future);
        };

        if (streamingQueryTemplate != null) {
            streamingQueryTemplate.forEachBatch(cursorSupplier, effectiveBatchSize, parallelDispatcher);
        } else {
            try (Cursor<T> cursor = cursorSupplier.get()) {
                if (cursor != null) {
                    List<T> buffer = new ArrayList<>(effectiveBatchSize);
                    for (T item : cursor) {
                        buffer.add(item);
                        if (buffer.size() >= effectiveBatchSize) {
                            parallelDispatcher.accept(buffer);
                            buffer = new ArrayList<>(effectiveBatchSize);
                        }
                    }
                    if (!buffer.isEmpty()) {
                        parallelDispatcher.accept(buffer);
                    }
                }
            } catch (Exception ex) {
                throw new IllegalStateException("游标流式拉取失败: " + ex.getMessage(), ex);
            }
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Throwable t) {
            if (effectiveFailFast) {
                Throwable cause = firstError.get() != null ? firstError.get() : (t.getCause() != null ? t.getCause() : t);
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException("并行流式消费聚合等待异常: " + cause.getMessage(), cause);
            }
            log.error("并行流式消费聚合等待异常: {}", t.getMessage(), t);
        }

        if (effectiveFailFast && firstError.get() != null) {
            Throwable t = firstError.get();
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("并行流式消费发生异常: " + t.getMessage(), t);
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
        return new BatchExecutionResult(
                totalCount.get(),
                successCount.get(),
                failCount.get(),
                batchCount.get(),
                duration,
                snapshotFailedItems(failedItems, failedItemsLock),
                truncatedFailures.get()
        );
    }
}
