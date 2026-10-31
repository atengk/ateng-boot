package io.github.atengk.boot.job.snail.template;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder.TenantSnapshot;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.SpringUtil;
import io.github.atengk.boot.job.snail.context.SnailJobContext;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * SnailJob 联动 MyBatis-Plus 流式游标批处理模板
 *
 * <p>继承自 {@link SnailShardingBatchTemplate}，专门在具备持久层 MyBatis-Plus 依赖的环境下，
 * 对接 {@link StreamingQueryTemplate} 与 MyBatis 原生 {@link Cursor} 流式查询，
 * 提供单线程游标分批拉取与受管多线程并发消费能力。</p>
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

    public SnailMyBatisStreamingTemplate(Executor taskExecutor, int defaultBatchSize, int defaultMaxConcurrency, StreamingQueryTemplate streamingQueryTemplate) {
        super(taskExecutor, defaultBatchSize, defaultMaxConcurrency);
        this.streamingQueryTemplate = streamingQueryTemplate;
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
        return executeStreaming(getStreamingQueryTemplate(), cursorSupplier, batchSize, batchConsumer);
    }

    /**
     * 联动指定 StreamingQueryTemplate 进行游标流式分批消费（顺序消费）
     *
     * @param customStreamingTemplate 自定义或注入的 StreamingQueryTemplate
     * @param cursorSupplier         游标提供者
     * @param batchSize              分批大小
     * @param batchConsumer          分批消费逻辑
     * @param <T>                    实体类型
     * @return 批处理执行汇总统计
     */
    public <T> BatchExecutionResult executeStreaming(
            StreamingQueryTemplate customStreamingTemplate,
            Supplier<Cursor<T>> cursorSupplier,
            int batchSize,
            Consumer<List<T>> batchConsumer) {
        Objects.requireNonNull(cursorSupplier, "cursorSupplier 不能为 null");
        Objects.requireNonNull(batchConsumer, "batchConsumer 不能为 null");
        int effectiveBatchSize = batchSize > 0 ? batchSize : getDefaultBatchSize();

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
                log.error("[SnailMyBatisStreamingTemplate] 游标遍历底层异常: {}", e.getMessage(), e);
            }
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
        return new BatchExecutionResult(totalCount.get(), successCount.get(), failCount.get(), batchCount.get(), duration);
    }

    /**
     * 联动 MyBatis-Plus 流式游标分批并发消费（受管线程池 + 信号量背压）
     */
    public <T> BatchExecutionResult executeStreamingParallel(
            Supplier<Cursor<T>> cursorSupplier,
            int batchSize,
            int maxConcurrency,
            Consumer<List<T>> batchConsumer) {
        return executeStreamingParallel(getStreamingQueryTemplate(), cursorSupplier, batchSize, maxConcurrency, batchConsumer);
    }

    /**
     * 联动指定 StreamingQueryTemplate 进行游标流式分批并发消费
     */
    public <T> BatchExecutionResult executeStreamingParallel(
            StreamingQueryTemplate customStreamingTemplate,
            Supplier<Cursor<T>> cursorSupplier,
            int batchSize,
            int maxConcurrency,
            Consumer<List<T>> batchConsumer) {
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

        Semaphore semaphore = new Semaphore(effectiveConcurrency);
        List<CompletableFuture<Void>> futures = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> firstError = new AtomicReference<>(null);

        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        String currentTenant = TenantContextHolder.getTenantId();
        boolean ignoreTenant = TenantContextHolder.isIgnoreTenant();
        String currentUser = UserContextHolder.getExplicit();
        SnailJobContext parentJobContext = SnailJobContextHolder.get();

        Consumer<List<T>> dispatcher = batch -> {
            if (batch == null || batch.isEmpty() || (isFailFast() && firstError.get() != null)) {
                return;
            }
            int size = batch.size();
            totalCount.addAndGet(size);
            int batchIndex = batchCount.incrementAndGet();

            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            List<T> immutableBatch = Collections.unmodifiableList(batch);
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
                    batchConsumer.accept(immutableBatch);
                    successCount.addAndGet(size);
                } catch (Throwable t) {
                    failCount.addAndGet(size);
                    firstError.compareAndSet(null, t);
                    log.error("[SnailMyBatisStreamingTemplate] 游标并发切片异常 [批次: {}]: {}", batchIndex, t.getMessage(), t);
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
                firstError.compareAndSet(null, e);
                log.error("[SnailMyBatisStreamingTemplate] 游标并发流式异常: {}", e.getMessage(), e);
            }
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(1, TimeUnit.HOURS);
        } catch (Exception e) {
            firstError.compareAndSet(null, e);
        }

        if (isFailFast() && firstError.get() != null) {
            Throwable t = firstError.get();
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("游标流式并发批处理执行失败: " + t.getMessage(), t);
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
        return new BatchExecutionResult(totalCount.get(), successCount.get(), failCount.get(), batchCount.get(), duration);
    }
}
