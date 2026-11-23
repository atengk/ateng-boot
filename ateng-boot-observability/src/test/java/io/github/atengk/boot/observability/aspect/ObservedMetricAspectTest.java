package io.github.atengk.boot.observability.aspect;

import io.github.atengk.boot.observability.annotation.ObservedMetric;
import io.github.atengk.boot.observability.metrics.MetricsTemplate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ObservedMetricAspect 声明式度量切面单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("ObservedMetricAspect 声明式方法度量切面单元测试")
class ObservedMetricAspectTest {

    private MeterRegistry registry;
    private MetricsTemplate metricsTemplate;

    public interface TestService {
        String defaultObservedMethod(String input);
        String customObservedMethod(String input);
        void throwingMethod();
        String disabledDurationMethod();
        String disabledCountersMethod();
        String settleWithHistogramAndSla();
        String settleWithInvalidSlaFiltered();
        String settleWithAllInvalidSla();
    }

    public static class TestServiceImpl implements TestService {

        @Override
        @ObservedMetric
        public String defaultObservedMethod(String input) {
            return "echo:" + input;
        }

        @Override
        @ObservedMetric(value = "order.create", tags = {"region", "cn", "channel", "app"})
        public String customObservedMethod(String input) {
            return "order:" + input;
        }

        @Override
        @ObservedMetric(value = "order.cancel")
        public void throwingMethod() {
            throw new IllegalArgumentException("订单不存在");
        }

        @Override
        @ObservedMetric(value = "query.fast", recordDuration = false)
        public String disabledDurationMethod() {
            return "fast";
        }

        @Override
        @ObservedMetric(value = "query.nocounter", recordSuccess = false, recordFailure = false)
        public String disabledCountersMethod() {
            return "nocounter";
        }

        @Override
        @ObservedMetric(
                value = "order.settle",
                tags = {"domain", "trade"},
                percentiles = {0.95, 0.99},
                histogram = true,
                slaMs = {50, 100, 500}
        )
        public String settleWithHistogramAndSla() {
            return "settled";
        }

        @Override
        @ObservedMetric(
                value = "order.sla.filtered",
                percentiles = {},
                histogram = false,
                slaMs = {0, -10, 50, -1, 200}
        )
        public String settleWithInvalidSlaFiltered() {
            return "filtered";
        }

        @Override
        @ObservedMetric(
                value = "order.sla.all_invalid",
                percentiles = {},
                histogram = false,
                slaMs = {0, -5}
        )
        public String settleWithAllInvalidSla() {
            return "all-invalid";
        }
    }

    @ObservedMetric(value = "class.level.metric", tags = {"tier", "standard"})
    public static class ClassLevelAnnotatedService {
        public String doWork() {
            return "work-done";
        }
    }

    @BeforeEach
    void setUp() {
        this.registry = new SimpleMeterRegistry();
        this.metricsTemplate = new MetricsTemplate(this.registry);
    }

