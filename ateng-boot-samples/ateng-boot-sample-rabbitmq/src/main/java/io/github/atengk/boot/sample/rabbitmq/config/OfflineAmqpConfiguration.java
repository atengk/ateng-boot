package io.github.atengk.boot.sample.rabbitmq.config;

import io.github.atengk.boot.sample.rabbitmq.offline.OfflineAmqpBroker;
import io.github.atengk.boot.sample.rabbitmq.offline.OfflineAmqpProxyFactory;
import io.github.atengk.boot.sample.rabbitmq.offline.OfflineRabbitTemplate;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * 离线自洽 AMQP 仿真自动装配（@Profile("!local")）
 * 在默认本地开发与 CI 环境下接管 ConnectionFactory 与 RabbitTemplate，零外部 Broker 依赖即可启动全部演示接口与端到端测试；
 * 当激活 local Profile 联调真实服务端时自动失效
 *
 * @author Ateng
 * @since 2026-11-15
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local")
public class OfflineAmqpConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OfflineAmqpBroker offlineAmqpBroker(
            ObjectProvider<MessageConverter> messageConverterProvider,
            ListableBeanFactory beanFactory) {
        return new OfflineAmqpBroker(messageConverterProvider, beanFactory);
    }

    @Bean
    @Primary
    public ConnectionFactory offlineConnectionFactory(OfflineAmqpBroker broker) {
        return OfflineAmqpProxyFactory.createConnectionFactory(broker);
    }

    @Bean
    @Primary
    public RabbitTemplate offlineRabbitTemplate(
            ConnectionFactory connectionFactory,
            OfflineAmqpBroker broker,
            ObjectProvider<RabbitTemplateCustomizer> customizers) {
        OfflineRabbitTemplate template = new OfflineRabbitTemplate(connectionFactory, broker);
        customizers.orderedStream().forEach(customizer -> customizer.customize(template));
        return template;
    }
}
