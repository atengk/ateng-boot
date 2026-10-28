package io.github.atengk.boot.search.bulk;

import co.elastic.clients.elasticsearch.core.BulkRequest;
import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import io.github.atengk.boot.core.util.CollectionUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.search.enums.SearchResultCode;
import io.github.atengk.boot.search.exception.SearchException;
import io.github.atengk.boot.search.model.SearchBulkResult;
import io.github.atengk.boot.search.template.SearchTemplate;
import io.github.atengk.boot.search.util.SearchMetadataHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 受管流式批量缓冲写入处理器 (SearchBulkProcessor)
 * <p>
 * 面向 Canal Binlog 消费、MQ 消息批量摄取与日志高吞吐写入场景：
 * <ul>
 *     <li>三维自适应触发阈值：文档条数 (batchSize)、时间窗口 (flushInterval) 与缓冲容量 (bufferSize)；</li>
 *     <li>并发背压流控：支持并发信号量限速与队列溢出微退避防爆保护；</li>
 *     <li>确定性生命周期与安全停机：实现 Spring {@link SmartLifecycle} 与 {@link DisposableBean}，容器关闭前强制排空残留文档。</li>
 * </ul>
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Slf4j
public class SearchBulkProcessor implements SmartLifecycle, DisposableBean, AutoCloseable {

    private final SearchTemplate searchTemplate;
    private final int batchSize;
    private final Duration flushInterval;
    private final long bufferSizeInBytes;
    private final int concurrentRequests;
    private final int maxQueueCapacity;
    private final Duration awaitCloseTimeout;
    private final BulkListener listener;

    private final Executor executor;
    private final boolean ownsExecutor;
    private ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private ScheduledFuture<?> scheduledFuture;

    private final ConcurrentLinkedQueue<SearchBulkAction> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger currentCount = new AtomicInteger(0);
    private final AtomicLong currentBytes = new AtomicLong(0);
    private final AtomicLong executionCounter = new AtomicLong(0);
    private final AtomicInteger inFlightRequests = new AtomicInteger(0);

    private final Semaphore concurrentSemaphore;
    private final ReentrantLock flushLock = new ReentrantLock();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private SearchBulkProcessor(Builder builder) {
        this.searchTemplate = Objects.requireNonNull(builder.searchTemplate, "searchTemplate must not be null");
        this.batchSize = builder.batchSize > 0 ? builder.batchSize : 1000;
        this.flushInterval = builder.flushInterval != null ? builder.flushInterval : Duration.ofSeconds(1);
        this.bufferSizeInBytes = builder.bufferSize != null ? builder.bufferSize.toBytes() : DataSize.ofMegabytes(5).toBytes();
        this.concurrentRequests = builder.concurrentRequests >= 0 ? builder.concurrentRequests : 1;
        this.maxQueueCapacity = builder.maxQueueCapacity > 0 ? builder.maxQueueCapacity : 10000;
        this.awaitCloseTimeout = builder.awaitCloseTimeout != null ? builder.awaitCloseTimeout : Duration.ofSeconds(10);
        this.listener = builder.listener != null ? builder.listener : BulkListener.NOOP;

        this.concurrentSemaphore = this.concurrentRequests > 0 ? new Semaphore(this.concurrentRequests) : null;

        if (builder.executor != null) {
            this.executor = builder.executor;
            this.ownsExecutor = false;
        } else if (this.concurrentRequests > 0) {
            this.executor = Executors.newFixedThreadPool(
                    this.concurrentRequests,
                    new NamedThreadFactory("search-bulk-worker-", true)
            );
            this.ownsExecutor = true;
        } else {
            this.executor = null;
            this.ownsExecutor = false;
        }

        if (builder.scheduler != null) {
            this.scheduler = builder.scheduler;
            this.ownsScheduler = false;
        } else {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(
                    new NamedThreadFactory("search-bulk-timer-", true)
            );
            this.ownsScheduler = true;
        }

        start();
    }

