package io.github.atengk.boot.job.snail.retry.aspect;

import com.aizuda.snailjob.client.core.MethodResult;
import com.aizuda.snailjob.client.core.RetryCondition;
import com.aizuda.snailjob.client.core.annotation.Propagation;
import com.aizuda.snailjob.client.core.annotation.Retryable;
import com.aizuda.snailjob.client.core.intercepter.RetrySiteSnapshot;
import com.aizuda.snailjob.client.core.intercepter.SnailRetryInterceptor;
import com.aizuda.snailjob.client.core.retryer.MethodParamsType;
import com.aizuda.snailjob.client.core.strategy.ExecutorAnnotationMethod;
import com.aizuda.snailjob.client.core.strategy.RetryStrategy;
import io.github.atengk.boot.job.snail.annotation.AtengSnailRetry;
import io.github.atengk.boot.job.snail.retry.scanner.AtengSnailRetryScanner;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 独立 {@link AtengSnailRetry} 原生重试试点拦截器适配器
 *
 * <p>继承 SnailJob 官方 {@link SnailRetryInterceptor} 并覆写入口元数据构造器，
 * 将 {@link AtengSnailRetry}（含 {@code value()} 与 {@code scene()} 别名及方法名回退）
 * 合成适配为标准 {@link Retryable} 契约，完整复刻官方本地重试、远程上报与 {@link RetrySiteSnapshot} 状态流转。</p>
 *
 * @author Ateng
 * @since 2026-11-20
 */
public class AtengSnailRetryInterceptor extends SnailRetryInterceptor {

    public AtengSnailRetryInterceptor(int order, RetryStrategy retryStrategy) {
        super(order, retryStrategy);
    }

    @Override
    public RetrySiteSnapshot.MethodEntranceMeta buildMethodEntranceMeta(MethodInvocation invocation) {
        Method method = invocation.getMethod();
        Object target = invocation.getThis();
        Class<?> targetClass = target != null ? target.getClass() : method.getDeclaringClass();
        Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);

        AtengSnailRetry atengRetry = AnnotatedElementUtils.findMergedAnnotation(specificMethod, AtengSnailRetry.class);
        if (atengRetry == null) {
            atengRetry = AnnotatedElementUtils.findMergedAnnotation(targetClass, AtengSnailRetry.class);
        }
        if (atengRetry == null) {
            return super.buildMethodEntranceMeta(invocation);
        }

        Retryable synthesizedRetryable = synthesizeRetryable(atengRetry, specificMethod);
        String executorClassName = targetClass.getName();
        return new AtengMethodEntranceMeta(invocation, synthesizedRetryable, executorClassName);
    }

    private static Retryable synthesizeRetryable(AtengSnailRetry atengRetry, Method specificMethod) {
        String scene = AtengSnailRetryScanner.resolveScene(atengRetry, specificMethod);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("scene", scene);
        attrs.put("include", atengRetry.include());
        attrs.put("exclude", atengRetry.exclude());
        attrs.put("retryStrategy", atengRetry.retryStrategy());
        attrs.put("retryMethod", ExecutorAnnotationMethod.class);
        attrs.put("idempotentId", atengRetry.idempotentId());
        attrs.put("retryCompleteCallback", atengRetry.retryCompleteCallback());
        attrs.put("bizNo", atengRetry.bizNo());
        attrs.put("localTimes", atengRetry.localTimes());
        attrs.put("localInterval", atengRetry.localInterval());
        attrs.put("isThrowException", atengRetry.isThrowException());
        attrs.put("async", false);
        attrs.put("timeout", 60000L);
        attrs.put("unit", TimeUnit.MILLISECONDS);
        attrs.put("propagation", Propagation.REQUIRED);
        attrs.put("methodResult", MethodResult.NoMethodResult.class);
        attrs.put("retryIfResult", RetryCondition.NoRetry.class);
        attrs.put("methodParamsStrategy", MethodParamsType.REFERENCE);
        return AnnotationUtils.synthesizeAnnotation(attrs, Retryable.class, specificMethod);
    }

    private static final class AtengMethodEntranceMeta implements RetrySiteSnapshot.MethodEntranceMeta {

        private final MethodInvocation methodInvocation;
        private final Retryable retryable;
        private final String executorClassName;
        private final String methodEntrance;
        private final Object[] methodParams;

        private AtengMethodEntranceMeta(
                MethodInvocation methodInvocation,
                Retryable retryable,
                String executorClassName) {
            this.methodInvocation = methodInvocation;
            this.retryable = retryable;
            this.executorClassName = executorClassName;
            this.methodEntrance = retryable.scene() + "_" + executorClassName;
            this.methodParams = methodInvocation.getArguments();
        }

        @Override
        public MethodInvocation getMethodInvocation() {
            return methodInvocation;
        }

        @Override
        public String getMethodEntrance() {
            return methodEntrance;
        }

        @Override
        public Object[] getMethodParams() {
            return methodParams;
        }

        @Override
        public Retryable getRetryable() {
            return retryable;
        }

        @Override
        public String getExecutorClassName() {
            return executorClassName;
        }

        @Override
        public boolean isRequiresNew() {
            return false;
        }

        @Override
        public boolean isThrowException() {
            return retryable.isThrowException();
        }
    }
}
