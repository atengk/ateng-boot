package io.github.atengk.boot.search.autoconfigure;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.search.actuator.SearchHealthIndicator;
import io.github.atengk.boot.search.bulk.BulkListener;
import io.github.atengk.boot.search.bulk.SearchBulkProcessor;
import io.github.atengk.boot.search.driver.ElasticsearchClientDriver;
import io.github.atengk.boot.search.metrics.SearchMetrics;
import io.github.atengk.boot.search.properties.AtengSearchProperties;
import io.github.atengk.boot.search.spi.SearchEngineClient;
import io.github.atengk.boot.search.template.SearchTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 搜索引擎自动装配配置类
 * <p>
 * 提供 SearchEngineClient 与 SearchTemplate 的防御性自动装配，
 * 支持云原生 Actuator 健康探针 SearchHealthIndicator 与 Micrometer 性能度量 SearchMetrics。
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Slf4j
@AutoConfiguration
@AutoConfigureAfter(name = {
        "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration"
})
@ConditionalOnClass(ElasticsearchClient.class)
@ConditionalOnProperty(prefix = "ateng.boot.search", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AtengSearchProperties.class)
public class AtengSearchAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(SearchEngineClient.class)
    public SearchEngineClient searchEngineClient(AtengSearchProperties properties,
                                                 ObjectProvider<ObjectMapper> objectMapperProvider) {
        log.info("Auto-configuring SearchEngineClient with engine: {}", properties.getEngine());
        return new ElasticsearchClientDriver(properties, objectMapperProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(SearchTemplate.class)
    public SearchTemplate searchTemplate(SearchEngineClient searchEngineClient,
                                         AtengSearchProperties properties,
                                         ObjectProvider<SearchMetrics> searchMetricsProvider) {
        log.info("Auto-configuring SearchTemplate");
        return new SearchTemplate(searchEngineClient, properties, searchMetricsProvider.getIfAvailable());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(SearchBulkProcessor.class)
    @ConditionalOnProperty(prefix = "ateng.boot.search.bulk", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SearchBulkProcessor searchBulkProcessor(SearchTemplate searchTemplate,
                                                   AtengSearchProperties properties,
                                                   ObjectProvider<BulkListener> bulkListenerProvider,
                                                   @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider,
                                                   @Qualifier("taskScheduler") ObjectProvider<ScheduledExecutorService> taskSchedulerProvider,
                                                   ObjectProvider<ScheduledExecutorService> scheduledExecutorProvider) {
        log.info("Auto-configuring SearchBulkProcessor with batchSize={}, flushInterval={}, bufferSize={}",
                properties.getBulk().getBatchSize(),
                properties.getBulk().getFlushInterval(),
                properties.getBulk().getBufferSize());
        SearchBulkProcessor.Builder builder = searchTemplate.newBulkProcessorBuilder();
        bulkListenerProvider.ifAvailable(builder::listener);
        taskExecutorProvider.ifAvailable(builder::executor);
        ScheduledExecutorService scheduler = taskSchedulerProvider.getIfAvailable(scheduledExecutorProvider::getIfAvailable);
        if (scheduler != null) {
            builder.scheduler(scheduler);
        }
        return builder.build();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnProperty(prefix = "management.health.search", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class SearchActuatorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public SearchHealthIndicator searchHealthIndicator(
                SearchEngineClient searchEngineClient,
                @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider) {
            log.info("Auto-configuring SearchHealthIndicator for Actuator health probing");
            return new SearchHealthIndicator(searchEngineClient, taskExecutorProvider.getIfAvailable());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "management.metrics.enable", name = "search", havingValue = "true", matchIfMissing = true)
    static class SearchMetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(MeterRegistry.class)
        public SearchMetrics searchMetrics(MeterRegistry meterRegistry) {
            log.info("Auto-configuring SearchMetrics for Micrometer performance monitoring");
            return new SearchMetrics(meterRegistry);
        }
    }
}
