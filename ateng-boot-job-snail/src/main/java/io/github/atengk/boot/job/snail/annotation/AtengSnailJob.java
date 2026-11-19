package io.github.atengk.boot.job.snail.annotation;

import io.github.atengk.boot.job.snail.enums.JobTenantMode;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * SnailJob 调度任务生产级高阶声明式注解
 *
 * <p>可独立声明调度任务或扩展原生 {@code @JobExecutor} 注解，自动接入服务端注册、本地硬超时看门狗熔断、
 * 方法级本地退避重试、多租户隔离沙箱策略、告警开关/抑制阈值覆写以及形参强类型 DTO 自动反序列化。</p>
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
     * 调度任务标识名称（与 {@link #name()} 互为别名；留空时优先取原生 {@code @JobExecutor#name()}，否则回退方法名）
     */
    @AliasFor("name")
    String value() default "";

    /**
     * 调度任务标识名称（与 {@link #value()} 互为别名）
     */
    @AliasFor("value")
    String name() default "";

    /**
     * 类级别声明时的默认执行方法名称（对齐原生 {@code @JobExecutor#method()}，默认为 {@code "jobExecute"}）
     */
    String method() default "jobExecute";

    /**
     * 本地硬超时阈值字符串（如 "30s"、"5m"、"1h"，超限强中断执行线程，防死锁与慢连接挂起）
     */
    String timeout() default "";

    /**
     * 多租户隔离边界策略（默认 IGNORE 忽略租户过滤）
     */
    JobTenantMode tenantMode() default JobTenantMode.IGNORE;

    /**
     * 是否在任务执行抛出未受控异常时触发告警聚合分发（旧属性名兼容保留，与 {@link #alertEnabled()} 取逻辑与）
     */
    boolean alert() default true;

    /**
     * 方法级告警开关（默认 true，设为 false 可静音高频探测等嘈杂任务）
     */
    boolean alertEnabled() default true;

    /**
     * 方法级连续失败告警抑制阈值（{@code <= 0} 表示继承全局 {@code ateng.boot.job.snail.alert.suppress-threshold}）
     */
    int alertSuppressThreshold() default -1;

    /**
     * 方法级本地退避重试次数（大于 0 时对业务执行期抛出的瞬态异常生效；参数校验、JSON 解析、负载超限与超时强制 0 次重试）
     */
    int retryCount() default 0;

    /**
     * 方法级本地重试退避间隔（支持 100ms、1s 等标准 Duration 格式，默认为 1s）
     */
    String retryInterval() default "1s";

    /**
     * 超时降级就地补偿方法名称（定义于同一 Spring Bean 内）
     *
     * <p>当任务触发看门狗硬超时被中断时，工作线程优先就地执行该方法进行补偿与资源清理。
     * 方法支持无参，或按需声明 {@link io.github.atengk.boot.job.snail.watchdog.SnailJobExecutionMetadata}、
     * {@link java.time.Duration}、{@link Throwable} 或原始方法入参。</p>
     */
    String fallbackMethod() default "";
}
