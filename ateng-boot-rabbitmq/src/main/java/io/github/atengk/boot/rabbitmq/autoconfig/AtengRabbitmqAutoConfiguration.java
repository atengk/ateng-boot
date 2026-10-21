package io.github.atengk.boot.rabbitmq.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.rabbitmq.properties.RabbitmqProperties;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * RabbitMQ 生产级增强底座自动装配
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@AutoConfiguration(after = RabbitAutoConfiguration.class)
@ConditionalOnClass({RabbitTemplate.class, Channel.class})
@ConditionalOnProperty(prefix = "ateng.boot.rabbitmq", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RabbitmqProperties.class)
public class AtengRabbitmqAutoConfiguration {

    /**
     * 装配纯净安全、开启 INFERRED 泛型推断的 Jackson 消息转换器
     */
    @Bean
    @ConditionalOnMissingBean
    public MessageConverter messageConverter(ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper mapper = objectMapperProvider.getIfAvailable(JsonUtil::getObjectMapper);
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(mapper);
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.INFERRED);
        typeMapper.setTrustedPackages("*");
        converter.setJavaTypeMapper(typeMapper);
        converter.setAlwaysConvertToInferredType(true);
        return converter;
    }

    /**
     * 定制原生 RabbitTemplate：绑定消息转换器、强制路由退回标志与可观测性度量
     */
    @Bean
    @ConditionalOnMissingBean
    public RabbitTemplateCustomizer atengRabbitTemplateCustomizer(
            RabbitmqProperties properties,
            MessageConverter messageConverter,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        return rabbitTemplate -> {
            rabbitTemplate.setMessageConverter(messageConverter);
            if (properties.getReliable().isReturnsCallbackEnabled()) {
                rabbitTemplate.setMandatory(true);
            }
            observationRegistryProvider.ifAvailable(registry -> rabbitTemplate.setObservationEnabled(true));
        };
    }

    /**
     * 容器级别优雅停机排空定制器
     */
    @Bean
    @ConditionalOnMissingBean
    public ContainerCustomizer<SimpleMessageListenerContainer> atengContainerCustomizer(RabbitmqProperties properties) {
        return container -> container.setShutdownTimeout(properties.getConsumer().getShutdownTimeout().toMillis());
    }

    /**
     * 容器工厂与 RabbitAdmin 生命周期后置增强处理器
     */
    @Bean
    public static BeanPostProcessor atengRabbitLifecycleBeanPostProcessor(
            ObjectProvider<RabbitmqProperties> propertiesProvider,
            ObjectProvider<MessageConverter> messageConverterProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof SimpleRabbitListenerContainerFactory factory) {
                    RabbitmqProperties properties = propertiesProvider.getIfAvailable();
                    if (properties != null) {
                        RabbitmqProperties.Consumer consumer = properties.getConsumer();
                        factory.setPrefetchCount(consumer.getPrefetch());
                        factory.setConcurrentConsumers(consumer.getConcurrency());
                        factory.setMaxConcurrentConsumers(consumer.getMaxConcurrency());
                    }
                    messageConverterProvider.ifAvailable(factory::setMessageConverter);
                    observationRegistryProvider.ifAvailable(registry -> factory.setObservationEnabled(true));
                } else if (bean instanceof RabbitAdmin admin) {
                    RabbitmqProperties properties = propertiesProvider.getIfAvailable();
                    if (properties != null && !properties.getTopology().isAutoDeclare()) {
                        log.info("[RABBITMQ-TOPOLOGY] auto-declare=false，已禁用 RabbitAdmin 启动自动建队与声明拓扑");
                        admin.setAutoStartup(false);
                    }
                }
                return bean;
            }
        };
    }
}
