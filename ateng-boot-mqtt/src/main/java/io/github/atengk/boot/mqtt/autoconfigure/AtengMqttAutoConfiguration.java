package io.github.atengk.boot.mqtt.autoconfigure;

import io.github.atengk.boot.mqtt.actuator.MqttHealthIndicator;
import io.github.atengk.boot.mqtt.codec.CompositeMqttMessageConverter;
import io.github.atengk.boot.mqtt.codec.MqttMessageConverter;
import io.github.atengk.boot.mqtt.defense.PayloadGuard;
import io.github.atengk.boot.mqtt.handler.DefaultMqttSendFailureHandler;
import io.github.atengk.boot.mqtt.handler.MqttSendFailureHandler;
import io.github.atengk.boot.mqtt.listener.MqttListenerAnnotationBeanPostProcessor;
import io.github.atengk.boot.mqtt.listener.MqttListenerEndpointRegistry;
import io.github.atengk.boot.mqtt.metrics.MqttMetrics;
import io.github.atengk.boot.mqtt.properties.AtengMqttProperties;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import io.github.atengk.boot.mqtt.requestreply.MqttRequestReplyManager;
import io.github.atengk.boot.mqtt.template.MqttTemplate;
import io.github.atengk.boot.mqtt.will.MqttWillProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * MQTT 基础设施与多客户端自动装配类
 *
 * @author Ateng
 * @since 2026-10-23
 */
@AutoConfiguration
@ConditionalOnClass(MqttAsyncClient.class)
@ConditionalOnProperty(prefix = "ateng.boot.mqtt", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AtengMqttProperties.class)
public class AtengMqttAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NamedMqttClientRegistry namedMqttClientRegistry(AtengMqttProperties properties,
                                                           ObjectProvider<MqttWillProvider> willProvider) {
        return new NamedMqttClientRegistry(properties, willProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public PayloadGuard mqttPayloadGuard() {
        return new PayloadGuard();
    }

    @Bean
    @ConditionalOnMissingBean
    public MqttSendFailureHandler mqttSendFailureHandler() {
        return new DefaultMqttSendFailureHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public CompositeMqttMessageConverter mqttMessageConverter(ObjectProvider<List<MqttMessageConverter>> customConverters) {
        CompositeMqttMessageConverter composite = new CompositeMqttMessageConverter();
        List<MqttMessageConverter> list = customConverters.getIfAvailable();
        if (list != null) {
            for (MqttMessageConverter converter : list) {
                if (!(converter instanceof CompositeMqttMessageConverter)) {
                    composite.addConverter(converter);
                }
            }
        }
        return composite;
    }

    @Bean(name = "mqttTaskExecutor")
    @ConditionalOnMissingBean(name = {"mqttTaskExecutor", "taskExecutor"})
    public ThreadPoolTaskExecutor mqttTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(2, Runtime.getRuntime().availableProcessors()));
        executor.setMaxPoolSize(Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("ateng-mqtt-");
        executor.initialize();
        return executor;
    }

    @Bean
    @ConditionalOnMissingBean
    public MqttRequestReplyManager mqttRequestReplyManager(NamedMqttClientRegistry namedMqttClientRegistry,
                                                           PayloadGuard mqttPayloadGuard,
                                                           @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider,
                                                           @Qualifier("mqttTaskExecutor") ObjectProvider<Executor> mqttExecutorProvider) {
        Executor executor = taskExecutorProvider.getIfAvailable(mqttExecutorProvider::getIfAvailable);
        return new MqttRequestReplyManager(namedMqttClientRegistry, mqttPayloadGuard, executor);
    }

    @Bean
    @ConditionalOnMissingBean
    public MqttTemplate mqttTemplate(NamedMqttClientRegistry namedMqttClientRegistry,
                                    PayloadGuard mqttPayloadGuard,
                                    MqttSendFailureHandler mqttSendFailureHandler,
                                    MqttRequestReplyManager mqttRequestReplyManager,
                                    ObjectProvider<MqttMessageConverter> messageConverterProvider,
                                    ObjectProvider<MqttMetrics> metricsProvider,
                                    @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider,
                                    @Qualifier("mqttTaskExecutor") ObjectProvider<Executor> mqttExecutorProvider) {
        Executor executor = taskExecutorProvider.getIfAvailable(mqttExecutorProvider::getIfAvailable);
        return new MqttTemplate(
                namedMqttClientRegistry,
                mqttPayloadGuard,
                mqttSendFailureHandler,
                executor,
                mqttRequestReplyManager,
                messageConverterProvider.getIfAvailable(),
                metricsProvider.getIfAvailable()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public MqttListenerEndpointRegistry mqttListenerEndpointRegistry(NamedMqttClientRegistry namedMqttClientRegistry,
                                                                    PayloadGuard mqttPayloadGuard,
                                                                    ObjectProvider<MqttMetrics> metricsProvider,
                                                                    @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider,
                                                                    @Qualifier("mqttTaskExecutor") ObjectProvider<Executor> mqttExecutorProvider) {
        Executor executor = taskExecutorProvider.getIfAvailable(mqttExecutorProvider::getIfAvailable);
        return new MqttListenerEndpointRegistry(namedMqttClientRegistry, mqttPayloadGuard, executor, metricsProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public static MqttListenerAnnotationBeanPostProcessor mqttListenerAnnotationBeanPostProcessor(
            MqttListenerEndpointRegistry mqttListenerEndpointRegistry,
            ObjectProvider<MqttMessageConverter> messageConverterProvider) {
        return new MqttListenerAnnotationBeanPostProcessor(mqttListenerEndpointRegistry, messageConverterProvider.getIfAvailable());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnProperty(prefix = "management.health.mqtt", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class MqttActuatorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public MqttHealthIndicator mqttHealthIndicator(NamedMqttClientRegistry namedMqttClientRegistry) {
            return new MqttHealthIndicator(namedMqttClientRegistry);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "management.metrics.export.mqtt", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class MqttMetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(MeterRegistry.class)
        public MqttMetrics mqttMetrics(MeterRegistry meterRegistry) {
            return new MqttMetrics(meterRegistry);
        }
    }
}
