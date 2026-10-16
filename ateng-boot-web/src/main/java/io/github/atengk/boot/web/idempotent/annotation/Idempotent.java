package io.github.atengk.boot.web.idempotent.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * Web 接口防重幂等注解
 * 支持精准 SpEL 业务键绑定与通用请求特征指纹哈希防抖
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * 幂等业务键 SpEL 表达式（如 "#dto.orderNo" 或 "#userId + '_' + #orderNo"）
     * 为空时自动提取当前请求特征（URI + Method + UserID + 请求入参哈希）作为防重指纹
     *
     * @return SpEL 表达式
     */
    String key() default "";

    /**
     * 幂等键微命名空间前缀（可选，便于特定业务分组隔离）
     *
     * @return 业务前缀
     */
    String prefix() default "";

    /**
     * 锁有效时长 / 防抖时间窗口（默认 3）
     *
     * @return 超时数值
     */
    long timeout() default 3;

    /**
     * 时间单位（默认秒）
     *
     * @return 时间单位
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 重复提交触发拦截时的提示文本（为空时使用 ResultCode.REPEAT_SUBMIT 默认提示）
     *
     * @return 业务提示文案
     */
    String message() default "";

    /**
     * 业务执行发生异常时是否主动释放锁（默认 true，允许用户立即修正参数重试）
     *
     * @return 异常是否释放锁
     */
    boolean delKeyOnException() default true;
}
