package io.github.atengk.boot.job.snail.annotation;

import io.github.atengk.boot.job.snail.enums.JobTenantMode;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * SnailJob 调度任务生产级高阶声明式注解
 *
 * <p>配合或扩展原生 {@code @JobExecutor} 注解，提供本地硬超时看门狗熔断、
 * 多租户隔离沙箱策略、异常告警防风暴以及形参强类型 DTO 自动反序列化。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface AtengSnailJob {

    /**
     * 调度任务标识名称（留空时优先取原生 @JobExecutor 的 name）
     */
    String value() default "";

    /**
     * 本地硬超时阈值字符串（如 "30s"、"5m"、"1h"，超限强中断执行线程，防死锁与慢连接挂起）
     */
    String timeout() default "";

    /**
     * 多租户隔离边界策略（默认 IGNORE 忽略租户过滤）
     */
    JobTenantMode tenantMode() default JobTenantMode.IGNORE;

    /**
     * 是否在任务执行抛出未受控异常时触发告警聚合分发
     */
    boolean alert() default true;

    /**
     * 超时降级就地补偿方法名称（定义于同一 Spring Bean 内）
     *
     * <p>当任务触发看门狗硬超时被中断时，工作线程优先就地执行该方法进行补偿与资源清理。
     * 方法支持无参，或按需声明 {@link io.github.atengk.boot.job.snail.watchdog.SnailJobExecutionMetadata}、
     * {@link java.time.Duration}、{@link Throwable} 或原始方法入参。</p>
     */
    String fallbackMethod() default "";
}
