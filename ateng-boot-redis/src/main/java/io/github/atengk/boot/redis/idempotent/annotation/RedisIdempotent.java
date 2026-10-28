package io.github.atengk.boot.redis.idempotent.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 业务级声明式分布式防重幂等注解
 * 声明于业务方法上，依托 Redis/Redisson 实施分布式幂等拦截与三态状态机保护
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RedisIdempotent {

    /**
     * 业务唯一幂等键或 SpEL 表达式（如 "#orderId"、"#dto.bizNo"）
     * 留空时默认降级采用方法全限定签名作为兜底键
     */
    String key() default "";

    /**
     * 幂等键业务前缀（留空时继承全局配置 ateng.boot.redis.idempotent.prefix）
     */
    String prefix() default "";

    /**
     * 幂等凭证保留时长表达式（支持 "10m"、"30s"、"1h" 等标准时长，默认 "10m"）
     */
    String expire() default "10m";

    /**
     * 业务执行抛出异常时是否主动删除防重 Key 以允许上游立即重试（默认 true）
     */
    boolean deleteOnFailure() default true;

    /**
     * 重复提交或并发重入被拦截时的提示消息（留空则使用 CommonResultCode.REPEAT_SUBMIT 默认提示）
     */
    String message() default "";
}
