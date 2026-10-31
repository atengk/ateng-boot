package io.github.atengk.boot.job.snail.actuator;

import com.aizuda.snailjob.client.core.cache.RetryerInfoCache;
import com.aizuda.snailjob.client.core.retryer.RetryerInfo;
import com.aizuda.snailjob.client.job.core.cache.JobExecutorInfoCache;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.client.job.core.dto.JobExecutorInfo;
import com.google.common.collect.Table;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.job.snail.context.SnailJobContext;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import io.github.atengk.boot.job.snail.drain.GracefulDrainStateMachine;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * SnailJob 任务调度与分布式重试运维管控与本地手动触发 Actuator 端点
 *
 * <p>端点路径为 {@code /actuator/snailjobs}：
 * <ul>
 *     <li>{@code GET /actuator/snailjobs}：列出当前应用装配的所有任务处理器、重试场景及执行器拓扑概览；</li>
 *     <li>{@code GET /actuator/snailjobs/{name}}：查询指定任务或重试场景的详细元数据配置；</li>
 *     <li>{@code POST /actuator/snailjobs/{jobName}}：脱机手动触发指定任务，支持传入自定义入参并返回执行明细。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Endpoint(id = "snailjobs")
public class SnailJobEndpoint {

    private final SnailJobProperties properties;
    private final GracefulDrainStateMachine drainStateMachine;

    public SnailJobEndpoint(SnailJobProperties properties, GracefulDrainStateMachine drainStateMachine) {
        this.properties = properties != null ? properties : new SnailJobProperties();
        this.drainStateMachine = drainStateMachine;
    }

    /**
     * 查询所有已注册的调度任务与重试场景概览
     *
     * @return 调度与重试资产概览信息
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
     *
     * @param name 任务名称 (executorName) 或重试场景标识 (scene)
     * @return 任务或重试场景元数据配置
     */
    @ReadOperation
    public Map<String, Object> jobDetail(@Selector String name) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);

        // 1. 优先匹配调度任务
        JobExecutorInfo jobInfo = JobExecutorInfoCache.get(name);
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

        // 2. 匹配重试场景
        Map<String, RetryerInfo> retryers = getAllRetryers();
        for (RetryerInfo retryer : retryers.values()) {
            if (name.equalsIgnoreCase(retryer.getScene()) || name.equalsIgnoreCase(retryer.getScene() + "#" + retryer.getExecutorClassName())) {
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
     * 脱机手动触发指定调度任务执行
     *
     * @param jobName 调度任务名称
     * @param args    调度入参 (可选)
     * @return 执行结果详情
     */
    @WriteOperation
    public Map<String, Object> triggerJob(@Selector String jobName, @Nullable String args) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobName", jobName);
        response.put("args", args);

        JobExecutorInfo jobInfo = JobExecutorInfoCache.get(jobName);
        if (jobInfo == null) {
            response.put("success", false);
            response.put("message", "调度任务 [" + jobName + "] 未找到或尚未注册装配");
            return response;
        }

        long startTime = System.currentTimeMillis();
        String traceId = IdUtil.fastSimpleUUID();

        // 构造本地执行上下文
        JobArgs jobArgs = new JobArgs();
        jobArgs.setExecutorInfo(jobName);
        jobArgs.setJobParams(args);
        jobArgs.setJobId(0L);
        jobArgs.setTaskBatchId(0L);

        SnailJobContext snailContext = new SnailJobContext();
        snailContext.setJobName(jobName);
        snailContext.setJobArgs(jobArgs);
        snailContext.setRawJobParams(args);
        SnailJobContextHolder.set(snailContext);

        try {
            Method method = jobInfo.getMethod();
            Object target = jobInfo.getExecutor();
            Object executionResult;

            if (method.getParameterCount() > 0) {
                executionResult = ReflectionUtils.invokeMethod(method, target, jobArgs);
            } else {
                executionResult = ReflectionUtils.invokeMethod(method, target);
            }

            long durationMs = System.currentTimeMillis() - startTime;
            response.put("success", true);
            response.put("durationMs", durationMs);
            response.put("traceId", traceId);
            response.put("result", executionResult != null ? executionResult.toString() : "null");
            return response;

        } catch (Throwable t) {
            long durationMs = System.currentTimeMillis() - startTime;
            response.put("success", false);
            response.put("durationMs", durationMs);
            response.put("traceId", traceId);
            response.put("error", t.getMessage());
            return response;

        } finally {
            SnailJobContextHolder.remove();
        }
    }

    private Map<String, JobExecutorInfo> getAllJobExecutors() {
        try {
            Field field = JobExecutorInfoCache.class.getDeclaredField("JOB_EXECUTOR_REPOSITORY");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, JobExecutorInfo> map = (Map<String, JobExecutorInfo>) field.get(null);
            if (map != null) {
                return map;
            }
        } catch (Throwable ignored) {
        }
        return Collections.emptyMap();
    }

    private Map<String, RetryerInfo> getAllRetryers() {
        try {
            Field field = RetryerInfoCache.class.getDeclaredField("RETRY_HANDLER_REPOSITORY");
            field.setAccessible(true);
            Object tableObj = field.get(null);
            if (tableObj instanceof Table<?, ?, ?> table) {
                Map<String, RetryerInfo> result = new LinkedHashMap<>();
                for (Table.Cell<?, ?, ?> cell : table.cellSet()) {
                    if (cell.getValue() instanceof RetryerInfo info) {
                        String key = info.getScene() + "#" + info.getExecutorClassName();
                        result.put(key, info);
                    }
                }
                return result;
            }
        } catch (Throwable ignored) {
        }
        return Collections.emptyMap();
    }
}
