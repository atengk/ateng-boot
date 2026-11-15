package io.github.atengk.boot.kafka.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.Assert;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

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
     * 位移治理与集群运维增强配置
     */
    @NestedConfigurationProperty
    private Offset offset = new Offset();

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

        /**
         * 支持的生产端压缩编码白名单
         */
        private static final Set<String> SUPPORTED_COMPRESSION_TYPES = Set.of("none", "gzip", "snappy", "lz4", "zstd");

        /**
         * 微批聚合缓冲区大小（对应 batch.size，默认 32KB，允许 0 至 Integer.MAX_VALUE 字节）
         */
        private DataSize batchSize = DataSize.ofKilobytes(32);

        /**
         * 微批聚合等待时间（对应 linger.ms，默认 10ms，允许 0ms 至 Integer.MAX_VALUE 毫秒）
         */
        private Duration linger = Duration.ofMillis(10);

        /**
         * 消息压缩算法（对应 compression.type，默认 snappy，可选 none/gzip/snappy/lz4/zstd）
         */
        private String compressionType = "snappy";

        /**
         * 生产端发送总缓冲内存大小（对应 buffer.memory，默认 32MB，必须大于 0）
         */
        private DataSize bufferMemory = DataSize.ofMegabytes(32);

        /**
         * 缓冲区满或元数据拉取最大阻塞超时（对应 max.block.ms，默认 60s，不能为负数）
         */
        private Duration maxBlock = Duration.ofSeconds(60);

        /**
         * 批量发送单批次允许的最大消息条数，超出将触发 Fail-Fast（默认 1000，必须大于 0）
         */
        private int maxBatchSize = 1000;

        /**
         * 设置微批聚合缓冲区大小（非负且不超过 Integer.MAX_VALUE 字节）
         *
         * @param batchSize 微批缓冲区大小
         */
        public void setBatchSize(DataSize batchSize) {
            Assert.isTrue(batchSize != null && !batchSize.isNegative() && batchSize.toBytes() <= Integer.MAX_VALUE,
                    "ateng.boot.kafka.producer.batch-size 必须为 0 至 " + Integer.MAX_VALUE + " 字节的非负容量");
            this.batchSize = batchSize;
        }

        /**
         * 设置微批聚合等待时间（非负且不超过 Integer.MAX_VALUE 毫秒）
         *
         * @param linger 微批等待时长
         */
        public void setLinger(Duration linger) {
            Assert.isTrue(linger != null && !linger.isNegative()
                            && linger.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) <= 0,
                    "ateng.boot.kafka.producer.linger 必须为 0 至 " + Integer.MAX_VALUE + " 毫秒的非负时长");
            this.linger = linger;
        }

        /**
         * 设置消息压缩算法并校验编码合法性
         *
         * @param compressionType 压缩编码类型
         */
        public void setCompressionType(String compressionType) {
            Assert.hasText(compressionType, "ateng.boot.kafka.producer.compression-type 不能为空");
            String normalized = compressionType.trim().toLowerCase(Locale.ROOT);
            Assert.isTrue(SUPPORTED_COMPRESSION_TYPES.contains(normalized),
                    "ateng.boot.kafka.producer.compression-type 不支持的压缩算法: " + compressionType
                            + "，可选值: none/gzip/snappy/lz4/zstd");
            this.compressionType = normalized;
        }

        /**
         * 设置生产端发送总缓冲内存大小（正数容量防线）
         *
         * @param bufferMemory 发送总缓冲容量，必须大于 0
         */
        public void setBufferMemory(DataSize bufferMemory) {
            Assert.isTrue(bufferMemory != null && bufferMemory.toBytes() > 0,
                    "ateng.boot.kafka.producer.buffer-memory 必须为大于 0 的容量");
            this.bufferMemory = bufferMemory;
        }

        /**
         * 设置最大阻塞超时时间（非负防线）
         *
         * @param maxBlock 最大阻塞时长，不能为负数
         */
        public void setMaxBlock(Duration maxBlock) {
            Assert.isTrue(maxBlock != null && !maxBlock.isNegative(),
                    "ateng.boot.kafka.producer.max-block 不能为负数时长");
            this.maxBlock = maxBlock;
        }

        /**
         * 设置批量发送单批次允许的最大消息条数（正数防线）
         *
         * @param maxBatchSize 单批次最大消息条数，必须大于 0
         */
        public void setMaxBatchSize(int maxBatchSize) {
            Assert.isTrue(maxBatchSize > 0, "ateng.boot.kafka.producer.max-batch-size 必须为大于 0 的整数");
            this.maxBatchSize = maxBatchSize;
        }
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
         * 本地重试指数退避最大间隔上限（默认 30s，防止多轮退避溢出）
         */
        private Duration maxRetryInterval = Duration.ofSeconds(30);

        /**
         * 优雅停机排空等待超时（默认 10s）
         */
        private Duration shutdownTimeout = Duration.ofSeconds(10);

        /**
         * 死信 Topic 后缀（默认 ".DLT"）
         */
        private String dltSuffix = ".DLT";

        /**
         * 设置本地重试最大退避间隔上限（正数校验防线）
         *
         * @param maxRetryInterval 最大退避间隔，必须为至少 1 毫秒的正数时长
         */
        public void setMaxRetryInterval(Duration maxRetryInterval) {
            this.maxRetryInterval = AtengKafkaProperties.requirePositiveDuration(
                    maxRetryInterval, "ateng.boot.kafka.consumer.max-retry-interval");
        }
    }

    /**
     * 校验超时或退避配置必须为至少 1 毫秒的正数时长（防止亚毫秒在转毫秒时截断为 0）
     *
     * @param duration     待校验时长
     * @param propertyName 配置项名称
     * @return 校验通过的正数时长
     */
    public static Duration requirePositiveDuration(Duration duration, String propertyName) {
        Assert.isTrue(duration != null && !duration.isNegative()
                        && duration.compareTo(Duration.ofMillis(1)) >= 0,
                propertyName + " 必须为至少 1 毫秒的正数时长");
        return duration;
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

        /**
         * 死信只读探查最大等待超时时间（默认 5s，必须为正数）
         */
        private Duration peekTimeout = Duration.ofSeconds(5);

        /**
         * 死信重放与物理清空管理操作超时时间（默认 10s，必须为正数）
         */
        private Duration operationTimeout = Duration.ofSeconds(10);

        /**
         * 设置死信只读探查超时时间（正数校验防线）
         *
         * @param peekTimeout 探查超时时间，必须大于 0
         */
        public void setPeekTimeout(Duration peekTimeout) {
            this.peekTimeout = requirePositiveDuration(peekTimeout, "ateng.boot.kafka.dead-letter.peek-timeout");
        }

        /**
         * 设置死信操作超时时间（正数校验防线）
         *
         * @param operationTimeout 操作超时时间，必须大于 0
         */
        public void setOperationTimeout(Duration operationTimeout) {
            this.operationTimeout = requirePositiveDuration(operationTimeout, "ateng.boot.kafka.dead-letter.operation-timeout");
        }

        /**
         * 校验死信超时配置必须为至少 1 毫秒的正数时长（兼容既有的外部调用）
         *
         * @param duration     待校验时长
         * @param propertyName 配置项名称
         * @return 校验通过的正数时长
         */
        public static Duration requirePositiveDuration(Duration duration, String propertyName) {
            return AtengKafkaProperties.requirePositiveDuration(duration, propertyName);
        }
    }

    /**
     * 位移治理参数配置
     */
    @Data
    public static class Offset {

        /**
         * AdminClient 操作超时时间（默认 10s）
         */
        private Duration timeout = Duration.ofSeconds(10);
    }
}
