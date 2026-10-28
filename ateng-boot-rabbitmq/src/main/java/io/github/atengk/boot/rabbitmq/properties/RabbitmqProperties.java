package io.github.atengk.boot.rabbitmq.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * RabbitMQ 生产级增强基础设施配置属性
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.rabbitmq")
public class RabbitmqProperties {

    /**
     * 是否启用 RabbitMQ 增强底座特性
     */
    private boolean enabled = true;

    /**
     * 生产者端防爆与压缩配置
     */
    @NestedConfigurationProperty
    private Producer producer = new Producer();

    /**
     * 生产端可靠性与确认超时配置
     */
    @NestedConfigurationProperty
    private Reliable reliable = new Reliable();

    /**
     * 消费端受控防卫与容器配置
     */
    @NestedConfigurationProperty
    private Consumer consumer = new Consumer();

    /**
     * 拓扑声明与运维安全配置
     */
    @NestedConfigurationProperty
    private Topology topology = new Topology();

    /**
     * 结构化宏日志观测配置
     */
    @NestedConfigurationProperty
    private Logging logging = new Logging();

    @Data
    public static class Producer {
        /**
         * 单条出站消息最大报文限制，超过该阈值在出站前 Fail-Fast 阻断抛出异常 (默认 2MB)
         */
        private DataSize maxPayloadSize = DataSize.ofMegabytes(2);

        /**
         * 透明 GZIP 压缩阈值，超过该大小自动启用压缩并在 Header 注入 Content-Encoding: gzip (默认 2KB)
         */
        private DataSize compressionThreshold = DataSize.ofKilobytes(2);

        /**
         * 是否启用大报文透明 GZIP 压缩特性
         */
        private boolean compressionEnabled = true;
    }

    @Data
    public static class Reliable {
        /**
         * Publisher Confirm 同步等待 Broker ACK 的默认超时时间
         */
        private Duration defaultTimeout = Duration.ofSeconds(3);

        /**
         * 是否激活不可路由消息 (ReturnsCallback) 的告警与 SPI 回调
         */
        private boolean returnsCallbackEnabled = true;
    }

    @Data
    public static class Consumer {
        /**
         * 默认单消费者预取数 (Prefetch Count)，调优为 20 兼顾吞吐与负载均衡，杜绝饥饿与内存膨胀
         */
        private int prefetch = 20;

        /**
         * 默认初始并发消费者数
         */
        private int concurrency = 1;

        /**
         * 默认最大并发消费者数
         */
        private int maxConcurrency = 8;

        /**
         * Kubernetes 滚动停机优雅下线超时时间，预留缓冲窗口等待在途处理中的消息完成 ACK
         */
        private Duration shutdownTimeout = Duration.ofSeconds(30);

        /**
         * 消费端本地指数退避重试配置
         */
        @NestedConfigurationProperty
        private Retry retry = new Retry();

        @Data
        public static class Retry {
            /**
             * 是否开启容器内本地指数退避重试
             */
            private boolean enabled = true;

            /**
             * 最大本地重试次数 (包含初次执行)
             */
            private int maxAttempts = 3;

            /**
             * 首次重试退避间隔时间
             */
            private Duration initialInterval = Duration.ofSeconds(1);

            /**
             * 指数退避倍率
             */
            private double multiplier = 2.0;

            /**
             * 最大重试退避间隔时间
             */
            private Duration maxInterval = Duration.ofSeconds(10);
        }
    }

    @Data
    public static class Topology {
        /**
         * 是否自动向 Broker 声明交换机、队列与绑定拓扑
         * 在生产受限只读账号环境下，请显式置为 false 防止启动时 403 权限异常崩溃
         */
        private boolean autoDeclare = true;
    }

    @Data
    public static class Logging {
        /**
         * 是否输出 [MQ-SEND] 与 [MQ-CONSUME] 标准结构化宏日志
         */
        private boolean enabled = true;

        /**
         * 日志中打印 Payload 报文的最大截断字符数，防止大报文冲垮日志管道
         */
        private int logPayloadMaxLength = 1024;
    }
}
