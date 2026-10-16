package io.github.atengk.boot.web.ratelimit.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * Web 接口防刷限流注解
 * 支持精准 SpEL 业务键与 DEFAULT/IP/USER 多维度复合限流防刷
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimiter {

    /**
     * 限流周期内允许的最大请求数（令牌总量 / 阈值）
     *
     * @return 允许访问的最大频次
     */
    long count() default 100;

    /**
     * 限流时间窗口周期大小
     *
     * @return 周期时长数值
     */
    long time() default 60;

    /**
     * 时间单位（默认秒）
     *
     * @return 时间单位
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 限流防刷限制维度类型（默认接口方法级全局维度）
     *
     * @return 维度类型
     */
    LimitType limitType() default LimitType.DEFAULT;

    /**
     * 动态 SpEL 业务表达式（如 "#dto.tenantId" 或 "#userId"）
     * 支持与 limitType 自动级联复合生成租户/用户/业务复合限流键
     *
     * @return SpEL 表达式
     */
    String key() default "";

    /**
     * 限流键业务微命名空间前缀（默认 "rate_limit:"）
     *
     * @return 键名前缀
     */
    String prefix() default "rate_limit:";

    /**
     * 达到限流阈值触发拦截时的提示文本（为空时使用 CommonResultCode.TOO_MANY_REQUESTS 默认提示）
     *
     * @return 业务提示文案
     */
    String message() default "";
}
