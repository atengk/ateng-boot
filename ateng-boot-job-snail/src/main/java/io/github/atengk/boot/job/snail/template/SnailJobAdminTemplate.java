package io.github.atengk.boot.job.snail.template;

import com.aizuda.snailjob.client.common.rpc.client.RequestBuilder;
import com.aizuda.snailjob.client.core.openapi.RetryOpenApiClientV2;
import com.aizuda.snailjob.client.job.core.openapi.JobOpenApiClientV2;
import com.aizuda.snailjob.common.core.enums.StatusEnum;
import com.aizuda.snailjob.common.core.model.Result;
import com.aizuda.snailjob.common.core.model.SnailJobOpenApiResult;
import com.aizuda.snailjob.model.request.*;
import com.aizuda.snailjob.model.request.base.JobRequest;
import com.aizuda.snailjob.model.request.base.StatusUpdateRequest;
import com.aizuda.snailjob.model.request.base.WorkflowTriggerRequest;
import com.aizuda.snailjob.model.response.*;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Set;
import java.util.function.Supplier;

/**
 * SnailJob 官方 OpenAPI 编程式管理门面
 *
 * <p>依托 SnailJob 2.0.2 官方 {@link JobOpenApiClientV2} 与 {@link RetryOpenApiClientV2}，
 * 提供面向业务开发者的强类型、因果解耦、高可靠的管理门面：
 * <ul>
 *     <li><b>对称操作完备性</b>：完整覆盖调度任务（Job）、DAG 工作流（Workflow）及分布式重试（Retry）的新增、更新、查询、触发、状态启停与批量删除操作；</li>
 *     <li><b>强类型与双模支持</b>：同时原生支持数字型 JobId 与业务唯一标识 BizId 两套 OpenAPI 规范；</li>
 *     <li><b>因果异常解耦与错误码转译</b>：将底层网络/HTTP 异常或 OpenAPI 业务失败结果受控转译为统一的 {@link BusinessException}，保留原始异常因果链；</li>
 *     <li><b>脱网自洽与可测性</b>：支持构造器依赖注入外部客户端或 Mock 桩，保障单测在无 SnailJob 服务端时 100% 离线自洽运行。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailJobAdminTemplate {

    private static final Logger log = LoggerFactory.getLogger(SnailJobAdminTemplate.class);

    private final JobOpenApiClientV2 jobClient;
    private final RetryOpenApiClientV2 retryClient;

    /**
     * 默认无参构造器：基于 SnailJob 默认 OpenAPI 客户端构建
     */
    public SnailJobAdminTemplate() {
        this(null, null);
    }

    /**
     * 指定 Job 客户端构建
     *
     * @param jobClient SnailJob 任务 OpenAPI 客户端
     */
    public SnailJobAdminTemplate(JobOpenApiClientV2 jobClient) {
        this(jobClient, null);
    }

    /**
     * 完整客户端注入构造器（支持单元测试 Mock 桩注入）
     *
     * @param jobClient   SnailJob 任务 OpenAPI 客户端
     * @param retryClient SnailJob 重试 OpenAPI 客户端
     */
    public SnailJobAdminTemplate(JobOpenApiClientV2 jobClient, RetryOpenApiClientV2 retryClient) {
        this.jobClient = (jobClient != null) ? jobClient : createDefaultJobClient();
        this.retryClient = (retryClient != null) ? retryClient : createDefaultRetryClient();
    }

    // =========================================================================
    // 1. 任务核心生命周期管理 (Job Operations by numeric ID)
    // =========================================================================

    /**
     * 新增调度任务
     *
     * @param request 任务新增参数
     * @return 新增任务 ID
     */
    public Long addJob(JobRequest request) {
        Assert.notNull(request, "JobRequest 不能为 null");
        return execute(() -> requireJobClient().addJob(request), "新增定时任务");
    }

    /**
     * 更新调度任务配置
     *
     * @param request 任务更新参数
     * @return 是否更新成功
     */
    public Boolean updateJob(JobRequest request) {
        Assert.notNull(request, "JobRequest 不能为 null");
        return execute(() -> requireJobClient().updateJob(request), "更新定时任务 (id=" + request.getId() + ")");
    }

    /**
     * 按 ID 查询调度任务详情
     *
     * @param jobId 任务 ID
     * @return 任务配置详情
     */
    public JobApiResponse getJobDetail(Long jobId) {
        Assert.notNull(jobId, "jobId 不能为 null");
        return execute(() -> requireJobClient().getJobDetail(jobId), "查询定时任务详情 (jobId=" + jobId + ")");
    }

    /**
     * 按批次 ID 查询任务执行批次详情
     *
     * @param jobBatchId 任务批次 ID
     * @return 任务批次执行详情
     */
    public JobBatchApiResponse getJobBatchDetail(Long jobBatchId) {
        Assert.notNull(jobBatchId, "jobBatchId 不能为 null");
        return execute(() -> requireJobClient().getJobBatchDetail(jobBatchId), "查询定时任务批次详情 (jobBatchId=" + jobBatchId + ")");
    }

    /**
     * 手动触发调度任务执行
     *
     * @param request 任务触发请求参数
     * @return 是否触发成功
     */
    public Boolean triggerJob(JobTriggerApiRequest request) {
        Assert.notNull(request, "JobTriggerApiRequest 不能为 null");
        return execute(() -> requireJobClient().triggerJob(request), "手动触发定时任务 (jobId=" + request.getJobId() + ")");
    }

    /**
     * 手动触发调度任务执行（无额外临时参数）
     *
     * @param jobId 任务 ID
     * @return 是否触发成功
     */
    public Boolean triggerJob(Long jobId) {
        return triggerJob(jobId, null);
    }

    /**
     * 手动触发调度任务执行并指定临时参数
     *
     * @param jobId      任务 ID
     * @param tmpArgsStr 临时参数字符串 (支持 JSON)
     * @return 是否触发成功
     */
    public Boolean triggerJob(Long jobId, String tmpArgsStr) {
        Assert.notNull(jobId, "jobId 不能为 null");
        JobTriggerApiRequest request = new JobTriggerApiRequest();
        request.setJobId(jobId);
        request.setTmpArgsStr(tmpArgsStr);
        return triggerJob(request);
    }

    /**
     * 更新调度任务运行状态
     *
     * @param request 状态更新参数
     * @return 是否更新成功
     */
    public Boolean updateJobStatus(StatusUpdateRequest request) {
        Assert.notNull(request, "StatusUpdateRequest 不能为 null");
        return execute(() -> requireJobClient().updateJobStatus(request),
                "更新定时任务状态 (id=" + request.getId() + ", status=" + request.getStatus() + ")");
    }

    /**
     * 更新调度任务运行状态
     *
     * @param jobId  任务 ID
     * @param status 任务状态 (0: 停用, 1: 启用)
     * @return 是否更新成功
     */
    public Boolean updateJobStatus(Long jobId, Integer status) {
        Assert.notNull(jobId, "jobId 不能为 null");
        Assert.notNull(status, "status 不能为 null");
        StatusUpdateRequest request = new StatusUpdateRequest();
        request.setId(jobId);
        request.setStatus(status);
        return updateJobStatus(request);
    }

    /**
     * 启用指定调度任务
     *
     * @param jobId 任务 ID
     * @return 是否操作成功
     */
    public Boolean enableJob(Long jobId) {
        return updateJobStatus(jobId, StatusEnum.YES.getStatus());
    }

    /**
     * 停用指定调度任务
     *
     * @param jobId 任务 ID
     * @return 是否操作成功
     */
    public Boolean disableJob(Long jobId) {
        return updateJobStatus(jobId, StatusEnum.NO.getStatus());
    }

    /**
     * 删除指定调度任务
     *
     * @param jobId 任务 ID
     * @return 是否删除成功
     */
    public Boolean deleteJob(Long jobId) {
        Assert.notNull(jobId, "jobId 不能为 null");
        return deleteJobs(Collections.singleton(jobId));
    }

    /**
     * 批量删除调度任务
     *
     * @param toDeleteIds 待删除任务 ID 集合
     * @return 是否删除成功
     */
    public Boolean deleteJobs(Set<Long> toDeleteIds) {
        Assert.notEmpty(toDeleteIds, "toDeleteIds 集合不能为空");
        return execute(() -> requireJobClient().deleteJob(toDeleteIds), "删除定时任务列表 (size=" + toDeleteIds.size() + ")");
    }

    /**
     * 检查指定任务 ID 是否存在
     *
     * @param jobId 任务 ID
     * @return 存在性响应元数据
     */
    public JobExistsResponse existsJobById(Long jobId) {
        Assert.notNull(jobId, "jobId 不能为 null");
        return execute(() -> requireJobClient().existsJobById(jobId), "检查定时任务是否存在 (jobId=" + jobId + ")");
    }

    /**
     * 判定指定任务 ID 是否存在（布尔判定便捷重载）
     *
     * @param jobId 任务 ID
     * @return true 若任务存在
     */
    public boolean existsJob(Long jobId) {
        JobExistsResponse resp = existsJobById(jobId);
        return resp != null && resp.getId() != null;
    }

    // =========================================================================
    // 2. 工作流 (DAG Workflow Operations by numeric ID)
    // =========================================================================

    /**
     * 查询工作流批次执行详情
     *
     * @param workflowBatchId 工作流批次 ID
     * @return 工作流执行详情
     */
    public WorkflowDetailApiResponse getWorkflowBatchDetail(Long workflowBatchId) {
        Assert.notNull(workflowBatchId, "workflowBatchId 不能为 null");
        return execute(() -> requireJobClient().getWorkflowBatchDetail(workflowBatchId),
                "查询工作流批次详情 (workflowBatchId=" + workflowBatchId + ")");
    }

    /**
     * 手动触发工作流执行
     *
     * @param request 工作流触发参数
     * @return 是否触发成功
     */
    public Boolean triggerWorkflow(WorkflowTriggerRequest request) {
        Assert.notNull(request, "WorkflowTriggerRequest 不能为 null");
        return execute(() -> requireJobClient().triggerWorkFlow(request),
                "手动触发工作流 (workflowId=" + request.getWorkflowId() + ")");
    }

    /**
     * 手动触发工作流执行（无额外临时上下文）
     *
     * @param workflowId 工作流 ID
     * @return 是否触发成功
     */
    public Boolean triggerWorkflow(Long workflowId) {
        return triggerWorkflow(workflowId, null);
    }

    /**
     * 手动触发工作流执行并传递临时上下文参数
     *
     * @param workflowId   工作流 ID
     * @param tmpWfContext 临时工作流上下文字符串 (支持 JSON)
     * @return 是否触发成功
     */
    public Boolean triggerWorkflow(Long workflowId, String tmpWfContext) {
        Assert.notNull(workflowId, "workflowId 不能为 null");
        WorkflowTriggerRequest request = new WorkflowTriggerRequest();
        request.setWorkflowId(workflowId);
        request.setTmpWfContext(tmpWfContext);
        return triggerWorkflow(request);
    }

    /**
     * 更新工作流运行状态
     *
     * @param request 状态更新参数
     * @return 是否更新成功
     */
    public Boolean updateWorkflowStatus(StatusUpdateRequest request) {
        Assert.notNull(request, "StatusUpdateRequest 不能为 null");
        return execute(() -> requireJobClient().updateWorkFlowStatus(request),
                "更新工作流状态 (id=" + request.getId() + ", status=" + request.getStatus() + ")");
    }

    /**
     * 更新工作流运行状态
     *
     * @param workflowId 工作流 ID
     * @param status     工作流状态 (0: 停用, 1: 启用)
     * @return 是否更新成功
     */
    public Boolean updateWorkflowStatus(Long workflowId, Integer status) {
        Assert.notNull(workflowId, "workflowId 不能为 null");
        Assert.notNull(status, "status 不能为 null");
        StatusUpdateRequest request = new StatusUpdateRequest();
        request.setId(workflowId);
        request.setStatus(status);
        return updateWorkflowStatus(request);
    }

    /**
     * 启用指定工作流
     *
     * @param workflowId 工作流 ID
     * @return 是否操作成功
     */
    public Boolean enableWorkflow(Long workflowId) {
        return updateWorkflowStatus(workflowId, StatusEnum.YES.getStatus());
    }

    /**
     * 停用指定工作流
     *
     * @param workflowId 工作流 ID
     * @return 是否操作成功
     */
    public Boolean disableWorkflow(Long workflowId) {
        return updateWorkflowStatus(workflowId, StatusEnum.NO.getStatus());
    }

    /**
     * 删除指定工作流
     *
     * @param workflowId 工作流 ID
     * @return 是否删除成功
     */
    public Boolean deleteWorkflow(Long workflowId) {
        Assert.notNull(workflowId, "workflowId 不能为 null");
        return deleteWorkflows(Collections.singleton(workflowId));
    }

    /**
     * 批量删除工作流
     *
     * @param toDeleteIds 待删除工作流 ID 集合
     * @return 是否删除成功
     */
    public Boolean deleteWorkflows(Set<Long> toDeleteIds) {
        Assert.notEmpty(toDeleteIds, "toDeleteIds 集合不能为空");
        return execute(() -> requireJobClient().deleteWorkflow(toDeleteIds), "删除工作流列表 (size=" + toDeleteIds.size() + ")");
    }

    /**
     * 检查工作流是否存在
     *
     * @param workflowId 工作流 ID
     * @return 存在性响应元数据
     */
    public JobExistsResponse existsWorkflowById(Long workflowId) {
        Assert.notNull(workflowId, "workflowId 不能为 null");
        return execute(() -> requireJobClient().existsWorkflowById(workflowId), "检查工作流是否存在 (workflowId=" + workflowId + ")");
    }

    /**
     * 判定指定工作流是否存在（布尔判定便捷重载）
     *
     * @param workflowId 工作流 ID
     * @return true 若工作流存在
     */
    public boolean existsWorkflow(Long workflowId) {
        JobExistsResponse resp = existsWorkflowById(workflowId);
        return resp != null && resp.getId() != null;
    }

    // =========================================================================
    // 3. 基于 BizId 业务唯一标识操作 (BizId-based Job & Workflow Operations)
    // =========================================================================

    /**
     * 按业务唯一标识 BizId 新增调度任务
     *
     * @param request 任务新增参数
     * @return 新增任务 ID
     */
    public Long addJobByBizId(JobBizIdApiRequest request) {
        Assert.notNull(request, "JobBizIdApiRequest 不能为 null");
        return execute(() -> requireJobClient().addJobByBizId(request), "按 BizId 新增任务 (bizId=" + request.getBizId() + ")");
    }

    /**
     * 按业务唯一标识 BizId 更新调度任务
     *
     * @param request 任务更新参数
     * @return 是否更新成功
     */
    public Boolean updateJobByBizId(JobBizIdApiRequest request) {
        Assert.notNull(request, "JobBizIdApiRequest 不能为 null");
        return execute(() -> requireJobClient().updateJobByBizId(request), "按 BizId 更新任务 (bizId=" + request.getBizId() + ")");
    }

    /**
     * 按业务唯一标识 BizId 查询调度任务详情
     *
     * @param bizId 业务唯一标识
     * @return 任务配置详情
     */
    public JobApiResponse getJobDetailByBizId(String bizId) {
        Assert.hasText(bizId, "bizId 不能为空");
        return execute(() -> requireJobClient().getJobDetailByBizId(bizId), "按 BizId 查询任务详情 (bizId=" + bizId + ")");
    }

    /**
     * 按业务唯一标识 BizId 触发调度任务
     *
     * @param request 任务触发参数
     * @return 是否触发成功
     */
    public Boolean triggerJobByBizId(JobTriggerBizIdRequest request) {
        Assert.notNull(request, "JobTriggerBizIdRequest 不能为 null");
        return execute(() -> requireJobClient().triggerJobByBizId(request), "按 BizId 触发任务 (bizId=" + request.getBizId() + ")");
    }

    /**
     * 按业务唯一标识 BizId 触发调度任务（无额外临时参数）
     *
     * @param bizId 业务唯一标识
     * @return 是否触发成功
     */
    public Boolean triggerJobByBizId(String bizId) {
        return triggerJobByBizId(bizId, null);
    }

    /**
     * 按业务唯一标识 BizId 触发调度任务并传递临时参数
     *
     * @param bizId      业务唯一标识
     * @param tmpArgsStr 临时参数字符串
     * @return 是否触发成功
     */
    public Boolean triggerJobByBizId(String bizId, String tmpArgsStr) {
        Assert.hasText(bizId, "bizId 不能为空");
        JobTriggerBizIdRequest request = new JobTriggerBizIdRequest();
        request.setBizId(bizId);
        request.setTmpArgsStr(tmpArgsStr);
        return triggerJobByBizId(request);
    }

    /**
     * 按业务唯一标识 BizId 更新调度任务状态
     *
     * @param request 状态更新参数
     * @return 是否更新成功
     */
    public Boolean updateJobStatusByBizId(StatusUpdateBizIdRequest request) {
        Assert.notNull(request, "StatusUpdateBizIdRequest 不能为 null");
        return execute(() -> requireJobClient().updateJobStatusByBizId(request),
                "按 BizId 更新任务状态 (bizId=" + request.getBizId() + ", status=" + request.getStatus() + ")");
    }

    /**
     * 按业务唯一标识 BizId 更新调度任务状态
     *
     * @param bizId  业务唯一标识
     * @param status 状态 (0: 停用, 1: 启用)
     * @return 是否更新成功
     */
    public Boolean updateJobStatusByBizId(String bizId, Integer status) {
        Assert.hasText(bizId, "bizId 不能为空");
        Assert.notNull(status, "status 不能为 null");
        StatusUpdateBizIdRequest request = new StatusUpdateBizIdRequest();
        request.setBizId(bizId);
        request.setStatus(status);
        return updateJobStatusByBizId(request);
    }

    /**
     * 按业务唯一标识 BizId 启用调度任务
     *
     * @param bizId 业务唯一标识
     * @return 是否操作成功
     */
    public Boolean enableJobByBizId(String bizId) {
        return updateJobStatusByBizId(bizId, StatusEnum.YES.getStatus());
    }

    /**
     * 按业务唯一标识 BizId 停用调度任务
     *
     * @param bizId 业务唯一标识
     * @return 是否操作成功
     */
    public Boolean disableJobByBizId(String bizId) {
        return updateJobStatusByBizId(bizId, StatusEnum.NO.getStatus());
    }

    /**
     * 按业务唯一标识 BizId 批量删除调度任务
     *
     * @param bizIds 业务唯一标识集合
     * @return 是否删除成功
     */
    public Boolean deleteJobByBizIds(Set<String> bizIds) {
        Assert.notEmpty(bizIds, "bizIds 集合不能为空");
        DeleteBizIdRequest request = new DeleteBizIdRequest();
        request.setBizIds(bizIds);
        return execute(() -> requireJobClient().deleteJobByBizIds(request), "按 BizId 删除任务列表 (size=" + bizIds.size() + ")");
    }

    /**
     * 按业务唯一标识 BizId 删除单个调度任务
     *
     * @param bizId 业务唯一标识
     * @return 是否删除成功
     */
    public Boolean deleteJobByBizId(String bizId) {
        Assert.hasText(bizId, "bizId 不能为空");
        return deleteJobByBizIds(Collections.singleton(bizId));
    }

    /**
     * 按业务唯一标识 BizId 查询任务是否存在
     *
     * @param bizId 业务唯一标识
     * @return 存在性元数据
     */
    public JobExistsResponse existsJobByBizId(String bizId) {
        Assert.hasText(bizId, "bizId 不能为空");
        return execute(() -> requireJobClient().existsJobByBizId(bizId), "按 BizId 检查任务是否存在 (bizId=" + bizId + ")");
    }

    /**
     * 判定指定 BizId 任务是否存在（布尔便捷重载）
     *
     * @param bizId 业务唯一标识
     * @return true 若任务存在
     */
    public boolean existsJobBizId(String bizId) {
        JobExistsResponse resp = existsJobByBizId(bizId);
        return resp != null && (resp.getId() != null || StringUtils.hasText(resp.getBizId()));
    }

    /**
     * 按业务唯一标识 BizId 触发工作流
     *
     * @param request 工作流触发参数
     * @return 是否触发成功
     */
    public Boolean triggerWorkflowByBizId(WorkflowTriggerBizIdRequest request) {
        Assert.notNull(request, "WorkflowTriggerBizIdRequest 不能为 null");
        return execute(() -> requireJobClient().triggerWorkflowByBizId(request),
                "按 BizId 触发工作流 (bizId=" + request.getBizId() + ")");
    }

    /**
     * 按业务唯一标识 BizId 触发工作流（无额外临时参数）
     *
     * @param bizId 业务唯一标识
     * @return 是否触发成功
     */
    public Boolean triggerWorkflowByBizId(String bizId) {
        return triggerWorkflowByBizId(bizId, null);
    }

    /**
     * 按业务唯一标识 BizId 触发工作流并传递临时上下文
     *
     * @param bizId        业务唯一标识
     * @param tmpWfContext 临时工作流上下文
     * @return 是否触发成功
     */
    public Boolean triggerWorkflowByBizId(String bizId, String tmpWfContext) {
        Assert.hasText(bizId, "bizId 不能为空");
        WorkflowTriggerBizIdRequest request = new WorkflowTriggerBizIdRequest();
        request.setBizId(bizId);
        request.setTmpWfContext(tmpWfContext);
        return triggerWorkflowByBizId(request);
    }

    /**
     * 按业务唯一标识 BizId 更新工作流状态
     *
     * @param request 状态更新参数
     * @return 是否更新成功
     */
    public Boolean updateWorkflowStatusByBizId(WorkflowStatusUpdateBizIdRequest request) {
        Assert.notNull(request, "WorkflowStatusUpdateBizIdRequest 不能为 null");
        return execute(() -> requireJobClient().updateWorkflowStatusByBizId(request),
                "按 BizId 更新工作流状态 (bizId=" + request.getBizId() + ", status=" + request.getStatus() + ")");
    }

    /**
     * 按业务唯一标识 BizId 更新工作流状态
     *
     * @param bizId  业务唯一标识
     * @param status 状态 (0: 停用, 1: 启用)
     * @return 是否更新成功
     */
    public Boolean updateWorkflowStatusByBizId(String bizId, Integer status) {
        Assert.hasText(bizId, "bizId 不能为空");
        Assert.notNull(status, "status 不能为 null");
        WorkflowStatusUpdateBizIdRequest request = new WorkflowStatusUpdateBizIdRequest();
        request.setBizId(bizId);
        request.setStatus(status);
        return updateWorkflowStatusByBizId(request);
    }

    /**
     * 按业务唯一标识 BizId 启用工作流
     *
     * @param bizId 业务唯一标识
     * @return 是否操作成功
     */
    public Boolean enableWorkflowByBizId(String bizId) {
        return updateWorkflowStatusByBizId(bizId, StatusEnum.YES.getStatus());
    }

    /**
     * 按业务唯一标识 BizId 停用工作流
     *
     * @param bizId 业务唯一标识
     * @return 是否操作成功
     */
    public Boolean disableWorkflowByBizId(String bizId) {
        return updateWorkflowStatusByBizId(bizId, StatusEnum.NO.getStatus());
    }

    /**
     * 按业务唯一标识 BizId 批量删除工作流
     *
     * @param bizIds 业务唯一标识集合
     * @return 是否删除成功
     */
    public Boolean deleteWorkflowByBizIds(Set<String> bizIds) {
        Assert.notEmpty(bizIds, "bizIds 集合不能为空");
        return execute(() -> requireJobClient().deleteWorkflowByBizIds(bizIds),
                "按 BizId 删除工作流列表 (size=" + bizIds.size() + ")");
    }

    /**
     * 按业务唯一标识 BizId 删除单个工作流
     *
     * @param bizId 业务唯一标识
     * @return 是否删除成功
     */
    public Boolean deleteWorkflowByBizId(String bizId) {
        Assert.hasText(bizId, "bizId 不能为空");
        return deleteWorkflowByBizIds(Collections.singleton(bizId));
    }

    /**
     * 按业务唯一标识 BizId 查询工作流是否存在
     *
     * @param bizId 业务唯一标识
     * @return 存在性元数据
     */
    public JobExistsResponse existsWorkflowByBizId(String bizId) {
        Assert.hasText(bizId, "bizId 不能为空");
        return execute(() -> requireJobClient().existsWorkflowByBizId(bizId), "按 BizId 检查工作流是否存在 (bizId=" + bizId + ")");
    }

    /**
     * 判定指定 BizId 工作流是否存在（布尔便捷重载）
     *
     * @param bizId 业务唯一标识
     * @return true 若工作流存在
     */
    public boolean existsWorkflowBizId(String bizId) {
        JobExistsResponse resp = existsWorkflowByBizId(bizId);
        return resp != null && (resp.getId() != null || StringUtils.hasText(resp.getBizId()));
    }

    // =========================================================================
    // 4. 远程重试任务管理 (Retry Operations)
    // =========================================================================

    /**
     * 按重试任务 ID 查询重试详情
     *
     * @param retryId 重试任务 ID
     * @return 重试任务详情响应
     */
    public RetryApiResponse queryRetryTask(Long retryId) {
        Assert.notNull(retryId, "retryId 不能为 null");
        return execute(() -> requireRetryClient().queryRetryTask(retryId), "查询重试任务详情 (retryId=" + retryId + ")");
    }

    /**
     * 手动触发指定分布式重试任务
     *
     * @param request 重试任务触发参数
     * @return 是否触发成功
     */
    public Boolean triggerRetryTask(TriggerRetryApiRequest request) {
        Assert.notNull(request, "TriggerRetryApiRequest 不能为 null");
        return execute(() -> requireRetryClient().triggerRetryTask(request), "手动触发重试任务 (retryId=" + request.getId() + ")");
    }

    /**
     * 手动触发指定分布式重试任务（ID 便捷重载）
     *
     * @param retryId 重试任务 ID
     * @return 是否触发成功
     */
    public Boolean triggerRetryTask(Long retryId) {
        Assert.notNull(retryId, "retryId 不能为 null");
        TriggerRetryApiRequest request = new TriggerRetryApiRequest();
        request.setId(retryId);
        return triggerRetryTask(request);
    }

    /**
     * 更新分布式重试任务状态
     *
     * @param request 重试任务状态更新参数
     * @return 是否更新成功
     */
    public Boolean updateRetryTaskStatus(StatusUpdateRequest request) {
        Assert.notNull(request, "StatusUpdateRequest 不能为 null");
        return execute(() -> requireRetryClient().updateRetryTaskStatus(request),
                "更新重试任务状态 (id=" + request.getId() + ", status=" + request.getStatus() + ")");
    }

    /**
     * 更新分布式重试任务状态（ID 与状态值便捷重载）
     *
     * @param retryId     重试任务 ID
     * @param retryStatus 重试状态
     * @return 是否更新成功
     */
    public Boolean updateRetryTaskStatus(Long retryId, Integer retryStatus) {
        Assert.notNull(retryId, "retryId 不能为 null");
        Assert.notNull(retryStatus, "retryStatus 不能为 null");
        StatusUpdateRequest request = new StatusUpdateRequest();
        request.setId(retryId);
        request.setStatus(retryStatus);
        return updateRetryTaskStatus(request);
    }

    // =========================================================================
    // 5. 底层驱动调用、因果异常转译与客户端探测
    // =========================================================================

    /**
     * 统一执行 OpenAPI 请求调用，捕获底层网络/HTTP 异常或服务端业务失败并转译为 BusinessException
     *
     * @param action        调用逻辑
     * @param operationName 操作名称 (用于诊断与日志)
     * @param <T>           返回数据类型
     * @return 成功调用后的返回值
     */
    private <T> T execute(Supplier<Result<T>> action, String operationName) {
        Assert.notNull(action, "执行动作不能为 null");
        Result<T> result;
        try {
            result = action.get();
        } catch (BusinessException e) {
            throw e;
        } catch (Throwable t) {
            log.warn("SnailJob OpenAPI 操作 [{}] 底层网络/RPC 异常: {}", operationName, t.getMessage());
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                    "SnailJob OpenAPI 操作 [" + operationName + "] 远程调用失败: " + t.getMessage(), t);
        }

        if (result == null) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                    "SnailJob OpenAPI 操作 [" + operationName + "] 服务端未返回有效响应");
        }

        if (result.getStatus() != StatusEnum.YES.getStatus()) {
            String msg = StringUtils.hasText(result.getMessage()) ? result.getMessage() : "未提供详细错误描述";
            log.warn("SnailJob OpenAPI 操作 [{}] 失败: status={}, message={}", operationName, result.getStatus(), msg);
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                    "SnailJob OpenAPI 操作 [" + operationName + "] 失败: " + msg);
        }

        return result.getData();
    }

    private JobOpenApiClientV2 requireJobClient() {
        if (this.jobClient == null) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                    "SnailJob JobOpenApiClientV2 未就绪或未启用，无法执行任务管理操作。请检查 'ateng.boot.job.snail.openapi.enabled'");
        }
        return this.jobClient;
    }

    private RetryOpenApiClientV2 requireRetryClient() {
        if (this.retryClient == null) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                    "SnailJob RetryOpenApiClientV2 未就绪或未启用，无法执行重试管理操作。请检查 'ateng.boot.job.snail.openapi.enabled'");
        }
        return this.retryClient;
    }

    private static JobOpenApiClientV2 createDefaultJobClient() {
        try {
            return RequestBuilder.<JobOpenApiClientV2, SnailJobOpenApiResult>newBuilder()
                    .client(JobOpenApiClientV2.class)
                    .async(false)
                    .openapi(true)
                    .build();
        } catch (Throwable t) {
            log.warn("构建默认 SnailJob JobOpenApiClientV2 客户端代理失败: {}", t.getMessage());
            return null;
        }
    }

    private static RetryOpenApiClientV2 createDefaultRetryClient() {
        try {
            return RequestBuilder.<RetryOpenApiClientV2, SnailJobOpenApiResult>newBuilder()
                    .client(RetryOpenApiClientV2.class)
                    .async(false)
                    .openapi(true)
                    .build();
        } catch (Throwable t) {
            log.warn("构建默认 SnailJob RetryOpenApiClientV2 客户端代理失败: {}", t.getMessage());
            return null;
        }
    }

    public JobOpenApiClientV2 getJobClient() {
        return jobClient;
    }

    public RetryOpenApiClientV2 getRetryClient() {
        return retryClient;
    }
}
