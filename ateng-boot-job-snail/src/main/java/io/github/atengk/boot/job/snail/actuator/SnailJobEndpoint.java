package io.github.atengk.boot.job.snail.actuator;

import com.aizuda.snailjob.client.core.retryer.RetryerInfo;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.client.job.core.dto.JobExecutorInfo;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.job.snail.context.SnailJobContext;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import io.github.atengk.boot.job.snail.drain.GracefulDrainStateMachine;
import io.github.atengk.boot.job.snail.governor.SnailJobRuntimeGovernor;
import io.github.atengk.boot.job.snail.model.ShardingContext;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import io.github.atengk.boot.job.snail.support.SnailJobInvocationSupport;
import org.slf4j.MDC;
import org.springframework.beans.BeansException;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SnailJob 任务调度与分布式重试运维管控与本地手动触发 Actuator 端点
 *
 * <p>端点路径为 {@code /actuator/snailjobs}：
 * <ul>
 *     <li>{@code GET /actuator/snailjobs}：列出当前应用装配的所有任务处理器（原生 {@code @JobExecutor}、独立 {@code @AtengSnailJob} 及切片任务）、重试场景及执行器拓扑概览；</li>
 *     <li>{@code GET /actuator/snailjobs/{name}}：查询指定任务或重试场景的详细元数据配置；</li>
 *     <li>{@code POST /actuator/snailjobs/{jobName}}：脱机手动触发或治理（pause/resume），统一校验 {@code @Valid}、{@code maxPayloadSize} 与运行时暂停态。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Endpoint(id = "snailjobs")
public class SnailJobEndpoint implements ApplicationContextAware {

    private static final String TRACE_ID_KEY = "traceId";

    private final SnailJobProperties properties;
    private final GracefulDrainStateMachine drainStateMachine;
    private final SnailJobRuntimeGovernor governor;
    private volatile ApplicationContext applicationContext;

    public SnailJobEndpoint(SnailJobProperties properties, GracefulDrainStateMachine drainStateMachine) {
        this(properties, drainStateMachine, null, null);
    }

    public SnailJobEndpoint(
            SnailJobProperties properties,
            GracefulDrainStateMachine drainStateMachine,
            SnailJobRuntimeGovernor governor) {
        this(properties, drainStateMachine, governor, null);
    }

    public SnailJobEndpoint(
            SnailJobProperties properties,
            GracefulDrainStateMachine drainStateMachine,
            SnailJobRuntimeGovernor governor,
            ApplicationContext applicationContext) {
        this.properties = properties != null ? properties : new SnailJobProperties();
        this.drainStateMachine = drainStateMachine;
        this.governor = governor;
        this.applicationContext = applicationContext;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * 查询所有已注册的调度任务与重试场景概览
     */
    @ReadOperation
    public Map<String, Object> listJobs() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("group", properties.getGroup());
        result.put("namespace", properties.getNamespace());
        result.put("server", properties.getServer());
        result.put("port", properties.getPort());
        result.put("enabled", properties.isEnabled());
        result.put("jobEnabled", properties.getJob().isEnabled());
        result.put("retryEnabled", properties.getRetry().isEnabled());

        if (drainStateMachine != null) {
            result.put("draining", drainStateMachine.isDraining());
            result.put("inFlightJobs", drainStateMachine.getInFlightJobCount());
            result.put("inFlightRetries", drainStateMachine.getInFlightRetryCount());
            result.put("totalInFlight", drainStateMachine.getTotalInFlightCount());
        }

        if (governor != null) {
            result.put("pausedJobs", new ArrayList<>(governor.getPausedHandlers()));
            result.put("pausedCount", governor.getPausedCount());
        } else {
            result.put("pausedJobs", Collections.emptyList());
            result.put("pausedCount", 0);
        }

        Map<String, JobExecutorInfo> jobExecutors = getAllJobExecutors();
        result.put("jobCount", jobExecutors.size());
        result.put("jobs", new ArrayList<>(jobExecutors.keySet()));

        Map<String, RetryerInfo> retryers = getAllRetryers();
        Set<String> scenes = new LinkedHashSet<>();
        for (RetryerInfo retryer : retryers.values()) {
            scenes.add(retryer.getScene());
        }
        result.put("retryCount", retryers.size());
        result.put("retryScenes", new ArrayList<>(scenes));

        return result;
    }

