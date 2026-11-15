package io.github.atengk.boot.sample.kafka.config;

import io.github.atengk.boot.kafka.spi.KafkaAdminClientFactory;
import io.github.atengk.boot.sample.kafka.offline.OfflineKafkaAdmin;
import io.github.atengk.boot.sample.kafka.offline.OfflineKafkaAdminClient;
import io.github.atengk.boot.sample.kafka.offline.OfflineKafkaBroker;
import io.github.atengk.boot.sample.kafka.offline.OfflineKafkaProxyFactory;
import io.github.atengk.boot.sample.kafka.offline.OfflineKafkaTemplate;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.kafka.support.converter.RecordMessageConverter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 离线运行环境 Kafka 基础设施配置（仅在未激活 {@code local} Profile 时生效），在底层驱动层替换为内存模拟 Broker、AdminClient、ConsumerFactory 与 KafkaTemplate，上层 Starter 门面与样例业务逻辑全真执行
 *
 * @author Ateng
 * @since 2026-11-15
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local")
public class OfflineKafkaConfiguration {

    /**
     * 注册全自洽内存模拟 Kafka Broker 单例
     */
    @Bean
    @ConditionalOnMissingBean
    public OfflineKafkaBroker offlineKafkaBroker() {
        return new OfflineKafkaBroker();
    }

    /**
     * 离线 {@link KafkaAdmin}，直接将拓扑声明同步至内存模拟 Broker
     */
    @Bean
    @Primary
    public KafkaAdmin kafkaAdmin(KafkaProperties kafkaProperties, OfflineKafkaBroker broker) {
        OfflineKafkaAdmin admin = new OfflineKafkaAdmin(kafkaProperties.buildAdminProperties(null), broker);
        admin.setAutoCreate(true);
        admin.setFatalIfBrokerNotAvailable(false);
        return admin;
    }

    /**
     * 注入离线 {@link KafkaAdminClientFactory} 扩展接缝，驱动 {@code KafkaOffsetManager} 与 {@code KafkaDeadLetterReplayTemplate} 全真执行
     */
    @Bean
    @Primary
    public KafkaAdminClientFactory offlineKafkaAdminClientFactory(OfflineKafkaBroker broker) {
        return properties -> new OfflineKafkaAdminClient(broker);
    }

    /**
     * 离线 {@link ProducerFactory}，执行全部 Starter Customizer 且屏蔽底层网络建连
     */
    @Bean
    @Primary
    public ProducerFactory<Object, Object> kafkaProducerFactory(
            KafkaProperties kafkaProperties,
            ObjectProvider<DefaultKafkaProducerFactoryCustomizer> customizers,
            OfflineKafkaBroker broker) {
        DefaultKafkaProducerFactory<Object, Object> factory =
                new DefaultKafkaProducerFactory<>(kafkaProperties.buildProducerProperties(null)) {
                    @Override
                    protected Producer<Object, Object> createRawProducer(Map<String, Object> rawConfigs) {
                        return OfflineKafkaProxyFactory.createProducer(broker);
                    }

                    @Override
                    public ProducerFactory<Object, Object> copyWithConfigurationOverride(Map<String, Object> overrideConfigs) {
                        Map<String, Object> merged = new LinkedHashMap<>(getConfigurationProperties());
                        if (overrideConfigs != null) {
                            merged.putAll(overrideConfigs);
                        }
                        return new DefaultKafkaProducerFactory<>(merged) {
                            @Override
                            protected Producer<Object, Object> createRawProducer(Map<String, Object> rawConfigs) {
                                return OfflineKafkaProxyFactory.createProducer(broker);
                            }
                        };
                    }
                };
        customizers.orderedStream().forEach(customizer -> customizer.customize(factory));
        return factory;
    }

    /**
     * 离线 {@link ConsumerFactory}，保留 {@code DefaultKafkaConsumerFactoryCustomizer} 链路并将拉取/位点语义路由至内存 Broker
     */
    @Bean
    @Primary
    public ConsumerFactory<Object, Object> kafkaConsumerFactory(
            KafkaProperties kafkaProperties,
            ObjectProvider<DefaultKafkaConsumerFactoryCustomizer> customizers,
            OfflineKafkaBroker broker) {
        DefaultKafkaConsumerFactory<Object, Object> factory =
                new DefaultKafkaConsumerFactory<>(kafkaProperties.buildConsumerProperties(null)) {
                    @Override
                    protected Consumer<Object, Object> createKafkaConsumer(
                            String groupId,
                            String clientIdPrefix,
                            String clientIdSuffix,
                            Properties properties) {
                        Map<String, Object> merged = new LinkedHashMap<>(getConfigurationProperties());
                        if (properties != null) {
                            properties.forEach((k, v) -> {
                                if (k != null && v != null) {
                                    merged.put(String.valueOf(k), v);
                                }
                            });
                        }
                        return OfflineKafkaProxyFactory.createConsumer(broker, groupId, merged);
                    }
                };
        customizers.orderedStream().forEach(customizer -> customizer.customize(factory));
        return factory;
    }

    /**
     * 离线 {@link KafkaTemplate}，保留拦截器、转换器与本地事务原子发布语义
     */
    @Bean
    @Primary
    public KafkaTemplate<Object, Object> kafkaTemplate(
            ProducerFactory<Object, Object> kafkaProducerFactory,
            ObjectProvider<ProducerListener<Object, Object>> producerListener,
            ObjectProvider<RecordMessageConverter> messageConverter,
            OfflineKafkaBroker broker) {
        OfflineKafkaTemplate template = new OfflineKafkaTemplate(kafkaProducerFactory, broker);
        producerListener.ifAvailable(template::setProducerListener);
        messageConverter.ifUnique(template::setMessageConverter);
        return template;
    }
}
