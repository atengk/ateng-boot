package io.github.atengk.boot.sample.job.local.controller;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.job.local.model.JobExecutionRecord;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.template.LocalJobTemplate;
import io.github.atengk.boot.job.local.util.DurationParser;
import io.github.atengk.boot.sample.job.local.dto.CronPreviewRequest;
import io.github.atengk.boot.sample.job.local.vo.CronPreviewVO;
import io.github.atengk.boot.sample.job.local.vo.JobExecutionRecordVO;
import io.github.atengk.boot.sample.job.local.vo.JobInfoVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 单机定时任务状态巡检与审计回查控制器
 * <p>
 * 演示受管定时任务全量清单巡检、单任务运行详情查询、执行历史审计日志回查、
 * Cron 语法校验与未来时钟点预演、以及有界日志滚动清理防 OOM。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/job-local/audit")
@RequiredArgsConstructor
public class LocalJobAuditController {

    private final LocalJobTemplate localJobTemplate;

    /**
     * 巡检系统内所有受管定时任务运行清单
     *
     * @return 任务清单视图对象列表
     */
    @GetMapping("/jobs")
    public Result<List<JobInfoVO>> listJobs() {
        log.info("[LocalJobAuditController] 查询全量受管任务清单");
        List<JobInfo> jobs = localJobTemplate.listJobs();
        if (jobs == null || jobs.isEmpty()) {
            return Result.success(Collections.emptyList());
        }
        List<JobInfoVO> voList = jobs.stream().map(JobInfoVO::from).toList();
        return Result.success(voList);
    }

    /**
     * 查询指定定时任务的详细定义与运行状态
     *
     * @param jobName 任务标识名称
     * @return 任务详情视图对象
     */
    @GetMapping("/jobs/{jobName}")
    public Result<JobInfoVO> getJob(@PathVariable @NotBlank(message = "任务标识不能为空") String jobName) {
        log.info("[LocalJobAuditController] 查询单任务详情: jobName={}", jobName);
        JobInfo jobInfo = localJobTemplate.getJob(jobName);
        if (jobInfo == null) {
            return Result.failure("任务 [" + jobName + "] 不存在");
        }
        return Result.success(JobInfoVO.from(jobInfo));
    }

    /**
     * 回查指定定时任务的最近执行历史审计日志清单
     *
     * @param jobName 任务标识名称
     * @param limit   最大回查条数（默认 10）
     * @return 审计记录列表
     */
    @GetMapping("/jobs/{jobName}/logs")
    public Result<List<JobExecutionRecordVO>> listRecentLogs(
            @PathVariable @NotBlank(message = "任务标识不能为空") String jobName,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
        log.info("[LocalJobAuditController] 回查任务执行日志: jobName={}, limit={}", jobName, limit);
        List<JobExecutionRecord> records = localJobTemplate.listRecentLogs(jobName, limit);
        if (records == null || records.isEmpty()) {
            return Result.success(Collections.emptyList());
        }
        List<JobExecutionRecordVO> voList = records.stream().map(JobExecutionRecordVO::from).toList();
        return Result.success(voList);
    }

    /**
     * 校验 Cron 表达式并预演未来指定次数的时钟触发时间点
     *
     * @param request Cron 预演请求
     * @return 预演结果视图对象
     */
    @PostMapping("/cron/preview")
    public Result<CronPreviewVO> previewCron(@Valid @RequestBody CronPreviewRequest request) {
        log.info("[LocalJobAuditController] 预演 Cron 表达式: cron={}, count={}",
                request.getCron(), request.getCount());
        boolean valid = localJobTemplate.isValidCron(request.getCron());
        List<Instant> nextTimes = valid
                ? localJobTemplate.getNextExecutionTimes(request.getCron(), request.getCount())
                : Collections.emptyList();

        CronPreviewVO vo = CronPreviewVO.builder()
                .cron(request.getCron())
                .valid(valid)
                .nextExecutionTimes(nextTimes)
                .build();
        return Result.success(vo);
    }

    /**
     * 滚动清理超出保留时长的历史审计记录
     *
     * @param retention 保留时长文本（默认 7d）
     * @return 实际清理的记录条数
     */
    @DeleteMapping("/logs/expired")
    public Result<Integer> cleanExpiredLogs(@RequestParam(defaultValue = "7d") String retention) {
        Duration duration;
        try {
            duration = DurationParser.parse(retention, Duration.ofDays(7));
            BusinessAssert.isTrue(duration != null && !duration.isNegative(), "保留时长必须大于等于 0");
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST,
                    "保留时长格式非法: " + retention, e);
        }
        log.info("[LocalJobAuditController] 滚动清理过期审计日志: retention={}", duration);
        int cleaned = localJobTemplate.cleanExpiredLogs(duration);
        return Result.success(cleaned);
    }
}
