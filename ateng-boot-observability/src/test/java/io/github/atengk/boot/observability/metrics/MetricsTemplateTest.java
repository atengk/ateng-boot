package io.github.atengk.boot.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MetricsTemplate 编程式度量门面单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("MetricsTemplate 业务度量门面单元测试")
class MetricsTemplateTest {

    private MeterRegistry registry;
    private MetricsTemplate metricsTemplate;

    @BeforeEach
    void setUp() {
        this.registry = new SimpleMeterRegistry();
        this.metricsTemplate = new MetricsTemplate(this.registry);
    }

    @Test
    @DisplayName("Counter 操作：步进递增、自定义步进、标签多维统计与查询")
    void testCounterOperations() {
        // 单步递增
        metricsTemplate.increment("order.created", "channel", "app", "region", "cn");
        metricsTemplate.increment("order.created", "channel", "app", "region", "cn");

        // 自定义数值递增
        metricsTemplate.increment("order.created", 3.0, "channel", "app", "region", "cn");

        double count = metricsTemplate.count("order.created", "channel", "app", "region", "cn");
        assertThat(count).isEqualTo(5.0);

        // 负数增量防御（不递增）
        metricsTemplate.increment("order.created", -2.0, "channel", "app", "region", "cn");
        assertThat(metricsTemplate.count("order.created", "channel", "app", "region", "cn")).isEqualTo(5.0);

        // 未注册 Counter 查询保底 0.0
        assertThat(metricsTemplate.count("non.existent")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Timer 操作：直接记录 Duration、函数式 Runnable 与 Supplier 耗时测量")
    void testTimerOperations() {
        // 记录指定 Duration
        metricsTemplate.record("api.latency", Duration.ofMillis(120), "endpoint", "/users");
        Timer timer = metricsTemplate.timer("api.latency", "endpoint", "/users");
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(120.0);

        // 函数式 Runnable 耗时测量
        metricsTemplate.record("task.runnable", () -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
            }
        }, "task", "backup");
        Timer runnableTimer = metricsTemplate.timer("task.runnable", "task", "backup");
        assertThat(runnableTimer.count()).isEqualTo(1);
        assertThat(runnableTimer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(15.0);

        // 函数式 Supplier 耗时测量与返回值透传
        String result = metricsTemplate.record("task.supplier", () -> "data-result", "source", "db");
        assertThat(result).isEqualTo("data-result");
        Timer supplierTimer = metricsTemplate.timer("task.supplier", "source", "db");
        assertThat(supplierTimer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Timer 操作：Callable 异常透传与跨代码块 Sample 测量")
    void testTimerCallableAndSample() {
        // Callable 正常返回
        String result = metricsTemplate.recordCallable("callable.test", () -> "hello", "type", "fast");
        assertThat(result).isEqualTo("hello");
        assertThat(metricsTemplate.timer("callable.test", "type", "fast").count()).isEqualTo(1);

        // Callable 异常抛出与耗时记录
        assertThatThrownBy(() -> metricsTemplate.recordCallable("callable.error", () -> {
            throw new IOException("IO error");
        }, "type", "error"))
                .isInstanceOf(IOException.class)
                .hasMessage("IO error");

        // 即使抛出异常，耗时采样依然被记录
        assertThat(metricsTemplate.timer("callable.error", "type", "error").count()).isEqualTo(1);

        // 手动 Sample 跨代码块测量
        Timer.Sample sample = metricsTemplate.startSample();
        metricsTemplate.stopSample(sample, "manual.sample", "step", "step1");
        assertThat(metricsTemplate.timer("manual.sample", "step", "step1").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Gauge 操作：Number 状态、函数式状态对象、集合容量与 Map 大小测量")
    void testGaugeOperations() {
        // 基础 Number Gauge
        AtomicInteger activeConnections = new AtomicInteger(10);
        metricsTemplate.gauge("pool.active.connections", activeConnections, "pool", "master");
        assertThat(registry.get("pool.active.connections").gauge().value()).isEqualTo(10.0);

        activeConnections.set(25);
        assertThat(registry.get("pool.active.connections").gauge().value()).isEqualTo(25.0);

        // 函数式状态对象 Gauge
        Map<String, String> sessionStore = new HashMap<>();
        sessionStore.put("s1", "v1");
        metricsTemplate.gauge("session.count", sessionStore, Map::size, "cluster", "east");
        assertThat(registry.get("session.count").gauge().value()).isEqualTo(1.0);

        sessionStore.put("s2", "v2");
        assertThat(registry.get("session.count").gauge().value()).isEqualTo(2.0);

        // 集合大小 Gauge 便捷方法
        List<String> queue = new ArrayList<>();
        queue.add("msg1");
        metricsTemplate.gaugeCollectionSize("queue.size", queue, "queueName", "orders");
        assertThat(registry.get("queue.size").gauge().value()).isEqualTo(1.0);

        queue.add("msg2");
        assertThat(registry.get("queue.size").gauge().value()).isEqualTo(2.0);

        // Map 大小 Gauge 便捷方法
        Map<String, Object> cache = new HashMap<>();
        cache.put("k1", "v1");
        metricsTemplate.gaugeMapSize("cache.size", cache, "cacheName", "users");
        assertThat(registry.get("cache.size").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("DistributionSummary 操作：载荷分发大小记录与摘要获取")
    void testDistributionSummaryOperations() {
        metricsTemplate.summary("http.payload.bytes", 1024, "uri", "/api/upload");
        metricsTemplate.summary("http.payload.bytes", 2048, "uri", "/api/upload");

        DistributionSummary summary = metricsTemplate.distributionSummary("http.payload.bytes", "uri", "/api/upload");
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.totalAmount()).isEqualTo(3072.0);
        assertThat(summary.max()).isEqualTo(2048.0);
    }

    @Test
    @DisplayName("标签清洗与健壮性：奇数标签容错、空值防卫与空注册表降级")
    void testTagSanitizationAndNullSafety() {
        // 空标签
        assertThat(MetricsTemplate.toTags((String[]) null)).isEqualTo(Tags.empty());
        assertThat(MetricsTemplate.toTags()).isEqualTo(Tags.empty());

        // 奇数标签自动补齐 UNKNOWN
        Tags oddTags = MetricsTemplate.toTags("key1", "val1", "key2");
        assertThat(oddTags).contains(io.micrometer.core.instrument.Tag.of("key1", "val1"));
        assertThat(oddTags).contains(io.micrometer.core.instrument.Tag.of("key2", "UNKNOWN"));

        // 空键过滤
        Tags filteredTags = MetricsTemplate.toTags(null, "val", "keyA", "valA");
        assertThat(filteredTags).containsExactly(io.micrometer.core.instrument.Tag.of("keyA", "valA"));

        // 空注册表与空名称安全降级（不抛出异常）
        MetricsTemplate nullTemplate = new MetricsTemplate(null);
        nullTemplate.increment("test");
        assertThat(nullTemplate.count("test")).isEqualTo(0.0);
        assertThat(nullTemplate.counter("test")).isNull();
        assertThat(nullTemplate.timer("test")).isNull();
        assertThat(nullTemplate.distributionSummary("test")).isNull();

        String val = nullTemplate.record("test", () -> "fallback");
        assertThat(val).isEqualTo("fallback");
    }

    @Test
    @DisplayName("对称注销与生命周期清理：支持按 Meter、Id、指标名+标签注销及清空")
    void testSymmetricRemoveAndClearOperations() {
        // 注册指标
        metricsTemplate.increment("order.temp.counter", 10.0, "type", "temp");
        Counter counter = metricsTemplate.counter("order.temp.counter", "type", "temp");
        assertThat(counter).isNotNull();

        // 1. 按名称+标签注销
        int removed = metricsTemplate.remove("order.temp.counter", "type", "temp");
        assertThat(removed).isEqualTo(1);
        assertThat(metricsTemplate.count("order.temp.counter", "type", "temp")).isEqualTo(0.0);

        // 2. 重新注册并按 Meter 实例注销
        metricsTemplate.increment("order.temp.counter2", 5.0, "type", "temp2");
        Counter counter2 = metricsTemplate.counter("order.temp.counter2", "type", "temp2");
        assertThat(metricsTemplate.remove(counter2)).isNotNull();

        // 3. 按 Meter.Id 注销
        Timer timer = metricsTemplate.timer("order.temp.timer", "type", "temp");
        assertThat(timer).isNotNull();
        assertThat(metricsTemplate.remove(timer.getId())).isNotNull();

        // 4. clear 清空全部度量
        metricsTemplate.increment("batch.metric.1");
        metricsTemplate.increment("batch.metric.2");
        metricsTemplate.clear();
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    @DisplayName("直方图与 SLO 重载：timer 与 record 全重载支持 percentiles/histogram/sla 分布导出")
    void testTimerAndRecordWithHistogramAndSla() {
        PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MetricsTemplate promTemplate = new MetricsTemplate(prometheusRegistry);

        double[] percentiles = {0.95, 0.99};
        Duration[] sla = {Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(500)};

        // 1. timer(String..., Iterable<Tag>)
        Timer directTimer = promTemplate.timer("trade.timer.direct", percentiles, true, sla, "channel", "app");
        assertThat(directTimer).isNotNull();
        directTimer.record(Duration.ofMillis(40));

        // 2. record Duration（String... 与 Iterable<Tag>）
        promTemplate.record("trade.duration.varargs", Duration.ofMillis(80), percentiles, true, sla, "channel", "web");
        promTemplate.record("trade.duration.iterable", Duration.ofMillis(120), percentiles, false, sla, Tags.of("channel", "mini"));

        // 3. record Runnable（String... 与 Iterable<Tag>）
        AtomicBoolean ran1 = new AtomicBoolean(false);
        AtomicBoolean ran2 = new AtomicBoolean(false);
        promTemplate.record("trade.runnable.varargs", () -> ran1.set(true), percentiles, true, sla, "step", "pay");
        promTemplate.record("trade.runnable.iterable", () -> ran2.set(true), percentiles, false, sla, Tags.of("step", "notify"));
        assertThat(ran1).isTrue();
        assertThat(ran2).isTrue();

        // 4. record Supplier（String... 与 Iterable<Tag>）
        String res1 = promTemplate.record("trade.supplier.varargs", () -> "risk-ok", percentiles, true, sla, "rule", "aml");
        String res2 = promTemplate.record("trade.supplier.iterable", () -> "risk-pass", percentiles, false, sla, Tags.of("rule", "fraud"));
        assertThat(res1).isEqualTo("risk-ok");
        assertThat(res2).isEqualTo("risk-pass");

        Timer iterableSlaTimer = prometheusRegistry.find("trade.duration.iterable").timer();
        assertThat(iterableSlaTimer).isNotNull();
        double[] sloBucketsMs = Arrays.stream(iterableSlaTimer.takeSnapshot().histogramCounts())
                .mapToDouble(b -> b.bucket(TimeUnit.MILLISECONDS))
                .toArray();
        assertThat(sloBucketsMs).containsExactly(50.0, 100.0, 500.0);

        double[] snapshotPercentiles = Arrays.stream(directTimer.takeSnapshot().percentileValues())
                .mapToDouble(p -> p.percentile())
                .toArray();
        assertThat(snapshotPercentiles).containsExactly(0.95, 0.99);

        String scrape = prometheusRegistry.scrape();
        assertThat(scrape)
                .contains("# TYPE trade_duration_varargs_seconds histogram")
                .contains("trade_duration_varargs_seconds_bucket{")
                .contains("trade_runnable_varargs_seconds_bucket{")
                .contains("trade_supplier_varargs_seconds_bucket{")
                .contains("le=\"0.05\"")
                .contains("le=\"0.1\"")
                .contains("le=\"0.5\"")
                .contains("le=\"+Inf\"");
    }

    @Test
    @DisplayName("直方图与 SLO 非法参数防御与空注册表降级：过滤非正数 SLA 与越界分位数，不阻断业务")
    void testDistributionOverloadsDefensiveFallback() {
        double[] mixedPercentiles = {-0.2, Double.NaN, 1.5, 0.95};
        Duration[] mixedSla = {null, Duration.ZERO, Duration.ofMillis(-20), Duration.ofMillis(80)};

        Timer sanitizedTimer = metricsTemplate.timer(
                "defensive.timer", mixedPercentiles, false, mixedSla, "env", "test");
        assertThat(sanitizedTimer).isNotNull();
        sanitizedTimer.record(Duration.ofMillis(30));

        double[] bucketMs = Arrays.stream(sanitizedTimer.takeSnapshot().histogramCounts())
                .mapToDouble(b -> b.bucket(TimeUnit.MILLISECONDS))
                .toArray();
        assertThat(bucketMs).containsExactly(80.0);

        double[] percentiles = Arrays.stream(sanitizedTimer.takeSnapshot().percentileValues())
                .mapToDouble(p -> p.percentile())
                .toArray();
        assertThat(percentiles).containsExactly(0.95);

        // 全部非法分位数与全部非正数 SLA 时应平稳降级为普通 Timer
        Timer allInvalidTimer = metricsTemplate.timer(
                "defensive.all_invalid",
                new double[]{-1.0, 2.0, Double.POSITIVE_INFINITY},
                false,
                new Duration[]{Duration.ZERO, Duration.ofMillis(-5)},
                "env", "test");
        assertThat(allInvalidTimer).isNotNull();
        assertThat(allInvalidTimer.takeSnapshot().histogramCounts()).isEmpty();
        assertThat(allInvalidTimer.takeSnapshot().percentileValues()).isEmpty();

        // 负数 duration 跳过记录
        metricsTemplate.record("defensive.neg_duration", Duration.ofMillis(-10), new double[]{0.95}, true, mixedSla, "k", "v");
        assertThat(registry.find("defensive.neg_duration").timer()).isNull();

        // null Runnable / Supplier 安全返回
        metricsTemplate.record("defensive.null_runnable", (Runnable) null, new double[]{0.95}, true, mixedSla, "k", "v");
        assertThat(metricsTemplate.record("defensive.null_supplier", (java.util.function.Supplier<String>) null,
                new double[]{0.95}, true, mixedSla, "k", "v")).isNull();

        // 空注册表与空白指标名下，业务回调照常执行不阻塞
        MetricsTemplate nullTemplate = new MetricsTemplate(null);
        assertThat(nullTemplate.timer("x", mixedPercentiles, true, mixedSla, "k", "v")).isNull();
        AtomicBoolean executed = new AtomicBoolean(false);
        nullTemplate.record("x", () -> executed.set(true), mixedPercentiles, true, mixedSla, "k", "v");
        assertThat(executed).isTrue();
        assertThat(nullTemplate.record("x", () -> "ok", mixedPercentiles, true, mixedSla, "k", "v")).isEqualTo("ok");
        assertThat(metricsTemplate.record("   ", () -> "blank-ok", mixedPercentiles, true, mixedSla, "k", "v")).isEqualTo("blank-ok");
    }
}
