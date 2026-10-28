package io.github.atengk.boot.rocketmq.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengRocketmqProperties 单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("AtengRocketmqProperties 配置属性单元测试")
class AtengRocketmqPropertiesTest {

    @Test
    @DisplayName("验证默认生产合理配置值")
    void testDefaultValues() {
        AtengRocketmqProperties properties = new AtengRocketmqProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getEndpoints()).isNull();
        assertThat(properties.getAccessKey()).isNull();
        assertThat(properties.getSecretKey()).isNull();
        assertThat(properties.getRequestTimeout()).isEqualTo(Duration.ofSeconds(3));

        // 生产端默认值
        assertThat(properties.getProducer().getSendTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.getProducer().getMaxAttempts()).isEqualTo(3);
        assertThat(properties.getProducer().getMaxPayloadSize()).isEqualTo(DataSize.ofMegabytes(4));
        assertThat(properties.getProducer().getCompressionThreshold()).isEqualTo(DataSize.ofKilobytes(4));
        assertThat(properties.getProducer().getTopics()).isEmpty();

        // 消费端默认值
        assertThat(properties.getConsumer().getConsumptionThreadCount()).isEqualTo(20);
        assertThat(properties.getConsumer().getMaxCacheMessageCount()).isEqualTo(1024);
        assertThat(properties.getConsumer().getCloseTimeout()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("验证自定义配置属性绑定生效")
    void testCustomBinding() {
        Map<String, Object> map = new HashMap<>();
        map.put("ateng.boot.rocketmq.enabled", "false");
        map.put("ateng.boot.rocketmq.endpoints", "192.168.1.100:8081");
        map.put("ateng.boot.rocketmq.access-key", "my-ak");
        map.put("ateng.boot.rocketmq.secret-key", "my-sk");
        map.put("ateng.boot.rocketmq.request-timeout", "5s");
        map.put("ateng.boot.rocketmq.producer.send-timeout", "6s");
        map.put("ateng.boot.rocketmq.producer.max-attempts", "5");
        map.put("ateng.boot.rocketmq.producer.max-payload-size", "8MB");
        map.put("ateng.boot.rocketmq.producer.compression-threshold", "2KB");
        map.put("ateng.boot.rocketmq.producer.topics[0]", "order-topic");
        map.put("ateng.boot.rocketmq.consumer.consumption-thread-count", "30");
        map.put("ateng.boot.rocketmq.consumer.max-cache-message-count", "2048");
        map.put("ateng.boot.rocketmq.consumer.close-timeout", "20s");

        ConfigurationPropertySource source = new MapConfigurationPropertySource(map);
        Binder binder = new Binder(source);
        AtengRocketmqProperties properties = binder.bind("ateng.boot.rocketmq", AtengRocketmqProperties.class).get();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getEndpoints()).isEqualTo("192.168.1.100:8081");
        assertThat(properties.getAccessKey()).isEqualTo("my-ak");
        assertThat(properties.getSecretKey()).isEqualTo("my-sk");
        assertThat(properties.getRequestTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getProducer().getSendTimeout()).isEqualTo(Duration.ofSeconds(6));
        assertThat(properties.getProducer().getMaxAttempts()).isEqualTo(5);
        assertThat(properties.getProducer().getMaxPayloadSize()).isEqualTo(DataSize.ofMegabytes(8));
        assertThat(properties.getProducer().getCompressionThreshold()).isEqualTo(DataSize.ofKilobytes(2));
        assertThat(properties.getProducer().getTopics()).containsExactly("order-topic");
        assertThat(properties.getConsumer().getConsumptionThreadCount()).isEqualTo(30);
        assertThat(properties.getConsumer().getMaxCacheMessageCount()).isEqualTo(2048);
        assertThat(properties.getConsumer().getCloseTimeout()).isEqualTo(Duration.ofSeconds(20));
    }
}
