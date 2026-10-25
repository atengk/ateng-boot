package io.github.atengk.boot.job.local.handler;

import io.github.atengk.boot.job.local.model.JobContext;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 基于反射的方法调用型任务处理器
 * <p>
 * 负责自适应分发调用 Bean 上的无参方法或以 {@link JobContext} 为入参的方法。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class MethodInvokingJobHandler implements JobHandler {

    private final Object targetBean;
    private final Method method;
    private final boolean passContext;

    public MethodInvokingJobHandler(Object targetBean, Method method) {
        this.targetBean = targetBean;
        this.method = method;
        ReflectionUtils.makeAccessible(method);
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length == 0) {
            this.passContext = false;
        } else if (parameterTypes.length == 1 && JobContext.class.isAssignableFrom(parameterTypes[0])) {
            this.passContext = true;
        } else {
            throw new IllegalArgumentException(
                    "Method [" + method.getName() + "] annotated with @LocalJob must have either zero parameters or a single JobContext parameter"
            );
        }
    }

    @Override
    public Object execute(JobContext context) throws Exception {
        try {
            if (passContext) {
                return method.invoke(targetBean, context);
            } else {
                return method.invoke(targetBean);
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
}
