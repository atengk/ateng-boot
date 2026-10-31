package io.github.atengk.boot.job.snail.template;

import com.aizuda.snailjob.common.log.SnailJobLog;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder.TenantSnapshot;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.SpringUtil;
import io.github.atengk.boot.job.snail.context.SnailJobContext;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import io.github.atengk.boot.job.snail.model.ShardingContext;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * SnailJob 分片流式批处理模板与大数据防 OOM 核心执行门面
 *
 * <p>面向千万级大表、分布式集群广播分片及 MapReduce 切片计算场景，提供基于主键/游标步长流式切片拉取与
 * MyBatis-Plus 流式游标对接能力，彻底消除深分页数据库慢查询与 JVM 堆内存 OOM 隐患。
 * 同时支持受管并发切片消费，内置基于信号量的背压流量保护，并全自动将父任务的四维上下文（MDC traceId、操作人、多租户、SnailJob 上下文）
 * 透传至并发子线程，在子任务终态时严格执行现场清理，防止线程复用污染与上下文泄漏。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailShardingBatchTemplate {

    private static final Logger log = LoggerFactory.getLogger(SnailShardingBatchTemplate.class);

    public static final int DEFAULT_BATCH_SIZE = 1000;
    public static final int DEFAULT_MAX_CONCURRENCY = 8;

    private final Executor taskExecutor;
    private final int defaultBatchSize;
    private final int defaultMaxConcurrency;
    private volatile boolean failFast = true;

    public SnailShardingBatchTemplate() {
        this(null, DEFAULT_BATCH_SIZE, DEFAULT_MAX_CONCURRENCY);
    }

    public SnailShardingBatchTemplate(Executor taskExecutor) {
        this(taskExecutor, DEFAULT_BATCH_SIZE, DEFAULT_MAX_CONCURRENCY);
    }

    public SnailShardingBatchTemplate(Executor taskExecutor, int defaultBatchSize, int defaultMaxConcurrency) {
        this.taskExecutor = taskExecutor;
        this.defaultBatchSize = defaultBatchSize > 0 ? defaultBatchSize : DEFAULT_BATCH_SIZE;
        this.defaultMaxConcurrency = defaultMaxConcurrency > 0 ? defaultMaxConcurrency : DEFAULT_MAX_CONCURRENCY;
    }

    public int getDefaultBatchSize() {
        return defaultBatchSize;
    }

    public int getDefaultMaxConcurrency() {
        return defaultMaxConcurrency;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    protected Executor getEffectiveExecutor() {
        return this.taskExecutor != null ? this.taskExecutor : ForkJoinPool.commonPool();
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

            try {
                batchConsumer.accept(Collections.unmodifiableList(chunk));
                successCount += chunkSize;
            } catch (Throwable t) {
                failCount += chunkSize;
                if (failFast) {
                    if (t instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new IllegalStateException("游标流式批处理发生未受检异常", t);
                }
                log.error("[SnailShardingBatchTemplate] 游标流式批处理执行异常 [批次: {}, 当前游标 ID: {}]: {}", batchCount, lastId, t.getMessage(), t);
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
     * 带有分片智能匹配的步长流式批处理（自动提取当前 SnailJob 分片）
     */
    public <T> BatchExecutionResult executeShardedStep(
            BiFunction<Long, Integer, List<T>> queryFunction,
            ToLongFunction<T> idExtractor,
            Consumer<List<T>> batchConsumer) {
        return executeShardedStep(ShardingContext.current(), null, this.defaultBatchSize, queryFunction, idExtractor, batchConsumer);
    }

    /**
     * 带有指定分片匹配的步长流式批处理
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
                log.warn("[SnailShardingBatchTemplate] 分片批处理线程检测到中断信号，提前终止流式批拉取 [当前游标 ID: {}]", lastId);
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
                    log.error("[SnailShardingBatchTemplate] 分片流式批处理执行异常 [批次: {}, 当前游标 ID: {}]: {}", batchCount, lastId, t.getMessage(), t);
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

    /**
     * 带有分片智能匹配的逐条流式消费
     */
    public <T> BatchExecutionResult executeShardedStepForEach(
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
     * @param initialId      初始游标 ID
     * @param batchSize      每批条数
     * @param maxConcurrency 最大并行度（背压上限）
     * @param queryFunction  游标查询函数
     * @param idExtractor    主键提取器
     * @param batchConsumer  切片消费逻辑
     * @param <T>            实体类型
     * @param <ID>           游标类型
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
        Executor executor = (this.taskExecutor != null) ? this.taskExecutor : ForkJoinPool.commonPool();

        long startTime = System.nanoTime();
        ID lastId = initialId;
        AtomicLong totalCount = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);
        AtomicInteger batchCount = new AtomicInteger(0);

        Semaphore semaphore = new Semaphore(effectiveConcurrency);
        List<CompletableFuture<Void>> futures = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> firstError = new AtomicReference<>(null);

        // 捕获父线程四维上下文
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        String currentTenant = TenantContextHolder.getTenantId();
        boolean ignoreTenant = TenantContextHolder.isIgnoreTenant();
        String currentUser = UserContextHolder.getExplicit();
        SnailJobContext parentJobContext = SnailJobContextHolder.get();

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
            totalCount.addAndGet(chunkSize);
            int currentBatch = batchCount.incrementAndGet();

            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[SnailShardingBatchTemplate] 等待并发许可被中断", e);
                break;
            }

            List<T> immutableChunk = Collections.unmodifiableList(chunk);
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                // 还原四维上下文
                Map<String, String> previousMdc = MDC.getCopyOfContextMap();
                if (mdcContext != null) {
                    MDC.setContextMap(mdcContext);
                } else {
                    MDC.clear();
                }

                TenantSnapshot tenantSnapshot = TenantContextHolder.capturePrevious();
                TenantContextHolder.setTenantId(currentTenant);
                TenantContextHolder.setIgnoreTenant(ignoreTenant);

                String previousUser = UserContextHolder.getExplicit();
                if (currentUser != null) {
                    UserContextHolder.set(currentUser);
                } else {
                    UserContextHolder.clear();
                }

                SnailJobContextHolder.set(parentJobContext);

                try {
                    batchConsumer.accept(immutableChunk);
                    successCount.addAndGet(chunkSize);
                } catch (Throwable t) {
                    failCount.addAndGet(chunkSize);
                    firstError.compareAndSet(null, t);
                    log.error("[SnailShardingBatchTemplate] 并发批处理子任务执行异常 [批次: {}]: {}", currentBatch, t.getMessage(), t);
                } finally {
                    semaphore.release();

                    // 清理现场
                    SnailJobContextHolder.remove();
                    TenantContextHolder.restore(tenantSnapshot);
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
            }, executor);

            futures.add(future);

            lastId = idExtractor.apply(chunk.get(chunkSize - 1));
            if (chunkSize < effectiveBatchSize) {
                break;
            }
        }

        // 等待所有在途并发切片完成
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(1, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("[SnailShardingBatchTemplate] 等待并发切片完成异常: {}", e.getMessage(), e);
            firstError.compareAndSet(null, e);
        }

        if (failFast && firstError.get() != null) {
            Throwable t = firstError.get();
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("并发分片批处理执行失败: " + t.getMessage(), t);
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
        return new BatchExecutionResult(totalCount.get(), successCount.get(), failCount.get(), batchCount.get(), duration);
    }

    /**
     * 带有分片过滤的并发批处理
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
        Executor executor = (this.taskExecutor != null) ? this.taskExecutor : ForkJoinPool.commonPool();

        long startTime = System.nanoTime();
        Long lastId = initialId;
        AtomicLong totalCount = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);
        AtomicInteger batchCount = new AtomicInteger(0);

        Semaphore semaphore = new Semaphore(effectiveConcurrency);
        List<CompletableFuture<Void>> futures = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> firstError = new AtomicReference<>(null);

        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        String currentTenant = TenantContextHolder.getTenantId();
        boolean ignoreTenant = TenantContextHolder.isIgnoreTenant();
        String currentUser = UserContextHolder.getExplicit();
        SnailJobContext parentJobContext = SnailJobContextHolder.get();

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
            List<T> matchedChunk = new ArrayList<>(chunkSize);
            for (T item : chunk) {
                long id = idExtractor.applyAsLong(item);
                if (sharding == null || sharding.match(id)) {
                    matchedChunk.add(item);
                }
            }

            if (!matchedChunk.isEmpty()) {
                int matchedSize = matchedChunk.size();
                totalCount.addAndGet(matchedSize);
                int currentBatch = batchCount.incrementAndGet();

                try {
                    semaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                List<T> immutableChunk = Collections.unmodifiableList(matchedChunk);
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    Map<String, String> previousMdc = MDC.getCopyOfContextMap();
                    if (mdcContext != null) {
                        MDC.setContextMap(mdcContext);
                    } else {
                        MDC.clear();
                    }

                    TenantSnapshot tenantSnapshot = TenantContextHolder.capturePrevious();
                    TenantContextHolder.setTenantId(currentTenant);
                    TenantContextHolder.setIgnoreTenant(ignoreTenant);

                    String previousUser = UserContextHolder.getExplicit();
                    if (currentUser != null) {
                        UserContextHolder.set(currentUser);
                    } else {
                        UserContextHolder.clear();
                    }

                    SnailJobContextHolder.set(parentJobContext);

                    try {
                        batchConsumer.accept(immutableChunk);
                        successCount.addAndGet(matchedSize);
                    } catch (Throwable t) {
                        failCount.addAndGet(matchedSize);
                        firstError.compareAndSet(null, t);
                        log.error("[SnailShardingBatchTemplate] 分片并发切片执行异常 [批次: {}]: {}", currentBatch, t.getMessage(), t);
                    } finally {
                        semaphore.release();
                        SnailJobContextHolder.remove();
                        TenantContextHolder.restore(tenantSnapshot);
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
                }, executor);

                futures.add(future);
            }

            lastId = idExtractor.applyAsLong(chunk.get(chunkSize - 1));
            if (chunkSize < effectiveBatchSize) {
                break;
            }
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(1, TimeUnit.HOURS);
        } catch (Exception e) {
            firstError.compareAndSet(null, e);
        }

        if (failFast && firstError.get() != null) {
            Throwable t = firstError.get();
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("分片并发批处理执行失败: " + t.getMessage(), t);
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
        return new BatchExecutionResult(totalCount.get(), successCount.get(), failCount.get(), batchCount.get(), duration);
    }
}
