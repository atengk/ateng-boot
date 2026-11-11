package io.github.atengk.boot.sample.job.local.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.template.LocalJobTemplate;
import io.github.atengk.boot.sample.job.local.dto.ArchivingJobParam;
import io.github.atengk.boot.sample.job.local.vo.JobResultVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 单机定时任务调度触发与执行控制器
 * <p>
 * 演示受管定时任务的手动即时触发、强类型 DTO 入参绑定与 JSR-303 Fail-Fast 防卫、
 * SaaS 多租户 ITERATE 模态轮询与 Fail-Safe 容错隔离、以及轻量集群互斥防重锁验证。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/job-local/schedule")
@RequiredArgsConstructor
public class LocalJobScheduleController {

    private final LocalJobTemplate localJobTemplate;

    /**
     * 手动即时触发指定受管定时任务
     *
     * @param jobName 任务唯一标识名称
     * @return 任务执行成果视图对象
     */
    @PostMapping("/trigger/{jobName}")
    public Result<JobResultVO> triggerJob(@PathVariable @NotBlank(message = "任务标识不能为空") String jobName) {
        log.info("[LocalJobScheduleController] 手动立即触发任务: jobName={}", jobName);
        JobResult result = localJobTemplate.trigger(jobName);
        return Result.success(JobResultVO.from(result));
    }

    /**
     * 携带强类型业务参数触发数据归档任务
     *
     * @param request 历史归档请求参数（带 JSR-303 格式校验）
     * @return 任务执行成果视图对象
     */
    @PostMapping("/trigger/archiving")
    public Result<JobResultVO> triggerArchivingJob(@Valid @RequestBody ArchivingJobParam request) {
        log.info("[LocalJobScheduleController] 携带业务参数触发归档任务: tableName={}, retentionDays={}, dryRun={}",
                request.getTableName(), request.getRetentionDays(), request.isDryRun());

        Map<String, Object> params = Map.of(
                "tableName", request.getTableName(),
                "retentionDays", request.getRetentionDays(),
                "batchSize", request.getBatchSize(),
                "dryRun", request.isDryRun()
        );

        JobResult result = localJobTemplate.trigger("typedParamArchivingJob", params);
        return Result.success(JobResultVO.from(result));
    }

    /**
     * 触发 SaaS 多租户账单轮询任务
     *
     * @return 任务执行成果视图对象
     */
    @PostMapping("/trigger/billing")
    public Result<JobResultVO> triggerBillingJob() {
        log.info("[LocalJobScheduleController] 触发多租户账单轮询任务 (ITERATE 模态)");
        JobResult result = localJobTemplate.trigger("multiTenantBillingJob");
        return Result.success(JobResultVO.from(result));
    }

    /**
     * 触发轻量集群防重互斥锁任务
     *
     * @return 任务执行成果视图对象
     */
    @PostMapping("/trigger/cluster-lock")
    public Result<JobResultVO> triggerClusterLockJob() {
        log.info("[LocalJobScheduleController] 触发轻量集群防重互斥锁任务");
        JobResult result = localJobTemplate.trigger("clusterDeduplicationJob");
        return Result.success(JobResultVO.from(result));
    }
}
