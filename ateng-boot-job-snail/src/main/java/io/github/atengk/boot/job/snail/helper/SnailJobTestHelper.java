package io.github.atengk.boot.job.snail.helper;

import com.aizuda.snailjob.client.core.cache.RetryerInfoCache;
import com.aizuda.snailjob.client.core.retryer.RetryerInfo;
import com.aizuda.snailjob.client.job.core.cache.JobExecutorInfoCache;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.client.job.core.dto.JobExecutorInfo;
import com.aizuda.snailjob.client.job.core.dto.MapArgs;
import com.aizuda.snailjob.client.job.core.dto.MergeReduceArgs;
import com.aizuda.snailjob.client.job.core.dto.ReduceArgs;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder.TenantSnapshot;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.job.snail.aspect.SnailJobContextAspect;
import io.github.atengk.boot.job.snail.context.SnailJobContext;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import io.github.atengk.boot.job.snail.governor.SnailJobRuntimeGovernor;
import io.github.atengk.boot.job.snail.model.ShardingContext;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import io.github.atengk.boot.job.snail.support.SnailJobInvocationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SnailJob 脱网离线单元测试与端到端调试辅助门面
 *
 * <p>与 {@link io.github.atengk.boot.job.snail.actuator.SnailJobEndpoint} 共享
 * {@link SnailJobInvocationSupport} 调度解析、多参数绑定与前置防护契约：
 * <ul>
 *     <li>统一支持原生 {@code @JobExecutor}、独立 {@code @AtengSnailJob}、{@code Map/Reduce/MergeReduce} 切片任务及 {@code @Retryable}/{@code @AtengSnailRetry}；</li>
 *     <li>支持完整多参数绑定、{@code @Valid} JSR-303 校验、{@code maxPayloadSize} 守卫与 {@link SnailJobRuntimeGovernor} 暂停态拦截，JSON 解析异常显式抛语义异常；</li>
 *     <li>全生命周期封装四维上下文与 {@link SnailJobContext}，并在终态严格还原现场。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailJobTestHelper implements ApplicationContextAware, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SnailJobTestHelper.class);

    private static final String TRACE_ID_KEY = "traceId";

    private static final Map<String, JobExecutorInfo> FALLBACK_JOB_REPOSITORY = new ConcurrentHashMap<>();
    private static final Map<String, RetryerInfo> FALLBACK_RETRY_REPOSITORY = new ConcurrentHashMap<>();
    private static final ReentrantLock REPOSITORY_LOCK = new ReentrantLock();
    private static final List<ApplicationContext> CONTEXT_STACK = new ArrayList<>();

    private static volatile ApplicationContext applicationContext;

    private ApplicationContext ownedContext;

    @Override
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        this.ownedContext = context;
        REPOSITORY_LOCK.lock();
        try {
            if (context != null) {
                CONTEXT_STACK.remove(context);
                CONTEXT_STACK.add(context);
            }
            applicationContext = context;
            clearRepositoriesLocked();
            scanAndRegisterSpringBeansLocked(context);
        } finally {
            if (REPOSITORY_LOCK.isHeldByCurrentThread()) {
                REPOSITORY_LOCK.unlock();
            }
        }
    }

    @Override
    public void destroy() {
        REPOSITORY_LOCK.lock();
        try {
            if (this.ownedContext != null) {
                CONTEXT_STACK.remove(this.ownedContext);
            }
            if (applicationContext == this.ownedContext || !isContextActive(applicationContext)) {
                ApplicationContext activeFallback = null;
                for (int i = CONTEXT_STACK.size() - 1; i >= 0; i--) {
                    ApplicationContext candidate = CONTEXT_STACK.get(i);
                    if (isContextActive(candidate)) {
                        activeFallback = candidate;
                        break;
                    }
                    CONTEXT_STACK.remove(i);
                }
                applicationContext = activeFallback;
                clearRepositoriesLocked();
                if (activeFallback != null) {
                    scanAndRegisterSpringBeansLocked(activeFallback);
                }
            }
        } finally {
            if (REPOSITORY_LOCK.isHeldByCurrentThread()) {
                REPOSITORY_LOCK.unlock();
            }
        }
    }

    public static SnailJobTestResult triggerJob(String jobName) {
        return triggerJob(jobName, null);
    }

    public static SnailJobTestResult triggerJob(String jobName, Object param) {
        return triggerJob(jobName, param, 0, 1, null, null);
    }

    public static SnailJobTestResult triggerMap(String jobName, String taskName, Object param) {
        String compositeName = jobName.contains(":") ? jobName : jobName + ":" + taskName;
        MapArgs mapArgs = new MapArgs();
        mapArgs.setTaskName(taskName);
        mapArgs.setJobParams(param);
        mapArgs.setExecutorInfo(jobName);
        return triggerJob(compositeName, mapArgs);
    }

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

    public static SnailJobTestResult triggerJob(String jobName, Object param, int shardIndex, int shardTotal) {
        return triggerJob(jobName, param, shardIndex, shardTotal, null, null);
    }

    public static SnailJobTestResult triggerJob(String jobName, Object param, int shardIndex, int shardTotal, String tenantId) {
        return triggerJob(jobName, param, shardIndex, shardTotal, tenantId, "SYSTEM_TEST");
    }

    /**
     * 完整配置触发调度任务（共享治理暂停拦截、载荷上限防护、多参数绑定与 JSR-303 参数校验）
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
        if (executorInfo == null || executorInfo.getMethod() == null) {
            throw new BusinessException(CommonResultCode.NOT_FOUND.getCode(),
                    "未找到任务名称为 [" + jobName + "] 的执行器，请确保标注了 @JobExecutor 或 @AtengSnailJob");
        }

        long startNs = System.nanoTime();
        String traceId = IdUtil.fastSimpleUUID();

        SnailJobContext previousJobContext = SnailJobContextHolder.get();
        TenantSnapshot previousTenantSnapshot = TenantContextHolder.capture();
        String previousUser = UserContextHolder.get();
        String previousTraceId = MDC.get(TRACE_ID_KEY);

        SnailJobRuntimeGovernor governor = getBeanIfPresent(SnailJobRuntimeGovernor.class);
        SnailJobProperties properties = getBeanIfPresent(SnailJobProperties.class);

        try {
            MDC.put(TRACE_ID_KEY, traceId);
            if (StringUtils.hasText(tenantId)) {
                TenantContextHolder.setTenantId(tenantId);
            }
            if (user != null) {
                UserContextHolder.set(user);
            }

            Method method = executorInfo.getMethod();
            Object target = executorInfo.getExecutor();

            SnailJobContext jobContext = new SnailJobContext();
            jobContext.setJobName(jobName);
            jobContext.setJobId(1001L);
            jobContext.setShardingIndex(shardIndex);
            jobContext.setShardingTotal(shardTotal);
            if (jobName.contains(":")) {
                jobContext.setTaskName(jobName.substring(jobName.indexOf(':') + 1));
            }

            JobArgs jobArgs = SnailJobInvocationSupport.buildJobArgs(jobName, method, param, shardIndex, shardTotal);
            jobContext.setJobArgs(jobArgs);
            if (jobArgs.getJobParams() instanceof String s) {
                jobContext.setRawJobParams(s);
            }
            SnailJobContextHolder.set(jobContext);

            log.info(">>>>>> [SnailJob脱网单测] 开始触发任务 [jobName: {}, param: {}, shard: {}/{}, tenant: {}]",
                    jobName, param, shardIndex, shardTotal, tenantId);

            // 若目标 Bean 已被 Spring AOP 代理且容器内存在调度切面，暂停拦截交由 SnailJobContextAspect 处理
            // 以忠实还原生产运行态（返回 [SKIPPED] 成功结果）；脱网裸对象测试则前置拦截抛出 BusinessException(503)
            boolean aspectHandlesGovernor = AopUtils.isAopProxy(target) && getBeanIfPresent(SnailJobContextAspect.class) != null;
            if (!aspectHandlesGovernor) {
                SnailJobInvocationSupport.enforceGovernorNotPaused(governor, jobName);
            }
            SnailJobInvocationSupport.enforceMaxPayloadSize(properties, param, jobName);

            ReflectionUtils.makeAccessible(method);
            Object[] invocationArgs = SnailJobInvocationSupport.prepareAndValidateArguments(
                    method, jobArgs, param, new ShardingContext(shardIndex, shardTotal), jobContext);
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

    public static SnailRetryTestResult triggerRetry(String scene, Object... args) {
        return executeRetryInternal(scene, null, args);
    }

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
            matched[i] = (i < args.length) ? args[i] : null;
        }
        return matched;
    }

    private static JobExecutorInfo loadJobExecutor(String jobName) {
        ApplicationContext ctx = applicationContext;
        if (isContextActive(ctx)) {
            JobExecutorInfo localMatched = SnailJobInvocationSupport.resolveJobExecutor(jobName, FALLBACK_JOB_REPOSITORY);
            if (localMatched != null && localMatched.getMethod() != null) {
                return localMatched;
            }
            scanAndRegisterSpringBeans(ctx);
        }
        return SnailJobInvocationSupport.resolveJobExecutor(jobName, FALLBACK_JOB_REPOSITORY);
    }

    private static RetryerInfo loadRetryer(String scene, String executorClassName) {
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

        if (StringUtils.hasText(executorClassName)) {
            RetryerInfo info = RetryerInfoCache.get(scene, executorClassName);
            if (info != null) {
                return info;
            }
        }
        for (RetryerInfo r : SnailJobInvocationSupport.getLoadedRetryers()) {
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

    private static <T> T getBeanIfPresent(Class<T> type) {
        ApplicationContext ctx = applicationContext;
        if (!isContextActive(ctx)) {
            return null;
        }
        try {
            return ctx.getBeanProvider(type).getIfAvailable();
        } catch (BeansException ex) {
            return null;
        }
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
        SnailJobInvocationSupport.scanSpringBeansInto(context, FALLBACK_JOB_REPOSITORY, FALLBACK_RETRY_REPOSITORY);
    }

    public Set<String> getRegisteredJobNames() {
        ApplicationContext ctx = applicationContext;
        if (isContextActive(ctx)) {
            scanAndRegisterSpringBeans(ctx);
        }
        Set<String> names = new TreeSet<>();
        names.addAll(SnailJobInvocationSupport.getLoadedJobExecutors().keySet());
        names.addAll(FALLBACK_JOB_REPOSITORY.keySet());
        return Collections.unmodifiableSet(names);
    }

    public Set<String> getRegisteredRetryScenes() {
        ApplicationContext ctx = applicationContext;
        if (isContextActive(ctx)) {
            scanAndRegisterSpringBeans(ctx);
        }
        Set<String> scenes = new TreeSet<>();
        for (RetryerInfo r : SnailJobInvocationSupport.getLoadedRetryers()) {
            scenes.add(r.getScene());
        }
        for (RetryerInfo r : FALLBACK_RETRY_REPOSITORY.values()) {
            scenes.add(r.getScene());
        }
        return Collections.unmodifiableSet(scenes);
    }

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
        clearCacheMap(SnailJobInvocationSupport.getLoadedJobExecutors(), "JobExecutorInfoCache");
        clearRetryerInfoCache();
    }

    private static void clearRetryerInfoCache() {
        Object repository = SnailJobInvocationSupport.getRetryerRepository();
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
