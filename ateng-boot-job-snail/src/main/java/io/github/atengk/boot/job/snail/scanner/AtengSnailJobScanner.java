package io.github.atengk.boot.job.snail.scanner;

import com.aizuda.snailjob.client.job.core.IJobExecutor;
import com.aizuda.snailjob.client.job.core.Scanner;
import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.annotation.MapExecutor;
import com.aizuda.snailjob.client.job.core.annotation.MergeReduceExecutor;
import com.aizuda.snailjob.client.job.core.annotation.ReduceExecutor;
import com.aizuda.snailjob.client.job.core.cache.JobExecutorInfoCache;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.client.job.core.dto.JobExecutorInfo;
import com.aizuda.snailjob.client.job.core.dto.MapArgs;
import com.aizuda.snailjob.client.job.core.dto.MergeReduceArgs;
import com.aizuda.snailjob.client.job.core.dto.ReduceArgs;
import com.aizuda.snailjob.client.job.core.dto.ShardingJobArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import io.github.atengk.boot.job.snail.aspect.SnailJobContextAspect;
import io.github.atengk.boot.job.snail.support.SnailJobInternalSupport;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立 {@link AtengSnailJob} 生产级执行器扫描器
 *
 * <p>对接 SnailJob 官方 {@link com.aizuda.snailjob.client.job.core.register.JobExecutorRegistrar} 的 {@link Scanner} SPI，
 * 扫描仅使用 {@link AtengSnailJob} 声明的独立调度任务与类级别 MapReduce 根任务并产出 {@link JobExecutorInfo}，
 * 对显式同时标注 {@link JobExecutor} 或已存在于 {@link JobExecutorInfoCache} 的任务保持幂等跳过，
 * 对不同方法声明相同独立任务名的冲突在扫描期 Fail-Fast 阻断。</p>
 *
 * @author Ateng
 * @since 2026-11-19
 */
