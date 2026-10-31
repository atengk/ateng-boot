package io.github.atengk.boot.job.xxl.template;

import com.xxl.job.core.openapi.admin.AdminJobBiz;
import com.xxl.job.core.openapi.admin.dto.JobAddRequest;
import com.xxl.job.core.openapi.admin.dto.JobOperateRequest;
import com.xxl.job.core.openapi.admin.dto.JobTriggerRequest;
import com.xxl.job.core.openapi.admin.dto.JobUpdateRequest;
import com.xxl.tool.http.HttpTool;
import com.xxl.tool.response.Response;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * XXL-Job 3.x OpenAPI 编程式管理门面
 *
 * <p>依托 XXL-Job 3.x 官方 {@link AdminJobBiz} OpenAPI 规范，提供强类型、无状态的任务编排门面：
 * <ul>
 *     <li><b>对称操作完备性</b>：提供 {@code addJob} / {@code removeJob}、{@code startJob} / {@code stopJob}、{@code updateJob} 与 {@code triggerJob} 全量对称方法；</li>
 *     <li><b>高可用集群容灾与故障转移</b>：支持配置多个调度中心地址，调用失败或网络超时自动故障转移 (Failover Retry) 探测下一个节点；</li>
 *     <li><b>强类型与便捷重载</b>：同时支持 DTO 原生参数与常用轻量入参 (如 {@code jobId}、{@code executorParam}) 便捷重载；</li>
 *     <li><b>因果异常解耦</b>：底层网络与调度中心业务报错统一转换为标准 {@link BusinessException}，杜绝异常泄露与调用方硬编码解析。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class XxlJobAdminTemplate {

    private static final Logger log = LoggerFactory.getLogger(XxlJobAdminTemplate.class);

    private static final String DEFAULT_ACCESS_TOKEN = "default_token";
    private static final String ACCESS_TOKEN_HEADER = "XXL-JOB-ACCESS-TOKEN";
    private static final String APPNAME_HEADER = "XXL-JOB-APPNAME";

    private final List<AdminBizEndpoint> endpoints;

    /**
     * 基于配置属性构建管理门面
     *
     * @param properties 配置属性
     */
    public XxlJobAdminTemplate(XxlJobProperties properties) {
        Assert.notNull(properties, "XxlJobProperties 不能为 null");
        this.endpoints = Collections.unmodifiableList(initEndpoints(properties));
    }

    /**
     * 基于预置客户端列表构建（供测试桩或高级扩展使用）
     *
     * @param clients 客户端代理列表
     */
    public XxlJobAdminTemplate(List<AdminJobBiz> clients) {
        Assert.notNull(clients, "AdminJobBiz 客户端列表不能为 null");
        List<AdminBizEndpoint> list = new ArrayList<>();
        for (int i = 0; i < clients.size(); i++) {
            list.add(new AdminBizEndpoint("mock-admin-" + (i + 1), clients.get(i)));
        }
        this.endpoints = Collections.unmodifiableList(list);
    }

    /**
     * 新增调度任务
     *
     * @param request 任务新增参数
     * @return 新增任务 ID 字符串 (XXL-Job OpenAPI 协议返回)
     */
    public String addJob(JobAddRequest request) {
        Assert.notNull(request, "JobAddRequest 不能为 null");
        return executeWithFailover(client -> client.addJob(request), "新增任务 (" + request.getName() + ")");
    }

    /**
     * 新增调度任务并获取数字型 JobId
     *
     * @param request 任务新增参数
     * @return 任务 ID
     */
    public int addJobAndGetId(JobAddRequest request) {
        String idStr = addJob(request);
        try {
            return Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "解析调度中心新增任务 ID 失败: " + idStr, e);
        }
    }

    /**
     * 更新调度任务配置
     *
     * @param request 任务更新参数
     */
    public void updateJob(JobUpdateRequest request) {
        Assert.notNull(request, "JobUpdateRequest 不能为 null");
        executeWithFailover(client -> client.updateJob(request), "更新任务 (JobId: " + request.getId() + ")");
    }

    /**
     * 删除调度任务
     *
     * @param request 任务操作参数
     */
    public void removeJob(JobOperateRequest request) {
        Assert.notNull(request, "JobOperateRequest 不能为 null");
        executeWithFailover(client -> client.removeJob(request), "删除任务 (JobId: " + request.getId() + ")");
    }

    /**
     * 删除调度任务 (便捷重载)
     *
     * @param jobId 任务 ID
     */
    public void removeJob(int jobId) {
        Assert.isTrue(jobId > 0, "jobId 必须大于 0");
        JobOperateRequest request = new JobOperateRequest();
        request.setId(jobId);
        removeJob(request);
    }

    /**
     * 启动调度任务
     *
     * @param request 任务操作参数
     */
    public void startJob(JobOperateRequest request) {
        Assert.notNull(request, "JobOperateRequest 不能为 null");
        executeWithFailover(client -> client.startJob(request), "启动任务 (JobId: " + request.getId() + ")");
    }

    /**
     * 启动调度任务 (便捷重载)
     *
     * @param jobId 任务 ID
     */
    public void startJob(int jobId) {
        Assert.isTrue(jobId > 0, "jobId 必须大于 0");
        JobOperateRequest request = new JobOperateRequest();
        request.setId(jobId);
        startJob(request);
    }

    /**
     * 停止调度任务
     *
     * @param request 任务操作参数
     */
    public void stopJob(JobOperateRequest request) {
        Assert.notNull(request, "JobOperateRequest 不能为 null");
        executeWithFailover(client -> client.stopJob(request), "停止任务 (JobId: " + request.getId() + ")");
    }

    /**
     * 停止调度任务 (便捷重载)
     *
     * @param jobId 任务 ID
     */
    public void stopJob(int jobId) {
        Assert.isTrue(jobId > 0, "jobId 必须大于 0");
        JobOperateRequest request = new JobOperateRequest();
        request.setId(jobId);
        stopJob(request);
    }

    /**
     * 即时触发一次任务调度
     *
     * @param request 任务触发参数
     */
    public void triggerJob(JobTriggerRequest request) {
        Assert.notNull(request, "JobTriggerRequest 不能为 null");
        executeWithFailover(client -> client.triggerJob(request), "即时触发任务 (JobId: " + request.getId() + ")");
    }

    /**
     * 即时触发一次任务调度 (完整参数便捷重载)
     *
     * @param jobId         任务 ID
     * @param executorParam 临时执行参数 (可为 null)
     * @param addressList   指定执行机器地址列表 (可为 null，默认全部集群轮询)
     */
    public void triggerJob(int jobId, String executorParam, String addressList) {
        Assert.isTrue(jobId > 0, "jobId 必须大于 0");
        JobTriggerRequest request = new JobTriggerRequest();
        request.setId(jobId);
        if (StringUtils.hasText(executorParam)) {
            request.setExecutorParam(executorParam);
        }
        if (StringUtils.hasText(addressList)) {
            request.setAddressList(addressList);
        }
        triggerJob(request);
    }

    /**
     * 即时触发一次任务调度 (仅指定参数便捷重载)
     *
     * @param jobId         任务 ID
     * @param executorParam 临时执行参数
     */
    public void triggerJob(int jobId, String executorParam) {
        triggerJob(jobId, executorParam, null);
    }

    /**
     * 即时触发一次任务调度 (无参便捷重载)
     *
     * @param jobId 任务 ID
     */
    public void triggerJob(int jobId) {
        triggerJob(jobId, null, null);
    }

    /**
     * 获取当前有效注册的调度中心节点数
     *
     * @return 节点数量
     */
    public int getEndpointCount() {
        return endpoints.size();
    }

    /**
     * 针对集群调度中心实施故障转移与容灾调用
     *
     * @param action        调用逻辑
     * @param operationName 操作名称 (用于诊断日志)
     * @param <R>           返回数据类型
     * @return 成功调用后的返回值
     */
    private <R> R executeWithFailover(AdminBizFunction<AdminJobBiz, Response<R>> action, String operationName) {
        if (endpoints.isEmpty()) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                    "XXL-Job 调度中心未配置或不可用，无法执行 [" + operationName + "]。请检查 'ateng.boot.job.xxl.admin-addresses'。");
        }

        List<String> failureReasons = new ArrayList<>();
        Throwable lastThrowable = null;

        for (AdminBizEndpoint endpoint : endpoints) {
            try {
                Response<R> response = action.apply(endpoint.client());
                if (response != null && response.isSuccess()) {
                    if (log.isDebugEnabled()) {
                        log.debug("XXL-Job OpenAPI 执行成功: {} [调度中心: {}]", operationName, endpoint.rawAddress());
                    }
                    return response.getData();
                }

                String msg = (response != null) ? response.getMsg() : "调度中心未返回响应内容";
                String reason = String.format("调度中心 [%s] 返回失败 (code=%s, msg=%s)",
                        endpoint.rawAddress(), response != null ? response.getCode() : "null", msg);
                log.warn("XXL-Job OpenAPI [{}] 在节点 [{}] 执行失败，尝试故障转移: {}", operationName, endpoint.rawAddress(), msg);
                failureReasons.add(reason);
            } catch (Throwable t) {
                lastThrowable = t;
                String reason = String.format("调度中心 [%s] 连接/调用异常: %s", endpoint.rawAddress(), t.getMessage());
                log.warn("XXL-Job OpenAPI [{}] 在节点 [{}] 抛出异常，尝试故障转移: {}", operationName, endpoint.rawAddress(), t.getMessage());
                failureReasons.add(reason);
            }
        }

        String aggregatedMsg = String.format("XXL-Job OpenAPI 操作 [%s] 全部调度中心节点均调用失败: %s",
                operationName, String.join("; ", failureReasons));
        if (lastThrowable != null) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, aggregatedMsg, lastThrowable);
        } else {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, aggregatedMsg);
        }
    }

    /**
     * 初始化调度中心代理列表
     */
    private List<AdminBizEndpoint> initEndpoints(XxlJobProperties properties) {
        String adminAddresses = properties.getAdminAddresses();
        if (!StringUtils.hasText(adminAddresses)) {
            return Collections.emptyList();
        }

        int timeoutSeconds = 3;
        if (properties.getTimeout() != null && properties.getTimeout().toSeconds() > 0) {
            timeoutSeconds = (int) properties.getTimeout().toSeconds();
        }
        int timeoutMs = timeoutSeconds * 1000;

        String accessToken = StringUtils.hasText(properties.getAccessToken()) ? properties.getAccessToken() : DEFAULT_ACCESS_TOKEN;
        String appname = properties.getAppname();

        List<AdminBizEndpoint> list = new ArrayList<>();
        String[] addresses = adminAddresses.trim().split(",");
        for (String addr : addresses) {
            if (!StringUtils.hasText(addr)) {
                continue;
            }
            String raw = addr.trim();
            String cleanUrl = raw.endsWith("/") ? (raw + "api/") : (raw + "/api/");

            AdminJobBiz client = HttpTool.createClient()
                    .url(cleanUrl)
                    .connectTimeout(timeoutMs)
                    .readTimeout(timeoutMs)
                    .header(ACCESS_TOKEN_HEADER, accessToken)
                    .header(APPNAME_HEADER, appname)
                    .proxy(AdminJobBiz.class);

            list.add(new AdminBizEndpoint(raw, client));
        }
        return list;
    }

    /**
     * 调度中心端点封装记录
     */
    private record AdminBizEndpoint(String rawAddress, AdminJobBiz client) {}

    @FunctionalInterface
    private interface AdminBizFunction<T, R> {
        R apply(T t) throws Exception;
    }
}
