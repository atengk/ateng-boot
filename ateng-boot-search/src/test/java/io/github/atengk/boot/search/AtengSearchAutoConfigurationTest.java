package io.github.atengk.boot.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.github.atengk.boot.search.autoconfigure.AtengSearchAutoConfiguration;
import io.github.atengk.boot.search.bulk.SearchBulkProcessor;
import io.github.atengk.boot.search.enums.SearchEngineType;
import io.github.atengk.boot.search.spi.SearchEngineClient;
import io.github.atengk.boot.search.template.SearchTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 搜索自动装配与条件守卫测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class AtengSearchAutoConfigurationTest {

    @org.junit.jupiter.api.AfterEach
    void cleanupTenantContext() {
        io.github.atengk.boot.core.context.TenantContextHolder.clear();
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengSearchAutoConfiguration.class));

    @Test
    @DisplayName("测试默认配置下 SearchEngineClient、SearchTemplate 与 SearchBulkProcessor 成功自动装配")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SearchEngineClient.class);
            assertThat(context).hasSingleBean(SearchTemplate.class);
            assertThat(context).hasSingleBean(SearchBulkProcessor.class);
            io.github.atengk.boot.search.properties.AtengSearchProperties props =
                    context.getBean(io.github.atengk.boot.search.properties.AtengSearchProperties.class);
            assertThat(props.getConnectionRequestTimeout()).isEqualTo(java.time.Duration.ofSeconds(3));
            assertThat(props.getMultiTenancy().getIsolationMode())
                    .isEqualTo(io.github.atengk.boot.search.enums.TenantIsolationMode.STRICT);
            assertThat(io.github.atengk.boot.search.enums.SearchResultCode.SEARCH_TENANT_ACCESS_DENIED.getCode())
                    .isEqualTo(60017);
        });
    }

    @Test
    @DisplayName("测试 TenantIsolationMode 枚举配置绑定与 @IgnoreTenant 方法级 AOP 豁免")
    void testTenantIsolationModeAndIgnoreTenantMethodAdvisor() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.aop.AopAutoConfiguration.class,
                        AtengSearchAutoConfiguration.class
                ))
                .withPropertyValues("ateng.boot.search.multi-tenancy.isolation-mode=filter-only")
                .withUserConfiguration(IgnoreTenantMethodServiceConfiguration.class)
                .run(context -> {
                    io.github.atengk.boot.search.properties.AtengSearchProperties props =
                            context.getBean(io.github.atengk.boot.search.properties.AtengSearchProperties.class);
                    assertThat(props.getMultiTenancy().getIsolationMode())
                            .isEqualTo(io.github.atengk.boot.search.enums.TenantIsolationMode.FILTER_ONLY);

                    IgnoreTenantSampleService service = context.getBean(IgnoreTenantSampleService.class);
                    io.github.atengk.boot.core.context.TenantContextHolder.runWithTenant("tenant_east", () -> {
                        assertThat(io.github.atengk.boot.core.context.TenantContextHolder.isIgnoreTenant()).isFalse();
                        assertThat(service.readAcrossTenants()).isTrue();
                        assertThat(io.github.atengk.boot.core.context.TenantContextHolder.isIgnoreTenant()).isFalse();
                        assertThatThrownBy(service::throwCheckedFailure)
                                .isExactlyInstanceOf(IOException.class)
                                .hasMessage("checked boom");
                        assertThat(io.github.atengk.boot.core.context.TenantContextHolder.isIgnoreTenant()).isFalse();
                    });
                });
    }

    @Test
    @DisplayName("测试强类型超时与可观测配置属性绑定及 RestClientBuilderCustomizer 有序装配")
    void testCustomTimeoutAndRestClientCustomizerBinding() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.search.connection-request-timeout=7s",
                        "ateng.boot.search.observability.slow-query-threshold=250ms",
                        "ateng.boot.search.observability.log-slow-query-dsl=false",
                        "ateng.boot.search.observability.mask-vector-in-logs=false"
                )
                .withUserConfiguration(CustomizerConfiguration.class)
                .run(context -> {
                    io.github.atengk.boot.search.properties.AtengSearchProperties props =
                            context.getBean(io.github.atengk.boot.search.properties.AtengSearchProperties.class);
                    assertThat(props.getConnectionRequestTimeout()).isEqualTo(java.time.Duration.ofSeconds(7));
                    assertThat(props.getObservability().getSlowQueryThreshold()).isEqualTo(java.time.Duration.ofMillis(250));
                    assertThat(props.getObservability().isLogSlowQueryDsl()).isFalse();
                    assertThat(props.getObservability().isMaskVectorInLogs()).isFalse();
                    assertThat(context).hasSingleBean(io.github.atengk.boot.search.spi.RestClientBuilderCustomizer.class);
                });
    }

    @Test
    @DisplayName("测试配置 enabled=false 时关闭自动装配")
    void testDisabledAutoConfiguration() {
        contextRunner
                .withPropertyValues("ateng.boot.search.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SearchEngineClient.class);
                    assertThat(context).doesNotHaveBean(SearchTemplate.class);
                    assertThat(context).doesNotHaveBean(SearchBulkProcessor.class);
                });
    }

    @Test
    @DisplayName("测试配置 bulk.enabled=false 时仅关闭 SearchBulkProcessor 装配")
    void testDisabledBulkProcessorAutoConfiguration() {
        contextRunner
                .withPropertyValues("ateng.boot.search.bulk.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(SearchEngineClient.class);
                    assertThat(context).hasSingleBean(SearchTemplate.class);
                    assertThat(context).doesNotHaveBean(SearchBulkProcessor.class);
                });
    }

    @Test
    @DisplayName("测试用户自定义 Bean 时 @ConditionalOnMissingBean 平滑接管")
    void testCustomBeanTakesPrecedence() {
        contextRunner
                .withUserConfiguration(CustomSearchConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(SearchEngineClient.class);
                    SearchEngineClient client = context.getBean(SearchEngineClient.class);
                    assertThat(client.getEngineType()).isEqualTo(SearchEngineType.OPENSEARCH);
                });
    }

    @Test
    @DisplayName("测试装配类声明了 @AutoConfigureAfter 包含 JacksonAutoConfiguration 与 AtengHealthAutoConfiguration")
    void testAutoConfigureAfterJackson() {
        org.springframework.boot.autoconfigure.AutoConfigureAfter after =
                AtengSearchAutoConfiguration.class.getAnnotation(org.springframework.boot.autoconfigure.AutoConfigureAfter.class);
        assertThat(after).isNotNull();
        assertThat(after.name()).contains(
                "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration",
                "io.github.atengk.boot.observability.autoconfigure.AtengHealthAutoConfiguration"
        );
    }

    @Test
    @DisplayName("测试存在 searchSafeHealthIndicator 时原生 SearchHealthIndicator 主动让位避让")
    void testYieldToObservabilitySafeHealthIndicator() {
        contextRunner
                .withUserConfiguration(MetricsConfiguration.class, SafeHealthIndicatorConfiguration.class)
                .run(context -> {
                    assertThat(context).hasBean("searchSafeHealthIndicator");
                    assertThat(context).doesNotHaveBean(io.github.atengk.boot.search.actuator.SearchHealthIndicator.class);
                });
    }

    @Test
    @DisplayName("测试在存在 Actuator 与 MeterRegistry 环境下成功装配 SearchHealthIndicator 与 SearchMetrics")
    void testActuatorAndMetricsAutoConfiguration() {
        contextRunner
                .withUserConfiguration(MetricsConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.search.actuator.SearchHealthIndicator.class);
                    assertThat(context).hasSingleBean(io.github.atengk.boot.search.metrics.SearchMetrics.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class MetricsConfiguration {
        @Bean
        public io.micrometer.core.instrument.MeterRegistry meterRegistry() {
            return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomSearchConfiguration {

        @Bean
        public SearchEngineClient customSearchEngineClient() {
            return new SearchEngineClient() {
                @Override
                public SearchEngineType getEngineType() {
                    return SearchEngineType.OPENSEARCH;
                }

                @Override
                public <T> T getNativeClient(Class<T> clientClass) {
                    return null;
                }

                @Override
                public ElasticsearchClient getElasticsearchClient() {
                    throw new UnsupportedOperationException("Custom Mock");
                }

                @Override
                public void close() throws IOException {
                }
            };
        }
    }

    @Test
    @DisplayName("测试多 RestClientBuilderCustomizer 与 SearchInterceptor 按 @Order 有序装配并生效")
    void testOrderedCustomizersAndInterceptorsAutoConfiguration() throws Exception {
        OrderedExtensionsConfiguration.TRACE.clear();
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            server.enqueue(new okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("X-Elastic-Product", "Elasticsearch")
                    .setBody("{\"_index\":\"user_idx\",\"_id\":\"1\",\"found\":false}"));

            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AtengSearchAutoConfiguration.class))
                    .withUserConfiguration(OrderedExtensionsConfiguration.class)
                    .withPropertyValues("ateng.boot.search.uris[0]=http://" + server.getHostName() + ":" + server.getPort())
                    .run(context -> {
                        SearchTemplate template = context.getBean(SearchTemplate.class);
                        template.getById(io.github.atengk.boot.search.entity.UserDoc.class, "1");

                        okhttp3.mockwebserver.RecordedRequest request = server.takeRequest();
                        assertThat(request.getHeader("X-Order-Trace")).isEqualTo("second");
                        assertThat(OrderedExtensionsConfiguration.TRACE).containsExactly(
                                "customizer-1",
                                "customizer-2",
                                "before-1",
                                "before-2",
                                "after-2",
                                "after-1"
                        );
                    });
        } finally {
            OrderedExtensionsConfiguration.TRACE.clear();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomizerConfiguration {
        @Bean
        public io.github.atengk.boot.search.spi.RestClientBuilderCustomizer headerCustomizer() {
            return (builder, props) -> builder.setDefaultHeaders(
                    new org.apache.http.Header[]{new org.apache.http.message.BasicHeader("X-Custom-Client", "ateng")}
            );
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SafeHealthIndicatorConfiguration {
        @Bean(name = {"searchSafeHealthIndicator", "searchHealthIndicator"})
        public org.springframework.boot.actuate.health.HealthIndicator searchSafeHealthIndicator() {
            return () -> org.springframework.boot.actuate.health.Health.up().withDetail("safe", true).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OrderedExtensionsConfiguration {
        static final java.util.List<String> TRACE = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        @Bean
        @org.springframework.core.annotation.Order(200)
        public io.github.atengk.boot.search.spi.RestClientBuilderCustomizer secondCustomizer() {
            return (builder, props) -> {
                TRACE.add("customizer-2");
                builder.setDefaultHeaders(
                        new org.apache.http.Header[]{new org.apache.http.message.BasicHeader("X-Order-Trace", "second")}
                );
            };
        }

        @Bean
        @org.springframework.core.annotation.Order(100)
        public io.github.atengk.boot.search.spi.RestClientBuilderCustomizer firstCustomizer() {
            return (builder, props) -> {
                TRACE.add("customizer-1");
                builder.setDefaultHeaders(
                        new org.apache.http.Header[]{new org.apache.http.message.BasicHeader("X-Order-Trace", "first")}
                );
            };
        }

        @Bean
        @org.springframework.core.annotation.Order(20)
        public io.github.atengk.boot.search.spi.SearchInterceptor secondInterceptor() {
            return new io.github.atengk.boot.search.spi.SearchInterceptor() {
                @Override
                public void beforeExecute(io.github.atengk.boot.search.spi.SearchInvocationContext context) {
                    TRACE.add("before-2");
                }

                @Override
                public void afterExecute(io.github.atengk.boot.search.spi.SearchInvocationContext context, Object result) {
                    TRACE.add("after-2");
                }
            };
        }

        @Bean
        @org.springframework.core.annotation.Order(10)
        public io.github.atengk.boot.search.spi.SearchInterceptor firstInterceptor() {
            return new io.github.atengk.boot.search.spi.SearchInterceptor() {
                @Override
                public void beforeExecute(io.github.atengk.boot.search.spi.SearchInvocationContext context) {
                    TRACE.add("before-1");
                }

                @Override
                public void afterExecute(io.github.atengk.boot.search.spi.SearchInvocationContext context, Object result) {
                    TRACE.add("after-1");
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class IgnoreTenantMethodServiceConfiguration {
        @Bean
        public IgnoreTenantSampleService ignoreTenantSampleService() {
            return new IgnoreTenantSampleService();
        }
    }

    static class IgnoreTenantSampleService {
        @io.github.atengk.boot.search.annotation.IgnoreTenant
        public boolean readAcrossTenants() {
            return io.github.atengk.boot.core.context.TenantContextHolder.isIgnoreTenant();
        }

        @io.github.atengk.boot.search.annotation.IgnoreTenant
        public void throwCheckedFailure() throws IOException {
            throw new IOException("checked boom");
        }
    }
}
