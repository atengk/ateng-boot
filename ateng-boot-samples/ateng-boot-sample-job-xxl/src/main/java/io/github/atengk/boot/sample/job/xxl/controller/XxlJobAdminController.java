package io.github.atengk.boot.sample.job.xxl.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.job.xxl.template.XxlJobAdminTemplate;
import io.github.atengk.boot.sample.job.xxl.dto.AdminJobAddRequestDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调度中心集群 OpenAPI 编程式运维控制器
 *
 * <p>依托 {@link XxlJobAdminTemplate} 对接 XXL-Job 3.x 官方 OpenAPI，提供任务动态注册、
 * 启停管控、即时触发与任务移除等生命周期管理能力，支持集群节点故障自动转移。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/job-xxl/admin")
@RequiredArgsConstructor
public class XxlJobAdminController {

    private final XxlJobAdminTemplate adminTemplate;

    /**
     * 编程式向调度中心注册新任务
     *
     * @param requestDTO 任务创建请求 DTO
     * @return 调度中心分配的新增任务 ID
     */
    @PostMapping("/jobs")
    public Result<Integer> addJob(@Valid @RequestBody AdminJobAddRequestDTO requestDTO) {
        log.info("[XxlJobAdminController] 编程式向调度中心注册任务: name={}, handler={}",
                requestDTO.getName(), requestDTO.getExecutorHandler());
        int jobId = adminTemplate.addJobAndGetId(requestDTO.toJobAddRequest());
        return Result.success(jobId);
    }

    /**
     * 启动调度任务
     *
     * @param jobId 任务 ID
     * @return 操作是否成功
     */
    @PostMapping("/jobs/{jobId}/start")
    public Result<Boolean> startJob(@PathVariable @NotNull(message = "任务 ID 不能为空") Integer jobId) {
        log.info("[XxlJobAdminController] 编程式启动任务: jobId={}", jobId);
        adminTemplate.startJob(jobId);
        return Result.success(true);
    }

    /**
     * 停止调度任务
     *
     * @param jobId 任务 ID
     * @return 操作是否成功
     */
    @PostMapping("/jobs/{jobId}/stop")
    public Result<Boolean> stopJob(@PathVariable @NotNull(message = "任务 ID 不能为空") Integer jobId) {
        log.info("[XxlJobAdminController] 编程式停止任务: jobId={}", jobId);
        adminTemplate.stopJob(jobId);
        return Result.success(true);
    }

    /**
     * 即时手动触发调度任务
     *
     * @param jobId 任务 ID
     * @param param 执行入参
     * @return 操作是否成功
     */
    @PostMapping("/jobs/{jobId}/trigger")
    public Result<Boolean> triggerJob(
            @PathVariable @NotNull(message = "任务 ID 不能为空") Integer jobId,
            @RequestParam(required = false, defaultValue = "manual-trigger") String param) {
        log.info("[XxlJobAdminController] 编程式即时触发任务: jobId={}, param={}", jobId, param);
        adminTemplate.triggerJob(jobId, param);
        return Result.success(true);
    }

    /**
     * 删除调度任务
     *
     * @param jobId 任务 ID
     * @return 操作是否成功
     */
    @DeleteMapping("/jobs/{jobId}")
    public Result<Boolean> removeJob(@PathVariable @NotNull(message = "任务 ID 不能为空") Integer jobId) {
        log.info("[XxlJobAdminController] 编程式删除任务: jobId={}", jobId);
        adminTemplate.removeJob(jobId);
        return Result.success(true);
    }
}
