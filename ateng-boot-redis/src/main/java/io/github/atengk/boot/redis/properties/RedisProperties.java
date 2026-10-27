package io.github.atengk.boot.redis.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis 增强基础设施与分布式协同配置属性
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.redis")
public class RedisProperties {

    /**
     * 是否启用 Redis 增强基础设施自动装配
     */
    private boolean enabled = true;

    /**
     * 全局 Redis 键统一命名前缀（可选，留空则不附加前缀）
     */
    private String keyPrefix = "";

    /**
     * 分布式锁配置属性
     */
    @NestedConfigurationProperty
    private LockProperties lock = new LockProperties();

    /**
     * 延时队列配置属性
     */
    @NestedConfigurationProperty
    private DelayQueueProperties delayQueue = new DelayQueueProperties();

    /**
     * 主题广播消息配置属性
     */
    @NestedConfigurationProperty
    private PubSubProperties pubSub = new PubSubProperties();

    /**
     * Spring Cache 缓存增强配置属性
     */
    @NestedConfigurationProperty
    private CacheProperties cache = new CacheProperties();

    /**
     * Redisson 专属扩展配置
     */
    @NestedConfigurationProperty
    private RedissonProperties redisson = new RedissonProperties();

    /**
     * 分布式布隆过滤器配置映射（key 为过滤器名称，如 blacklist、orderFilter 等）
     */
    private Map<String, BloomFilterConfig> bloomFilters = new HashMap<>();

    /**
     * 内存+Redis 两级缓存配置属性
     */
    @NestedConfigurationProperty
    private TwoLevelCacheProperties twoLevel = new TwoLevelCacheProperties();

    /**
     * 分布式锁配置详情
     */
    @Data
    public static class LockProperties {

        /**
         * 是否启用声明式分布式锁 AOP 切面
         */
        private boolean enabled = true;

        /**
         * 默认获取锁等待超时时长（获取锁最大阻塞等待时长，默认 3s）
         */
        private Duration waitTime = Duration.ofSeconds(3);

        /**
         * 默认持锁租约超时时长（-1ms 表示启用 Redisson 看门狗 Watchdog 自动续期，默认 -1ms）
         */
        private Duration leaseTime = Duration.ofMillis(-1);

        /**
         * 默认时间单位（向后兼容保留，建议优先在配置中使用标准 Duration 格式如 3s、500ms）
         */
        private TimeUnit timeUnit = TimeUnit.SECONDS;

        /**
         * 分布式锁统一键名前缀（默认 "lock:"）
         */
        private String prefix = "lock:";

        /**
         * 慢锁告警阈值（支持 3s、3000ms 等标准 Duration 格式，默认 3000ms），持锁执行时间超过该阈值时自动记录 WARN 日志
         */
        private Duration slowThreshold = Duration.ofMillis(3000L);

        public Duration getWaitTime() {
            return waitTime;
        }

        public void setWaitTime(Duration waitTime) {
            this.waitTime = waitTime;
        }

        /**
         * 兼容旧版秒级/数值设置
         *
         * @param waitTimeSeconds 等待超时秒数
         */
        public void setWaitTime(long waitTimeSeconds) {
            this.waitTime = Duration.ofSeconds(waitTimeSeconds);
        }

        public long getWaitTimeSeconds() {
            return waitTime != null ? waitTime.toSeconds() : 3L;
        }

        public void setWaitTimeSeconds(long waitTimeSeconds) {
            this.waitTime = Duration.ofSeconds(waitTimeSeconds);
        }

        public Duration getLeaseTime() {
            return leaseTime;
        }

        public void setLeaseTime(Duration leaseTime) {
            this.leaseTime = leaseTime;
        }

        /**
         * 兼容旧版秒级/数值设置
         *
         * @param leaseTimeSeconds 租约超时秒数（-1 表示看门狗）
         */
        public void setLeaseTime(long leaseTimeSeconds) {
            this.leaseTime = leaseTimeSeconds < 0 ? Duration.ofMillis(-1) : Duration.ofSeconds(leaseTimeSeconds);
        }