    private TestService createProxy(TestService target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new ObservedMetricAspect(metricsTemplate));
        return factory.getProxy();
    }

    @Test
    @DisplayName("默认方法级别度量：方法正常返回，记录 method.execution Timer 与 calls Counter")
    void testDefaultObservedMethodSuccess() {
        TestService proxy = createProxy(new TestServiceImpl());
        String result = proxy.defaultObservedMethod("test");
        assertThat(result).isEqualTo("echo:test");

        // 校验 Counter (method.execution.calls)
        Counter counter = registry.find("method.execution.calls")
                .tag("class", "TestServiceImpl")
                .tag("method", "defaultObservedMethod")
                .tag("status", "success")
                .tag("exception", "none")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        // 校验 Timer (method.execution)
        Timer timer = registry.find("method.execution")
                .tag("class", "TestServiceImpl")
                .tag("method", "defaultObservedMethod")
                .tag("status", "success")
                .tag("exception", "none")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("自定义指标名与静态标签：按指定 name 与 tags 聚合度量")
    void testCustomObservedMethod() {
        TestService proxy = createProxy(new TestServiceImpl());
        proxy.customObservedMethod("1001");

        Counter counter = registry.find("order.create.calls")
                .tag("class", "TestServiceImpl")
                .tag("method", "customObservedMethod")
                .tag("status", "success")
                .tag("exception", "none")
                .tag("region", "cn")
                .tag("channel", "app")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        Timer timer = registry.find("order.create")
                .tag("region", "cn")
                .tag("channel", "app")
                .tag("status", "success")
                .tag("exception", "none")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("异常抛出拦截：记录 error 与 exception 标签，并绝对原样向外抛出业务异常")
    void testThrowingMethodFailureMetrics() {
        TestService proxy = createProxy(new TestServiceImpl());

        assertThatThrownBy(proxy::throwingMethod)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("订单不存在");

        Counter counter = registry.find("order.cancel.calls")
                .tag("class", "TestServiceImpl")
                .tag("method", "throwingMethod")
                .tag("status", "error")
                .tag("exception", "IllegalArgumentException")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        Timer timer = registry.find("order.cancel")
                .tag("class", "TestServiceImpl")
                .tag("method", "throwingMethod")
                .tag("status", "error")
                .tag("exception", "IllegalArgumentException")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("细粒度度量开闭：recordDuration=false 时跳过耗时直方图采集")
    void testDisabledDurationOption() {
        TestService proxy = createProxy(new TestServiceImpl());
        proxy.disabledDurationMethod();

        // Counter 正常记录
        assertThat(registry.find("query.fast.calls").counter()).isNotNull();
        // Timer 不记录
        assertThat(registry.find("query.fast").timer()).isNull();
    }

    @Test
    @DisplayName("细粒度度量开闭：recordSuccess=false 时跳过调用计数")
    void testDisabledCountersOption() {
        TestService proxy = createProxy(new TestServiceImpl());
        proxy.disabledCountersMethod();

        // Counter 不记录
        assertThat(registry.find("query.nocounter.calls").counter()).isNull();
        // Timer 正常记录
        assertThat(registry.find("query.nocounter").timer()).isNotNull();
    }

    @Test
    @DisplayName("类级别注解继承：类上标注 @ObservedMetric 时，类内方法自动透明度量")
    void testClassLevelAnnotation() {
        ClassLevelAnnotatedService target = new ClassLevelAnnotatedService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new ObservedMetricAspect(metricsTemplate));
        ClassLevelAnnotatedService proxy = factory.getProxy();

        String result = proxy.doWork();
        assertThat(result).isEqualTo("work-done");

        Counter counter = registry.find("class.level.metric.calls")
                .tag("class", "ClassLevelAnnotatedService")
                .tag("method", "doWork")
                .tag("tier", "standard")
                .tag("status", "success")
                .tag("exception", "none")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("直方图与 SLO 耗时桶：开启 histogram=true 与 slaMs 时向 Prometheus 暴露 _bucket 桶与分位数")
    void testHistogramAndSlaBucketsPublished() {
        PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        AspectJProxyFactory factory = new AspectJProxyFactory(new TestServiceImpl());
        factory.addAspect(new ObservedMetricAspect(new MetricsTemplate(prometheusRegistry)));
        TestService proxy = factory.getProxy();

        String result = proxy.settleWithHistogramAndSla();
        assertThat(result).isEqualTo("settled");

        Timer timer = prometheusRegistry.find("order.settle").timer();
        assertThat(timer).isNotNull();
        double[] bucketsMs = Arrays.stream(timer.takeSnapshot().histogramCounts())
                .mapToDouble(b -> b.bucket(TimeUnit.MILLISECONDS))
                .toArray();
        assertThat(bucketsMs).contains(50.0, 100.0, 500.0);

        double[] snapshotPercentiles = Arrays.stream(timer.takeSnapshot().percentileValues())
                .mapToDouble(p -> p.percentile())
                .toArray();
        assertThat(snapshotPercentiles).containsExactly(0.95, 0.99);

        String scrape = prometheusRegistry.scrape();
        assertThat(scrape)
                .contains("# TYPE order_settle_seconds histogram")
                .contains("order_settle_seconds_bucket{")
                .contains("le=\"0.05\"")
                .contains("le=\"0.1\"")
                .contains("le=\"0.5\"")
                .contains("le=\"+Inf\"");
    }

    @Test
    @DisplayName("非正数 slaMs 过滤与默认向后兼容：忽略 <=0 阈值，默认不发布直方图桶")
    void testNonPositiveSlaFilteredAndBackwardCompatible() {
        TestService proxy = createProxy(new TestServiceImpl());

        // 默认配置：histogram=false, slaMs={} 不生成直方图桶
        proxy.defaultObservedMethod("compat");
        Timer defaultTimer = registry.find("method.execution").timer();
        assertThat(defaultTimer).isNotNull();
        assertThat(defaultTimer.takeSnapshot().histogramCounts()).isEmpty();

        // 混合正数与非正数 slaMs：仅保留大于 0 的 50ms 与 200ms
        proxy.settleWithInvalidSlaFiltered();
        Timer filteredTimer = registry.find("order.sla.filtered").timer();
        assertThat(filteredTimer).isNotNull();
        CountAtBucket[] filteredBuckets = filteredTimer.takeSnapshot().histogramCounts();
        assertThat(filteredBuckets).hasSize(2);
        double[] filteredMs = Arrays.stream(filteredBuckets)
                .mapToDouble(b -> b.bucket(TimeUnit.MILLISECONDS))
                .toArray();
        assertThat(filteredMs).containsExactly(50.0, 200.0);

        // 全部非正数 slaMs：安全忽略，不抛异常，不注册 SLO 桶
        proxy.settleWithAllInvalidSla();
        Timer allInvalidTimer = registry.find("order.sla.all_invalid").timer();
        assertThat(allInvalidTimer).isNotNull();
        assertThat(allInvalidTimer.takeSnapshot().histogramCounts()).isEmpty();
    }
}
