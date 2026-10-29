package io.github.atengk.boot.rocketmq.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * RocketMQ 5.x 增强底座强类型配置属性类
 *
 * <p>纳管于 {@code ateng.boot.rocketmq.*} 命名空间，严格采用强类型 {@link Duration} 与 {@link DataSize} 消除单位歧义。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.rocketmq")
public class AtengRocketmqProperties {

    /**
     * 是否启用 RocketMQ 增强基础设施（默认 true）
     */
    private boolean enabled = true;

    /**
     * RocketMQ 5.x gRPC Proxy 服务接入点地址（例如 127.0.0.1:8081；未配置时框架平滑静默跳过自动装配）
     */
    private String endpoints;

    /**
     * 访问凭证 AccessKey（可选，云上或开启 ACL 鉴权时配置）
     */
    private String accessKey;

    /**
     * 访问凭证 SecretKey（可选，云上或开启 ACL 鉴权时配置）
     */
    private String secretKey;

    /**
     * 客户端默认请求超时时间（默认 3s）
     */
    private Duration requestTimeout = Duration.ofSeconds(3);

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
     * 生产端参数配置
     */
    @Data
    public static class Producer {

        /**
         * 发送消息等待 Broker 确认的超时时间（默认 3s）
         */
        private Duration sendTimeout = Duration.ofSeconds(3);

        /**
         * 客户端发送失败最大重试次数（默认 3 次）
         */
        private int maxAttempts = 3;

        /**
         * 批量发信单批次最大容量上限，超出将触发 Fail-Fast（默认 500 条）
         */
        private int maxBatchSize = 500;

        /**
         * 单条消息最大载荷容量限制，超出将触发 Fail-Fast（默认 4MB）
         */
        private DataSize maxPayloadSize = DataSize.ofMegabytes(4);

        /**
         * 消息透明 GZIP 压缩阈值，超过此大小自动压缩并打标（默认 4KB）
         */
        private DataSize compressionThreshold = DataSize.ofKilobytes(4);

        /**
         * 预热主题列表（可选，在 Producer 初始化时提前拉取路由元数据）
         */
        private List<String> topics = new ArrayList<>();
    }

    /**
     * 消费端参数配置
     */
    @Data
    public static class Consumer {

        /**
         * 消费端监听器并发处理线程数（默认 20）
         */
        private int consumptionThreadCount = 20;

        /**
         * 消费者本地最大缓存消息数量（默认 1024）
         */
        private int maxCacheMessageCount = 1024;

        /**
         * 优雅停机排空在途消费消息的最大等待超时时间（默认 15s）
         */
        private Duration closeTimeout = Duration.ofSeconds(15);
    }
}
