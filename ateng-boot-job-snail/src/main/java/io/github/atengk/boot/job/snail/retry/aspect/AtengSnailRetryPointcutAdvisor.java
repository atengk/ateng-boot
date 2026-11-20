package io.github.atengk.boot.job.snail.retry.aspect;

import com.aizuda.snailjob.client.core.annotation.Retryable;
import io.github.atengk.boot.job.snail.annotation.AtengSnailRetry;
import org.aopalliance.aop.Advice;
import org.springframework.aop.ClassFilter;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractPointcutAdvisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 独立 {@link AtengSnailRetry} Spring AOP 切面通知器
 *
 * <p>仅匹配标注了 {@link AtengSnailRetry} 且未显式直写原生 {@link Retryable} 的方法，
 * 避免与官方 {@code SnailRetryPointcutAdvisor} 发生双重拦截，同时保障显式 {@link Retryable} 优先级。</p>
 *
 * @author Ateng
 * @since 2026-11-20
 */
public class AtengSnailRetryPointcutAdvisor extends AbstractPointcutAdvisor {

    private final Advice advice;
    private final Pointcut pointcut;

    public AtengSnailRetryPointcutAdvisor(AtengSnailRetryInterceptor interceptor) {
        this.advice = interceptor;
        this.pointcut = new StandaloneAtengRetryPointcut();
    }

    @Override
    public Pointcut getPointcut() {
        return pointcut;
    }

    @Override
    public Advice getAdvice() {
        return advice;
    }

    private static final class StandaloneAtengRetryPointcut extends StaticMethodMatcherPointcut {

        private StandaloneAtengRetryPointcut() {
            setClassFilter(new StandaloneAtengRetryClassFilter());
        }

        @Override
        public boolean matches(Method method, Class<?> targetClass) {
            Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);
            if (AnnotatedElementUtils.findMergedAnnotation(specificMethod, Retryable.class) != null) {
                return false;
            }
            if (AnnotatedElementUtils.findMergedAnnotation(specificMethod, AtengSnailRetry.class) != null) {
                return true;
            }
            return targetClass != null
                    && specificMethod.getDeclaringClass() != Object.class
                    && AnnotatedElementUtils.findMergedAnnotation(targetClass, AtengSnailRetry.class) != null;
        }
    }

    private static final class StandaloneAtengRetryClassFilter implements ClassFilter {

        @Override
        public boolean matches(Class<?> clazz) {
            if (clazz == null || clazz == Object.class) {
                return false;
            }
            if (AnnotatedElementUtils.findMergedAnnotation(clazz, AtengSnailRetry.class) != null) {
                return true;
            }
            AtomicBoolean matched = new AtomicBoolean(false);
            ReflectionUtils.doWithMethods(clazz, method -> {
                if (matched.get()) {
                    return;
                }
                if (AnnotatedElementUtils.findMergedAnnotation(method, AtengSnailRetry.class) != null
                        && AnnotatedElementUtils.findMergedAnnotation(method, Retryable.class) == null) {
                    matched.set(true);
                }
            });
            return matched.get();
        }
    }
}