        public long getLeaseTimeSeconds() {
            return leaseTime != null ? (leaseTime.isNegative() ? -1L : leaseTime.toSeconds()) : -1L;
        }

        public void setLeaseTimeSeconds(long leaseTimeSeconds) {
            this.leaseTime = leaseTimeSeconds < 0 ? Duration.ofMillis(-1) : Duration.ofSeconds(leaseTimeSeconds);
        }

        public long getSlowThresholdMs() {
            return slowThreshold != null ? slowThreshold.toMillis() : 0L;
        }

        public void setSlowThresholdMs(long slowThresholdMs) {
            this.slowThreshold = Duration.ofMillis(slowThresholdMs);
        }

        public Duration getSlowThreshold() {
            return slowThreshold;
        }

        public void setSlowThreshold(Duration slowThreshold) {
            this.slowThreshold = slowThreshold;
        }

        public Duration getSlowLockThreshold() {
            return slowThreshold;
        }

        public void setSlowLockThreshold(Duration slowLockThreshold) {
            this.slowThreshold = slowLockThreshold;
        }
    }

    /**
     * 延时队列配置详情
     */
    @Data
    public static class DelayQueueProperties {

        /**
         * 是否启用延时队列支持与生命周期消费者调度器
         */
        private boolean enabled = true;

        /**
         * 延时消费失败最大重试次数（默认 3 次）
         */
        private int retryTimes = 3;

        /**
         * 延时消费失败重试等待间隔（默认 3 秒）
         */
        private Duration retryDelay = Duration.ofSeconds(3);

        /**
         * 是否在消费重试耗尽后自动投递至死信队列（默认 true）
         */
        private boolean deadLetterEnabled = true;

        /**
         * 死信队列名称后缀（默认 ":dlq"）
         */
        private String deadLetterSuffix = ":dlq";
    }

    /**
     * 主题广播消息配置详情
     */
    @Data
    public static class PubSubProperties {

        /**
         * 是否启用主题广播发布与监听容器
         */
        private boolean enabled = true;
    }

    /**
     * Spring Cache 缓存增强配置详情
     */
    @Data
    public static class CacheProperties {

        /**
         * 是否启用 RedisCacheManager 定制装配
         */
        private boolean enabled = true;

        /**
         * 默认缓存过期时间（默认 30 分钟）
         */
        private Duration defaultTtl = Duration.ofMinutes(30);

        /**
         * 是否缓存空对象防穿透（默认 true）
         */
        private boolean cacheNullValues = true;

        /**
         * 空对象防穿透独立短过期时间（默认 5 分钟，避免脏数据长期常驻）
         */
        private Duration nullTtl = Duration.ofMinutes(5);

        /**
         * 是否启用 TTL 随机抖动防雪崩（默认 true）
         */
        private boolean jitterEnabled = true;

        /**
         * 随机抖动最大时长（默认 60 秒，在基准 TTL 上随机上浮 0 ~ maxJitter）
         */
        private Duration maxJitter = Duration.ofSeconds(60);

        /**
         * 针对特定 cacheName 的独立过期时间配置（如 userCache: 10m, dictCache: 2h）
         */
        private Map<String, Duration> expires = new HashMap<>();

        /**
         * 缓存防击穿分布式互斥锁获取等待超时时长（默认 3 秒）
         */
        private Duration lockWaitTime = Duration.ofSeconds(3);
    }

    /**
     * Redisson 扩展配置详情
     */
    @Data
    public static class RedissonProperties {

        /**
         * 自定义 Redisson 外部配置文件路径（如 classpath:redisson.yaml），配置后优先采用此文件
         */
        private String config;

        /**
         * Redisson 通用线程池大小（0 表示采用 Redisson 默认值 2 * CPU 核心数）
         */
        private int threads = 0;

        /**
         * Redisson Netty 线程池大小（0 表示采用 Redisson 默认值 2 * CPU 核心数）
         */
        private int nettyThreads = 0;

