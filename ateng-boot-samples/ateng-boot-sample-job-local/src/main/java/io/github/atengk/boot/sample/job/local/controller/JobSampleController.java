package io.github.atengk.boot.sample.job.local.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.job.local.model.JobExecutionRecord;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.template.LocalJobTemplate;
import io.github.atengk.boot.job.local.util.DurationParser;
import io.github.atengk.boot.sample.job.local.dto.DynamicJobRegisterRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * 示例定时任务管控 Controller，演示基于 LocalJobTemplate 的端点契约交互
 *
 * @author Ateng
 * @since 2026-10-25
 */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobSampleController {

    private static final Logger log = LoggerFactory.getLogger(JobSampleController.class);

    private final LocalJobTemplate localJobTemplate;

    public JobSampleController(LocalJobTemplate localJobTemplate) {
        this.localJobTemplate = localJobTemplate;
    }

    /**
     * 获取所有受管定时任务清单
     */
    @GetMapping
    public Result<List<JobInfo>> listJobs() {
        return Result.success(localJobTemplate.listJobs());
    }

    /**
     * 查询指定定时任务详情
     */
    @GetMapping("/{jobName}")
    public Result<JobInfo> getJob(@PathVariable String jobName) {
        JobInfo job = localJobTemplate.getJob(jobName);
        return Result.success(job);
    }

    /**
     * 手动立即触发指定任务
     */
    @PostMapping("/{jobName}/trigger")
    public Result<JobResult> triggerJob(@PathVariable String jobName) {
        log.info("Received manual trigger request for job: {}", jobName);
        JobResult result = localJobTemplate.trigger(jobName);
        return Result.success(result);
    }

    /**
     * 暂停指定定时任务
     */
    @PostMapping("/{jobName}/pause")
    public Result<Boolean> pauseJob(@PathVariable String jobName) {
        boolean success = localJobTemplate.pause(jobName);
        return Result.success(success);
    }

    /**
     * 恢复指定定时任务
     */
    @PostMapping("/{jobName}/resume")
    public Result<Boolean> resumeJob(@PathVariable String jobName) {
        boolean success = localJobTemplate.resume(jobName);
        return Result.success(success);
    }

    /**
     * 查询指定任务最近的执行历史记录
     */
    @GetMapping("/{jobName}/logs")
    public Result<List<JobExecutionRecord>> listRecentLogs(
            @PathVariable String jobName,
            @RequestParam(defaultValue = "10") int limit) {
        List<JobExecutionRecord> records = localJobTemplate.listRecentLogs(jobName, limit);
        return Result.success(records != null ? records : Collections.emptyList());
    }

    /**
     * 编程式动态注册定时任务（支持 Cron、FixedRate、FixedDelay）
     */
    @PostMapping("/dynamic-register")
    public Result<Boolean> registerDynamicJob(@Valid @RequestBody DynamicJobRegisterRequest request) {
        log.info("Registering dynamic job: {}", request.getJobName());
        boolean success;
        Runnable taskLogic = () -> log.info("Dynamic job [{}] executed successfully", request.getJobName());

        if (request.getCron() != null && !request.getCron().isBlank()) {
            success = localJobTemplate.registerCron(request.getJobName(), request.getCron().trim(), taskLogic);
        } else if (request.getFixedRate() != null && !request.getFixedRate().isBlank()) {
            Duration rate = DurationParser.parse(request.getFixedRate());
            success = localJobTemplate.registerFixedRate(request.getJobName(), rate, taskLogic);
        } else if (request.getFixedDelay() != null && !request.getFixedDelay().isBlank()) {
            Duration delay = DurationParser.parse(request.getFixedDelay());
            success = localJobTemplate.registerFixedDelay(request.getJobName(), delay, taskLogic);
        } else {
            success = localJobTemplate.registerCron(request.getJobName(), "0 0 * * * ?", taskLogic);
        }
        return Result.success(success);
    }
}
