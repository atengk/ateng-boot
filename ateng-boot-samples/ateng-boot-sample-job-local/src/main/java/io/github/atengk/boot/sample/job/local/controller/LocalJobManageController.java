package io.github.atengk.boot.sample.job.local.controller;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.job.local.template.LocalJobTemplate;
import io.github.atengk.boot.job.local.util.DurationParser;
import io.github.atengk.boot.sample.job.local.dto.JobCronUpdateRequest;
import io.github.atengk.boot.sample.job.local.dto.JobDelayUpdateRequest;
import io.github.atengk.boot.sample.job.local.dto.JobDynamicRegisterRequest;
import io.github.atengk.boot.sample.job.local.dto.JobRateUpdateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 单机定时任务生命周期与动态管控控制器
 * <p>
 * 演示受管定时任务的暂停、恢复、Cron/频率/延迟动态重置、运行时编程式注册与注销移除等运维管控能力。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/job-local/manage")
@RequiredArgsConstructor
public class LocalJobManageController {

    private final LocalJobTemplate localJobTemplate;

    /**
     * 暂停指定受管定时任务的周期自动调度
     *
     * @param jobName 任务标识名称
     * @return 操作是否成功
     */
    @PostMapping("/pause/{jobName}")
    public Result<Boolean> pauseJob(@PathVariable @NotBlank(message = "任务标识不能为空") String jobName) {
        log.info("[LocalJobManageController] 暂停任务周期调度: jobName={}", jobName);
        boolean success = localJobTemplate.pause(jobName);
        return Result.success(success);
    }

    /**
     * 恢复指定受管定时任务的周期自动调度
     *
     * @param jobName 任务标识名称
     * @return 操作是否成功
     */
    @PostMapping("/resume/{jobName}")
    public Result<Boolean> resumeJob(@PathVariable @NotBlank(message = "任务标识不能为空") String jobName) {
        log.info("[LocalJobManageController] 恢复任务周期调度: jobName={}", jobName);
        boolean success = localJobTemplate.resume(jobName);
        return Result.success(success);
    }

    /**
     * 动态修改指定任务的 Cron 表达式并重新调度
     *
     * @param request Cron 更新请求
     * @return 操作是否成功
     */
    @PostMapping("/update/cron")
    public Result<Boolean> updateCron(@Valid @RequestBody JobCronUpdateRequest request) {
        log.info("[LocalJobManageController] 动态修改 Cron 周期: jobName={}, cron={}",
                request.getJobName(), request.getCronExpression());
        boolean success = localJobTemplate.updateCron(request.getJobName(), request.getCronExpression());
        return Result.success(success);
    }

    /**
     * 动态修改指定任务的固定频率 (FixedRate) 并重新调度
     *
     * @param request 固定频率更新请求
     * @return 操作是否成功
     */
    @PostMapping("/update/rate")
    public Result<Boolean> updateRate(@Valid @RequestBody JobRateUpdateRequest request) {
        Duration duration;
        try {
            duration = DurationParser.parse(request.getFixedRate());
            BusinessAssert.isTrue(duration != null && !duration.isNegative() && !duration.isZero(),
                    "固定频率调度间隔必须为正时间间隔（如 5s, 1m, 1h）");
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST,
                    "固定频率调度间隔格式非法: " + request.getFixedRate(), e);
        }
        log.info("[LocalJobManageController] 动态修改固定频率: jobName={}, rate={}",
                request.getJobName(), duration);
        boolean success = localJobTemplate.updateFixedRate(request.getJobName(), duration);
        return Result.success(success);
    }

    /**
     * 动态修改指定任务的固定延迟 (FixedDelay) 并重新调度
     *
     * @param request 固定延迟更新请求
     * @return 操作是否成功
     */
    @PostMapping("/update/delay")
    public Result<Boolean> updateDelay(@Valid @RequestBody JobDelayUpdateRequest request) {
        Duration duration;
        try {
            duration = DurationParser.parse(request.getFixedDelay());
            BusinessAssert.isTrue(duration != null && !duration.isNegative() && !duration.isZero(),
                    "固定延迟调度间隔必须为正时间间隔（如 5s, 1m, 1h）");
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST,
                    "固定延迟调度间隔格式非法: " + request.getFixedDelay(), e);
        }
        log.info("[LocalJobManageController] 动态修改固定延迟: jobName={}, delay={}",
                request.getJobName(), duration);
        boolean success = localJobTemplate.updateFixedDelay(request.getJobName(), duration);
        return Result.success(success);
    }

    /**
     * 编程式动态注册新的受管定时任务
     *
     * @param request 动态注册请求
     * @return 操作是否成功
     */
    @PostMapping("/dynamic/register")
    public Result<Boolean> registerDynamicJob(@Valid @RequestBody JobDynamicRegisterRequest request) {
        log.info("[LocalJobManageController] 编程式动态注册任务: jobName={}, cron={}, desc={}",
                request.getJobName(), request.getCron(), request.getDescription());
        boolean success = localJobTemplate.registerCron(
                request.getJobName(),
                request.getCron(),
                () -> log.info("Dynamic registered job [{}] executed successfully", request.getJobName())
        );
        return Result.success(success);
    }

    /**
     * 动态注销并彻底移除受管定时任务
     *
     * @param jobName 任务标识名称
     * @return 操作是否成功
     */
    @DeleteMapping("/{jobName}")
    public Result<Boolean> removeJob(@PathVariable @NotBlank(message = "任务标识不能为空") String jobName) {
        log.info("[LocalJobManageController] 注销并移除受管任务: jobName={}", jobName);
        boolean success = localJobTemplate.remove(jobName);
        return Result.success(success);
    }
}
