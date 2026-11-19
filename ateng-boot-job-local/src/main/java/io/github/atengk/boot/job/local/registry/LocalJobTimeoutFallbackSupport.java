package io.github.atengk.boot.job.local.registry;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.local.exception.JobTimeoutException;
import io.github.atengk.boot.job.local.fallback.LocalJobTimeoutFallbackHandler;
import io.github.atengk.boot.job.local.handler.JobHandler;
import io.github.atengk.boot.job.local.handler.MethodInvokingJobHandler;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobDefinition;
import io.github.atengk.boot.job.local.model.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 硬超时熔断两级降级补偿解析与调用协作器
 *
 * @author Ateng
 * @since 2026-11-19
 */
final class LocalJobTimeoutFallbackSupport {

    private static final Logger log = LoggerFactory.getLogger(LocalJobTimeoutFallbackSupport.class);

    private LocalJobTimeoutFallbackSupport() {
    }

    static JobResult resolveTimeoutFallback(JobDefinition definition,
                                            JobHandler handler,
                                            Supplier<LocalJobTimeoutFallbackHandler> globalHandlerProvider,
                                            JobContext context,
                                            JobTimeoutException timeoutEx) {
        String declaredMethodName = definition.getFallbackMethod();
        boolean hasDeclaredFallback = declaredMethodName != null && !declaredMethodName.isBlank();
        if (hasDeclaredFallback) {
            Object rawTarget = (handler instanceof MethodInvokingJobHandler mijh) ? mijh.getTargetBean() : handler;
            if (rawTarget != null) {
                Class<?> targetClass = AopUtils.getTargetClass(rawTarget);
                Method fallbackMethod = findFallbackMethod(targetClass, declaredMethodName.trim(), timeoutEx);
                if (fallbackMethod != null) {
                    return invokeDeclaredFallbackMethod(definition.getJobName(), rawTarget, fallbackMethod,
                            declaredMethodName.trim(), context, timeoutEx);
                }
                log.warn("Job [{}] declared fallbackMethod [{}], but no compatible method found on [{}]",
                        definition.getJobName(), declaredMethodName, targetClass.getName());
            }
        }

        if (handler != null) {
            JobResult handlerFallback = handler.executeTimeoutFallback(context, timeoutEx);
            if (handlerFallback != null) {
                return normalizeFallbackResult(handlerFallback,
                        "Timeout fallback handled by [" + handler.getClass().getSimpleName() + "]");
            }
        }

        LocalJobTimeoutFallbackHandler fallbackHandler = globalHandlerProvider != null
                ? globalHandlerProvider.get()
                : null;
        if (fallbackHandler != null) {
            JobResult globalResult = fallbackHandler.handleTimeoutFallback(context, timeoutEx);
            return normalizeFallbackResult(globalResult,
                    "Timeout fallback handled by [" + fallbackHandler.getClass().getSimpleName() + "]");
        }
        return null;
    }

    private static JobResult invokeDeclaredFallbackMethod(String jobName,
                                                          Object rawTarget,
                                                          Method method,
                                                          String methodName,
                                                          JobContext context,
                                                          JobTimeoutException timeoutEx) {
        Object invokeTarget = unwrapTargetBean(rawTarget);
        ReflectionUtils.makeAccessible(method);
        Object[] args = resolveFallbackArgs(method, context, timeoutEx);
        try {
            Object result = args.length == 0 ? method.invoke(invokeTarget) : method.invoke(invokeTarget, args);
            log.info("Job [{}] executed fallbackMethod [{}] after hard timeout", jobName, methodName);
            return normalizeFallbackResult(result, "Timeout fallback executed: " + methodName);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getTargetException() != null ? ex.getTargetException() : ex;
            if (cause instanceof JobTimeoutException timeoutCause) {
                throw timeoutCause;
            }
            if (cause instanceof BusinessException businessCause) {
                throw businessCause;
            }
            throw new BusinessException("Fallback method [" + methodName + "] failed: " + cause.getMessage(), cause);
        } catch (Throwable ex) {
            throw new BusinessException("Failed to invoke fallback method [" + methodName + "]: " + ex.getMessage(), ex);
        }
    }

