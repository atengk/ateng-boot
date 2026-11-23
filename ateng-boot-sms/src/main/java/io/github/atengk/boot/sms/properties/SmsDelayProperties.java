package io.github.atengk.boot.sms.properties;

import lombok.Data;

import java.io.Serializable;
import java.time.Duration;

/**
 * 短信延迟与定时调度引擎配置属性
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Data
public class SmsDelayProperties implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 延迟与定时调度底层实现模式（默认 MEMORY 单机受管调度线程池；可选 REDIS 分布式 ZSet 持久化轮询）
     */
    private DelayMode mode = DelayMode.MEMORY;

    /**
     * 调度工作线程池核心线程数（默认 2）
     */
    private int poolSize = 2;

    /**
     * 容器停机时等待在途到期发信任务优雅排空的最大超时时长（默认 15s）
     */
    private Duration awaitTermination = Duration.ofSeconds(15);

    /**
     * REDIS 模式下后台 ZSet 到期任务轮询间隔（默认 1s）
     */
    private Duration redisPollInterval = Duration.ofSeconds(1);

    /**
     * REDIS 模式下单轮轮询原子认领的最大任务批次上限（默认 100）
     */
    private int redisBatchSize = 100;

    /**
     * REDIS 模式下调度 ZSet 与任务负载键统一前缀（默认 "ateng:sms:delay:"）
     */
    private String redisKeyPrefix = "ateng:sms:delay:";

    /**
     * 内存中保留近期已完成/已取消回执状态轨迹的最大条目上限（防长稳运行内存膨胀，默认 10000）
     */
    private int maxStatusHistory = 10000;

    /**
     * 延迟调度运行模式枚举
     */
    public enum DelayMode {
        /**
         * 基于 Spring ThreadPoolTaskScheduler 的内存受管定时调度模式
         */
        MEMORY,

        /**
         * 基于 Redis ZSet + Lua 原子认领的分布式持久化轮询调度模式
         */
        REDIS
    }
}
