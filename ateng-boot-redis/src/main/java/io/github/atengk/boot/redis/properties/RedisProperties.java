package io.github.atengk.boot.redis.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
    private LockProperties lock = new LockProperties();

    /**
     * 延时队列配置属性
     */
    private DelayQueueProperties delayQueue = new DelayQueueProperties();

    /**
     * 主题广播消息配置属性
     */
    private PubSubProperties pubSub = new PubSubProperties();

    /**
     * Spring Cache 缓存增强配置属性
     */
    private CacheProperties cache = new CacheProperties();

    /**
     * Redisson 专属扩展配置
     */
    private RedissonProperties redisson = new RedissonProperties();

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
         * 默认获取锁等待超时时长（获取锁最大阻塞等待时长）
         */
        private long waitTime = 3L;

        /**
         * 默认持锁租约超时时长（-1 表示启用 Redisson 看门狗 Watchdog 自动续期）
         */
        private long leaseTime = -1L;

        /**
         * 默认时间单位
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
    }
}
