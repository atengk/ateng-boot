package io.github.atengk.boot.job.xxl.annotation;

import io.github.atengk.boot.job.xxl.enums.JobTenantMode;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * XXL-Job 高阶生产加固与多租户调度注解
 *
 * <p>用于配合或增强官方 {@code @XxlJob} 任务，支持显式声明方法级本地看门狗超时（{@link #timeout()}）
 * 与多租户驱动策略（{@link #tenantMode()}）。可标注于方法或类级别，由框架切面自动织入四维上下文与链路追踪。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface AtengXxlJob {

    /**
     * 任务 Handler 名称（可选，若同时标注了 @XxlJob 则优先以 @XxlJob.value() 为准）
     */
    String value() default "";

    /**
     * 任务初始化执行方法
     */
    String init() default "";

    /**
     * 任务销毁执行方法
     */
    String destroy() default "";

    /**
     * 本地硬看门狗超时时限 (如 30s, 1m, 2h)，为空表示采用全局默认配置
     */
    String timeout() default "";

    /**
     * 多租户隔离策略模式，默认为 PARAM (从任务入参中提取 tenantId)
     */
    JobTenantMode tenantMode() default JobTenantMode.PARAM;

}
