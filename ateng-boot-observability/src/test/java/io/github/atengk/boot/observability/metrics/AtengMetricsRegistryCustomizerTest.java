package io.github.atengk.boot.observability.metrics;

import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengMetricsRegistryCustomizer 全局标签与高基数防爆治理单元测试
 *
 * @author Ateng
 * @since 2026-11-16
 */
@DisplayName("AtengMetricsRegistryCustomizer 全局标签与高基数熔断单元测试")
class AtengMetricsRegistryCustomizerTest {

    @Test
    @DisplayName("全局公共标签注入：自动挂载 application 与 commonTags，支持开关控制")
    void testCommonTagsAndApplicationTagInjection() {
        ObservabilityProperties props = new ObservabilityProperties();
        props.getMetrics().setIncludeApplicationTag(true);
        props.getMetrics().setCommonTags(Map.of("env", "prod", "region", "cn-east"));

        MockEnvironment env = new MockEnvironment().withProperty("spring.application.name", "order-service");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new AtengMetricsRegistryCustomizer(props, env).customize(registry);

        Counter counter = registry.counter("biz.orders.total", "channel", "app");
        counter.increment();

        assertThat(counter.getId().getTag("application")).isEqualTo("order-service");
        assertThat(counter.getId().getTag("env")).isEqualTo("prod");
        assertThat(counter.getId().getTag("region")).isEqualTo("cn-east");
        assertThat(counter.getId().getTag("channel")).isEqualTo("app");

        // 关闭 includeApplicationTag 后不再自动注入 application 标签
        ObservabilityProperties noAppProps = new ObservabilityProperties();
        noAppProps.getMetrics().setIncludeApplicationTag(false);
        SimpleMeterRegistry noAppRegistry = new SimpleMeterRegistry();
        new AtengMetricsRegistryCustomizer(noAppProps, env).customize(noAppRegistry);

        Counter noAppCounter = noAppRegistry.counter("biz.orders.total");
        assertThat(noAppCounter.getId().getTag("application")).isNull();

        // commonTags 显式配置 application 时应覆盖自动推导的 spring.application.name
        ObservabilityProperties overrideProps = new ObservabilityProperties();
        overrideProps.getMetrics().setIncludeApplicationTag(true);
        Map<String, String> overrideTags = new LinkedHashMap<>();
        overrideTags.put("application", "custom-override-app");
        overrideProps.getMetrics().setCommonTags(overrideTags);
        SimpleMeterRegistry overrideRegistry = new SimpleMeterRegistry();
        new AtengMetricsRegistryCustomizer(overrideProps, env).customize(overrideRegistry);

        Counter overrideCounter = overrideRegistry.counter("biz.orders.total");
        assertThat(overrideCounter.getId().getTag("application")).isEqualTo("custom-override-app");
    }

    @Test
    @DisplayName("超长标签值截断：超出 maxTagValueLength 自动截断，小于等于 0 时不限制")
    void testTagValueTruncation() {
        ObservabilityProperties props = new ObservabilityProperties();
        props.getMetrics().setIncludeApplicationTag(false);
        props.getMetrics().setMaxTagValueLength(10);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new AtengMetricsRegistryCustomizer(props, new MockEnvironment()).customize(registry);

        String longValue = "0123456789abcdefghijklmnopqrstuvwxyz";
        Counter counter = registry.counter("biz.api.requests", "path", longValue);
        assertThat(counter.getId().getTag("path")).isEqualTo("0123456789");

        // maxTagValueLength <= 0 时不截断
        ObservabilityProperties unlimitedProps = new ObservabilityProperties();
        unlimitedProps.getMetrics().setIncludeApplicationTag(false);
        unlimitedProps.getMetrics().setMaxTagValueLength(0);

        SimpleMeterRegistry unlimitedRegistry = new SimpleMeterRegistry();
        new AtengMetricsRegistryCustomizer(unlimitedProps, new MockEnvironment()).customize(unlimitedRegistry);
        Counter unlimitedCounter = unlimitedRegistry.counter("biz.api.requests", "path", longValue);
        assertThat(unlimitedCounter.getId().getTag("path")).isEqualTo(longValue);
    }

