package io.github.atengk.boot.job.xxl.template;

import com.xxl.job.core.context.XxlJobContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder.TenantSnapshot;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.SpringUtil;
import io.github.atengk.boot.job.xxl.model.ShardingContext;
import io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate;
import org.apache.ibatis.cursor.Cursor;
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
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * XXL-Job 分片流式批处理模板与大数据防 OOM 核心执行门面
 *
 * <p>面向千万级大表与分布式集群广播分片调度场景，提供基于主键/游标步长流式切片拉取与 MyBatis-Plus 流式游标对接能力，
 * 彻底消除深分页数据库慢查询与 JVM 堆内存 OOM 隐患。同时支持受管并发切片消费，内置基于信号量的背压流量保护，
 * 并全自动将父任务的四维上下文（MDC traceId、操作人、多租户）与 XXL-Job 日志镜像句柄透传至并发子线程，
 * 在子任务终态时严格执行现场清理，防止线程复用污染与上下文泄漏。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class XxlShardingBatchTemplate {

    private static final Logger log = LoggerFactory.getLogger(XxlShardingBatchTemplate.class);

    public static final int DEFAULT_BATCH_SIZE = 1000;
    public static final int DEFAULT_MAX_CONCURRENCY = 8;

    private final Executor taskExecutor;
    private final int defaultBatchSize;
    private final int defaultMaxConcurrency;
    private volatile boolean failFast = true;
    private volatile StreamingQueryTemplate streamingQueryTemplate;

    public XxlShardingBatchTemplate() {
        this(null, DEFAULT_BATCH_SIZE, DEFAULT_MAX_CONCURRENCY);
    }

    public XxlShardingBatchTemplate(Executor taskExecutor) {
        this(taskExecutor, DEFAULT_BATCH_SIZE, DEFAULT_MAX_CONCURRENCY);
    }

    public XxlShardingBatchTemplate(Executor taskExecutor, int defaultBatchSize, int defaultMaxConcurrency) {
        this.taskExecutor = taskExecutor;
        this.defaultBatchSize = defaultBatchSize > 0 ? defaultBatchSize : DEFAULT_BATCH_SIZE;
        this.defaultMaxConcurrency = defaultMaxConcurrency > 0 ? defaultMaxConcurrency : DEFAULT_MAX_CONCURRENCY;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
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

    // ==================== 1. 基于游标/步长流式分页 (单线程顺序消费) ====================

    /**
     * 基于游标步长流式分批拉取并消费（采用默认切片大小）
     *
     * @param queryFunction 游标查询函数：入参为 (lastId, batchSize)，返回当前切片数据
     * @param idExtractor   实体主键提取器：从实体中提取游标 ID
     * @param batchConsumer 分批消费逻辑
     * @param <T>           实体类型
     * @param <ID>          游标 ID 类型
     * @return 批处理执行汇总统计
     */
    public <T, ID extends Comparable<ID>> BatchExecutionResult executeStep(
            BiFunction<ID, Integer, List<T>> queryFunction,
            Function<T, ID> idExtractor,
            Consumer<List<T>> batchConsumer) {
        return executeStep(null, this.defaultBatchSize, queryFunction, idExtractor, batchConsumer);
    }

    /**
     * 基于游标步长流式分批拉取并消费（支持自定义初始游标与切片大小）
     *
     * @param initialId     初始游标 ID（为 null 时从头查询）
     * @param batchSize     每批拉取条数
     * @param queryFunction 游标查询函数：(lastId, batchSize) -> List<T>
     * @param idExtractor   实体主键提取器
     * @param batchConsumer 分批消费逻辑
     * @param <T>           实体类型
     * @param <ID>          游标 ID 类型
     * @return 批处理执行汇总统计
     */
    public <T, ID extends Comparable<ID>> BatchExecutionResult executeStep(
            ID initialId,
            int batchSize,
            BiFunction<ID, Integer, List<T>> queryFunction,
            Function<T, ID> idExtractor,
            Consumer<List<T>> batchConsumer) {
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

        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                log.warn("分片批处理线程检测到中断信号，提前终止流式批拉取 [当前游标 ID: {}]", lastId);
                break;
            }

            List<T> chunk = queryFunction.apply(lastId, effectiveBatchSize);
            if (chunk == null || chunk.isEmpty()) {
                break;
            }

            int chunkSize = chunk.size();
            totalCount += chunkSize;
            batchCount++;

            try {
                batchConsumer.accept(Collections.unmodifiableList(chunk));
                successCount += chunkSize;
            } catch (Throwable t) {
                failCount += chunkSize;
                log.error("分片流式批处理执行异常 [批次: {}, 当前游标 ID: {}]: {}", batchCount, lastId, t.getMessage(), t);
                if (failFast) {
                    if (t instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new IllegalStateException("分片流式批处理发生未受检异常", t);
                }
            }

            lastId = idExtractor.apply(chunk.get(chunkSize - 1));
            if (chunkSize < effectiveBatchSize) {
                break;
            }
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
        return new BatchExecutionResult(totalCount, successCount, failCount, batchCount, duration);
    }

    /**
     * 逐条消费步长游标数据
     */
    public <T, ID extends Comparable<ID>> BatchExecutionResult executeStepForEach(
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

    /**
     * 带有分片智能匹配的步长流式批处理（采用默认切片大小）
     */
    public <T> BatchExecutionResult executeShardedStep(
            ShardingContext sharding,
            BiFunction<Long, Integer, List<T>> queryFunction,
            ToLongFunction<T> idExtractor,
            Consumer<List<T>> batchConsumer) {
        return executeShardedStep(sharding, null, this.defaultBatchSize, queryFunction, idExtractor, batchConsumer);
    }

    /**
     * 带有分片智能匹配的步长流式批处理
     *
     * <p>仅将符合当前分片（sharding.match(id) == true）的数据切片交付给 batchConsumer，
     * 游标按底层原始拉取数据的最后一条推进，确保多节点调度无缝协同且不漏单。</p>
     *
     * @param sharding      当前节点分片上下文（若为 null 则不过滤）
     * @param initialId     初始游标 ID（Long）
     * @param batchSize     每批拉取大小
     * @param queryFunction 游标查询函数 (lastId, batchSize) -> List<T>
     * @param idExtractor   Long 数值主键提取器
     * @param batchConsumer 匹配分片的记录消费器
     * @param <T>           实体类型
     * @return 批处理执行汇总统计
     */
    public <T> BatchExecutionResult executeShardedStep(
            ShardingContext sharding,
            Long initialId,
            int batchSize,
            BiFunction<Long, Integer, List<T>> queryFunction,
            ToLongFunction<T> idExtractor,
            Consumer<List<T>> batchConsumer) {
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

        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                log.warn("分片批处理线程检测到中断信号，提前终止流式批拉取 [当前游标 ID: {}]", lastId);
                break;
            }

            List<T> chunk = queryFunction.apply(lastId, effectiveBatchSize);
            if (chunk == null || chunk.isEmpty()) {
                break;
            }

            int chunkSize = chunk.size();
            List<T> matchedChunk = new ArrayList<>(chunkSize);
            for (T item : chunk) {
                long id = idExtractor.applyAsLong(item);
                if (sharding == null || sharding.match(id)) {
                    matchedChunk.add(item);
                }
            }

            if (!matchedChunk.isEmpty()) {
                int matchedSize = matchedChunk.size();
                totalCount += matchedSize;
                batchCount++;
                try {
                    batchConsumer.accept(Collections.unmodifiableList(matchedChunk));
                    successCount += matchedSize;
                } catch (Throwable t) {
                    failCount += matchedSize;
                    log.error("分片流式批处理执行异常 [批次: {}, 当前游标 ID: {}]: {}", batchCount, lastId, t.getMessage(), t);
                    if (failFast) {
                        if (t instanceof RuntimeException re) {
                            throw re;
                        }
                        throw new IllegalStateException("分片流式批处理发生未受检异常", t);
                    }
                }
            }

            lastId = idExtractor.applyAsLong(chunk.get(chunkSize - 1));
            if (chunkSize < effectiveBatchSize) {
                break;
            }
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
        return new BatchExecutionResult(totalCount, successCount, failCount, batchCount, duration);
    }

    // ==================== 3. 并发切片批处理 (带背压流量保护与四维上下文透传) ====================

    /**
     * 基于游标步长拉取 + 受管线程池并发消费（采用默认切片与默认并发度）
     */
    public <T, ID extends Comparable<ID>> BatchExecutionResult executeStepParallel(
            ID initialId,
            BiFunction<ID, Integer, List<T>> queryFunction,
            Function<T, ID> idExtractor,
            Consumer<List<T>> batchConsumer) {
        return executeStepParallel(initialId, this.defaultBatchSize, this.defaultMaxConcurrency, queryFunction, idExtractor, batchConsumer);
    }

    /**
     * 基于游标步长拉取 + 受管线程池并发消费
     *
     * <p>引入基于信号量的背压流量保护，防止生产者过快拉取导致堆内存膨胀；
     * 自动捕获当前线程的四维上下文（MDC、User、Tenant、XxlJobContext）并透传至并发子线程，
     * 并在子线程执行终态严格执行无害清理，彻底消灭线程池污染。</p>
     *
     * @param initialId      初始游标 ID
     * @param batchSize      每批拉取大小
     * @param maxConcurrency 最大在途并发切片数（背压阈值）
     * @param queryFunction  游标查询函数
     * @param idExtractor    实体主键提取器
     * @param batchConsumer  分批并发消费逻辑
     * @param <T>            实体类型
     * @param <ID>           游标 ID 类型
     * @return 批处理执行汇总统计
     */
    public <T, ID extends Comparable<ID>> BatchExecutionResult executeStepParallel(
            ID initialId,
            int batchSize,
            int maxConcurrency,
            BiFunction<ID, Integer, List<T>> queryFunction,
            Function<T, ID> idExtractor,
            Consumer<List<T>> batchConsumer) {
        Objects.requireNonNull(queryFunction, "queryFunction 不能为 null");
        Objects.requireNonNull(idExtractor, "idExtractor 不能为 null");
        Objects.requireNonNull(batchConsumer, "batchConsumer 不能为 null");
        int effectiveBatchSize = batchSize > 0 ? batchSize : this.defaultBatchSize;
        int effectiveConcurrency = maxConcurrency > 0 ? maxConcurrency : this.defaultMaxConcurrency;

        long startTime = System.nanoTime();
        Executor executor = getEffectiveExecutor();
        Semaphore semaphore = new Semaphore(effectiveConcurrency);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        TaskSnapshot snapshot = TaskSnapshot.capture();

        ID lastId = initialId;
        AtomicLong totalCount = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);
        AtomicInteger batchCount = new AtomicInteger(0);
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        while (true) {
            if (failFast && firstError.get() != null) {
                log.warn("检测到前序并发切片执行失败，快速熔断终止后续拉取");
                break;
            }
            if (Thread.currentThread().isInterrupted()) {
                log.warn("分片批处理线程检测到中断信号，提前终止流式批拉取 [当前游标 ID: {}]", lastId);
                break;
            }

            List<T> chunk = queryFunction.apply(lastId, effectiveBatchSize);
            if (chunk == null || chunk.isEmpty()) {
                break;
            }

            int chunkSize = chunk.size();
            int currentBatchIndex = batchCount.incrementAndGet();
            totalCount.addAndGet(chunkSize);
            lastId = idExtractor.apply(chunk.get(chunkSize - 1));
            List<T> immutableBatch = Collections.unmodifiableList(new ArrayList<>(chunk));

            try {
                semaphore.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("并行批处理在等待背压信号量时被中断", ie);
            }

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    snapshot.run(() -> batchConsumer.accept(immutableBatch));
                    successCount.addAndGet(chunkSize);
                } catch (Throwable t) {
                    failCount.addAndGet(chunkSize);
                    firstError.compareAndSet(null, t);
                    log.error("并行分片批处理异常 [批次: {}]: {}", currentBatchIndex, t.getMessage(), t);
                    if (failFast) {
                        if (t instanceof RuntimeException re) {
                            throw re;
                        }
                        throw new IllegalStateException("并发切片消费异常", t);
                    }
                } finally {
                    semaphore.release();
                }
            }, executor);
            futures.add(future);

            if (chunkSize < effectiveBatchSize) {
                break;
            }
        }

        // 等待所有在途并发切片处理完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        if (failFast && firstError.get() != null) {
            Throwable t = firstError.get();
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("并行分片批处理发生异常: " + t.getMessage(), t);
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
        return new BatchExecutionResult(totalCount.get(), successCount.get(), failCount.get(), batchCount.get(), duration);
    }

    /**
     * 带有分片智能匹配的并发批处理
     */
    public <T> BatchExecutionResult executeShardedStepParallel(
            ShardingContext sharding,
            Long initialId,
            int batchSize,
            int maxConcurrency,
            BiFunction<Long, Integer, List<T>> queryFunction,
            ToLongFunction<T> idExtractor,
            Consumer<List<T>> batchConsumer) {
        Objects.requireNonNull(queryFunction, "queryFunction 不能为 null");
        Objects.requireNonNull(idExtractor, "idExtractor 不能为 null");
        Objects.requireNonNull(batchConsumer, "batchConsumer 不能为 null");
        int effectiveBatchSize = batchSize > 0 ? batchSize : this.defaultBatchSize;
        int effectiveConcurrency = maxConcurrency > 0 ? maxConcurrency : this.defaultMaxConcurrency;

        long startTime = System.nanoTime();
        Executor executor = getEffectiveExecutor();
        Semaphore semaphore = new Semaphore(effectiveConcurrency);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        TaskSnapshot snapshot = TaskSnapshot.capture();

        Long lastId = initialId;
        AtomicLong totalCount = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);
        AtomicInteger batchCount = new AtomicInteger(0);
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        while (true) {
            if (failFast && firstError.get() != null) {
                log.warn("检测到前序并发切片执行失败，快速熔断终止后续拉取");
                break;
            }
            if (Thread.currentThread().isInterrupted()) {
                log.warn("分片批处理线程检测到中断信号，提前终止流式批拉取 [当前游标 ID: {}]", lastId);
                break;
            }

            List<T> chunk = queryFunction.apply(lastId, effectiveBatchSize);
            if (chunk == null || chunk.isEmpty()) {
                break;
            }

            int chunkSize = chunk.size();
            lastId = idExtractor.applyAsLong(chunk.get(chunkSize - 1));

            List<T> matchedChunk = new ArrayList<>(chunkSize);
            for (T item : chunk) {
                long id = idExtractor.applyAsLong(item);
                if (sharding == null || sharding.match(id)) {
                    matchedChunk.add(item);
                }
            }

            if (!matchedChunk.isEmpty()) {
                int matchedSize = matchedChunk.size();
                int currentBatchIndex = batchCount.incrementAndGet();
                totalCount.addAndGet(matchedSize);
                List<T> immutableBatch = Collections.unmodifiableList(matchedChunk);

                try {
                    semaphore.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("并行批处理在等待背压信号量时被中断", ie);
                }

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        snapshot.run(() -> batchConsumer.accept(immutableBatch));
                        successCount.addAndGet(matchedSize);
                    } catch (Throwable t) {
                        failCount.addAndGet(matchedSize);
                        firstError.compareAndSet(null, t);
                        log.error("并行分片批处理异常 [批次: {}]: {}", currentBatchIndex, t.getMessage(), t);
                        if (failFast) {
                            if (t instanceof RuntimeException re) {
                                throw re;
                            }
                            throw new IllegalStateException("并发切片消费异常", t);
                        }
                    } finally {
                        semaphore.release();
                    }
                }, executor);
                futures.add(future);
            }

            if (chunkSize < effectiveBatchSize) {
                break;
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        if (failFast && firstError.get() != null) {
            Throwable t = firstError.get();
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("并行分片批处理发生异常: " + t.getMessage(), t);
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
        return new BatchExecutionResult(totalCount.get(), successCount.get(), failCount.get(), batchCount.get(), duration);
    }

    // ==================== 4. 联动 MyBatis-Plus 流式游标 (StreamingQueryTemplate) ====================

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
        return executeStreaming(getStreamingQueryTemplate(), cursorSupplier, batchSize, batchConsumer);
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
        Objects.requireNonNull(cursorSupplier, "cursorSupplier 不能为 null");
        Objects.requireNonNull(batchConsumer, "batchConsumer 不能为 null");
        int effectiveBatchSize = batchSize > 0 ? batchSize : this.defaultBatchSize;

        long startTime = System.nanoTime();
        AtomicLong totalCount = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);
        AtomicInteger batchCount = new AtomicInteger(0);

        Consumer<List<T>> dispatcher = batch -> {
            if (batch == null || batch.isEmpty()) {
                return;
            }
            int size = batch.size();
            totalCount.addAndGet(size);
            int batchIndex = batchCount.incrementAndGet();
            try {
                batchConsumer.accept(Collections.unmodifiableList(batch));
                successCount.addAndGet(size);
            } catch (Throwable t) {
                failCount.addAndGet(size);
                log.error("流式游标批处理异常 [批次: {}]: {}", batchIndex, t.getMessage(), t);
                if (failFast) {
                    if (t instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new IllegalStateException("流式游标消费异常", t);
                }
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
        return new BatchExecutionResult(totalCount.get(), successCount.get(), failCount.get(), batchCount.get(), duration);
    }

    /**
     * 联动 MyBatis-Plus 流式游标并发批处理
     */
    public <T> BatchExecutionResult executeStreamingParallel(
            Supplier<Cursor<T>> cursorSupplier,
            int batchSize,
            int maxConcurrency,
            Consumer<List<T>> batchConsumer) {
        return executeStreamingParallel(getStreamingQueryTemplate(), cursorSupplier, batchSize, maxConcurrency, batchConsumer);
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
        Objects.requireNonNull(cursorSupplier, "cursorSupplier 不能为 null");
        Objects.requireNonNull(batchConsumer, "batchConsumer 不能为 null");
        int effectiveBatchSize = batchSize > 0 ? batchSize : this.defaultBatchSize;
        int effectiveConcurrency = maxConcurrency > 0 ? maxConcurrency : this.defaultMaxConcurrency;

        long startTime = System.nanoTime();
        Executor executor = getEffectiveExecutor();
        Semaphore semaphore = new Semaphore(effectiveConcurrency);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        TaskSnapshot snapshot = TaskSnapshot.capture();

        AtomicLong totalCount = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);
        AtomicInteger batchCount = new AtomicInteger(0);
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        Consumer<List<T>> parallelDispatcher = chunk -> {
            if (chunk == null || chunk.isEmpty()) {
                return;
            }
            if (failFast && firstError.get() != null) {
                return;
            }
            int chunkSize = chunk.size();
            int batchIndex = batchCount.incrementAndGet();
            totalCount.addAndGet(chunkSize);
            List<T> immutableBatch = Collections.unmodifiableList(new ArrayList<>(chunk));

            try {
                semaphore.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("并行流式调度在获取背压信号量时被中断", ie);
            }

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    snapshot.run(() -> batchConsumer.accept(immutableBatch));
                    successCount.addAndGet(chunkSize);
                } catch (Throwable t) {
                    failCount.addAndGet(chunkSize);
                    firstError.compareAndSet(null, t);
                    log.error("并行流式游标消费异常 [批次: {}]: {}", batchIndex, t.getMessage(), t);
                    if (failFast) {
                        if (t instanceof RuntimeException re) {
                            throw re;
                        }
                        throw new IllegalStateException("并发切片消费异常", t);
                    }
                } finally {
                    semaphore.release();
                }
            }, executor);
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

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        if (failFast && firstError.get() != null) {
            Throwable t = firstError.get();
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("并行流式消费发生异常: " + t.getMessage(), t);
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
        return new BatchExecutionResult(totalCount.get(), successCount.get(), failCount.get(), batchCount.get(), duration);
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

    /**
     * 线程执行上下文快照
     *
     * <p>捕获父任务执行线程的 MDC traceId、操作人、多租户上下文与 XXL-Job 日志镜像句柄，
     * 并在子线程执行时严格透传并在执行终态安全清理现场，杜绝线程池复用污染与上下文泄露。</p>
     */
    public static class TaskSnapshot {

        private final Map<String, String> mdcContext;
        private final String user;
        private final TenantSnapshot tenantSnapshot;
        private final XxlJobContext xxlJobContext;

        public TaskSnapshot(Map<String, String> mdcContext, String user, TenantSnapshot tenantSnapshot, XxlJobContext xxlJobContext) {
            this.mdcContext = mdcContext;
            this.user = user;
            this.tenantSnapshot = tenantSnapshot;
            this.xxlJobContext = xxlJobContext;
        }

        /**
         * 捕获当前线程的四维上下文与 XXL-Job 调度上下文快照
         *
         * @return 上下文快照对象
         */
        public static TaskSnapshot capture() {
            return new TaskSnapshot(
                    MDC.getCopyOfContextMap(),
                    UserContextHolder.getExplicit(),
                    TenantContextHolder.capturePrevious(),
                    XxlJobContext.getXxlJobContext()
            );
        }

        /**
         * 在当前（子）线程上挂载快照上下文执行任务，并在 finally 中严格恢复现场
         *
         * @param task 待执行任务逻辑
         */
        public void run(Runnable task) {
            Map<String, String> prevMdc = MDC.getCopyOfContextMap();
            String prevUser = UserContextHolder.getExplicit();
            TenantSnapshot prevTenant = TenantContextHolder.capturePrevious();
            XxlJobContext prevJobContext = XxlJobContext.getXxlJobContext();

            try {
                if (mdcContext != null) {
                    MDC.setContextMap(mdcContext);
                } else {
                    MDC.clear();
                }
                if (user != null) {
                    UserContextHolder.set(user);
                } else {
                    UserContextHolder.clear();
                }
                TenantContextHolder.restore(tenantSnapshot);
                if (xxlJobContext != null) {
                    XxlJobContext.setXxlJobContext(xxlJobContext);
                }

                task.run();
            } finally {
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
                TenantContextHolder.restore(prevTenant);
                XxlJobContext.setXxlJobContext(prevJobContext);
            }
        }

        public Map<String, String> getMdcContext() {
            return mdcContext;
        }

        public String getUser() {
            return user;
        }

        public TenantSnapshot getTenantSnapshot() {
            return tenantSnapshot;
        }

        public XxlJobContext getXxlJobContext() {
            return xxlJobContext;
        }
    }

}
