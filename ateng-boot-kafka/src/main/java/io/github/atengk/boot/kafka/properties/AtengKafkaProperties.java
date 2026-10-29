package io.github.atengk.boot.kafka.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Kafka 增强底座强类型配置属性类
 *
 * <p>纳管于 {@code ateng.boot.kafka.*} 命名空间，与 Spring 官方 {@code spring.kafka.*} 保持正交隔离。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.kafka")
public class AtengKafkaProperties {

    /**
     * 是否启用 Kafka 增强基础设施（默认 true）
     */
    private boolean enabled = true;

    /**
     * 是否自动创建声明的 Topic（默认 true；生产受限只读账号建议置为 false）
     */
    private boolean autoCreateTopics = true;

    /**
     * 生产端增强配置
     */
    @NestedConfigurationProperty
    private Producer producer = new Producer();

    /**
     * 消费端增强配置
     */
    @NestedConfigurationProperty
    private Consumer consumer = new Consumer();

    /**
     * 死信受控治理增强配置
     */
    @NestedConfigurationProperty
    private DeadLetter deadLetter = new DeadLetter();

    /**
     * 生产端参数配置
     */
    @Data
    public static class Producer {

        /**
         * 发送阻塞等待 Broker 确认的超时时间（默认 5s）
         */
        private Duration sendTimeout = Duration.ofSeconds(5);

        /**
         * 单条消息最大载荷容量限制，超出将触发 Fail-Fast（默认 1MB）
         */
        private DataSize maxPayloadSize = DataSize.ofMegabytes(1);

        /**
         * 是否开启生产端默认调优（acks=all, enable.idempotence=true, linger.ms=10, batch.size=32KB, snappy 压缩等；默认 true）
         */
        private boolean defaultTuningEnabled = true;
    }

    /**
     * 消费端参数配置
     */
    @Data
    public static class Consumer {

        /**
         * 默认单条监听容器并发线程数（默认 3，建议根据主题分区数合理调优）
         */
        private int concurrency = 3;

        /**
         * 批量监听容器并发线程数（默认 3）
         */
        private int batchConcurrency = 3;

        /**
         * 本地指数退避最大重试次数（默认 3 次）
         */
        private int maxRetries = 3;

        /**
         * 本地重试初始退避间隔（默认 1s）
         */
        private Duration retryInterval = Duration.ofSeconds(1);

        /**
         * 本地重试退避倍数（默认 2.0）
         */
        private double retryMultiplier = 2.0;

        /**
         * 优雅停机排空等待超时（默认 10s）
         */
        private Duration shutdownTimeout = Duration.ofSeconds(10);

        /**
         * 死信 Topic 后缀（默认 ".DLT"）
         */
        private String dltSuffix = ".DLT";
    }

    /**
     * 死信受控治理参数配置
     */
    @Data
    public static class DeadLetter {

        /**
         * 单批次默认最大重放数量（默认 100）
         */
        private int defaultReplayLimit = 100;

        /**
         * 单条死信消息最大允许重放次数，超出将触发防二次雪崩熔断（默认 1）
         */
        private int maxReplayCount = 1;

        /**
         * 死信重放专属消费组 ID（默认 "ateng-kafka-dlt-replay-group"）
         */
        private String replayGroupId = "ateng-kafka-dlt-replay-group";
    }
}