    /**
     * 查询指定调度任务或重试场景的元数据详情
     */
    @ReadOperation
    public Map<String, Object> jobDetail(@Selector String name) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("isPaused", governor != null && governor.isPaused(name));

        Map<String, JobExecutorInfo> scannedJobs = SnailJobInvocationSupport.scanSpringJobs(applicationContext);
        JobExecutorInfo jobInfo = SnailJobInvocationSupport.resolveJobExecutor(name, scannedJobs);
        if (jobInfo != null) {
            result.put("exists", true);
            result.put("type", "JOB");
            result.put("executorName", jobInfo.getExecutorName());
            if (jobInfo.getExecutor() != null) {
                result.put("targetClass", jobInfo.getExecutor().getClass().getName());
            }
            if (jobInfo.getMethod() != null) {
                Method method = jobInfo.getMethod();
                result.put("methodName", method.getName());
                result.put("returnType", method.getReturnType().getSimpleName());
                result.put("parameterCount", method.getParameterCount());
                List<String> paramTypes = new ArrayList<>();
                for (Class<?> p : method.getParameterTypes()) {
                    paramTypes.add(p.getSimpleName());
                }
                result.put("parameterTypes", paramTypes);
            }
            result.put("hasMapExecutor", jobInfo.getMapExecutorMap() != null && !jobInfo.getMapExecutorMap().isEmpty());
            result.put("hasReduceExecutor", jobInfo.getReduceExecutor() != null);
            result.put("hasMergeReduceExecutor", jobInfo.getMergeReduceExecutor() != null);
            return result;
        }

        Map<String, RetryerInfo> retryers = getAllRetryers();
        for (RetryerInfo retryer : retryers.values()) {
            if (name.equalsIgnoreCase(retryer.getScene())
                    || name.equalsIgnoreCase(retryer.getScene() + "#" + retryer.getExecutorClassName())) {
                result.put("exists", true);
                result.put("type", "RETRY");
                result.put("scene", retryer.getScene());
                result.put("executorClassName", retryer.getExecutorClassName());
                if (retryer.getMethod() != null) {
                    result.put("methodName", retryer.getMethod().getName());
                }
                result.put("localTimes", retryer.getLocalTimes());
                result.put("localInterval", retryer.getLocalInterval());
                result.put("async", retryer.isAsync());
                result.put("isThrowException", retryer.isThrowException());
                if (retryer.getRetryType() != null) {
                    result.put("retryType", retryer.getRetryType().name());
                }
                return result;
            }
        }