    private static Method findFallbackMethod(Class<?> targetClass, String methodName, JobTimeoutException timeoutEx) {
        List<Method> candidates = new ArrayList<>();
        Class<?> current = targetClass;
        while (current != null && !Object.class.equals(current)) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    candidates.add(method);
                }
            }
            current = current.getSuperclass();
        }
        if (candidates.isEmpty()) {
            return null;
        }
        for (int priority = 1; priority <= 4; priority++) {
            Method bestMatch = null;
            for (Method candidate : candidates) {
                if (matchesFallbackPriority(candidate.getParameterTypes(), priority, timeoutEx)) {
                    if (bestMatch == null || isMoreSpecificThrowableSignature(candidate.getParameterTypes(), bestMatch.getParameterTypes())) {
                        bestMatch = candidate;
                    }
                }
            }
            if (bestMatch != null) {
                return bestMatch;
            }
        }
        return null;
    }

    private static boolean isMoreSpecificThrowableSignature(Class<?>[] candidateTypes, Class<?>[] currentBestTypes) {
        Class<?> candidateThrowable = extractThrowableParamType(candidateTypes);
        Class<?> currentBestThrowable = extractThrowableParamType(currentBestTypes);
        if (candidateThrowable == null || currentBestThrowable == null) {
            return false;
        }
        return !candidateThrowable.equals(currentBestThrowable) && currentBestThrowable.isAssignableFrom(candidateThrowable);
    }

    private static Class<?> extractThrowableParamType(Class<?>[] paramTypes) {
        for (Class<?> paramType : paramTypes) {
            if (Throwable.class.isAssignableFrom(paramType)) {
                return paramType;
            }
        }
        return null;
    }

    private static boolean matchesFallbackPriority(Class<?>[] paramTypes, int priority, JobTimeoutException timeoutEx) {
        return switch (priority) {
            case 1 -> paramTypes.length == 2
                    && ((JobContext.class.isAssignableFrom(paramTypes[0]) && isCompatibleThrowable(paramTypes[1], timeoutEx))
                    || (JobContext.class.isAssignableFrom(paramTypes[1]) && isCompatibleThrowable(paramTypes[0], timeoutEx)));
            case 2 -> paramTypes.length == 1 && JobContext.class.isAssignableFrom(paramTypes[0]);
            case 3 -> paramTypes.length == 1 && isCompatibleThrowable(paramTypes[0], timeoutEx);
            case 4 -> paramTypes.length == 0;
            default -> false;
        };
    }

    private static boolean isCompatibleThrowable(Class<?> paramType, JobTimeoutException timeoutEx) {
        return Throwable.class.isAssignableFrom(paramType) && paramType.isInstance(timeoutEx);
    }

    private static Object[] resolveFallbackArgs(Method method, JobContext context, JobTimeoutException timeoutEx) {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 0) {
            return new Object[0];
        }
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            if (JobContext.class.isAssignableFrom(paramTypes[i])) {
                args[i] = context;
            } else if (Throwable.class.isAssignableFrom(paramTypes[i])) {
                args[i] = timeoutEx;
            }
        }
        return args;
    }

    private static Object unwrapTargetBean(Object bean) {
        if (AopUtils.isAopProxy(bean)) {
            Object singleton = AopProxyUtils.getSingletonTarget(bean);
            if (singleton != null) {
                return singleton;
            }
            if (bean instanceof Advised advised) {
                try {
                    Object target = advised.getTargetSource().getTarget();
                    if (target != null) {
                        return target;
                    }
                } catch (Exception ignored) {
                    // 回退使用原始代理对象
                }
            }
        }
        return bean;
    }

    static JobResult normalizeFallbackResult(Object result, String defaultMessage) {
        if (result instanceof JobResult jobResult) {
            return jobResult;
        }
        if (result != null) {
            return JobResult.failure("Timeout fallback executed: " + result);
        }
        return JobResult.failure(defaultMessage);
    }
}
