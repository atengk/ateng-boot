package io.github.atengk.boot.job.local.annotation;

import io.github.atengk.boot.job.local.enums.OverlappingStrategy;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工业级单机定时任务声明式注解
 * <p>
 * 标注于 Spring 受管 Bean 的方法上，赋予其开箱即用的定时调度、并发防卫、MDC 链路追踪与运维治理能力。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LocalJob {

    /**
     * 任务唯一标识名称（{@link #name()} 的别名）
     * <p>
     * 缺省为空时，自动使用 {@code 类名#方法名} 作为唯一标识。
     * </p>
     *
     * @return 任务名称
     */
    @AliasFor("name")
    String value() default "";

    /**
     * 任务唯一标识名称（{@link #value()} 的别名）
     *
     * @return 任务名称
     */
    @AliasFor("value")
    String name() default "";

    /**
     * Cron 调度周期表达式（标准 Spring 6 位 Cron 格式，如 {@code 0 0 2 * * ?}）
     *
     * @return Cron 表达式
     */
    String cron() default "";

    /**
     * 固定频率调度间隔（上次开始执行到本次开始执行的间隔，支持强类型时间字符串，如 {@code 30s}、{@code 1m}）
     *
     * @return 固定频率时长
     */
    String fixedRate() default "";

    /**
     * 固定延迟调度间隔（上次执行结束到本次开始执行的间隔，支持强类型时间字符串，如 {@code 10s}、{@code 5m}）
     *
     * @return 固定延迟时长
     */
    String fixedDelay() default "";

    /**
     * 应用启动就绪后的首次调度延迟启动时长（支持强类型时间字符串，如 {@code 5s}、{@code 10s}）
     *
     * @return 初始延迟时长
     */
    String initialDelay() default "";

    /**
     * 并发重叠防卫策略（默认 {@link OverlappingStrategy#DISCARD} 防雪崩跳过）
     *
     * @return 并发策略
     */
    OverlappingStrategy strategy() default OverlappingStrategy.DISCARD;

    /**
     * 单次任务执行的硬超时熔断阈值（支持强类型时间字符串，如 {@code 30s}、{@code 1m}，缺省为空表示不限时）
     *
     * @return 硬超时时长
     */
    String timeout() default "";

    /**
     * 任务执行失败后的最大自适应退避重试次数（默认 0，表示不重试）
     *
     * @return 最大重试次数
     */
    int maxRetries() default 0;

    /**
     * 任务执行失败后的重试退避间隔（支持强类型时间字符串，如 {@code 1s}、{@code 5s}，默认 {@code 1s}）
     *
     * @return 重试间隔
     */
    String retryInterval() default "1s";

    /**
     * 是否开启多节点轻量集群互斥防重（需配合集群锁提供者，默认 false）
     *
     * @return 是否开启集群互斥锁
     */
    boolean clusterLock() default false;

    /**
     * 集群互斥锁的安全租约防死锁时长（默认 {@code 5m}）
     *
     * @return 锁租约时长
     */
    String clusterLockLeaseTime() default "5m";

    /**
     * 任务的人读业务描述（用于 Actuator 运维端点与管理后台展示）
     *
     * @return 任务描述
     */
    String description() default "";

    /**
     * 任务静态启动开关（默认 true 开启）
     *
     * @return 是否启用
     */
    boolean enabled() default true;
}