        result.put("exists", false);
        result.put("message", "未找到名为 [" + name + "] 的调度任务或重试场景");
        return result;
    }

    /**
     * 运维管控与脱机手动触发指定调度任务 (支持 pause / resume / trigger)
     */
    @WriteOperation
    public Map<String, Object> manageJob(
            @Selector String jobName,
            @Nullable String action,
            @Nullable String args) {
        if ("pause".equalsIgnoreCase(action) || "resume".equalsIgnoreCase(action)) {
            return executeGovernorAction(jobName, action);
        }
        return triggerJob(jobName, args);
    }

    /**
     * 全局批量动态治理操作 (如 resumeAll / clear / 批量 pause / resume)
     */
    @WriteOperation
    public Map<String, Object> manageGovernor(@Nullable String action, @Nullable String jobName) {
        if ("resumeAll".equalsIgnoreCase(action) || "clear".equalsIgnoreCase(action)) {
            int count = (governor != null) ? governor.resumeAll() : 0;
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("action", action);
            response.put("resumedCount", count);
            response.put("success", true);
            return response;
        }
        if (jobName != null && ("pause".equalsIgnoreCase(action) || "resume".equalsIgnoreCase(action))) {
            return executeGovernorAction(jobName, action);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("message", "未知治理动作或缺少必要参数: action=" + action + ", jobName=" + jobName);
        return response;
    }

    private Map<String, Object> executeGovernorAction(String jobName, String action) {
        boolean isPause = "pause".equalsIgnoreCase(action);
        boolean statusChanged = (governor != null) && (isPause ? governor.pause(jobName) : governor.resume(jobName));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobName", jobName);
        response.put("action", isPause ? "pause" : "resume");
        response.put("paused", isPause);
        response.put("statusChanged", statusChanged);
        response.put("success", true);
        return response;
    }

    /**
     * 脱机手动触发指定调度任务执行（与 SnailJobTestHelper 共享发现、参数绑定、@Valid、载荷上限与暂停拦截语义）
     */
    public Map<String, Object> triggerJob(String jobName, @Nullable String args) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobName", jobName);
        response.put("args", args);

        Map<String, JobExecutorInfo> scannedJobs = SnailJobInvocationSupport.scanSpringJobs(applicationContext);
        JobExecutorInfo jobInfo = SnailJobInvocationSupport.resolveJobExecutor(jobName, scannedJobs);
        if (jobInfo == null || jobInfo.getMethod() == null) {
            response.put("success", false);
            response.put("message", "调度任务 [" + jobName + "] 未找到或尚未注册装配");
            return response;
        }

        long startTime = System.currentTimeMillis();
        String traceId = IdUtil.fastSimpleUUID();
        String previousTraceId = MDC.get(TRACE_ID_KEY);
        SnailJobContext previousJobContext = SnailJobContextHolder.get();

        Method method = jobInfo.getMethod();
        Object target = jobInfo.getExecutor();

        JobArgs jobArgs = SnailJobInvocationSupport.buildJobArgs(jobName, method, args, 0, 1);
        jobArgs.setJobId(0L);
        jobArgs.setTaskBatchId(0L);

        SnailJobContext snailContext = new SnailJobContext();
        snailContext.setJobName(jobName);
        snailContext.setJobArgs(jobArgs);
        snailContext.setRawJobParams(args);
        if (jobName.contains(":")) {
            snailContext.setTaskName(jobName.substring(jobName.indexOf(':') + 1));
        }

        try {
            MDC.put(TRACE_ID_KEY, traceId);
            SnailJobContextHolder.set(snailContext);

            SnailJobInvocationSupport.enforceGovernorNotPaused(governor, jobName);
            SnailJobInvocationSupport.enforceMaxPayloadSize(properties, args, jobName);

            ReflectionUtils.makeAccessible(method);
            Object[] invocationArgs = SnailJobInvocationSupport.prepareAndValidateArguments(
                    method, jobArgs, args, new ShardingContext(0, 1), snailContext);
            Object executionResult = ReflectionUtils.invokeMethod(method, target, invocationArgs);

            long durationMs = System.currentTimeMillis() - startTime;
            response.put("success", true);
            response.put("durationMs", durationMs);
            response.put("traceId", traceId);
            response.put("result", executionResult != null ? executionResult.toString() : "null");
            return response;

        } catch (Throwable t) {
            Throwable root = (t instanceof InvocationTargetException ite && ite.getTargetException() != null)
                    ? ite.getTargetException()
                    : t;
            long durationMs = System.currentTimeMillis() - startTime;
            response.put("success", false);
            response.put("durationMs", durationMs);
            response.put("traceId", traceId);
            response.put("error", root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName());
            response.put("errorType", root.getClass().getSimpleName());
            return response;

        } finally {
            if (previousTraceId != null) {
                MDC.put(TRACE_ID_KEY, previousTraceId);
            } else {
                MDC.remove(TRACE_ID_KEY);
            }
            if (previousJobContext != null) {
                SnailJobContextHolder.set(previousJobContext);
            } else {
                SnailJobContextHolder.remove();
            }
        }
    }

    private Map<String, JobExecutorInfo> getAllJobExecutors() {
        Map<String, JobExecutorInfo> merged = new LinkedHashMap<>();
        merged.putAll(SnailJobInvocationSupport.getLoadedJobExecutors());
        if (applicationContext != null) {
            Map<String, JobExecutorInfo> scanned = SnailJobInvocationSupport.scanSpringJobs(applicationContext);
            for (Map.Entry<String, JobExecutorInfo> entry : scanned.entrySet()) {
                merged.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    private Map<String, RetryerInfo> getAllRetryers() {
        Map<String, RetryerInfo> result = new LinkedHashMap<>();
        for (RetryerInfo info : SnailJobInvocationSupport.getLoadedRetryers()) {
            String key = info.getScene() + "#" + info.getExecutorClassName();
            result.put(key, info);
        }
        if (applicationContext != null) {
            Map<String, RetryerInfo> scanned = SnailJobInvocationSupport.scanSpringRetryers(applicationContext);
            for (Map.Entry<String, RetryerInfo> entry : scanned.entrySet()) {
                result.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }
}
