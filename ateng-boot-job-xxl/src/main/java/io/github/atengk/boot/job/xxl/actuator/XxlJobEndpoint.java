package io.github.atengk.boot.job.xxl.actuator;

import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper.XxlJobTestResult;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * XXL-Job 运维管控与本地手动触发 Actuator 端点
 *
 * <p>端点路径为 {@code /actuator/xxljobs}：
 * <ul>
 *     <li>{@code GET /actuator/xxljobs}：列出当前应用装配的所有 XXL-Job 任务处理器及元数据；</li>
 *     <li>{@code POST /actuator/xxljobs/{handler}}：手动模拟调度中心触发指定任务，支持传入自定义入参与分片。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Endpoint(id = "xxljobs")
public class XxlJobEndpoint {

    private final XxlJobProperties properties;

    public XxlJobEndpoint(XxlJobProperties properties) {
        this.properties = properties != null ? properties : new XxlJobProperties();
    }

    /**
     * 查询所有已注册的调度任务概览
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
        return result;
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
