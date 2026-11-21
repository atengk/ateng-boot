package io.github.atengk.boot.sms.properties;

import lombok.Data;

import java.io.Serializable;
import java.time.Duration;

/**
 * 动态短信渠道注册中心与热驱逐缓存配置属性
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Data
public class SmsRegistryProperties implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否启用动态渠道有界 LRU 缓存，默认 true
     */
    private boolean dynamicCacheEnabled = true;

    /**
     * 动态渠道 LRU 缓存最大条目容量，默认 1024
     */
    private int dynamicCacheMaxSize = 1024;

    /**
     * 动态渠道缓存有效期（超时后自动过期并重新向 SmsChannelProvider 加载），默认 30 分钟
     */
    private Duration dynamicCacheTtl = Duration.ofMinutes(30);

    /**
     * 存在 Redis 时是否启用 Pub/Sub 跨节点集群热驱逐广播，默认 true
     */
    private boolean evictPubSubEnabled = true;

    /**
     * Redis Pub/Sub 集群渠道驱逐广播频道名
     */
    private String evictTopic = "ateng:sms:channel:evict";
}
