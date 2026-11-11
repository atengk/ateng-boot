package io.github.atengk.boot.sample.job.snail.controller;

import com.aizuda.snailjob.model.response.JobApiResponse;
import com.aizuda.snailjob.model.response.RetryApiResponse;
import com.aizuda.snailjob.model.response.WorkflowDetailApiResponse;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate;
import io.github.atengk.boot.sample.job.snail.dto.AdminJobSaveDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * SnailJob 调度中心 OpenAPI 编程式管理领域控制器
 *
 * <p>基于 {@link SnailJobAdminTemplate} 提供任务全生命周期管理（注册/更新/查询/触发/删除）、
 * DAG 工作流编排控制与分布式重试任务运维的 REST 端点。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/job-snail/admin")
@RequiredArgsConstructor
public class SnailJobAdminController {

    private final SnailJobAdminTemplate adminTemplate;

    /**
     * 编程式向调度中心注册新任务
     *
     * @param dto 调度任务保存请求 DTO
     * @return 调度中心分配的任务 ID
     */
    @PostMapping("/jobs")
    public Result<Long> createJob(@Valid @RequestBody AdminJobSaveDTO dto) {
        Long jobId = adminTemplate.addJob(dto.toJobRequest());
        log.info("[SnailJobAdminController] 编程式注册任务: {}, jobId: {}", dto.getJobName(), jobId);
        return Result.success(requireNonNullResult(jobId, "注册调度任务"));
    }

    /**
     * 编程式更新调度中心任务配置
     *
     * @param dto 调度任务更新请求 DTO（需携带有效任务 ID）
     * @return 是否更新成功
     */
    @PutMapping("/jobs")
    public Result<Boolean> updateJob(@Valid @RequestBody AdminJobSaveDTO dto) {
        BusinessAssert.notNull(dto.getId(), "更新调度任务时任务 ID 不能为空");
        Boolean updated = adminTemplate.updateJob(dto.toJobRequest());
        log.info("[SnailJobAdminController] 编程式更新任务 [jobId={}, jobName={}]: {}", dto.getId(), dto.getJobName(), updated);
        return Result.success(requireSuccessResult(updated, "更新调度任务"));
    }

    /**
     * 查询指定调度任务详情
     *
     * @param jobId 任务 ID
     * @return 任务元数据详情
     */
    @GetMapping("/jobs/{jobId}")
    public Result<JobApiResponse> getJobDetail(
            @PathVariable @NotNull(message = "任务 ID 不能为空") @Min(value = 1, message = "任务 ID 必须大于 0") Long jobId) {
        JobApiResponse detail = adminTemplate.getJobDetail(jobId);
        return Result.success(requireNonNullResult(detail, "查询任务详情"));
    }

    /**
     * 即时触发一次指定调度任务
     *
     * @param jobId 任务 ID
     * @return 是否触发成功
     */
    @PostMapping("/jobs/{jobId}/trigger")
    public Result<Boolean> triggerJob(
            @PathVariable @NotNull(message = "任务 ID 不能为空") @Min(value = 1, message = "任务 ID 必须大于 0") Long jobId) {
        Boolean triggered = adminTemplate.triggerJob(jobId);
        log.info("[SnailJobAdminController] 即时触发任务 [jobId={}]: {}", jobId, triggered);
        return Result.success(requireSuccessResult(triggered, "即时触发任务"));
    }

    /**
     * 删除指定调度任务
     *
     * @param jobId 任务 ID
     * @return 是否删除成功
     */
    @DeleteMapping("/jobs/{jobId}")
    public Result<Boolean> deleteJob(
            @PathVariable @NotNull(message = "任务 ID 不能为空") @Min(value = 1, message = "任务 ID 必须大于 0") Long jobId) {
        Boolean deleted = adminTemplate.deleteJob(jobId);
        log.info("[SnailJobAdminController] 删除任务 [jobId={}]: {}", jobId, deleted);
        return Result.success(requireSuccessResult(deleted, "删除调度任务"));
    }

    /**
     * 查询指定工作流批次详情
     *
     * @param batchId 工作流批次 ID
     * @return 工作流批次执行详情
     */
    @GetMapping("/workflows/batch/{batchId}")
    public Result<WorkflowDetailApiResponse> getWorkflowBatchDetail(
            @PathVariable @NotNull(message = "工作流批次 ID 不能为空") @Min(value = 1, message = "工作流批次 ID 必须大于 0") Long batchId) {
        WorkflowDetailApiResponse detail = adminTemplate.getWorkflowBatchDetail(batchId);
        return Result.success(requireNonNullResult(detail, "查询工作流批次详情"));
    }

