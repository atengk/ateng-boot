package io.github.atengk.boot.redis.lock.annotation;

import io.github.atengk.boot.redis.lock.enums.LockMode;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 生产级声明式分布式锁注解
 * 支持四阶锁模式、SpEL 动态解析、数组多键联锁、看门狗自动续期、安全持锁释放与慢锁审计
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

    /**
     * 单一锁业务键表达式，支持 SpEL 表达式（如 #order.orderNo）或纯静态字面量
     */
    String key() default "";

    /**
     * 复合多键锁表达式数组（用于 MultiLock 联锁，如 {"#fromUserId", "#toUserId"}）
     */
    String[] keys() default {};

    /**
     * 锁键统一命名前缀
     */
    String prefix() default "lock:";

    /**
     * 分布式锁模式，默认可重入锁
     */
    LockMode mode() default LockMode.REENTRANT;

    /**
     * 获取锁最大等待超时时长（默认 -1 表示使用全局配置 ateng.boot.redis.lock.waitTime）
     */
    long waitTime() default -1L;

    /**
     * 持锁租约超时时长（默认 -1 表示启用 Redisson 看门狗 Watchdog 自动续期）
     */
    long leaseTime() default -1L;

    /**
     * 时间单位，默认秒
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 获取锁失败时的错误提示信息
     */
    String message() default "系统繁忙，获取分布式锁超时，请稍后再试";
}
