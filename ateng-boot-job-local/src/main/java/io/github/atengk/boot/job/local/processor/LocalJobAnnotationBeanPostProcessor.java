package io.github.atengk.boot.job.local.processor;

import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.enums.TriggerType;
import io.github.atengk.boot.job.local.handler.MethodInvokingJobHandler;
import io.github.atengk.boot.job.local.model.JobDefinition;
import io.github.atengk.boot.job.local.registry.LocalJobRegistry;
import io.github.atengk.boot.job.local.util.DurationParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * {@link LocalJob} 注解 Bean 后置处理器
 * <p>
 * 负责在 Spring 容器生命周期中扫描受管 Bean 上标注的 {@link LocalJob} 方法，并将其注册到 {@link LocalJobRegistry}。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class LocalJobAnnotationBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(LocalJobAnnotationBeanPostProcessor.class);

    private final ObjectProvider<LocalJobRegistry> registryProvider;

    public LocalJobAnnotationBeanPostProcessor(ObjectProvider<LocalJobRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        ReflectionUtils.doWithMethods(targetClass, method -> {
            LocalJob localJob = AnnotatedElementUtils.findMergedAnnotation(method, LocalJob.class);
            if (localJob != null) {
                processMethod(bean, targetClass, method, localJob);
            }
        });
        return bean;
    }

    private void processMethod(Object bean, Class<?> targetClass, Method method, LocalJob annotation) {
        LocalJobRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            log.warn("LocalJobRegistry is not available yet, skipping job registration for method: {}#{}",
                    targetClass.getSimpleName(), method.getName());
            return;
        }

        String jobName = resolveJobName(targetClass, method, annotation);
        TriggerType triggerType = resolveTriggerType(jobName, annotation);

        JobDefinition definition = JobDefinition.builder()
                .jobName(jobName)
                .triggerType(triggerType)
                .cron(StringUtils.hasText(annotation.cron()) ? annotation.cron().trim() : null)
                .fixedRate(DurationParser.parse(annotation.fixedRate()))
                .fixedDelay(DurationParser.parse(annotation.fixedDelay()))
                .initialDelay(DurationParser.parse(annotation.initialDelay()))
                .strategy(annotation.strategy())
                .timeout(DurationParser.parse(annotation.timeout()))
                .maxRetries(annotation.maxRetries())
                .retryInterval(DurationParser.parse(annotation.retryInterval(), Duration.ofSeconds(1)))
                .clusterLock(annotation.clusterLock())
                .clusterLockLeaseTime(DurationParser.parse(annotation.clusterLockLeaseTime(), Duration.ofMinutes(5)))
                .description(annotation.description())
                .enabled(annotation.enabled())
                .build();

        MethodInvokingJobHandler handler = new MethodInvokingJobHandler(bean, method);
        registry.register(definition, handler);
        log.info("Discovered @LocalJob on method [{}#{}] as job [{}] with trigger [{}]",
                targetClass.getSimpleName(), method.getName(), jobName, triggerType);
    }

    private String resolveJobName(Class<?> targetClass, Method method, LocalJob annotation) {
        if (StringUtils.hasText(annotation.name())) {
            return annotation.name().trim();
        }
        if (StringUtils.hasText(annotation.value())) {
            return annotation.value().trim();
        }
        return targetClass.getSimpleName() + "#" + method.getName();
    }

    private TriggerType resolveTriggerType(String jobName, LocalJob annotation) {
        if (StringUtils.hasText(annotation.cron())) {
            return TriggerType.CRON;
        }
        if (StringUtils.hasText(annotation.fixedRate())) {
            return TriggerType.FIXED_RATE;
        }
        if (StringUtils.hasText(annotation.fixedDelay())) {
            return TriggerType.FIXED_DELAY;
        }
        throw new IllegalArgumentException(
                "Job [" + jobName + "] must specify at least one of [cron], [fixedRate], or [fixedDelay]"
        );
    }
}