    /**
     * 即时触发一次指定工作流 DAG
     *
     * @param workflowId 工作流 ID
     * @return 是否触发成功
     */
    @PostMapping("/workflows/{workflowId}/trigger")
    public Result<Boolean> triggerWorkflow(
            @PathVariable @NotNull(message = "工作流 ID 不能为空") @Min(value = 1, message = "工作流 ID 必须大于 0") Long workflowId) {
        Boolean triggered = adminTemplate.triggerWorkflow(workflowId);
        log.info("[SnailJobAdminController] 即时触发工作流 [workflowId={}]: {}", workflowId, triggered);
        return Result.success(requireSuccessResult(triggered, "即时触发工作流"));
    }

    /**
     * 启用指定工作流 DAG
     *
     * @param workflowId 工作流 ID
     * @return 是否启用成功
     */
    @PostMapping("/workflows/{workflowId}/enable")
    public Result<Boolean> enableWorkflow(
            @PathVariable @NotNull(message = "工作流 ID 不能为空") @Min(value = 1, message = "工作流 ID 必须大于 0") Long workflowId) {
        Boolean enabled = adminTemplate.enableWorkflow(workflowId);
        log.info("[SnailJobAdminController] 启用工作流 [workflowId={}]: {}", workflowId, enabled);
        return Result.success(requireSuccessResult(enabled, "启用工作流"));
    }

    /**
     * 停用指定工作流 DAG
     *
     * @param workflowId 工作流 ID
     * @return 是否停用成功
     */
    @PostMapping("/workflows/{workflowId}/disable")
    public Result<Boolean> disableWorkflow(
            @PathVariable @NotNull(message = "工作流 ID 不能为空") @Min(value = 1, message = "工作流 ID 必须大于 0") Long workflowId) {
        Boolean disabled = adminTemplate.disableWorkflow(workflowId);
        log.info("[SnailJobAdminController] 停用工作流 [workflowId={}]: {}", workflowId, disabled);
        return Result.success(requireSuccessResult(disabled, "停用工作流"));
    }

    /**
     * 删除指定工作流 DAG
     *
     * @param workflowId 工作流 ID
     * @return 是否删除成功
     */
    @DeleteMapping("/workflows/{workflowId}")
    public Result<Boolean> deleteWorkflow(
            @PathVariable @NotNull(message = "工作流 ID 不能为空") @Min(value = 1, message = "工作流 ID 必须大于 0") Long workflowId) {
        Boolean deleted = adminTemplate.deleteWorkflow(workflowId);
        log.info("[SnailJobAdminController] 删除工作流 [workflowId={}]: {}", workflowId, deleted);
        return Result.success(requireSuccessResult(deleted, "删除工作流"));
    }

    /**
     * 查询指定重试任务详情
     *
     * @param retryId 重试任务 ID
     * @return 重试任务元数据详情
     */
    @GetMapping("/retry/{retryId}")
    public Result<RetryApiResponse> queryRetryTask(
            @PathVariable @NotNull(message = "重试任务 ID 不能为空") @Min(value = 1, message = "重试任务 ID 必须大于 0") Long retryId) {
        RetryApiResponse detail = adminTemplate.queryRetryTask(retryId);
        return Result.success(requireNonNullResult(detail, "查询重试任务详情"));
    }

    /**
     * 运维手动触发指定重试任务
     *
     * @param retryId 重试任务 ID
     * @return 是否触发成功
     */
    @PostMapping("/retry/{retryId}/trigger")
    public Result<Boolean> triggerRetryTask(
            @PathVariable @NotNull(message = "重试任务 ID 不能为空") @Min(value = 1, message = "重试任务 ID 必须大于 0") Long retryId) {
        Boolean triggered = adminTemplate.triggerRetryTask(retryId);
        log.info("[SnailJobAdminController] 运维触发重试 [retryId={}]: {}", retryId, triggered);
        return Result.success(requireSuccessResult(triggered, "手动触发重试任务"));
    }

    /**
     * 运维更新重试任务状态
     *
     * @param retryId 重试任务 ID
     * @param status  目标重试状态码
     * @return 是否更新成功
     */
    @PostMapping("/retry/{retryId}/status")
    public Result<Boolean> updateRetryTaskStatus(
            @PathVariable @NotNull(message = "重试任务 ID 不能为空") @Min(value = 1, message = "重试任务 ID 必须大于 0") Long retryId,
            @RequestParam(defaultValue = "1") @NotNull(message = "重试状态 status 不能为空") @Min(value = 0, message = "重试状态 status 不能小于 0") Integer status) {
        Boolean updated = adminTemplate.updateRetryTaskStatus(retryId, status);
        log.info("[SnailJobAdminController] 运维更新重试状态 [retryId={}, status={}]: {}", retryId, status, updated);
        return Result.success(requireSuccessResult(updated, "更新重试任务状态"));
    }

    private <T> T requireNonNullResult(T value, String action) {
        if (value == null) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                    "SnailJob 调度中心 [" + action + "] 未返回有效数据");
        }
        return value;
    }

    private boolean requireSuccessResult(Boolean result, String action) {
        if (!Boolean.TRUE.equals(result)) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                    "SnailJob 调度中心 [" + action + "] 执行未成功");
        }
        return true;
    }
}
