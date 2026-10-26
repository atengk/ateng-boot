package io.github.atengk.boot.search.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 搜索引擎 Micrometer 性能度量指标
 * <p>
 * 注册与采集 query 与 bulk 操作的 P99 延迟及吞吐量。
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class SearchMetrics {

    private final MeterRegistry meterRegistry;
    private final Timer queryTimer;
    private final Timer bulkTimer;

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
