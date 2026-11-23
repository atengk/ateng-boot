package io.github.atengk.boot.notification.properties;

import lombok.Data;

import java.time.Duration;

/**
 * 协同通知客户端频控配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class NotificationRateLimitProperties {

    /**
     * 是否开启客户端主动频控拦截，默认开启
     */
    private boolean enabled = true;

    /**
     * 是否启用基于 Redis 的分布式多节点频控（需引入 spring-boot-starter-data-redis 且存在 StringRedisTemplate Bean），默认关闭（使用单机内存版）
     */
    private boolean distributed = false;

    /**
     * 分布式 Redis 滑动窗口限流键统一前缀，默认 ateng:notification:ratelimit:
     */
    private String redisKeyPrefix = "ateng:notification:ratelimit:";

    /**
     * Redis 集群异常或网络抖动时的容灾降级策略：true 为 fail-open（记录警告日志并放行发信避免告警丢失），false 为 fail-closed（保留原始异常链快速失败）
     */
    private boolean failOpenOnRedisError = true;

    /**
     * 突发超频时的最大微退避排队等待时长，默认 2 秒
     */
    private Duration maxWaitDuration = Duration.ofSeconds(2);

    /**
     * 钉钉群机器人默认基准频控配额（条/分钟），默认 20 条/分钟
     */
    private int defaultDingTalkPermits = 20;

    /**
     * 企业微信群机器人默认基准频控配额（条/分钟），默认 20 条/分钟
     */
    private int defaultWeComPermits = 20;

    /**
     * 飞书群机器人默认基准频控配额（条/分钟），默认 100 条/分钟
     */
    private int defaultFeishuPermits = 100;

    /**
     * 飞书群机器人默认单秒突发防卫配额（条/秒），默认 5 条/秒（对齐飞书官方 5次/秒 限制）
     */
    private int defaultFeishuBurstPermits = 5;

    /**
     * 通用 Webhook 机器人默认基准频控配额（条/分钟），默认 60 条/分钟
     */
    private int defaultGenericPermits = 60;
}
