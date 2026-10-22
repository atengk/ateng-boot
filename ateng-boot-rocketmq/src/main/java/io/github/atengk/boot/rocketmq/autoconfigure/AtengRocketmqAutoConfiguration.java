package io.github.atengk.boot.rocketmq.autoconfigure;

import io.github.atengk.boot.rocketmq.checker.RocketmqTransactionChecker;
import io.github.atengk.boot.rocketmq.consumer.DefaultPushConsumerFactory;
import io.github.atengk.boot.rocketmq.consumer.PushConsumerFactory;
import io.github.atengk.boot.rocketmq.listener.RocketmqConsumerContainer;
import io.github.atengk.boot.rocketmq.properties.AtengRocketmqProperties;
import io.github.atengk.boot.rocketmq.spi.RocketmqLocalTransactionChecker;
import io.github.atengk.boot.rocketmq.spi.RocketmqTransactionFailureCallback;
import io.github.atengk.boot.rocketmq.template.RocketmqTemplate;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientConfigurationBuilder;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.ProducerBuilder;
import org.apache.rocketmq.client.apis.producer.TransactionChecker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * RocketMQ 5.x 生产级增强基础设施自动装配核心类
 *
 * <p>统一遵循防御性自动装配铁律，受控于 {@code ateng.boot.rocketmq.enabled} 配置开关。
 * 当未配置 {@code ateng.boot.rocketmq.endpoints} 时，框架执行平滑静默跳过，严禁在未配置关键外部连接信息时盲目初始化导致启动崩溃。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(Producer.class)
@ConditionalOnProperty(prefix = "ateng.boot.rocketmq", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AtengRocketmqProperties.class)
public class AtengRocketmqAutoConfiguration {

    private final AtengRocketmqProperties properties;

    public AtengRocketmqAutoConfiguration(AtengRocketmqProperties properties) {
        this.properties = properties;
        if (StringUtils.hasText(properties.getEndpoints())) {
            log.info("[ateng-boot-rocketmq] RocketMQ 5.x 增强基础设施自动装配已激活 (endpoints={}, requestTimeout={})",
                    properties.getEndpoints(),
                    properties.getRequestTimeout());
        } else {
            log.warn("[ateng-boot-rocketmq] 未配置 ateng.boot.rocketmq.endpoints，RocketMQ 增强底座已平滑静默跳过物理客户端初始化");
        }
    }

    /**
     * RocketMQ 客户端基础连接配置
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.rocketmq", name = "endpoints")
    public ClientConfiguration rocketmqClientConfiguration(AtengRocketmqProperties properties) {
        ClientConfigurationBuilder builder = ClientConfiguration.newBuilder()
                .setEndpoints(properties.getEndpoints())
                .setRequestTimeout(properties.getRequestTimeout());

        if (StringUtils.hasText(properties.getAccessKey()) && StringUtils.hasText(properties.getSecretKey())) {
            builder.setCredentialProvider(new StaticSessionCredentialsProvider(
                    properties.getAccessKey().trim(),
                    properties.getSecretKey().trim()));
        }
        return builder.build();
    }

    /**
     * RocketMQ 全局统一事务回查分发器
     */
    @Bean
    @ConditionalOnMissingBean(TransactionChecker.class)
    @ConditionalOnBean(ClientConfiguration.class)
    public RocketmqTransactionChecker rocketmqTransactionChecker(
            ObjectProvider<RocketmqLocalTransactionChecker> checkerProvider) {
        return new RocketmqTransactionChecker(checkerProvider);
    }

    /**
     * RocketMQ 原生底层 Producer
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnBean(ClientConfiguration.class)
    public Producer rocketmqProducer(
            ClientConfiguration clientConfiguration,
            AtengRocketmqProperties properties,
            ObjectProvider<TransactionChecker> transactionCheckerProvider) throws ClientException {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ProducerBuilder builder = provider.newProducerBuilder()
                .setClientConfiguration(clientConfiguration)
                .setMaxAttempts(properties.getProducer().getMaxAttempts());

        TransactionChecker checker = transactionCheckerProvider.getIfAvailable();
        if (checker != null) {
            builder.setTransactionChecker(checker);
        }

        if (properties.getProducer().getTopics() != null && !properties.getProducer().getTopics().isEmpty()) {
            builder.setTopics(properties.getProducer().getTopics().toArray(new String[0]));
        }
        return builder.build();
    }

    /**
     * RocketMQ 生产端高阶增强门面
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(Producer.class)
    public RocketmqTemplate rocketmqTemplate(
            Producer producer,
            AtengRocketmqProperties properties,
            ObjectProvider<RocketmqTransactionFailureCallback> failureCallbackProvider) {
        return new RocketmqTemplate(producer, properties, failureCallbackProvider);
    }

    /**
     * RocketMQ PushConsumer 构造工厂
     */
    @Bean
    @ConditionalOnMissingBean
    public PushConsumerFactory pushConsumerFactory() {
        return new DefaultPushConsumerFactory();
    }

    /**
     * RocketMQ 声明式消费端监听容器与生命周期管理器
     */
    @Bean
    @ConditionalOnMissingBean
    public RocketmqConsumerContainer rocketmqConsumerContainer(
            AtengRocketmqProperties properties,
            ObjectProvider<ClientConfiguration> clientConfigurationProvider,
            PushConsumerFactory pushConsumerFactory,
            ObjectProvider<Validator> validatorProvider) {
        return new RocketmqConsumerContainer(
                properties,
                clientConfigurationProvider,
                pushConsumerFactory,
                validatorProvider);
    }
}
