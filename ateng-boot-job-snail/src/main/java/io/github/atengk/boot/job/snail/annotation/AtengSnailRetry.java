package io.github.atengk.boot.job.snail.annotation;

import com.aizuda.snailjob.client.core.IdempotentIdGenerate;
import com.aizuda.snailjob.client.core.retryer.RetryType;
import io.github.atengk.boot.job.snail.enums.JobTenantMode;
import io.github.atengk.boot.job.snail.retry.generator.SpelIdempotentIdGenerator;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * SnailJob 分布式重试生产级高阶声明式注解
 *
 * <p>提供生产级声明式重试配置：
 * <ul>
 *     <li>支持业务 SpEL 幂等键表达式提取（{@link #idempotentSpel()}）；</li>
 *     <li>默认挂载 {@link SpelIdempotentIdGenerator} 实现强业务唯一性防重；</li>
 *     <li>融合四维上下文（MDC traceId、User、TenantId、IgnoreTenant）跨时间与跨节点现场保真还原；</li>
 *     <li>支持多租户隔离沙箱策略（{@link #tenantMode()}）。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface AtengSnailRetry {

    /**
     * 重试场景唯一标识（留空时默认取当前方法名）
     */
    String scene() default "";

    /**
     * 场景标识别名
     */
    String value() default "";

    /**
     * 业务幂等标识 SpEL 表达式（如 "#order.orderNo"、"#dto.id"、"#orderId"）
     */
    String idempotentSpel() default "";

    /**
     * 幂等 ID 生成器类型（默认为 SpelIdempotentIdGenerator）
     */
    Class<? extends IdempotentIdGenerate> idempotentId() default SpelIdempotentIdGenerator.class;

    /**
     * 业务追踪标识 SpEL 表达式（如 "#order.orderNo"）
     */
    String bizNo() default "";

    /**
     * 重试模式策略（默认 LOCAL_REMOTE，支持本地 + 远程两阶段递进重试）
     */
    RetryType retryStrategy() default RetryType.LOCAL_REMOTE;

    /**
     * 本地重试最大次数
     */
    int localTimes() default 3;

    /**
     * 本地重试间隔时间（秒）
     */
    int localInterval() default 2;

    /**
     * 重试耗尽后是否继续向外抛出异常
     */
    boolean isThrowException() default true;

    /**
     * 触发重试的目标异常类型列表
     */
    Class<? extends Throwable>[] include() default {};

    /**
     * 排除不触发重试的异常类型列表
     */
    Class<? extends Throwable>[] exclude() default {};

    /**
     * 多租户隔离边界策略（默认 PARAM 从入参智能解析租户，或 IGNORE 忽略租户）
     */
    JobTenantMode tenantMode() default JobTenantMode.PARAM;
}