    @Test
    @DisplayName("单指标标签基数上限熔断：超限序列被拒收，已有序列与移除重注不受影响，并发写下严格封顶")
    void testCardinalityCircuitBreakerAndConcurrency() throws Exception {
        ObservabilityProperties props = new ObservabilityProperties();
        props.getMetrics().setIncludeApplicationTag(false);
        props.getMetrics().setMaxMeterCardinality(3);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new AtengMetricsRegistryCustomizer(props, new MockEnvironment()).customize(registry);

        Counter c1 = registry.counter("orders.count", "userId", "u1");
        c1.increment();
        registry.counter("orders.count", "userId", "u2").increment();
        registry.counter("orders.count", "userId", "u3").increment();

        // 已注册的序列可继续累加
        registry.counter("orders.count", "userId", "u1").increment();
        assertThat(c1.count()).isEqualTo(2.0);

        // 超出基数上限的全新序列被拦截拒收
        registry.counter("orders.count", "userId", "u4").increment();
        registry.counter("orders.count", "userId", "u5").increment();
        assertThat(registry.find("orders.count").counters()).hasSize(3);

        // 其他指标名称拥有独立基数配额
        registry.counter("payments.count", "channel", "alipay").increment();
        assertThat(registry.find("payments.count").counters()).hasSize(1);

        // 注销已有指标后释放配额，允许新序列注册；全部清空后重建追踪器仍可正常工作
        registry.remove(c1);
        registry.counter("orders.count", "userId", "u4").increment();
        assertThat(registry.find("orders.count").counters()).hasSize(3);
        new ArrayList<>(registry.find("orders.count").counters()).forEach(registry::remove);
        assertThat(registry.find("orders.count").counters()).isEmpty();
        registry.counter("orders.count", "userId", "u6").increment();
        assertThat(registry.find("orders.count").counters()).hasSize(1);

        // maxMeterCardinality <= 0 时关闭基数限制
        ObservabilityProperties unlimitedProps = new ObservabilityProperties();
        unlimitedProps.getMetrics().setIncludeApplicationTag(false);
        unlimitedProps.getMetrics().setMaxMeterCardinality(0);
        SimpleMeterRegistry unlimitedRegistry = new SimpleMeterRegistry();
        new AtengMetricsRegistryCustomizer(unlimitedProps, new MockEnvironment()).customize(unlimitedRegistry);
        for (int i = 0; i < 6; i++) {
            unlimitedRegistry.counter("unlimited.orders", "userId", "u" + i).increment();
        }
        assertThat(unlimitedRegistry.find("unlimited.orders").counters()).hasSize(6);

        // 并发基数冲击：多线程同时注册不同维度组合，严格封顶于 maxMeterCardinality
        ObservabilityProperties concurrentProps = new ObservabilityProperties();
        concurrentProps.getMetrics().setIncludeApplicationTag(false);
        concurrentProps.getMetrics().setMaxMeterCardinality(5);
        SimpleMeterRegistry concurrentRegistry = new SimpleMeterRegistry();
        new AtengMetricsRegistryCustomizer(concurrentProps, new MockEnvironment()).customize(concurrentRegistry);

        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(8);
        pool.setMaxPoolSize(8);
        pool.setThreadNamePrefix("card-test-");
        pool.initialize();

        try {
            int attempts = 24;
            CountDownLatch startGate = new CountDownLatch(1);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                final int idx = i;
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        startGate.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    concurrentRegistry.counter("concurrent.orders", "trace", "t-" + idx).increment();
                }, pool));
            }
            startGate.countDown();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
            assertThat(concurrentRegistry.find("concurrent.orders").counters()).hasSize(5);
        } finally {
            pool.destroy();
        }
    }

    @Test
    @DisplayName("前缀黑名单过滤与多注册表隔离：命中 denyMetricPrefixes 指标不注册")
    void testDenyMetricPrefixesAndRegistryIsolation() {
        ObservabilityProperties props = new ObservabilityProperties();
        props.getMetrics().setIncludeApplicationTag(false);
        props.getMetrics().setMaxMeterCardinality(1);
        props.getMetrics().setDenyMetricPrefixes(List.of("jvm.gc.pause", "noise."));

        AtengMetricsRegistryCustomizer customizer = new AtengMetricsRegistryCustomizer(props, new MockEnvironment());

        SimpleMeterRegistry regA = new SimpleMeterRegistry();
        SimpleMeterRegistry regB = new SimpleMeterRegistry();
        customizer.customize(regA);
        customizer.customize(regB);

        regA.counter("jvm.gc.pause").increment();
        regA.counter("noise.debug.latency").increment();
        regA.counter("jvm.memory.used", "area", "heap").increment();

        assertThat(regA.find("jvm.gc.pause").counters()).isEmpty();
        assertThat(regA.find("noise.debug.latency").counters()).isEmpty();
        assertThat(regA.find("jvm.memory.used").counters()).hasSize(1);

        // 注册表 B 的基数配额不受注册表 A 影响
        regB.counter("jvm.memory.used", "area", "nonheap").increment();
        assertThat(regB.find("jvm.memory.used").counters()).hasSize(1);
    }

    @Test
    @DisplayName("PrometheusMeterRegistry 端到端集成：公共标签、超长截断、基数熔断与前缀过滤同步生效")
    void testPrometheusMeterRegistryGovernance() {
        ObservabilityProperties props = new ObservabilityProperties();
        props.getMetrics().setIncludeApplicationTag(true);
        props.getMetrics().setCommonTags(Map.of("env", "prod"));
        props.getMetrics().setMaxTagValueLength(16);
        props.getMetrics().setMaxMeterCardinality(2);
        props.getMetrics().setDenyMetricPrefixes(List.of("debug."));

        PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MockEnvironment env = new MockEnvironment().withProperty("spring.application.name", "gateway-service");
        new AtengMetricsRegistryCustomizer(props, env).customize(prometheusRegistry);

        prometheusRegistry.counter("http.requests", "uri", "/api/v1/very/long/path").increment();
        prometheusRegistry.counter("http.requests", "uri", "/health").increment();
        prometheusRegistry.counter("http.requests", "uri", "/overflow").increment();
        prometheusRegistry.counter("debug.trace").increment();

        String scrape = prometheusRegistry.scrape();
        assertThat(scrape)
                .contains("http_requests_total")
                .contains("application=\"gateway-service\"")
                .contains("env=\"prod\"")
                .contains("uri=\"/api/v1/very/lon\"")
                .doesNotContain("uri=\"/overflow\"")
                .doesNotContain("debug_trace");
    }
}
