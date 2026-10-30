package io.github.atengk.boot.observability.aspect;

import io.github.atengk.boot.observability.annotation.ObservedMetric;
import io.github.atengk.boot.observability.metrics.MetricsTemplate;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 声明式方法性能与健康度量 AOP 切面
 * <p>
 * 环绕拦截标注了 {@link ObservedMetric} 的方法或类，透明测量方法执行耗时与分位数，
 * 并按成功/失败状态（{@code success} / {@code error}）分别递增计数器与打标，
 * 保障业务代码与可观测性底座彻底解耦，在度量收集异常时静默降级，坚决不阻塞业务主线程。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Aspect
@Slf4j
public class ObservedMetricAspect {

    private final MetricsTemplate metricsTemplate;

    public ObservedMetricAspect(MetricsTemplate metricsTemplate) {
        this.metricsTemplate = Objects.requireNonNull(metricsTemplate, "metricsTemplate must not be null");
    }

    @Around("@annotation(io.github.atengk.boot.observability.annotation.ObservedMetric) || @within(io.github.atengk.boot.observability.annotation.ObservedMetric)")
    public Object observeMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Class<?> targetClass = joinPoint.getTarget() != null ? joinPoint.getTarget().getClass() : signature.getDeclaringType();
        Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);

        ObservedMetric annotation = resolveAnnotation(specificMethod, targetClass);
        if (annotation == null || this.metricsTemplate.getRegistry() == null) {
            return joinPoint.proceed();
        }

        String rawName = annotation.value();
        String metricName = (rawName != null && !rawName.isBlank()) ? rawName : "method.execution";

        String className = targetClass.getSimpleName();
        String methodName = specificMethod.getName();

        Tags staticTags = MetricsTemplate.toTags(annotation.tags());
        Tags baseTags = Tags.of("class", className, "method", methodName).and(staticTags);

        Timer.Sample sample = annotation.recordDuration() ? Timer.start(this.metricsTemplate.getRegistry()) : null;

        try {
            Object result = joinPoint.proceed();
            recordSuccess(annotation, metricName, baseTags, sample);
            return result;
        } catch (Throwable ex) {
            recordFailure(annotation, metricName, baseTags, sample, ex);
            throw ex;
        }
    }

    private ObservedMetric resolveAnnotation(Method method, Class<?> targetClass) {
        ObservedMetric annotation = AnnotatedElementUtils.findMergedAnnotation(method, ObservedMetric.class);
        if (annotation != null) {
            return annotation;
        }
        return AnnotatedElementUtils.findMergedAnnotation(targetClass, ObservedMetric.class);
    }

    private void recordSuccess(ObservedMetric annotation, String metricName, Tags baseTags, Timer.Sample sample) {
        try {
            Tags successTags = baseTags.and("status", "success");
            if (annotation.recordDuration() && sample != null) {
                Timer.Builder builder = Timer.builder(metricName)
                        .tags(successTags)
                        .description("Observed method execution timer");
                if (annotation.percentiles() != null && annotation.percentiles().length > 0) {
                    builder.publishPercentiles(annotation.percentiles());
                }
                sample.stop(builder.register(this.metricsTemplate.getRegistry()));
            }
            if (annotation.recordSuccess()) {
                this.metricsTemplate.increment(metricName + ".calls", 1.0, successTags);
            }
        } catch (Exception e) {
            log.warn("ObservedMetricAspect 记录成功指标异常: {}", e.getMessage());
        }
    }

    private void recordFailure(ObservedMetric annotation, String metricName, Tags baseTags, Timer.Sample sample, Throwable ex) {
        try {
            String exceptionName = ex.getClass().getSimpleName();
            Tags failureTags = baseTags.and("status", "error", "exception", exceptionName);
            if (annotation.recordDuration() && sample != null) {
                Timer.Builder builder = Timer.builder(metricName)
                        .tags(failureTags)
                        .description("Observed method execution timer");
                if (annotation.percentiles() != null && annotation.percentiles().length > 0) {
                    builder.publishPercentiles(annotation.percentiles());
                }
                sample.stop(builder.register(this.metricsTemplate.getRegistry()));
            }
            if (annotation.recordFailure()) {
                this.metricsTemplate.increment(metricName + ".calls", 1.0, failureTags);
            }
        } catch (Exception e) {
            log.warn("ObservedMetricAspect 记录失败指标异常: {}", e.getMessage());
        }
    }
}
