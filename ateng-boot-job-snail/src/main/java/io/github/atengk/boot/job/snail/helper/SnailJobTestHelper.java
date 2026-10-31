package io.github.atengk.boot.job.snail.helper;

import com.aizuda.snailjob.client.core.annotation.Retryable;
import com.aizuda.snailjob.client.core.cache.RetryerInfoCache;
import com.aizuda.snailjob.client.core.retryer.RetryerInfo;
import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.cache.JobExecutorInfoCache;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.client.job.core.dto.JobExecutorInfo;
import com.aizuda.snailjob.client.job.core.dto.ShardingJobArgs;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder.TenantSnapshot;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import io.github.atengk.boot.job.snail.annotation.AtengSnailRetry;
import io.github.atengk.boot.job.snail.context.SnailJobContext;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SnailJob 脱网离线单元测试与端到端调试辅助门面
 *
 * <p>为应用层单测提供 100% 离线脱网运行能力：
 * <ul>
 *     <li>基于内存反射驱动，支持以单测方式直接触发 {@link JobExecutor}、{@link AtengSnailJob}、{@link Retryable} 与 {@link AtengSnailRetry}；</li>
 *     <li>自动根据入参构建模拟 {@link JobArgs}、{@link ShardingJobArgs} 或强类型 DTO；</li>
 *     <li>全生命周期封装四维上下文现场（MDC traceId、多租户、操作人与 SnailJobContext），并在用例终态时确定性清理恢复现场；</li>
 *     <li>彻底杜绝外部服务端网络依赖，保障构建与 CI 流程 100% 极速稳定全绿。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailJobTestHelper implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(SnailJobTestHelper.class);

    private static final String TRACE_ID_KEY = "traceId";

    private static final Map<String, JobExecutorInfo> FALLBACK_JOB_REPOSITORY = new ConcurrentHashMap<>();
    private static final Map<String, RetryerInfo> FALLBACK_RETRY_REPOSITORY = new ConcurrentHashMap<>();

    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        applicationContext = context;
        scanAndRegisterSpringBeans(context);
    }

    /**
     * 手动触发无参调度任务
     *
     * @param jobName 任务名称
     * @return 测试执行诊断报告
     */
    public static SnailJobTestResult triggerJob(String jobName) {
        return triggerJob(jobName, null);
    }

    /**
     * 手动触发调度任务 (便捷重载)
     *
     * @param jobName 任务名称
     * @param param   任务入参 (支持 String、强类型 DTO 或 null)
     * @return 测试执行诊断报告
     */
    public static SnailJobTestResult triggerJob(String jobName, Object param) {
        return triggerJob(jobName, param, 0, 1, null, null);
    }

    /**
     * 手动触发带分片上下文的调度任务
     *
     * @param jobName    任务名称
     * @param param      任务入参
     * @param shardIndex 当前分片索引 (从 0 开始)
     * @param shardTotal 分片总数
     * @return 测试执行诊断报告
     */
    public static SnailJobTestResult triggerJob(String jobName, Object param, int shardIndex, int shardTotal) {
        return triggerJob(jobName, param, shardIndex, shardTotal, null, null);
    }

    /**
     * 手动触发带多租户上下文的调度任务
     *
     * @param jobName    任务名称
     * @param param      任务入参
     * @param shardIndex 当前分片索引
     * @param shardTotal 分片总数
     * @param tenantId   模拟当前租户 ID
     * @return 测试执行诊断报告
     */
    public static SnailJobTestResult triggerJob(String jobName, Object param, int shardIndex, int shardTotal, String tenantId) {
        return triggerJob(jobName, param, shardIndex, shardTotal, tenantId, "SYSTEM_TEST");
    }

    /**
     * 完整配置触发调度任务
     *
     * @param jobName    任务名称
     * @param param      任务入参
     * @param shardIndex 分片索引
     * @param shardTotal 分片总数
     * @param tenantId   租户标识
     * @param user       操作人上下文 (用户 ID)
     * @return 测试执行诊断报告
     */
    public static SnailJobTestResult triggerJob(
            String jobName,
            Object param,
            int shardIndex,
            int shardTotal,
            String tenantId,
            String user) {

        Assert.hasText(jobName, "jobName 任务名称不能为空");
        JobExecutorInfo executorInfo = loadJobExecutor(jobName);
        if (executorInfo == null) {
            throw new BusinessException(CommonResultCode.NOT_FOUND.getCode(),
                    "未找到任务名称为 [" + jobName + "] 的执行器，请确保标注了 @JobExecutor 或 @AtengSnailJob");
        }

        long startNs = System.nanoTime();
        String traceId = IdUtil.fastSimpleUUID();

        // 暂存原上下文
        SnailJobContext previousJobContext = SnailJobContextHolder.get();
        TenantSnapshot previousTenantSnapshot = TenantContextHolder.capture();
        String previousUser = UserContextHolder.get();
        String previousTraceId = MDC.get(TRACE_ID_KEY);

        try {
            // 组装新上下文
            MDC.put(TRACE_ID_KEY, traceId);
            if (StringUtils.hasText(tenantId)) {
                TenantContextHolder.setTenantId(tenantId);
            }
            if (user != null) {
                UserContextHolder.set(user);
            }

            SnailJobContext jobContext = new SnailJobContext();
            jobContext.setJobName(jobName);
            jobContext.setJobId(1001L);
            jobContext.setShardingIndex(shardIndex);
            jobContext.setShardingTotal(shardTotal);
            SnailJobContextHolder.set(jobContext);

            // 构造模拟 JobArgs
            JobArgs jobArgs;
            if (shardTotal > 1) {
                ShardingJobArgs shardingArgs = new ShardingJobArgs();
                shardingArgs.setJobParams(param);
                shardingArgs.setShardingIndex(shardIndex);
                shardingArgs.setShardingTotal(shardTotal);
                jobArgs = shardingArgs;
            } else {
                jobArgs = new JobArgs();
                jobArgs.setJobParams(param);
            }

            log.info(">>>>>> [SnailJob脱网单测] 开始触发任务 [jobName: {}, param: {}, shard: {}/{}, tenant: {}]",
                    jobName, param, shardIndex, shardTotal, tenantId);

            Method method = executorInfo.getMethod();
            Object target = executorInfo.getExecutor();
            ReflectionUtils.makeAccessible(method);

            Object executionResult;

            if (method.getParameterCount() > 0) {
                Class<?> firstType = method.getParameterTypes()[0];
                if (JobArgs.class.isAssignableFrom(firstType)) {
                    executionResult = ReflectionUtils.invokeMethod(method, target, jobArgs);
                } else if (param != null && firstType.isAssignableFrom(param.getClass())) {
                    executionResult = ReflectionUtils.invokeMethod(method, target, param);
                } else if (param instanceof String jsonStr && firstType != String.class && jsonStr.trim().startsWith("{")) {
                    Object parsedDto = JsonUtil.parseObject(jsonStr, firstType);
                    executionResult = ReflectionUtils.invokeMethod(method, target, parsedDto);
                } else {
                    executionResult = ReflectionUtils.invokeMethod(method, target, param);
                }
            } else {
                executionResult = ReflectionUtils.invokeMethod(method, target);
            }

            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            log.info("<<<<<< [SnailJob脱网单测] 任务 [{}] 执行成功 (耗时: {}ms, traceId: {})", jobName, durationMs, traceId);
            return new SnailJobTestResult(true, executionResult, traceId, durationMs, null);

        } catch (Throwable t) {
            Throwable root = (t instanceof InvocationTargetException ite && ite.getTargetException() != null)
                    ? ite.getTargetException()
                    : t;
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            log.error("<<<<<< [SnailJob脱网单测] 任务 [{}] 执行抛出异常 (耗时: {}ms): {}", jobName, durationMs, root.getMessage());
            return new SnailJobTestResult(false, null, traceId, durationMs, root);

        } finally {
            if (previousJobContext != null) {
                SnailJobContextHolder.set(previousJobContext);
            } else {
                SnailJobContextHolder.remove();
            }
            TenantContextHolder.restore(previousTenantSnapshot);
            if (previousUser != null) {
                UserContextHolder.set(previousUser);
            } else {
                UserContextHolder.clear();
            }
            if (previousTraceId != null) {
                MDC.put(TRACE_ID_KEY, previousTraceId);
            } else {
                MDC.remove(TRACE_ID_KEY);
            }
        }
    }

    /**
     * 手动触发重试场景 (便捷重载)
     *
     * @param scene 重试场景名称
     * @param args  方法实参
     * @return 测试执行诊断报告
     */
    public static SnailRetryTestResult triggerRetry(String scene, Object... args) {
        return executeRetryInternal(scene, null, args);
    }

    /**
     * 手动触发指定执行器类名的重试场景
     *
     * @param scene             重试场景名称
     * @param executorClassName 指定目标执行器类全限定名
     * @param args              方法实参
     * @return 测试执行诊断报告
     */
    public static SnailRetryTestResult triggerRetryWithExecutor(String scene, String executorClassName, Object... args) {
        return executeRetryInternal(scene, executorClassName, args);
    }

    private static SnailRetryTestResult executeRetryInternal(String scene, String executorClassName, Object[] args) {
        Assert.hasText(scene, "scene 重试场景不能为空");
        RetryerInfo retryerInfo = loadRetryer(scene, executorClassName);
        if (retryerInfo == null) {
            throw new BusinessException(CommonResultCode.NOT_FOUND.getCode(),
                    "未找到场景为 [" + scene + "] 的重试器，请确保标注了 @Retryable 或 @AtengSnailRetry");
        }

        long startNs = System.nanoTime();
        String traceId = IdUtil.fastSimpleUUID();

        TenantSnapshot previousTenantSnapshot = TenantContextHolder.capture();
        String previousUser = UserContextHolder.get();
        String previousTraceId = MDC.get(TRACE_ID_KEY);

        try {
            MDC.put(TRACE_ID_KEY, traceId);

            Method method = retryerInfo.getMethod();
            Object target = retryerInfo.getExecutor();
            ReflectionUtils.makeAccessible(method);

            log.info(">>>>>> [SnailJob脱网重试] 开始触发重试方法 [scene: {}, executor: {}]", scene, retryerInfo.getExecutorClassName());

            Object[] invocationArgs = matchArguments(method, args);
            Object result = ReflectionUtils.invokeMethod(method, target, invocationArgs);

            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            log.info("<<<<<< [SnailJob脱网重试] 重试场景 [{}] 执行成功 (耗时: {}ms)", scene, durationMs);
            return new SnailRetryTestResult(true, result, traceId, durationMs, null);

        } catch (Throwable t) {
            Throwable root = (t instanceof InvocationTargetException ite && ite.getTargetException() != null)
                    ? ite.getTargetException()
                    : t;
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            log.warn("<<<<<< [SnailJob脱网重试] 重试场景 [{}] 执行捕获异常 (耗时: {}ms): {}", scene, durationMs, root.getMessage());
            return new SnailRetryTestResult(false, null, traceId, durationMs, root);

        } finally {
            TenantContextHolder.restore(previousTenantSnapshot);
            if (previousUser != null) {
                UserContextHolder.set(previousUser);
            } else {
                UserContextHolder.clear();
            }
            if (previousTraceId != null) {
                MDC.put(TRACE_ID_KEY, previousTraceId);
            } else {
                MDC.remove(TRACE_ID_KEY);
            }
        }
    }

    private static Object[] matchArguments(Method method, Object[] args) {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 0) {
            return new Object[0];
        }
        if (args == null || args.length == 0) {
            return new Object[paramTypes.length];
        }
        if (args.length == paramTypes.length) {
            return args;
        }
        Object[] matched = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            if (i < args.length) {
                matched[i] = args[i];
            } else {
                matched[i] = null;
            }
        }
        return matched;
    }

    private static JobExecutorInfo loadJobExecutor(String jobName) {
        // 1. 优先从运行期缓存获取
        JobExecutorInfo info = JobExecutorInfoCache.get(jobName);
        if (info != null) {
            return info;
        }

        // 2. 从本地扫描缓存获取
        JobExecutorInfo fallback = FALLBACK_JOB_REPOSITORY.get(jobName);
        if (fallback != null) {
            return fallback;
        }

        // 3. 从 Spring 容器动态内省扫描
        if (applicationContext != null) {
            scanAndRegisterSpringBeans(applicationContext);
            return FALLBACK_JOB_REPOSITORY.get(jobName);
        }
        return null;
    }

    private static RetryerInfo loadRetryer(String scene, String executorClassName) {
        // 1. 优先从运行期缓存获取
        if (StringUtils.hasText(executorClassName)) {
            RetryerInfo info = RetryerInfoCache.get(scene, executorClassName);
            if (info != null) {
                return info;
            }
        }
        Map<String, RetryerInfo> loaded = getLoadedRetryers();
        for (RetryerInfo r : loaded.values()) {
            if (scene.equalsIgnoreCase(r.getScene())) {
                if (!StringUtils.hasText(executorClassName) || executorClassName.equalsIgnoreCase(r.getExecutorClassName())) {
                    return r;
                }
            }
        }

        // 2. 从 Spring 容器动态内省扫描
        if (applicationContext != null) {
            scanAndRegisterSpringBeans(applicationContext);
            for (RetryerInfo r : FALLBACK_RETRY_REPOSITORY.values()) {
                if (scene.equalsIgnoreCase(r.getScene())) {
                    return r;
                }
            }
        }
        return null;
    }

    private static synchronized void scanAndRegisterSpringBeans(ApplicationContext context) {
        if (context == null) {
            return;
        }
        String[] beanNames = context.getBeanNamesForType(Object.class);
        for (String beanName : beanNames) {
            Object bean;
            try {
                bean = context.getBean(beanName);
            } catch (Exception ignored) {
                continue;
            }

            // 1. 扫描 @JobExecutor 与 @AtengSnailJob
            Map<Method, JobExecutor> jobMethods = MethodIntrospector.selectMethods(
                    bean.getClass(),
                    (MethodIntrospector.MetadataLookup<JobExecutor>) method -> AnnotatedElementUtils.findMergedAnnotation(method, JobExecutor.class)
            );
            for (Map.Entry<Method, JobExecutor> entry : jobMethods.entrySet()) {
                Method m = entry.getKey();
                String name = entry.getValue().name();
                if (StringUtils.hasText(name)) {
                    JobExecutorInfo info = new JobExecutorInfo(name, m, Collections.emptyMap(), null, null, bean);
                    FALLBACK_JOB_REPOSITORY.putIfAbsent(name, info);
                }
            }

            Map<Method, AtengSnailJob> atengJobMethods = MethodIntrospector.selectMethods(
                    bean.getClass(),
                    (MethodIntrospector.MetadataLookup<AtengSnailJob>) method -> AnnotatedElementUtils.findMergedAnnotation(method, AtengSnailJob.class)
            );
            for (Map.Entry<Method, AtengSnailJob> entry : atengJobMethods.entrySet()) {
                Method m = entry.getKey();
                String name = StringUtils.hasText(entry.getValue().value()) ? entry.getValue().value() : m.getName();
                if (StringUtils.hasText(name)) {
                    JobExecutorInfo info = new JobExecutorInfo(name, m, Collections.emptyMap(), null, null, bean);
                    FALLBACK_JOB_REPOSITORY.putIfAbsent(name, info);
                }
            }

            // 2. 扫描 @Retryable 与 @AtengSnailRetry
            Map<Method, Retryable> retryMethods = MethodIntrospector.selectMethods(
                    bean.getClass(),
                    (MethodIntrospector.MetadataLookup<Retryable>) method -> AnnotatedElementUtils.findMergedAnnotation(method, Retryable.class)
            );
            for (Map.Entry<Method, Retryable> entry : retryMethods.entrySet()) {
                Method m = entry.getKey();
                Retryable r = entry.getValue();
                String scene = StringUtils.hasText(r.scene()) ? r.scene() : m.getName();
                String key = scene + "#" + bean.getClass().getName();
                RetryerInfo retryerInfo = new RetryerInfo(
                        scene,
                        bean.getClass().getName(),
                        Collections.emptySet(),
                        Collections.emptySet(),
                        bean,
                        m,
                        r.retryStrategy(),
                        r.localTimes(),
                        r.localInterval(),
                        r.idempotentId(),
                        r.bizNo(),
                        r.retryMethod(),
                        r.isThrowException(),
                        r.retryCompleteCallback(),
                        r.async(),
                        false,
                        r.timeout(),
                        r.unit(),
                        r.retryIfResult()
                );
                FALLBACK_RETRY_REPOSITORY.putIfAbsent(key, retryerInfo);
            }

            Map<Method, AtengSnailRetry> atengRetryMethods = MethodIntrospector.selectMethods(
                    bean.getClass(),
                    (MethodIntrospector.MetadataLookup<AtengSnailRetry>) method -> AnnotatedElementUtils.findMergedAnnotation(method, AtengSnailRetry.class)
            );
            for (Map.Entry<Method, AtengSnailRetry> entry : atengRetryMethods.entrySet()) {
                Method m = entry.getKey();
                AtengSnailRetry ar = entry.getValue();
                String scene = StringUtils.hasText(ar.scene()) ? ar.scene() : m.getName();
                String key = scene + "#" + bean.getClass().getName();
                RetryerInfo retryerInfo = new RetryerInfo(
                        scene,
                        bean.getClass().getName(),
                        Collections.emptySet(),
                        Collections.emptySet(),
                        bean,
                        m,
                        null,
                        3,
                        1,
                        null,
                        "",
                        null,
                        true,
                        null,
                        false,
                        false,
                        0,
                        null,
                        null
                );
                FALLBACK_RETRY_REPOSITORY.putIfAbsent(key, retryerInfo);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, RetryerInfo> getLoadedRetryers() {
        try {
            Field field = RetryerInfoCache.class.getDeclaredField("CACHE");
            field.setAccessible(true);
            return (Map<String, RetryerInfo>) field.get(null);
        } catch (Throwable ignored) {
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, JobExecutorInfo> getLoadedJobExecutors() {
        try {
            Field field = JobExecutorInfoCache.class.getDeclaredField("JOB_EXECUTOR_REPOSITORY");
            field.setAccessible(true);
            return (Map<String, JobExecutorInfo>) field.get(null);
        } catch (Throwable ignored) {
            return Collections.emptyMap();
        }
    }

    /**
     * 获取所有已发现注册的任务名称清单
     *
     * @return 任务名称只读集合
     */
    public Set<String> getRegisteredJobNames() {
        if (applicationContext != null) {
            scanAndRegisterSpringBeans(applicationContext);
        }
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        names.addAll(getLoadedJobExecutors().keySet());
        names.addAll(FALLBACK_JOB_REPOSITORY.keySet());
        return Collections.unmodifiableSet(names);
    }

    /**
     * 获取所有已发现注册的重试场景标识清单
     *
     * @return 重试场景标识只读集合
     */
    public Set<String> getRegisteredRetryScenes() {
        if (applicationContext != null) {
            scanAndRegisterSpringBeans(applicationContext);
        }
        java.util.TreeSet<String> scenes = new java.util.TreeSet<>();
        for (RetryerInfo r : getLoadedRetryers().values()) {
            scenes.add(r.getScene());
        }
        for (RetryerInfo r : FALLBACK_RETRY_REPOSITORY.values()) {
            scenes.add(r.getScene());
        }
        return Collections.unmodifiableSet(scenes);
    }

    /**
     * 清理脱网沙箱本地回退注册表
     */
    public static void clear() {
        FALLBACK_JOB_REPOSITORY.clear();
        FALLBACK_RETRY_REPOSITORY.clear();
    }

    /**
     * 调度任务测试执行诊断报告
     *
     * @param success    是否成功执行
     * @param result     方法返回值
     * @param traceId    全链路追踪 traceId
     * @param durationMs 执行耗时 (毫秒)
     * @param throwable  抛出的异常 (若有)
     */
    public record SnailJobTestResult(
            boolean success,
            Object result,
            String traceId,
            long durationMs,
            Throwable throwable
    ) {
        public boolean isSuccess() {
            return success;
        }
    }

    /**
     * 重试场景测试执行诊断报告
     *
     * @param success    是否成功执行
     * @param result     方法返回值
     * @param traceId    全链路追踪 traceId
     * @param durationMs 执行耗时 (毫秒)
     * @param throwable  抛出的异常 (若有)
     */
    public record SnailRetryTestResult(
            boolean success,
            Object result,
            String traceId,
            long durationMs,
            Throwable throwable
    ) {
        public boolean isSuccess() {
            return success;
        }
    }
}
