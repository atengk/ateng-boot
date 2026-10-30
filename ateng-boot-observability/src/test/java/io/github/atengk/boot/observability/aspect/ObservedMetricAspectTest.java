package io.github.atengk.boot.observability.aspect;

import io.github.atengk.boot.observability.annotation.ObservedMetric;
import io.github.atengk.boot.observability.metrics.MetricsTemplate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

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
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        // 校验 Timer (method.execution)
        Timer timer = registry.find("method.execution")
                .tag("class", "TestServiceImpl")
                .tag("method", "defaultObservedMethod")
                .tag("status", "success")
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
                .tag("region", "cn")
                .tag("channel", "app")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        Timer timer = registry.find("order.create")
                .tag("region", "cn")
                .tag("channel", "app")
                .tag("status", "success")
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
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }
}
