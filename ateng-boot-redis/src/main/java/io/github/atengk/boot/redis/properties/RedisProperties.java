package io.github.atengk.boot.redis.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
         * 慢锁告警阈值（毫秒），方法持锁执行时间超过该阈值时自动记录 WARN 日志
         */
        private long slowThresholdMs = 3000L;
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