        /**
         * TCP 连接心跳保活检测间隔（默认 30s），定期发送 PING 命令防御云原生 NAT 网关或负载均衡静默切断连接
         */
        private Duration pingConnectionInterval = Duration.ofSeconds(30);

        /**
         * 主连接池最大连接数（单机 connectionPoolSize / 集群 masterConnectionPoolSize，默认 64）
         */
        private int connectionPoolSize = 64;

        /**
         * 主连接池最小空闲连接数（单机 connectionMinimumIdleSize / 集群 masterConnectionMinimumIdleSize，默认 16）
         */
        private int connectionMinimumIdleSize = 16;

        /**
         * 命令重试次数（默认 3 次）
         */
        private int retryAttempts = 3;

        /**
         * 命令重试间隔时间（默认 1500ms）
         */
        private Duration retryInterval = Duration.ofMillis(1500);
    }

    /**
     * 分布式布隆过滤器配置详情
     */
    @Data
    public static class BloomFilterConfig {

        /**
         * 预计插入元素总量（默认 100,000）
         */
        private long expectedInsertions = 100_000L;

        /**
         * 期望误判率（默认 0.01 即 1%，取值范围 (0, 1)）
         */
        private double falseProbability = 0.01;
    }

    /**
     * 构建受管全局键名前缀
     *
     * @param rawKey 原始键名
     * @return 附加 keyPrefix 后的最终键名
     */
    public String buildKey(String rawKey) {
        if (rawKey == null) {
            return null;
        }
        if (org.springframework.util.StringUtils.hasText(keyPrefix) && !rawKey.startsWith(keyPrefix)) {
            return keyPrefix + rawKey;
        }
        return rawKey;
    }

    /**
     * 内存+Redis 两级缓存配置详情
     */
    @Data
    public static class TwoLevelCacheProperties {

        /**
         * 是否启用两级缓存 (L1 Caffeine/LRU + L2 Redis)
         */
        private boolean enabled = false;

        /**
         * 跨节点缓存失效广播主题（默认 "ateng:cache:evict:topic"）
         */
        private String topic = "ateng:cache:evict:topic";

        /**
         * L1 本地内存缓存配置
         */
        @NestedConfigurationProperty
        private L1Properties l1 = new L1Properties();

        /**
         * L2 分布式 Redis 缓存配置
         */
        @NestedConfigurationProperty
        private L2Properties l2 = new L2Properties();

        /**
         * L1 本地内存缓存配置详情
         */
        @Data
        public static class L1Properties {

            /**
             * L1 本地内存缓存默认最大条数（默认 10,000 条，严格防 OOM）
             */
            private long defaultMaxSize = 10_000L;

            /**
             * L1 本地内存缓存默认写入后过期时长（默认 5 分钟）
             */
            private Duration defaultTtl = Duration.ofMinutes(5);

            /**
             * 针对特定 cacheName 的 L1 最大条数映射
             */
            private Map<String, Long> maxSizes = new HashMap<>();

            /**
             * 针对特定 cacheName 的 L1 写入后过期时长映射
             */
            private Map<String, Duration> ttls = new HashMap<>();
        }

        /**
         * L2 分布式 Redis 缓存配置详情
         */
        @Data
        public static class L2Properties {

            /**
             * L2 分布式缓存默认过期时长（默认 30 分钟）
             */
            private Duration defaultTtl = Duration.ofMinutes(30);

            /**
             * 是否缓存空对象防穿透（默认 true）
             */
            private boolean cacheNullValues = true;

            /**
             * 空对象防穿透独立短过期时间（默认 5 分钟）
             */
            private Duration nullTtl = Duration.ofMinutes(5);

            /**
             * 是否启用 TTL 随机抖动防雪崩（默认 true）
             */
            private boolean jitterEnabled = true;

            /**
             * 随机抖动最大时长（默认 60 秒）
             */
            private Duration maxJitter = Duration.ofSeconds(60);

            /**
             * 针对特定 cacheName 的 L2 独立过期时间映射
             */
            private Map<String, Duration> ttls = new HashMap<>();
        }
    }
}

