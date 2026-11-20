package io.github.atengk.boot.job.snail.retry.scanner;

import com.aizuda.snailjob.client.core.RetryCondition;
import com.aizuda.snailjob.client.core.Scanner;
import com.aizuda.snailjob.client.core.annotation.Retryable;
import com.aizuda.snailjob.client.core.cache.RetryerInfoCache;
import com.aizuda.snailjob.client.core.retryer.RetryerInfo;
import com.aizuda.snailjob.client.core.strategy.ExecutorAnnotationMethod;
import io.github.atengk.boot.job.snail.annotation.AtengSnailRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 独立 {@link AtengSnailRetry} 生产级重试执行器扫描器
 *
 * <p>对接 SnailJob 官方 {@link com.aizuda.snailjob.client.core.register.RetryableRegistrar} 的 {@link Scanner} SPI，
 * 扫描仅使用 {@link AtengSnailRetry} 声明的方法并构建 {@link RetryerInfo} 上报注册；
 * 若同一方法已显式直写原生 {@link Retryable} 或 {@link RetryerInfoCache} 已存在同键注册记录，则自动幂等跳过，
 * 确保混用场景下显式 {@link Retryable} 100% 优先生效。</p>
 *
 * @author Ateng
 * @since 2026-11-20
 */
public class AtengSnailRetryScanner implements Scanner, ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(AtengSnailRetryScanner.class);

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public List<RetryerInfo> doScan() {
        List<RetryerInfo> result = new ArrayList<>();
        if (applicationContext == null) {
            return result;
        }

        String[] beanNames = applicationContext.getBeanNamesForType(Object.class, false, true);
        for (String beanName : beanNames) {
            Object bean;
            try {
                bean = applicationContext.getBean(beanName);
            } catch (Throwable t) {
                log.debug("跳过无法实例化的 Spring Bean [{}]: {}", beanName, t.getMessage());
                continue;
            }
            if (bean == null) {
                continue;
            }

            Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
            Map<Method, AtengSnailRetry> annotatedMethods;
            try {
                annotatedMethods = MethodIntrospector.selectMethods(
                        targetClass,
                        (MethodIntrospector.MetadataLookup<AtengSnailRetry>) method ->
                                AnnotatedElementUtils.findMergedAnnotation(method, AtengSnailRetry.class)
                );
            } catch (Throwable ex) {
                log.error("扫描 Bean [{}] 的 @AtengSnailRetry 方法失败: {}", targetClass.getName(), ex.getMessage(), ex);
                continue;
            }
            if (annotatedMethods == null || annotatedMethods.isEmpty()) {
                continue;
            }

            String executorClassName = targetClass.getName();
            for (Map.Entry<Method, AtengSnailRetry> entry : annotatedMethods.entrySet()) {
                Method method = entry.getKey();
                AtengSnailRetry atengRetry = entry.getValue();

                // 若该方法显式标注了原生 @Retryable，交由 SnailJob 原生 RetryableScanner 注册，显式直写优先
                if (AnnotatedElementUtils.findMergedAnnotation(method, Retryable.class) != null) {
                    continue;
                }

                String scene = resolveScene(atengRetry, method);
                if (RetryerInfoCache.get(scene, executorClassName) != null) {
                    continue;
                }

                Set<Class<? extends Throwable>> includeSet = atengRetry.include().length > 0
                        ? new HashSet<>(Arrays.asList(atengRetry.include()))
                        : new HashSet<>();
                Set<Class<? extends Throwable>> excludeSet = atengRetry.exclude().length > 0
                        ? new HashSet<>(Arrays.asList(atengRetry.exclude()))
                        : new HashSet<>();

                Method invocableMethod = AopUtils.selectInvocableMethod(method, bean.getClass());
                ReflectionUtils.makeAccessible(invocableMethod);
                RetryerInfo retryerInfo = new RetryerInfo(
                        scene,
                        executorClassName,
                        includeSet,
                        excludeSet,
                        bean,
                        invocableMethod,
                        atengRetry.retryStrategy(),
                        atengRetry.localTimes(),
                        atengRetry.localInterval(),
                        atengRetry.idempotentId(),
                        atengRetry.bizNo(),
                        ExecutorAnnotationMethod.class,
                        atengRetry.isThrowException(),
                        atengRetry.retryCompleteCallback(),
                        false,
                        false,
                        60000L,
                        TimeUnit.MILLISECONDS,
                        RetryCondition.NoRetry.class
                );
                result.add(retryerInfo);
            }
        }
        return result;
    }

    /**
     * 统一解析 {@link AtengSnailRetry} 场景名称（{@code scene} -> {@code value} -> 方法名）
     *
     * @param annotation 注解实例
     * @param method     目标方法
     * @return 解析后的场景名称
     */
    public static String resolveScene(AtengSnailRetry annotation, Method method) {
        if (annotation != null) {
            if (StringUtils.hasText(annotation.scene())) {
                return annotation.scene().trim();
            }
            if (StringUtils.hasText(annotation.value())) {
                return annotation.value().trim();
            }
        }
        return method != null ? method.getName() : "";
    }
}
