package io.github.atengk.boot.search.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 搜索引擎 Micrometer 性能度量指标
 * <p>
 * 采集兼容总览指标 {@code ateng.search.query.time} / {@code ateng.search.bulk.time}，
 * 以及携带低基数标签（{@code operation} / {@code outcome}）的操作细分指标 {@code ateng.search.operation.time}。
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class SearchMetrics {

    private final MeterRegistry meterRegistry;
    private final Timer queryTimer;
    private final Timer bulkTimer;
    private final ConcurrentHashMap<String, Timer> operationTimers = new ConcurrentHashMap<>();

    public SearchMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.queryTimer = Timer.builder("ateng.search.query.time")
                .description("Search query execution latency and throughput")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);
        this.bulkTimer = Timer.builder("ateng.search.bulk.time")
                .description("Search bulk operation execution latency and throughput")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);
    }

    /**
     * 记录带低基数操作与结果标签的执行耗时（同时兼容记录总览 Timer）
     * <p>
     * 注意：{@code operation} 必须保持有界低基数（如 {@code search}、{@code index}、{@code bulk}），
     * 严禁传入动态索引名或请求 ID 等高基数值以防指标时序爆炸。
     *
     * @param operation 操作标识（如 search、index、bulk）
     * @param isBulk    是否为批量写入操作
     * @param supplier  执行逻辑
     * @param <T>       返回类型
     * @return 执行结果
     */
    public <T> T recordOperation(String operation, boolean isBulk, Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier must not be null");
        long startNanos = System.nanoTime();
        String outcome = "SUCCESS";
        try {
            return supplier.get();
        } catch (Throwable t) {
            outcome = "ERROR";
            throw t;
        } finally {
            recordDuration(operation, isBulk, outcome, System.nanoTime() - startNanos);
        }
    }

    /**
     * 直接按给定耗时记录操作指标
     *
     * @param operation     操作标识
     * @param isBulk        是否为批量操作
     * @param outcome       执行结果（SUCCESS / ERROR）
     * @param durationNanos 耗时纳秒
     */
    public void recordDuration(String operation, boolean isBulk, String outcome, long durationNanos) {
        long safeNanos = Math.max(0L, durationNanos);
        if (isBulk) {
            bulkTimer.record(safeNanos, TimeUnit.NANOSECONDS);
        } else {
            queryTimer.record(safeNanos, TimeUnit.NANOSECONDS);
        }
        String opTag = (operation != null && !operation.isBlank()) ? operation.trim() : "unknown";
        String outcomeTag = (outcome != null && !outcome.isBlank()) ? outcome.trim() : "UNKNOWN";
        String cacheKey = opTag + "|" + outcomeTag;
        Timer opTimer = operationTimers.computeIfAbsent(cacheKey, k ->
                Timer.builder("ateng.search.operation.time")
                        .description("Search operation latency breakdown by operation and outcome")
                        .tags(Tags.of("operation", opTag, "outcome", outcomeTag))
                        .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                        .register(meterRegistry)
        );
        opTimer.record(safeNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 记录查询操作耗时
     *
     * @param supplier 查询执行逻辑
     * @param <T>      返回类型
     * @return 查询结果
     */
    public <T> T recordQuery(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier must not be null");
        return queryTimer.record(supplier);
    }

    /**
     * 记录可抛异常的查询操作耗时
     *
     * @param callable 查询执行逻辑
     * @param <T>      返回类型
     * @return 查询结果
     * @throws Exception 原始异常
     */
    public <T> T recordQueryCallable(Callable<T> callable) throws Exception {
        Objects.requireNonNull(callable, "callable must not be null");
        return queryTimer.recordCallable(callable);
    }

    /**
     * 记录无返回值的查询操作耗时
     *
     * @param runnable 查询执行逻辑
     */
    public void recordQuery(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable must not be null");
        queryTimer.record(runnable);
    }

    /**
     * 记录批量写入操作耗时
     *
     * @param supplier 批量操作执行逻辑
     * @param <T>      返回类型
     * @return 批量操作结果
     */
    public <T> T recordBulk(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier must not be null");
        return bulkTimer.record(supplier);
    }

    /**
     * 记录无返回值的批量写入操作耗时
     *
     * @param runnable 批量操作执行逻辑
     */
    public void recordBulk(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable must not be null");
        bulkTimer.record(runnable);
    }

    public Timer getQueryTimer() {
        return queryTimer;
    }

    public Timer getBulkTimer() {
        return bulkTimer;
    }

    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }
}
