package io.github.atengk.boot.mqtt.autoconfigure;

import io.github.atengk.boot.mqtt.defense.PayloadGuard;
import io.github.atengk.boot.mqtt.handler.DefaultMqttSendFailureHandler;
import io.github.atengk.boot.mqtt.handler.MqttSendFailureHandler;
import io.github.atengk.boot.mqtt.properties.AtengMqttProperties;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import io.github.atengk.boot.mqtt.template.MqttTemplate;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
    public NamedMqttClientRegistry namedMqttClientRegistry(AtengMqttProperties properties) {
        return new NamedMqttClientRegistry(properties);
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
    public MqttTemplate mqttTemplate(NamedMqttClientRegistry namedMqttClientRegistry,
                                    PayloadGuard mqttPayloadGuard,
                                    MqttSendFailureHandler mqttSendFailureHandler) {
        return new MqttTemplate(namedMqttClientRegistry, mqttPayloadGuard, mqttSendFailureHandler);
    }
}
