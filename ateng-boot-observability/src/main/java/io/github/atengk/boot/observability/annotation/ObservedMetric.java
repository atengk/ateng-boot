package io.github.atengk.boot.observability.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式方法度量与性能观测注解
 * <p>
 * 标注于 Spring 受管 Bean 的方法或类级别，由 {@code ObservedMetricAspect} 透明拦截方法执行，
 * 自动采集方法调用总次数、执行成功与失败计数（Counter）、执行耗时与分位数（Timer）以及异常类型标签，
 * 并将指标标准化注册至 Micrometer / Prometheus。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface ObservedMetric {

    /**
     * 度量指标前缀名称
     * <p>
     * 若为空字符串，则默认采用 {@code method.execution} 作为全局基准指标名，
     * 并在 Tag 中自动挂载具体的 {@code class} 与 {@code method} 标签；
     * 若显式指定（例如 {@code user.login} 或 {@code order.create}），则以此作为指标名称。
     * </p>
     */
    String value() default "";

    /**
     * 静态附加标签（按 key, value 成对指定，例如 {@code {"env", "prod", "module", "trade"}}）
     */
    String[] tags() default {};

    /**
     * 是否记录方法调用耗时（Timer 度量，默认开启）
     */
    boolean recordDuration() default true;

    /**
     * 是否记录成功调用计数（Counter 度量，默认开启）
     */
    boolean recordSuccess() default true;

    /**
     * 是否记录失败/异常调用计数（Counter 度量，默认开启）
     */
    boolean recordFailure() default true;

    /**
     * 耗时直方图分位数配置（默认采集 P50、P95、P99 分位数）
     */
    double[] percentiles() default {0.5, 0.95, 0.99};
}
