package io.github.atengk.boot.job.xxl.actuator;

import com.xxl.job.core.handler.IJobHandler;
import com.xxl.job.core.handler.impl.MethodJobHandler;
import io.github.atengk.boot.job.xxl.governor.JobRuntimeGovernor;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper.XxlJobTestResult;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * XXL-Job 运维管控与本地手动触发 Actuator 端点
 *
 * <p>端点路径为 {@code /actuator/xxljobs}：
 * <ul>
 *     <li>{@code GET /actuator/xxljobs}：列出当前应用装配的所有 XXL-Job 任务处理器、元数据及静默状态；</li>
 *     <li>{@code GET /actuator/xxljobs/{handler}}：查询指定任务处理器的详细元数据与当前静默状态；</li>
 *     <li>{@code POST /actuator/xxljobs/{handler}}：手动模拟调度中心触发指定任务，支持传入自定义入参与分片；</li>
 *     <li>{@code POST /actuator/xxljobs/{handler}/pause}：在当前节点暂停（静默）指定任务调度；</li>
 *     <li>{@code POST /actuator/xxljobs/{handler}/resume}：在当前节点恢复指定任务的正常调度。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Endpoint(id = "xxljobs")
public class XxlJobEndpoint {

    private final XxlJobProperties properties;
    private final JobRuntimeGovernor governor;

    public XxlJobEndpoint(XxlJobProperties properties) {
        this(properties, null);
    }

    public XxlJobEndpoint(XxlJobProperties properties, JobRuntimeGovernor governor) {
        this.properties = properties != null ? properties : new XxlJobProperties();
        this.governor = governor;
    }

    /**
     * 查询所有已注册的调度任务概览与节点静默状态
     *
     * @return 任务概览信息
     */
    @ReadOperation
    public Map<String, Object> listJobs() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appname", properties.getAppname());
        result.put("port", properties.getPort());
        result.put("enabled", properties.isEnabled());
        result.put("adminAddresses", properties.getAdminAddresses());

        Set<String> handlers = XxlJobTestHelper.getLoadedHandlers().keySet();
        result.put("jobCount", handlers.size());
        result.put("handlers", handlers);

        if (governor != null) {
            Set<String> paused = governor.getPausedHandlers();
            result.put("pausedCount", paused.size());
            result.put("pausedHandlers", paused);
        } else {
            result.put("pausedCount", 0);
            result.put("pausedHandlers", Collections.emptySet());
        }
        return result;
    }

    /**
     * 查询指定调度任务处理器的详细元数据与配置
     *
     * @param handler 任务 Handler 名称
     * @return 任务元数据详情
     */
    @ReadOperation
    public Map<String, Object> jobDetail(@Selector String handler) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("handler", handler);

        if (governor != null) {
            result.put("paused", governor.isPaused(handler));
        }

        IJobHandler jobHandler = XxlJobTestHelper.getHandler(handler);
        if (jobHandler == null) {
            result.put("exists", false);
            result.put("message", "任务处理器 [" + handler + "] 未找到或尚未装配");
            return result;
        }

        result.put("exists", true);
        result.put("handlerType", jobHandler.getClass().getSimpleName());

        if (jobHandler instanceof MethodJobHandler methodJobHandler) {
            try {
                Field targetField = MethodJobHandler.class.getDeclaredField("target");
                targetField.setAccessible(true);
                Object target = targetField.get(methodJobHandler);

                Field methodField = MethodJobHandler.class.getDeclaredField("method");
                methodField.setAccessible(true);
                Method method = (Method) methodField.get(methodJobHandler);

                if (target != null) {
                    result.put("targetClass", target.getClass().getName());
                }
                if (method != null) {
                    result.put("methodName", method.getName());
                    result.put("returnType", method.getReturnType().getSimpleName());
                    result.put("parameterCount", method.getParameterCount());
                }
            } catch (Throwable ignored) {
                // 忽略反射私有属性异常
            }
        }
        return result;
    }

    /**
     * 节点级任务运行时治理操作 (暂停或恢复)
     *
     * <p>对应 HTTP 请求：{@code POST /actuator/xxljobs/{handler}/pause} 或 {@code POST /actuator/xxljobs/{handler}/resume}。</p>
     *
     * @param handler 任务 Handler 名称
     * @param action  治理动作 (pause 或 resume)
     * @return 治理操作结果详情
     */
    @WriteOperation
    public Map<String, Object> operateJob(
            @Selector String handler,
            @Selector String action) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("handler", handler);
        response.put("action", action);

        if (governor == null) {
            response.put("success", false);
            response.put("message", "JobRuntimeGovernor 未装配或已禁用");
            return response;
        }

        if ("pause".equalsIgnoreCase(action)) {
            governor.pause(handler);
            response.put("success", true);
            response.put("paused", true);
            response.put("message", "任务处理器 [" + handler + "] 已在当前节点暂停 (PAUSED)");
        } else if ("resume".equalsIgnoreCase(action)) {
            governor.resume(handler);
            response.put("success", true);
            response.put("paused", false);
            response.put("message", "任务处理器 [" + handler + "] 已在当前节点恢复 (RESUMED)");
        } else {
            response.put("success", false);
            response.put("message", "不支持的治理动作: [" + action + "]，仅支持 'pause' 或 'resume'");
        }
        return response;
    }

    /**
     * 手动触发执行指定调度任务
     *
     * @param handler    任务 Handler 名称
     * @param param      执行入参 (可选)
     * @param shardIndex 分片索引 (可选，默认为 0)
     * @param shardTotal 分片总数 (可选，默认为 1)
     * @param tenantId   显式租户标识 (可选)
     * @return 执行结果详情
     */
    @WriteOperation
    public Map<String, Object> triggerJob(
            @Selector String handler,
            @Nullable String param,
            @Nullable Integer shardIndex,
            @Nullable Integer shardTotal,
            @Nullable String tenantId) {

        int idx = shardIndex != null ? shardIndex : 0;
        int total = shardTotal != null ? shardTotal : 1;

        XxlJobTestResult testResult = XxlJobTestHelper.trigger(handler, param, idx, total, tenantId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("handler", handler);
        response.put("success", testResult.isSuccess());
        response.put("handleCode", testResult.handleCode());
        response.put("handleMsg", testResult.handleMsg());
        response.put("durationMs", testResult.durationMs());
        if (testResult.throwable() != null) {
            response.put("error", testResult.throwable().getMessage());
        }
        return response;
    }
}
