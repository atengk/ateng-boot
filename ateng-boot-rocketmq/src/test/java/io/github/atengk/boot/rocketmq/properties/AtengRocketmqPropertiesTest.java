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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(properties.getProducer().getMaxBatchSize()).isEqualTo(500);
        assertThat(properties.getProducer().getBatchSliceSize()).isEqualTo(64);
        assertThat(properties.getProducer().getMaxPayloadSize()).isEqualTo(DataSize.ofMegabytes(4));
        assertThat(properties.getProducer().getCompressionThreshold()).isEqualTo(DataSize.ofKilobytes(4));
        assertThat(properties.getProducer().getTopics()).isEmpty();

        // 消费端默认值
        assertThat(properties.getConsumer().getConsumptionThreadCount()).isEqualTo(20);
        assertThat(properties.getConsumer().getMaxCacheMessageCount()).isEqualTo(1024);
        assertThat(properties.getConsumer().getCloseTimeout()).isEqualTo(Duration.ofSeconds(15));

        // SimpleConsumer 默认值
        assertThat(properties.getSimpleConsumer().getInvisibleDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getSimpleConsumer().getMaxMessageNum()).isEqualTo(32);
        assertThat(properties.getSimpleConsumer().getAwaitDuration()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getSimpleConsumer().isAutoRenewLease()).isTrue();
        assertThat(properties.getSimpleConsumer().getMaxLeaseRenewAttempts()).isEqualTo(5);
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
        map.put("ateng.boot.rocketmq.producer.max-batch-size", "200");
        map.put("ateng.boot.rocketmq.producer.batch-slice-size", "32");
        map.put("ateng.boot.rocketmq.producer.max-payload-size", "8MB");
        map.put("ateng.boot.rocketmq.producer.compression-threshold", "2KB");
        map.put("ateng.boot.rocketmq.producer.topics[0]", "order-topic");
        map.put("ateng.boot.rocketmq.consumer.consumption-thread-count", "30");
        map.put("ateng.boot.rocketmq.consumer.max-cache-message-count", "2048");
        map.put("ateng.boot.rocketmq.consumer.close-timeout", "20s");
        map.put("ateng.boot.rocketmq.simple-consumer.invisible-duration", "45s");
        map.put("ateng.boot.rocketmq.simple-consumer.max-message-num", "64");
        map.put("ateng.boot.rocketmq.simple-consumer.await-duration", "10s");
        map.put("ateng.boot.rocketmq.simple-consumer.auto-renew-lease", "false");
        map.put("ateng.boot.rocketmq.simple-consumer.max-lease-renew-attempts", "8");

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
        assertThat(properties.getProducer().getMaxBatchSize()).isEqualTo(200);
        assertThat(properties.getProducer().getBatchSliceSize()).isEqualTo(32);
        assertThat(properties.getProducer().getMaxPayloadSize()).isEqualTo(DataSize.ofMegabytes(8));
        assertThat(properties.getProducer().getCompressionThreshold()).isEqualTo(DataSize.ofKilobytes(2));
        assertThat(properties.getProducer().getTopics()).containsExactly("order-topic");
        assertThat(properties.getConsumer().getConsumptionThreadCount()).isEqualTo(30);
        assertThat(properties.getConsumer().getMaxCacheMessageCount()).isEqualTo(2048);
        assertThat(properties.getConsumer().getCloseTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.getSimpleConsumer().getInvisibleDuration()).isEqualTo(Duration.ofSeconds(45));
        assertThat(properties.getSimpleConsumer().getMaxMessageNum()).isEqualTo(64);
        assertThat(properties.getSimpleConsumer().getAwaitDuration()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getSimpleConsumer().isAutoRenewLease()).isFalse();
        assertThat(properties.getSimpleConsumer().getMaxLeaseRenewAttempts()).isEqualTo(8);
    }

    @Test
    @DisplayName("验证分片大小与批量上限非法非正数配置触发 Fail-Fast")
    void testInvalidBatchSizesThrowException() {
        AtengRocketmqProperties properties = new AtengRocketmqProperties();

        assertThatThrownBy(() -> properties.getProducer().setBatchSliceSize(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.getProducer().setBatchSliceSize(-8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.getProducer().setMaxBatchSize(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.getProducer().setMaxBatchSize(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("验证 Producer 超时、重试与容量阈值非法值触发 Fail-Fast")
    void testInvalidProducerGuardsThrowException() {
        AtengRocketmqProperties.Producer producer = new AtengRocketmqProperties.Producer();

        assertThatThrownBy(() -> producer.setSendTimeout(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> producer.setSendTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> producer.setSendTimeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> producer.setMaxAttempts(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> producer.setMaxAttempts(-2))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> producer.setMaxPayloadSize(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> producer.setMaxPayloadSize(DataSize.ofBytes(0)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> producer.setCompressionThreshold(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> producer.setCompressionThreshold(DataSize.ofBytes(-1)))
                .isInstanceOf(IllegalArgumentException.class);

        // 0 阈值合法，表示始终尝试压缩
        producer.setCompressionThreshold(DataSize.ofBytes(0));
        assertThat(producer.getCompressionThreshold()).isEqualTo(DataSize.ofBytes(0));
    }

    @Test
    @DisplayName("验证 SimpleConsumer 租约最大续期次数非法负数触发 Fail-Fast")
    void testInvalidSimpleConsumerGuardsThrowException() {
        AtengRocketmqProperties.SimpleConsumer simpleConsumer = new AtengRocketmqProperties.SimpleConsumer();

        assertThatThrownBy(() -> simpleConsumer.setMaxLeaseRenewAttempts(-1))
                .isInstanceOf(IllegalArgumentException.class);

        simpleConsumer.setMaxLeaseRenewAttempts(0);
        assertThat(simpleConsumer.getMaxLeaseRenewAttempts()).isZero();
    }
}
