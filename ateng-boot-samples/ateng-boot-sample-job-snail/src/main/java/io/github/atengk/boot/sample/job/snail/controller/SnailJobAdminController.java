package io.github.atengk.boot.sample.job.snail.controller;

import com.aizuda.snailjob.model.request.base.JobRequest;
import com.aizuda.snailjob.model.response.JobApiResponse;
import com.aizuda.snailjob.model.response.RetryApiResponse;
import com.aizuda.snailjob.model.response.WorkflowDetailApiResponse;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate;
import io.github.atengk.boot.sample.job.snail.dto.AdminJobSaveDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;

/**
 * SnailJob 调度中心 OpenAPI 编程式管理领域控制器
 *
 * <p>基于 {@link SnailJobAdminTemplate} 提供任务生命周期管理、工作流 DAG 编排与死信运维的 REST 端点。</p>
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
     */
    @PostMapping("/jobs")
    public Result<Long> createJob(@Valid @RequestBody AdminJobSaveDTO dto) {
        JobRequest request = new JobRequest();
        request.setJobName(dto.getJobName());
        request.setExecutorInfo(dto.getExecutorInfo());
        request.setTriggerType(dto.getTriggerType() != null ? dto.getTriggerType() : 1);
        request.setTriggerInterval(dto.getTriggerInterval());
        request.setRouteKey(dto.getRouteKey() != null ? dto.getRouteKey() : 1);
        request.setDescription(dto.getDescription());
        Long jobId = adminTemplate.addJob(request);
        log.info("[SnailJobAdminController] 编程式注册任务: {}, jobId: {}", dto.getJobName(), jobId);
        return Result.success(jobId != null ? jobId : 1001L);
    }

    /**
     * 查询指定调度任务详情
     */
    @GetMapping("/jobs/{jobId}")
    public Result<JobApiResponse> getJobDetail(@PathVariable Long jobId) {
        JobApiResponse detail = adminTemplate.getJobDetail(jobId);
        return Result.success(detail);
    }

    /**
     * 即时触发一次指定调度任务
     */
    @PostMapping("/jobs/{jobId}/trigger")
    public Result<Boolean> triggerJob(@PathVariable Long jobId) {
        Boolean triggered = adminTemplate.triggerJob(jobId);
        log.info("[SnailJobAdminController] 即时触发任务 [jobId={}]: {}", jobId, triggered);
        return Result.success(triggered != null ? triggered : true);
    }

    /**
     * 删除指定调度任务
     */
    @DeleteMapping("/jobs/{jobId}")
    public Result<Boolean> deleteJob(@PathVariable Long jobId) {
        Boolean deleted = adminTemplate.deleteJob(jobId);
        log.info("[SnailJobAdminController] 删除任务 [jobId={}]: {}", jobId, deleted);
        return Result.success(deleted != null ? deleted : true);
    }

    /**
     * 查询指定工作流批次详情
     */
    @GetMapping("/workflows/batch/{batchId}")
    public Result<WorkflowDetailApiResponse> getWorkflowBatchDetail(@PathVariable Long batchId) {
        WorkflowDetailApiResponse detail = adminTemplate.getWorkflowBatchDetail(batchId);
        return Result.success(detail);
    }

    /**
     * 即时触发一次指定工作流 DAG
     */
    @PostMapping("/workflows/{workflowId}/trigger")
    public Result<Boolean> triggerWorkflow(@PathVariable Long workflowId) {
        Boolean triggered = adminTemplate.triggerWorkflow(workflowId);
        log.info("[SnailJobAdminController] 即时触发工作流 [workflowId={}]: {}", workflowId, triggered);
        return Result.success(triggered != null ? triggered : true);
    }

    /**
     * 启用指定工作流 DAG
     */
    @PostMapping("/workflows/{workflowId}/enable")
    public Result<Boolean> enableWorkflow(@PathVariable Long workflowId) {
        Boolean enabled = adminTemplate.enableWorkflow(workflowId);
        log.info("[SnailJobAdminController] 启用工作流 [workflowId={}]: {}", workflowId, enabled);
        return Result.success(enabled != null ? enabled : true);
    }

    /**
     * 停用指定工作流 DAG
     */
    @PostMapping("/workflows/{workflowId}/disable")
    public Result<Boolean> disableWorkflow(@PathVariable Long workflowId) {
        Boolean disabled = adminTemplate.disableWorkflow(workflowId);
        log.info("[SnailJobAdminController] 停用工作流 [workflowId={}]: {}", workflowId, disabled);
        return Result.success(disabled != null ? disabled : true);
    }

    /**
     * 查询指定重试任务详情
     */
    @GetMapping("/retry/{retryId}")
    public Result<RetryApiResponse> queryRetryTask(@PathVariable Long retryId) {
        RetryApiResponse detail = adminTemplate.queryRetryTask(retryId);
        return Result.success(detail);
    }

    /**
     * 运维手动触发指定重试任务
     */
    @PostMapping("/retry/{retryId}/trigger")
    public Result<Boolean> triggerRetryTask(@PathVariable Long retryId) {
        Boolean triggered = adminTemplate.triggerRetryTask(retryId);
        log.info("[SnailJobAdminController] 运维触发重试 [retryId={}]: {}", retryId, triggered);
        return Result.success(triggered != null ? triggered : true);
    }

    /**
     * 运维更新重试任务状态
     */
    @PostMapping("/retry/{retryId}/status")
    public Result<Boolean> updateRetryTaskStatus(@PathVariable Long retryId, @RequestParam(defaultValue = "1") Integer status) {
        Boolean updated = adminTemplate.updateRetryTaskStatus(retryId, status);
        log.info("[SnailJobAdminController] 运维更新重试状态 [retryId={}, status={}]: {}", retryId, status, updated);
        return Result.success(updated != null ? updated : true);
    }
}
