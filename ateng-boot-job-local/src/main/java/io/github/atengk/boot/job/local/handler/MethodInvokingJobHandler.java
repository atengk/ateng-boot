package io.github.atengk.boot.job.local.handler;

import io.github.atengk.boot.job.local.model.JobContext;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 基于反射的方法调用型任务处理器
 * <p>
 * 负责自适应分发调用 Bean 上声明的方法，灵活支持以下四种参数方法签名：
 * <ul>
 *   <li>无参方法：{@code public void myJob()}</li>
 *   <li>仅上下文：{@code public JobResult myJob(JobContext context)}</li>
 *   <li>仅自定义 DTO：{@code public JobResult myJob(MyParam param)}</li>
 *   <li>自定义 DTO 与上下文并存：{@code public JobResult myJob(MyParam param, JobContext context)} 或 {@code public JobResult myJob(JobContext context, MyParam param)}</li>
 * </ul>
 * 声明自定义 DTO 参数时，将自动利用上下文参数反序列化并执行 JSR-303 Fail-Fast 校验。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class MethodInvokingJobHandler implements JobHandler {

    private final Object targetBean;
    private final Method method;
    private final Class<?> dtoType;
    private final int contextIndex;
    private final int dtoIndex;

    public MethodInvokingJobHandler(Object targetBean, Method method) {
        if (targetBean == null || method == null) {
            throw new IllegalArgumentException("targetBean and method must not be null");
        }
        this.targetBean = targetBean;
        this.method = method;
        ReflectionUtils.makeAccessible(method);
        Class<?>[] parameterTypes = method.getParameterTypes();

        if (parameterTypes.length == 0) {
            this.dtoType = null;
            this.contextIndex = -1;
            this.dtoIndex = -1;
        } else if (parameterTypes.length == 1) {
            if (JobContext.class.isAssignableFrom(parameterTypes[0])) {
                this.dtoType = null;
                this.contextIndex = 0;
                this.dtoIndex = -1;
            } else {
                this.dtoType = parameterTypes[0];
                this.contextIndex = -1;
                this.dtoIndex = 0;
            }
        } else if (parameterTypes.length == 2) {
            boolean firstIsContext = JobContext.class.isAssignableFrom(parameterTypes[0]);
            boolean secondIsContext = JobContext.class.isAssignableFrom(parameterTypes[1]);
            if (firstIsContext && !secondIsContext) {
                this.contextIndex = 0;
                this.dtoIndex = 1;
                this.dtoType = parameterTypes[1];
            } else if (!firstIsContext && secondIsContext) {
                this.contextIndex = 1;
                this.dtoIndex = 0;
                this.dtoType = parameterTypes[0];
            } else {
                throw new IllegalArgumentException(
                        "Method [" + method.getName() + "] annotated with @LocalJob with 2 parameters must contain one JobContext and one custom DTO parameter"
                );
            }
        } else {
            throw new IllegalArgumentException(
                    "Method [" + method.getName() + "] annotated with @LocalJob must have at most 2 parameters (JobContext and/or custom DTO), found: " + parameterTypes.length
            );
        }
    }

    @Override
    public Object execute(JobContext context) throws Exception {
        Object[] args;
        if (contextIndex == -1 && dtoIndex == -1) {
            args = null;
        } else if (dtoIndex == -1) {
            args = new Object[]{context};
        } else if (contextIndex == -1) {
            Object dto = context != null ? context.bind(dtoType) : null;
            args = new Object[]{dto};
        } else {
            Object dto = context != null ? context.bind(dtoType) : null;
            args = new Object[2];
            args[contextIndex] = context;
            args[dtoIndex] = dto;
        }

        try {
            if (args == null) {
                return method.invoke(targetBean);
            } else {
                return method.invoke(targetBean, args);
            }
        } catch (InvocationTargetException ex) {
            Throwable targetException = ex.getTargetException();
            if (targetException instanceof Exception e) {
                throw e;
            } else if (targetException instanceof Error error) {
                throw error;
            } else {
                throw new IllegalStateException("Failed to execute @LocalJob method: " + method.getName(), targetException);
            }
        }
    }

    public Object getTargetBean() {
        return targetBean;
    }

    public Method getMethod() {
        return method;
    }

    public Class<?> getDtoType() {
        return dtoType;
    }

    public boolean isPassContext() {
        return contextIndex != -1;
    }
}