    /**
     * 创建 SearchBulkProcessor 构建器
     *
     * @param searchTemplate 搜索引擎操作模板
     * @return Builder 构建器实例
     */
    public static Builder builder(SearchTemplate searchTemplate) {
        return new Builder(searchTemplate);
    }

    // ==================== 1. 高速非阻塞入队 API (Symmetric Ingestion) ====================

    /**
     * 添加单条待索引文档实体
     *
     * @param doc 实体对象
     * @param <T> 实体类型
     * @return 当前处理器实例
     */
    public <T> SearchBulkProcessor add(T doc) {
        return add(null, doc);
    }

    /**
     * 添加单条待索引文档实体，显式指定目标索引
     *
     * @param index 目标索引名（为 null 或空时自动解析 @SearchIndex）
     * @param doc   实体对象
     * @param <T>   实体类型
     * @return 当前处理器实例
     */
    public <T> SearchBulkProcessor add(String index, T doc) {
        Objects.requireNonNull(doc, "document must not be null");
        String targetIndex = StringUtils.hasText(index) ? index : SearchMetadataHelper.resolveIndexName(doc.getClass());
        String docId = SearchMetadataHelper.resolveDocumentId(doc);
        String routing = SearchMetadataHelper.resolveRouting(doc);
        return addAction(SearchBulkAction.builder()
                .type(SearchBulkAction.ActionType.INDEX)
                .index(targetIndex)
                .id(docId)
                .routing(routing)
                .document(doc)
                .estimatedBytes(estimateDocSize(doc))
                .build());
    }

    /**
     * 批量添加待索引文档集合
     *
     * @param docs 文档集合
     * @param <T>  实体类型
     * @return 当前处理器实例
     */
    public <T> SearchBulkProcessor addAll(Collection<T> docs) {
        return addAll(null, docs);
    }

    /**
     * 批量添加待索引文档集合，显式指定目标索引
     *
     * @param index 目标索引名
     * @param docs  文档集合
     * @param <T>   实体类型
     * @return 当前处理器实例
     */
    public <T> SearchBulkProcessor addAll(String index, Collection<T> docs) {
        if (CollectionUtil.isEmpty(docs)) {
            return this;
        }
        for (T doc : docs) {
            if (doc != null) {
                add(index, doc);
            }
        }
        return this;
    }

    /**
     * 添加局部更新操作项（Partial Update）
     *
     * @param partialDoc 局部更新实体对象（必须包含有效主键 ID）
     * @param <T>        实体类型
     * @return 当前处理器实例
     */
    public <T> SearchBulkProcessor update(T partialDoc) {
        return update(null, partialDoc);
    }

    /**
     * 添加局部更新操作项，显式指定目标索引
     *
     * @param index      目标索引名
     * @param partialDoc 局部更新实体对象
     * @param <T>        实体类型
     * @return 当前处理器实例
     */
    public <T> SearchBulkProcessor update(String index, T partialDoc) {
        Objects.requireNonNull(partialDoc, "partialDoc must not be null");
        String targetIndex = StringUtils.hasText(index) ? index : SearchMetadataHelper.resolveIndexName(partialDoc.getClass());
        String docId = SearchMetadataHelper.resolveDocumentId(partialDoc);
        if (!StringUtils.hasText(docId)) {
            throw new IllegalArgumentException("Document ID must not be null or blank for update action");
        }
        String routing = SearchMetadataHelper.resolveRouting(partialDoc);
        return addAction(SearchBulkAction.builder()
                .type(SearchBulkAction.ActionType.UPDATE)
                .index(targetIndex)
                .id(docId)
                .routing(routing)
                .document(partialDoc)
                .estimatedBytes(estimateDocSize(partialDoc))
                .build());
    }

    /**
     * 添加未存在时 Upsert 操作项
     *
     * @param doc 文档实体（必须包含有效主键 ID）
     * @param <T> 实体类型
     * @return 当前处理器实例
     */
    public <T> SearchBulkProcessor upsert(T doc) {
        return upsert(null, doc);
    }