public class AtengSnailJobScanner implements Scanner, ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(AtengSnailJobScanner.class);

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public List<JobExecutorInfo> doScan() {
        List<JobExecutorInfo> result = new ArrayList<>();
        if (applicationContext == null) {
            return result;
        }

        Map<String, Method> seenStandaloneMethods = new LinkedHashMap<>();
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
            if (IJobExecutor.class.isAssignableFrom(targetClass)) {
                continue;
            }

            scanClassLevelExecutor(bean, targetClass, result, seenStandaloneMethods);
            scanMethodLevelExecutors(bean, targetClass, result, seenStandaloneMethods);
        }
        return result;
    }

    private void scanClassLevelExecutor(
            Object bean,
            Class<?> targetClass,
            List<JobExecutorInfo> result,
            Map<String, Method> seenStandaloneMethods) {
        AtengSnailJob classJob = AnnotatedElementUtils.findMergedAnnotation(targetClass, AtengSnailJob.class);
        if (classJob == null || AnnotatedElementUtils.findMergedAnnotation(targetClass, JobExecutor.class) != null) {
            return;
        }

        String executorName = SnailJobInternalSupport.resolveStandaloneJobName(classJob, targetClass.getSimpleName());
        if (JobExecutorInfoCache.isExisted(executorName)) {
            return;
        }
        Method existingClassMethod = seenStandaloneMethods.get(executorName);
        if (existingClassMethod != null) {
            throw new IllegalStateException("检测到重复的 @AtengSnailJob 任务名称 [" + executorName
                    + "]: " + existingClassMethod + " 与类级别执行器 " + targetClass.getName());
        }

        Method executeMethod = findClassExecuteMethod(targetClass, classJob.method());
        Method invocableExecute = executeMethod != null ? AopUtils.selectInvocableMethod(executeMethod, bean.getClass()) : null;

        Map<String, Method> mapExecutorMap = new HashMap<>();
        Method reduceExecutor = null;
        Method mergeReduceExecutor = null;
        for (Method declaredMethod : ReflectionUtils.getAllDeclaredMethods(targetClass)) {
            Class<?>[] paramTypes = declaredMethod.getParameterTypes();
            if (paramTypes.length == 0) {
                continue;
            }
            MapExecutor mapAnn = AnnotatedElementUtils.findMergedAnnotation(declaredMethod, MapExecutor.class);
            if (mapAnn != null && MapArgs.class.isAssignableFrom(paramTypes[0])) {
                mapExecutorMap.put(mapAnn.taskName(), AopUtils.selectInvocableMethod(declaredMethod, bean.getClass()));
                continue;
            }
            ReduceExecutor reduceAnn = AnnotatedElementUtils.findMergedAnnotation(declaredMethod, ReduceExecutor.class);
            if (reduceAnn != null && ReduceArgs.class.isAssignableFrom(paramTypes[0])) {
                reduceExecutor = AopUtils.selectInvocableMethod(declaredMethod, bean.getClass());
                continue;
            }
            MergeReduceExecutor mergeAnn = AnnotatedElementUtils.findMergedAnnotation(declaredMethod, MergeReduceExecutor.class);
            if (mergeAnn != null && MergeReduceArgs.class.isAssignableFrom(paramTypes[0])) {
                mergeReduceExecutor = AopUtils.selectInvocableMethod(declaredMethod, bean.getClass());
            }
        }

        if (invocableExecute == null && mapExecutorMap.isEmpty() && reduceExecutor == null && mergeReduceExecutor == null) {
            throw new IllegalStateException("类级别 @AtengSnailJob [" + executorName
                    + "] 未找到目标执行方法 [" + classJob.method() + "] 且未声明 Map/Reduce 方法");
        }

        seenStandaloneMethods.put(executorName, invocableExecute);
        if (invocableExecute != null && !isDirectNativeCompatibleSignature(invocableExecute)
                && mapExecutorMap.isEmpty() && reduceExecutor == null && mergeReduceExecutor == null) {
            StandaloneJobDispatcher dispatcher = new StandaloneJobDispatcher(bean, invocableExecute);
            result.add(new JobExecutorInfo(executorName, StandaloneJobDispatcher.DISPATCH_METHOD, null, null, null, dispatcher));
            return;
        }
        result.add(new JobExecutorInfo(executorName, invocableExecute, mapExecutorMap, reduceExecutor, mergeReduceExecutor, bean));
    }

    private void scanMethodLevelExecutors(
            Object bean,
            Class<?> targetClass,
            List<JobExecutorInfo> result,
            Map<String, Method> seenStandaloneMethods) {
        Map<Method, AtengSnailJob> annotatedMethods;
        try {
            annotatedMethods = MethodIntrospector.selectMethods(
                    targetClass,
                    (MethodIntrospector.MetadataLookup<AtengSnailJob>) method ->
                            AnnotatedElementUtils.findMergedAnnotation(method, AtengSnailJob.class)
            );
        } catch (Throwable ex) {
            log.error("扫描 Bean [{}] 的 @AtengSnailJob 方法失败: {}", targetClass.getName(), ex.getMessage(), ex);
            return;
        }
        if (annotatedMethods == null || annotatedMethods.isEmpty()) {
            return;
        }

        for (Map.Entry<Method, AtengSnailJob> entry : annotatedMethods.entrySet()) {
            Method method = entry.getKey();
            AtengSnailJob atengJob = entry.getValue();

            // 切片方法由类级别根任务统一纳管；同时标注 @JobExecutor 的方法由官方扫描器负责注册
            if (AnnotatedElementUtils.findMergedAnnotation(method, MapExecutor.class) != null
                    || AnnotatedElementUtils.findMergedAnnotation(method, ReduceExecutor.class) != null
                    || AnnotatedElementUtils.findMergedAnnotation(method, MergeReduceExecutor.class) != null
                    || AnnotatedElementUtils.findMergedAnnotation(method, JobExecutor.class) != null) {
                continue;
            }

            String declaredName = resolveDeclaredName(atengJob);
            String executorName = StringUtils.hasText(declaredName) ? declaredName : method.getName();

            if (JobExecutorInfoCache.isExisted(executorName)) {
                continue;
            }

            Method existing = seenStandaloneMethods.get(executorName);
            if (existing != null) {
                if (!existing.equals(method)) {
                    throw new IllegalStateException("检测到重复的 @AtengSnailJob 任务名称 [" + executorName
                            + "]: " + existing + " 与 " + method);
                }
                continue;
            }

            seenStandaloneMethods.put(executorName, method);
            Method invocableMethod = AopUtils.selectInvocableMethod(method, bean.getClass());
            if (isDirectNativeCompatibleSignature(invocableMethod)) {
                result.add(new JobExecutorInfo(executorName, invocableMethod, null, null, null, bean));
            } else {
                // 多形参或强类型 DTO 签名通过桥接器接入 AnnotationJobExecutor，保证切面反序列化与 JSR-303 校验完整触发
                StandaloneJobDispatcher dispatcher = new StandaloneJobDispatcher(bean, invocableMethod);
                result.add(new JobExecutorInfo(executorName, StandaloneJobDispatcher.DISPATCH_METHOD, null, null, null, dispatcher));
            }
        }
    }

    private static String resolveDeclaredName(AtengSnailJob annotation) {
        if (annotation == null) {
            return "";
        }
        if (StringUtils.hasText(annotation.name())) {
            return annotation.name().trim();
        }
        return StringUtils.hasText(annotation.value()) ? annotation.value().trim() : "";
    }

    private static Method findClassExecuteMethod(Class<?> targetClass, String methodName) {
        if (!StringUtils.hasText(methodName)) {
            return null;
        }
        for (Class<?> paramType : new Class<?>[]{ShardingJobArgs.class, JobArgs.class}) {
            Method m = ReflectionUtils.findMethod(targetClass, methodName.trim(), paramType);
            if (m != null) {
                return m;
            }
        }
        return ReflectionUtils.findMethod(targetClass, methodName.trim());
    }

    private static boolean isDirectNativeCompatibleSignature(Method method) {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 0) {
            return true;
        }
        return paramTypes.length == 1 && JobArgs.class.isAssignableFrom(paramTypes[0]);
    }

    /**
     * 多形参与强类型 DTO 方法桥接调度器
     */
    public static final class StandaloneJobDispatcher {

        public static final Method DISPATCH_METHOD =
                ReflectionUtils.findMethod(StandaloneJobDispatcher.class, "dispatch", JobArgs.class);

        private final Object targetBean;
        private final Method targetMethod;

        public StandaloneJobDispatcher(Object targetBean, Method targetMethod) {
            this.targetBean = targetBean;
            this.targetMethod = targetMethod;
        }

        public ExecuteResult dispatch(JobArgs jobArgs) {
            SnailJobContextAspect.bindDispatchedJobArgs(jobArgs);
            try {
                Object[] invocationArgs = buildPlaceholderArgs(targetMethod, jobArgs);
                ReflectionUtils.makeAccessible(targetMethod);
                Object result = ReflectionUtils.invokeMethod(targetMethod, targetBean, invocationArgs);
                if (result instanceof ExecuteResult er) {
                    return er;
                }
                return ExecuteResult.success(result);
            } finally {
                SnailJobContextAspect.clearDispatchedJobArgs();
            }
        }

        private static Object[] buildPlaceholderArgs(Method method, JobArgs jobArgs) {
            Class<?>[] paramTypes = method.getParameterTypes();
            Object[] args = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                Class<?> type = paramTypes[i];
                if (JobArgs.class.isAssignableFrom(type)) {
                    args[i] = jobArgs;
                } else if (type.isPrimitive()) {
                    args[i] = SnailJobInternalSupport.defaultPrimitiveValue(type);
                } else {
                    args[i] = null;
                }
            }
            return args;
        }
    }
}
