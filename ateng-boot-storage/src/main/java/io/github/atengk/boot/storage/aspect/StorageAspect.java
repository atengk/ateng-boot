package io.github.atengk.boot.storage.aspect;

import io.github.atengk.boot.storage.annotation.Storage;
import io.github.atengk.boot.storage.context.StorageContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;

/**
 * 声明式多存储源切换切面
 * 拦截标注了 {@link Storage} 的类与方法，自动切换当前线程存储源现场并在执行完毕后严格恢复
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class StorageAspect {

    @Around("@annotation(io.github.atengk.boot.storage.annotation.Storage) || @within(io.github.atengk.boot.storage.annotation.Storage)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        String sourceKey = resolveSourceKey(joinPoint);
        String previous = StorageContextHolder.capture();
        try {
            if (sourceKey != null && !sourceKey.isBlank()) {
                StorageContextHolder.set(sourceKey);
            }
            return joinPoint.proceed();
        } finally {
            StorageContextHolder.restore(previous);
        }
    }

    /**
     * 解析目标切点对应的存储源标识（方法级优先于类级）
     */
    private String resolveSourceKey(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 1. 优先尝试从方法上获取注解
        Storage methodAnnotation = method.getAnnotation(Storage.class);
        if (methodAnnotation != null && !methodAnnotation.value().isBlank()) {
            return methodAnnotation.value().trim();
        }

        // 2. 其次尝试从目标类上获取注解
        Class<?> targetClass = joinPoint.getTarget() != null
                ? joinPoint.getTarget().getClass()
                : signature.getDeclaringType();
        Storage classAnnotation = targetClass.getAnnotation(Storage.class);
        if (classAnnotation != null && !classAnnotation.value().isBlank()) {
            return classAnnotation.value().trim();
        }

        return null;
    }
}