    /**
     * 添加未存在时 Upsert 操作项，显式指定目标索引
     *
     * @param index 目标索引名
     * @param doc   文档实体
     * @param <T>   实体类型
     * @return 当前处理器实例
     */
    public <T> SearchBulkProcessor upsert(String index, T doc) {
        Objects.requireNonNull(doc, "doc must not be null");
        String targetIndex = StringUtils.hasText(index) ? index : SearchMetadataHelper.resolveIndexName(doc.getClass());
        String docId = SearchMetadataHelper.resolveDocumentId(doc);
        if (!StringUtils.hasText(docId)) {
            throw new IllegalArgumentException("Document ID must not be null or blank for upsert action");
        }
        String routing = SearchMetadataHelper.resolveRouting(doc);
        return addAction(SearchBulkAction.builder()
                .type(SearchBulkAction.ActionType.UPSERT)
                .index(targetIndex)
                .id(docId)
                .routing(routing)
                .document(doc)
                .estimatedBytes(estimateDocSize(doc))
                .build());
    }

    /**
     * 添加删除操作项，根据实体类型解析目标索引
     *
     * @param id    待删除文档 ID
     * @param clazz 实体类类型
     * @return 当前处理器实例
     */
    public SearchBulkProcessor delete(String id, Class<?> clazz) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String targetIndex = SearchMetadataHelper.resolveIndexName(clazz);
        return delete(targetIndex, id);
    }

    /**
     * 添加删除操作项，显式指定目标索引与主键 ID
     *
     * @param index 目标索引名
     * @param id    待删除文档 ID
     * @return 当前处理器实例
     */
    public SearchBulkProcessor delete(String index, String id) {
        if (!StringUtils.hasText(index)) {
            throw new IllegalArgumentException("index must not be null or blank for delete action");
        }
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("id must not be null or blank for delete action");
        }
        return addAction(SearchBulkAction.builder()
                .type(SearchBulkAction.ActionType.DELETE)
                .index(index)
                .id(id)
                .estimatedBytes(64L)
                .build());
    }

    /**
     * 添加底层原始批量操作项
     *
     * @param action 批量操作项
     * @return 当前处理器实例
     */
    public SearchBulkProcessor addAction(SearchBulkAction action) {
        Objects.requireNonNull(action, "action must not be null");
        ensureOpen();

        // 1. 并发背压流控：若队列堆积超过上限，触发微退避防爆保护
        if (currentCount.get() >= maxQueueCapacity) {
            applyBackpressure();
        }

        // 2. 存入无锁高性能并发队列并累加度量
        queue.add(action);
        int count = currentCount.incrementAndGet();
        long bytes = currentBytes.addAndGet(action.getEstimatedBytes());

        // 3. 多维自适应触发：文档条数或容量阈值满足时立即异步刷盘
        if (count >= batchSize || bytes >= bufferSizeInBytes) {
            triggerFlush(false);
        }

        return this;
    }

    // ==================== 2. 内部背压与多维刷盘机制 ====================

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("SearchBulkProcessor is already closed");
        }
    }

    private void applyBackpressure() {
        int backoffAttempts = 0;
        while (currentCount.get() >= maxQueueCapacity && !closed.get()) {
            triggerFlush(false);
            try {
                long sleepMillis = Math.min(50L * (1L << Math.min(backoffAttempts, 4)), 500L);
                Thread.sleep(sleepMillis);
                backoffAttempts++;
                if (backoffAttempts > 20) {
                    throw new SearchException(SearchResultCode.SEARCH_OPERATION_ERROR,
                            "SearchBulkProcessor queue backlog exceeded limit [" + maxQueueCapacity + "] after backpressure timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SearchException(SearchResultCode.SEARCH_OPERATION_ERROR, "Interrupted while applying bulk queue backpressure", e);
            }
        }
    }

    private void triggerFlush(boolean sync) {
        if (sync || concurrentRequests <= 0 || executor == null) {
            drainAndExecute(false);
        } else {
            try {
                executor.execute(() -> drainAndExecute(true));
            } catch (Exception e) {
                log.warn("Failed to dispatch async bulk flush, falling back to synchronous execution: {}", e.getMessage());
                drainAndExecute(false);
            }
        }
    }

    private void drainAndExecute(boolean async) {
        if (queue.isEmpty()) {
            return;
        }

        // 尝试获取排他抽取锁，避免多线程并发重复抽取相同批次
        if (!flushLock.tryLock()) {
            return;
        }

        List<SearchBulkAction> batch;
        try {
            batch = pollBatch(batchSize, bufferSizeInBytes);
        } finally {
            flushLock.unlock();
        }

        if (batch.isEmpty()) {
            return;
        }

        // 物理提交当前批次
        executeBatch(batch);

        // 若抽取后队列中仍有足够数据满足批处理阈值，级联触发下一次 Flush
        if (currentCount.get() >= batchSize || currentBytes.get() >= bufferSizeInBytes) {
            triggerFlush(async);
        }
    }

    private void executeBatch(List<SearchBulkAction> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }

        long executionId = executionCounter.incrementAndGet();
        inFlightRequests.incrementAndGet();
        boolean acquired = false;
        try {
            if (concurrentSemaphore != null) {
                concurrentSemaphore.acquire();
                acquired = true;
            }
            listener.beforeBulk(executionId, batch.size());
            BulkRequest bulkRequest = buildBulkRequest(batch);
            SearchBulkResult result = searchTemplate.executeBulk(bulkRequest, batch.size(), true);
            listener.afterBulk(executionId, batch.size(), result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.afterBulk(executionId, batch.size(), e);
            throw new SearchException(SearchResultCode.SEARCH_OPERATION_ERROR, "Bulk execution interrupted", e);
        } catch (Throwable t) {
            listener.afterBulk(executionId, batch.size(), t);
            log.error("Bulk execution [{}] failed for {} actions: {}", executionId, batch.size(), t.getMessage(), t);
        } finally {
            if (acquired && concurrentSemaphore != null) {
                concurrentSemaphore.release();
            }
            inFlightRequests.decrementAndGet();
        }
    }

    private BulkRequest buildBulkRequest(List<SearchBulkAction> batch) {
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        for (SearchBulkAction action : batch) {
            switch (action.getType()) {
                case INDEX -> bulkBuilder.operations(op -> op.index(idx -> {
                    idx.index(action.getIndex()).document(action.getDocument());
                    if (StringUtils.hasText(action.getId())) {
                        idx.id(action.getId());
                    }
                    if (StringUtils.hasText(action.getRouting())) {
                        idx.routing(action.getRouting());
                    }
                    return idx;
                }));
                case UPDATE -> bulkBuilder.operations(op -> op.update(u -> {
                    u.index(action.getIndex()).id(action.getId()).action(a -> a.doc(action.getDocument()).docAsUpsert(false));
                    if (StringUtils.hasText(action.getRouting())) {
                        u.routing(action.getRouting());
                    }
                    return u;
                }));
                case UPSERT -> bulkBuilder.operations(op -> op.update(u -> {
                    u.index(action.getIndex()).id(action.getId()).action(a -> a.doc(action.getDocument()).docAsUpsert(true));
                    if (StringUtils.hasText(action.getRouting())) {
                        u.routing(action.getRouting());
                    }
                    return u;
                }));
                case DELETE -> bulkBuilder.operations(op -> op.delete(d -> {
                    d.index(action.getIndex()).id(action.getId());
                    if (StringUtils.hasText(action.getRouting())) {
                        d.routing(action.getRouting());
                    }
                    return d;
                }));
            }
        }
        return bulkBuilder.build();
    }

    private static final ClassValue<Field[]> FIELD_CACHE = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            Field[] all = type.getDeclaredFields();
            List<Field> instanceFields = new ArrayList<>();
            for (Field f : all) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    instanceFields.add(f);
                }
            }
            return instanceFields.toArray(new Field[0]);
        }
    };

    /**
     * 估算文档实体占用内存字节容量（轻量化启发式计算，杜绝全量 JSON 序列化开销）
     *
     * @param doc 文档实体
     * @return 估算字节数
     */
    private long estimateDocSize(Object doc) {
        if (doc == null) {
            return 0L;
        }
        if (doc instanceof CharSequence cs) {
            return cs.length();
        }
        if (doc instanceof byte[] bytes) {
            return bytes.length;
        }
        if (doc instanceof Number || doc instanceof Boolean) {
            return 16L;
        }
        if (doc instanceof Map<?, ?> map) {
            long size = 32L;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                size += estimateDocSize(entry.getKey()) + estimateDocSize(entry.getValue()) + 16L;
            }
            return size;
        }
        // 对于普通 POJO 实体：基于反射缓存字段进行轻量级估算，避免入队热路径上的全量 JSON 序列化
        try {
            Field[] fields = FIELD_CACHE.get(doc.getClass());
            long size = 32L;
            for (Field field : fields) {
                Class<?> type = field.getType();
                if (type == String.class || CharSequence.class.isAssignableFrom(type)) {
                    Object val = field.get(doc);
                    size += val != null ? ((CharSequence) val).length() + 16L : 8L;
                } else if (type.isPrimitive()) {
                    size += 16L;
                } else {
                    size += 32L;
                }
            }
            return Math.max(64L, size);
        } catch (Exception e) {
            return 128L;
        }
    }

    /**
     * 从队列中抽取单批待执行操作项（受 batch-size 条数与 buffer-size 字节容量双重阈值约束）
     * <p>
     * 保证在当前批次非空时，若追加下一项将超过 maxBytes，则提前截断批次；
     * 当批次为空时，首个元素即便单体超过 maxBytes 也允许出队，保障超大文档不阻塞队列向前推进。
     *
     * @param maxCount 最大条数限制
     * @param maxBytes 最大字节限制
     * @return 抽取的批次列表（无可用元素时返回空列表）
     */
    private List<SearchBulkAction> pollBatch(int maxCount, long maxBytes) {
        if (queue.isEmpty()) {
            return Collections.emptyList();
        }
        List<SearchBulkAction> batch = new ArrayList<>(Math.min(maxCount, 1000));
        long batchBytes = 0;
        while (batch.size() < maxCount) {
            SearchBulkAction next = queue.peek();
            if (next == null) {
                break;
            }
            if (!batch.isEmpty() && maxBytes > 0 && (batchBytes + next.getEstimatedBytes()) > maxBytes) {
                break;
            }
            SearchBulkAction polled = queue.poll();
            if (polled == null) {
                break;
            }
            batch.add(polled);
            batchBytes += polled.getEstimatedBytes();
        }
        if (!batch.isEmpty()) {
            currentCount.addAndGet(-batch.size());
            currentBytes.addAndGet(-batchBytes);
        }
        return batch;
    }

    // ==================== 3. 运维、度量与确定性生命周期 ====================

    /**
     * 获取当前处于缓冲队列中等待提交的文档总条数
     *
     * @return 缓冲队列条数
     */
    public int getQueueSize() {
        return currentCount.get();
    }

    /**
     * 获取当前缓冲队列占用的估算内存字节容量
     *
     * @return 估算字节数
     */
    public long getBufferBytes() {
        return currentBytes.get();
    }

    /**
     * 手动同步强制刷盘，排空当前缓冲队列中累积的所有数据
     */
    public void flush() {
        while (!queue.isEmpty()) {
            flushLock.lock();
            List<SearchBulkAction> batch;
            try {
                batch = pollBatch(batchSize, bufferSizeInBytes);
            } finally {
                flushLock.unlock();
            }

            if (batch.isEmpty()) {
                break;
            }
            executeBatch(batch);
        }
    }

    /**
     * 阻塞等待在途所有 Bulk 请求完成排空并优雅关闭
     *
     * @param timeout 超时时间
     * @return 若在超时前全部完成排空返回 true；若超时返回 false
     * @throws InterruptedException 若等待过程中线程被中断
     */
    public boolean awaitClose(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (closed.compareAndSet(false, true)) {
            // 1. 停止定时调度
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
                scheduledFuture = null;
            }

            // 2. 强制刷盘排空队列剩余残留
            flush();

            // 3. 关闭内部受管资源
            if (ownsScheduler && scheduler != null) {
                scheduler.shutdown();
            }
            if (ownsExecutor && executor instanceof ExecutorService es) {
                es.shutdown();
            }
        }

        // 4. 等待在途请求全部归零
        long timeoutNanos = timeout.toNanos();
        long start = System.nanoTime();
        while (inFlightRequests.get() > 0) {
            long elapsed = System.nanoTime() - start;
            if (elapsed >= timeoutNanos) {
                return false;
            }
            long remainingMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(timeoutNanos - elapsed));
            Thread.sleep(Math.min(remainingMs, 50));
        }

        return true;
    }

    @Override
    public void close() {
        try {
            awaitClose(awaitCloseTimeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("SearchBulkProcessor close interrupted", e);
        }
    }

    @Override
    public void destroy() {
        close();
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            if (scheduler != null && flushInterval != null && !flushInterval.isZero() && !flushInterval.isNegative()) {
                long intervalMs = Math.max(10, flushInterval.toMillis());
                scheduledFuture = scheduler.scheduleWithFixedDelay(() -> {
                    try {
                        if (!queue.isEmpty() && running.get() && !closed.get()) {
                            triggerFlush(false);
                        }
                    } catch (Throwable t) {
                        log.warn("Error in scheduled bulk flush: {}", t.getMessage());
                    }
                }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
            }
        }
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public void stop(Runnable callback) {
        if (running.compareAndSet(true, false)) {
            try {
                awaitClose(awaitCloseTimeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("SearchBulkProcessor graceful stop interrupted", e);
            } finally {
                if (callback != null) {
                    callback.run();
                }
            }
        } else {
            if (callback != null) {
                callback.run();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get() && !closed.get();
    }

    @Override
    public int getPhase() {
        // Spring SmartLifecycle 停机按 Phase 降序执行（数值大者先停止）。
        // DEFAULT_PHASE 即为 Integer.MAX_VALUE（最高优先级阶段），
        // 确保在其他低阶段 Bean（如 Phase 0 的底层连接驱动与普通 Bean）销毁前，优先执行 Flush 安全排空
        return SmartLifecycle.DEFAULT_PHASE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    // ==================== 4. Builder 构建器 ====================

    /**
     * SearchBulkProcessor 实例构建器
     */
    public static class Builder {
        private final SearchTemplate searchTemplate;
        private int batchSize = 1000;
        private Duration flushInterval = Duration.ofSeconds(1);
        private DataSize bufferSize = DataSize.ofMegabytes(5);
        private int concurrentRequests = 1;
        private int maxQueueCapacity = 10000;
        private Duration awaitCloseTimeout = Duration.ofSeconds(10);
        private BulkListener listener = BulkListener.NOOP;
        private Executor executor;
        private ScheduledExecutorService scheduler;

        public Builder(SearchTemplate searchTemplate) {
            this.searchTemplate = Objects.requireNonNull(searchTemplate, "searchTemplate must not be null");
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder flushInterval(Duration flushInterval) {
            this.flushInterval = flushInterval;
            return this;
        }

        public Builder bufferSize(DataSize bufferSize) {
            this.bufferSize = bufferSize;
            return this;
        }

        public Builder concurrentRequests(int concurrentRequests) {
            this.concurrentRequests = concurrentRequests;
            return this;
        }

        public Builder maxQueueCapacity(int maxQueueCapacity) {
            this.maxQueueCapacity = maxQueueCapacity;
            return this;
        }

        public Builder awaitCloseTimeout(Duration awaitCloseTimeout) {
            this.awaitCloseTimeout = awaitCloseTimeout;
            return this;
        }

        public Builder listener(BulkListener listener) {
            this.listener = listener;
            return this;
        }

        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        public Builder scheduler(ScheduledExecutorService scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        public SearchBulkProcessor build() {
            return new SearchBulkProcessor(this);
        }
    }
}
