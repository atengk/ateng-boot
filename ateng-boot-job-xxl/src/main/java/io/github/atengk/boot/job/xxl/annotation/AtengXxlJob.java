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

    /**
     * 超时降级补偿方法名（声明在同一个 Bean 上的方法名）
     *
     * <p>当任务被本地硬看门狗中断或执行超时时，由框架受管异步线程池调度执行该降级方法，
     * 用于就地释放分布式外部锁、冲正数据库流水或重置任务状态。</p>
     */
    String fallback() default "";

    /**
     * 方法级告警开关，默认为 true。设置为 false 可静音高频探测等嘈杂任务
     */
    boolean alertEnabled() default true;

    /**
     * 方法级连续失败告警抑制阈值（连续失败达到该次数时触发周期聚合摘要卡片），默认为 -1 表示继承全局配置
     */
    int alertSuppressThreshold() default -1;

}
