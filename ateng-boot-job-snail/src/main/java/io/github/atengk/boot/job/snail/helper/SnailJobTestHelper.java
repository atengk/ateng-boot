package io.github.atengk.boot.job.snail.helper;

import com.aizuda.snailjob.client.core.annotation.Retryable;
import com.aizuda.snailjob.client.core.cache.RetryerInfoCache;
import com.aizuda.snailjob.client.core.retryer.RetryerInfo;
import com.aizuda.snailjob.client.job.core.MapHandler;
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
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

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
    private static final ReentrantLock REPOSITORY_LOCK = new ReentrantLock();

    private static volatile ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        REPOSITORY_LOCK.lock();
        try {
            applicationContext = context;
            clearRepositoriesLocked();
            scanAndRegisterSpringBeansLocked(context);
        } finally {
            if (REPOSITORY_LOCK.isHeldByCurrentThread()) {
                REPOSITORY_LOCK.unlock();
            }
        }
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
     * 手动触发 Map 切片任务 (支持自动组装 MapArgs 与复合命名)
     *
     * @param jobName  根任务名称或复合任务名称
     * @param taskName 切片任务名称
     * @param param    切片任务入参
     * @return 测试执行诊断报告
     */
    public static SnailJobTestResult triggerMap(String jobName, String taskName, Object param) {
        String compositeName = jobName.contains(":") ? jobName : jobName + ":" + taskName;
        MapArgs mapArgs = new MapArgs();
        mapArgs.setTaskName(taskName);
        mapArgs.setJobParams(param);
        mapArgs.setExecutorInfo(jobName);
        return triggerJob(compositeName, mapArgs);
    }

    /**
     * 手动触发 Reduce 规约任务 (支持自动组装 ReduceArgs 与复合命名)
     *
     * @param jobName 根任务名称或复合任务名称
     * @param param   规约入参 (支持 ReduceArgs 或普通入参)
     * @return 测试执行诊断报告
     */
    public static SnailJobTestResult triggerReduce(String jobName, Object param) {
        String compositeName = jobName.endsWith(":reduce") ? jobName : (jobName.contains(":") ? jobName : jobName + ":reduce");
        ReduceArgs reduceArgs;
        if (param instanceof ReduceArgs ra) {
            reduceArgs = ra;
        } else {
            reduceArgs = new ReduceArgs();
            reduceArgs.setJobParams(param);
            reduceArgs.setExecutorInfo(jobName);
        }
        return triggerJob(compositeName, reduceArgs);
    }

    /**
     * 手动触发 MergeReduce 合并规约任务 (支持自动组装 MergeReduceArgs 与复合命名)
     *
     * @param jobName 根任务名称或复合任务名称
     * @param param   合并规约入参 (支持 MergeReduceArgs 或普通入参)
     * @return 测试执行诊断报告
     */
    public static SnailJobTestResult triggerMergeReduce(String jobName, Object param) {
        String compositeName = jobName.endsWith(":mergeReduce") ? jobName : (jobName.contains(":") ? jobName : jobName + ":mergeReduce");
        MergeReduceArgs mergeArgs;
        if (param instanceof MergeReduceArgs ma) {
            mergeArgs = ma;
        } else {
            mergeArgs = new MergeReduceArgs();
            mergeArgs.setJobParams(param);
            mergeArgs.setExecutorInfo(jobName);
        }
        return triggerJob(compositeName, mergeArgs);
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
            if (param instanceof JobArgs ja) {
                jobArgs = ja;
            } else if (shardTotal > 1) {
                ShardingJobArgs shardingArgs = new ShardingJobArgs();
                shardingArgs.setJobParams(param);
                shardingArgs.setShardingIndex(shardIndex);
                shardingArgs.setShardingTotal(shardTotal);
                jobArgs = shardingArgs;
            } else if (jobName.endsWith(":reduce") || (executorInfo.getMethod() != null && AnnotatedElementUtils.findMergedAnnotation(executorInfo.getMethod(), ReduceExecutor.class) != null)) {
                ReduceArgs reduceArgs = new ReduceArgs();
                reduceArgs.setJobParams(param);
                jobArgs = reduceArgs;
            } else if (jobName.endsWith(":mergeReduce") || (executorInfo.getMethod() != null && AnnotatedElementUtils.findMergedAnnotation(executorInfo.getMethod(), MergeReduceExecutor.class) != null)) {
                MergeReduceArgs mergeArgs = new MergeReduceArgs();
                mergeArgs.setJobParams(param);
                jobArgs = mergeArgs;
            } else if (jobName.contains(":") || (executorInfo.getMethod() != null && AnnotatedElementUtils.findMergedAnnotation(executorInfo.getMethod(), MapExecutor.class) != null)) {
                MapArgs mapArgs = new MapArgs();
                mapArgs.setJobParams(param);
                if (jobName.contains(":")) {
                    mapArgs.setTaskName(jobName.substring(jobName.indexOf(":") + 1));
                }
                jobArgs = mapArgs;
            } else {
                jobArgs = new JobArgs();
                jobArgs.setJobParams(param);
            }

            if (jobName.contains(":")) {
                String taskName = jobName.substring(jobName.indexOf(":") + 1);
                jobContext.setTaskName(taskName);
            }

            log.info(">>>>>> [SnailJob脱网单测] 开始触发任务 [jobName: {}, param: {}, shard: {}/{}, tenant: {}]",
                    jobName, param, shardIndex, shardTotal, tenantId);

            Method method = executorInfo.getMethod();
            Object target = executorInfo.getExecutor();
            ReflectionUtils.makeAccessible(method);

            Object[] invocationArgs = prepareJobArguments(method, jobArgs, param);
            Object executionResult = ReflectionUtils.invokeMethod(method, target, invocationArgs);

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

    @SuppressWarnings("unchecked")
    private static Object[] prepareJobArguments(Method method, JobArgs jobArgs, Object param) {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 0) {
            return new Object[0];
        }
        Object explicitParam = (param instanceof JobArgs ja) ? ja.getJobParams() : param;
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> pt = paramTypes[i];
            if (JobArgs.class.isAssignableFrom(pt)) {
                args[i] = jobArgs;
            } else if (MapHandler.class.isAssignableFrom(pt)) {
                args[i] = (MapHandler<Object>) (list, tName) -> ExecuteResult.success();
            } else if (explicitParam != null && pt.isInstance(explicitParam)) {
                args[i] = explicitParam;
            } else if (explicitParam instanceof String jsonStr && pt != String.class && jsonStr.trim().startsWith("{")) {
                try {
                    args[i] = JsonUtil.parseObject(jsonStr, pt);
                } catch (Exception e) {
                    args[i] = jsonStr;
                }
            } else if (i == 0 && explicitParam != null) {
                args[i] = explicitParam;
            } else if (i == 1 && explicitParam != null) {
                args[i] = explicitParam;
            } else {
                args[i] = null;
            }
        }
        return args;
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
        // 1. 若当前持有活跃的 Spring 容器，优先内省获取当前容器的活跃 Bean
        ApplicationContext ctx = applicationContext;
        if (isContextActive(ctx)) {
            JobExecutorInfo localInfo = FALLBACK_JOB_REPOSITORY.get(jobName);
            if (localInfo != null) {
                return localInfo;
            }
            scanAndRegisterSpringBeans(ctx);
            localInfo = FALLBACK_JOB_REPOSITORY.get(jobName);
            if (localInfo != null) {
                return localInfo;
            }
        }

        // 2. 从官方运行期缓存获取
        JobExecutorInfo info = JobExecutorInfoCache.get(jobName);
        if (info != null) {
            return info;
        }

        // 3. 从本地扫描缓存获取
        JobExecutorInfo fallback = FALLBACK_JOB_REPOSITORY.get(jobName);
        if (fallback != null) {
            return fallback;
        }

        // 4. 若为复合命名 <rootJobName>:<taskName>，尝试通过 taskName 匹配
        if (jobName.contains(":")) {
            String taskName = jobName.substring(jobName.indexOf(":") + 1);
            JobExecutorInfo byTask = FALLBACK_JOB_REPOSITORY.get(taskName);
            if (byTask != null) {
                return byTask;
            }
        }
        return null;
    }

    private static RetryerInfo loadRetryer(String scene, String executorClassName) {
        // 1. 若当前持有活跃的 Spring 容器，优先检查并内省当前容器的重试 Bean
        ApplicationContext ctx = applicationContext;
        if (isContextActive(ctx)) {
            RetryerInfo fallbackMatched = findRetryerInFallback(scene, executorClassName);
            if (fallbackMatched != null) {
                return fallbackMatched;
            }
            scanAndRegisterSpringBeans(ctx);
            fallbackMatched = findRetryerInFallback(scene, executorClassName);
            if (fallbackMatched != null) {
                return fallbackMatched;
            }
        }

        // 2. 从官方运行期缓存获取
        if (StringUtils.hasText(executorClassName)) {
            RetryerInfo info = RetryerInfoCache.get(scene, executorClassName);
            if (info != null) {
                return info;
            }
        }
        Collection<RetryerInfo> loaded = getLoadedRetryers();
        for (RetryerInfo r : loaded) {
            if (scene.equalsIgnoreCase(r.getScene())) {
                if (!StringUtils.hasText(executorClassName) || executorClassName.equalsIgnoreCase(r.getExecutorClassName())) {
                    return r;
                }
            }
        }
        return null;
    }

    private static RetryerInfo findRetryerInFallback(String scene, String executorClassName) {
        for (RetryerInfo r : FALLBACK_RETRY_REPOSITORY.values()) {
            if (scene.equalsIgnoreCase(r.getScene())) {
                if (!StringUtils.hasText(executorClassName) || executorClassName.equalsIgnoreCase(r.getExecutorClassName())) {
                    return r;
                }
            }
        }
        return null;
    }

    private static boolean isContextActive(ApplicationContext context) {
        if (context == null) {
            return false;
        }
        if (context instanceof ConfigurableApplicationContext configurableContext) {
            return configurableContext.isActive();
        }
        return true;
    }

    private static void scanAndRegisterSpringBeans(ApplicationContext context) {
        if (!isContextActive(context)) {
            return;
        }
        REPOSITORY_LOCK.lock();
        try {
            if (applicationContext != null && applicationContext != context) {
                return;
            }
            scanAndRegisterSpringBeansLocked(context);
        } finally {
            if (REPOSITORY_LOCK.isHeldByCurrentThread()) {
                REPOSITORY_LOCK.unlock();
            }
        }
    }

    private static void scanAndRegisterSpringBeansLocked(ApplicationContext context) {
        if (!isContextActive(context)) {
            return;
        }
        String[] beanNames = context.getBeanNamesForType(Object.class);
        for (String beanName : beanNames) {
            Object bean;
            try {
                bean = context.getBean(beanName);
            } catch (BeansException ex) {
                log.trace("跳过无法实例化的 Spring Bean [{}]: {}", beanName, ex.getMessage());
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
                    FALLBACK_JOB_REPOSITORY.put(name, info);
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
                    FALLBACK_JOB_REPOSITORY.put(name, info);
                }
            }

            // 2. 扫描 @MapExecutor、@ReduceExecutor、@MergeReduceExecutor
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            String rootJobName = resolveRootJobName(targetClass);

            Map<Method, MapExecutor> mapMethods = MethodIntrospector.selectMethods(
                    bean.getClass(),
                    (MethodIntrospector.MetadataLookup<MapExecutor>) method -> AnnotatedElementUtils.findMergedAnnotation(method, MapExecutor.class)
            );
            for (Map.Entry<Method, MapExecutor> entry : mapMethods.entrySet()) {
                Method m = entry.getKey();
                String taskName = StringUtils.hasText(entry.getValue().taskName()) ? entry.getValue().taskName().trim() : m.getName();
                JobExecutorInfo info = new JobExecutorInfo(taskName, m, Collections.emptyMap(), null, null, bean);
                FALLBACK_JOB_REPOSITORY.put(rootJobName + ":" + taskName, info);
                FALLBACK_JOB_REPOSITORY.put(taskName, info);
            }

            Map<Method, ReduceExecutor> reduceMethods = MethodIntrospector.selectMethods(
                    bean.getClass(),
                    (MethodIntrospector.MetadataLookup<ReduceExecutor>) method -> AnnotatedElementUtils.findMergedAnnotation(method, ReduceExecutor.class)
            );
            for (Map.Entry<Method, ReduceExecutor> entry : reduceMethods.entrySet()) {
                Method m = entry.getKey();
                JobExecutorInfo info = new JobExecutorInfo("reduce", m, Collections.emptyMap(), null, null, bean);
                FALLBACK_JOB_REPOSITORY.put(rootJobName + ":reduce", info);
                FALLBACK_JOB_REPOSITORY.put("reduce", info);
                FALLBACK_JOB_REPOSITORY.put(m.getName(), info);
            }

            Map<Method, MergeReduceExecutor> mergeReduceMethods = MethodIntrospector.selectMethods(
                    bean.getClass(),
                    (MethodIntrospector.MetadataLookup<MergeReduceExecutor>) method -> AnnotatedElementUtils.findMergedAnnotation(method, MergeReduceExecutor.class)
            );
            for (Map.Entry<Method, MergeReduceExecutor> entry : mergeReduceMethods.entrySet()) {
                Method m = entry.getKey();
                JobExecutorInfo info = new JobExecutorInfo("mergeReduce", m, Collections.emptyMap(), null, null, bean);
                FALLBACK_JOB_REPOSITORY.put(rootJobName + ":mergeReduce", info);
                FALLBACK_JOB_REPOSITORY.put("mergeReduce", info);
                FALLBACK_JOB_REPOSITORY.put(m.getName(), info);
            }

            // 3. 扫描 @Retryable 与 @AtengSnailRetry
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
                FALLBACK_RETRY_REPOSITORY.put(key, retryerInfo);
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
                FALLBACK_RETRY_REPOSITORY.put(key, retryerInfo);
            }
        }
    }

    private static String resolveRootJobName(Class<?> targetClass) {
        JobExecutor jobExecutor = AnnotatedElementUtils.findMergedAnnotation(targetClass, JobExecutor.class);
        if (jobExecutor != null && StringUtils.hasText(jobExecutor.name())) {
            return jobExecutor.name().trim();
        }
        AtengSnailJob atengJob = AnnotatedElementUtils.findMergedAnnotation(targetClass, AtengSnailJob.class);
        if (atengJob != null && StringUtils.hasText(atengJob.value())) {
            return atengJob.value().trim();
        }
        for (Method m : targetClass.getDeclaredMethods()) {
            JobExecutor mExec = AnnotatedElementUtils.findMergedAnnotation(m, JobExecutor.class);
            if (mExec != null && StringUtils.hasText(mExec.name())) {
                return mExec.name().trim();
            }
            AtengSnailJob mAteng = AnnotatedElementUtils.findMergedAnnotation(m, AtengSnailJob.class);
            if (mAteng != null && StringUtils.hasText(mAteng.value())) {
                return mAteng.value().trim();
            }
        }
        return targetClass.getSimpleName();
    }

    private static Object getRetryerRepository() {
        try {
            Field field = RetryerInfoCache.class.getDeclaredField("RETRY_HANDLER_REPOSITORY");
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException | SecurityException | LinkageError ex) {
            log.debug("读取 RetryerInfoCache.RETRY_HANDLER_REPOSITORY 失败: {}", ex.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<RetryerInfo> getLoadedRetryers() {
        Object repository = getRetryerRepository();
        if (repository == null) {
            return Collections.emptyList();
        }
        try {
            if (repository instanceof Map<?, ?> map) {
                return (Collection<RetryerInfo>) map.values();
            }
            Class<?> tableInterface = Class.forName("com.google.common.collect.Table");
            if (tableInterface.isInstance(repository)) {
                return (Collection<RetryerInfo>) tableInterface.getMethod("values").invoke(repository);
            }
        } catch (ReflectiveOperationException | SecurityException | LinkageError ex) {
            log.debug("读取 RetryerInfoCache 重试器列表失败，退化为空集合: {}", ex.getMessage());
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, JobExecutorInfo> getLoadedJobExecutors() {
        try {
            Field field = JobExecutorInfoCache.class.getDeclaredField("JOB_EXECUTOR_REPOSITORY");
            field.setAccessible(true);
            return (Map<String, JobExecutorInfo>) field.get(null);
        } catch (ReflectiveOperationException | SecurityException | LinkageError ex) {
            log.debug("读取 JobExecutorInfoCache.JOB_EXECUTOR_REPOSITORY 失败，退化为空映射: {}", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 获取所有已发现注册的任务名称清单
     *
     * @return 任务名称只读集合
     */
    public Set<String> getRegisteredJobNames() {
        ApplicationContext ctx = applicationContext;
        if (isContextActive(ctx)) {
            scanAndRegisterSpringBeans(ctx);
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
        ApplicationContext ctx = applicationContext;
        if (isContextActive(ctx)) {
            scanAndRegisterSpringBeans(ctx);
        }
        java.util.TreeSet<String> scenes = new java.util.TreeSet<>();
        for (RetryerInfo r : getLoadedRetryers()) {
            scenes.add(r.getScene());
        }
        for (RetryerInfo r : FALLBACK_RETRY_REPOSITORY.values()) {
            scenes.add(r.getScene());
        }
        return Collections.unmodifiableSet(scenes);
    }

    /**
     * 清理脱网沙箱本地回退注册表与 SnailJob 静态缓存
     */
    public static void clear() {
        REPOSITORY_LOCK.lock();
        try {
            clearRepositoriesLocked();
        } finally {
            if (REPOSITORY_LOCK.isHeldByCurrentThread()) {
                REPOSITORY_LOCK.unlock();
            }
        }
    }

    private static void clearRepositoriesLocked() {
        FALLBACK_JOB_REPOSITORY.clear();
        FALLBACK_RETRY_REPOSITORY.clear();
        clearCacheMap(getLoadedJobExecutors(), "JobExecutorInfoCache");
        clearRetryerInfoCache();
    }

    private static void clearRetryerInfoCache() {
        Object repository = getRetryerRepository();
        if (repository == null) {
            return;
        }
        try {
            if (repository instanceof Map<?, ?> map) {
                map.clear();
                return;
            }
            Class<?> tableInterface = Class.forName("com.google.common.collect.Table");
            if (tableInterface.isInstance(repository)) {
                tableInterface.getMethod("clear").invoke(repository);
            }
        } catch (ReflectiveOperationException | SecurityException | LinkageError | UnsupportedOperationException ex) {
            log.debug("清理 RetryerInfoCache 静态缓存失败，已忽略: {}", ex.getMessage());
        }
    }

    private static void clearCacheMap(Map<?, ?> cache, String cacheName) {
        if (cache == null || cache.isEmpty()) {
            return;
        }
        try {
            cache.clear();
        } catch (UnsupportedOperationException ex) {
            log.debug("静态缓存 [{}] 不支持清理操作，已忽略: {}", cacheName, ex.getMessage());
        }
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
