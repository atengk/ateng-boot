package io.github.atengk.boot.sample.job.local.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.template.LocalJobTemplate;
import io.github.atengk.boot.sample.job.local.dto.ArchivingJobParam;
import io.github.atengk.boot.sample.job.local.handler.SampleGlobalTimeoutFallbackHandler;
import io.github.atengk.boot.sample.job.local.job.OrderTimeoutCancelScenarioJob;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 单机定时任务调度触发与执行控制器
 * <p>
 * 演示受管定时任务的手动即时触发、强类型 DTO 入参绑定与 JSR-303 Fail-Fast 防卫、
 * 硬超时熔断同 Bean 降级补偿、SaaS 多租户信号量并发账单轮询与集群互斥锁最小持有窗口防重验证。
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
    private final OrderTimeoutCancelScenarioJob orderTimeoutCancelScenarioJob;
    private final SampleGlobalTimeoutFallbackHandler sampleGlobalTimeoutFallbackHandler;

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

    /**
     * 触发支付对账硬超时熔断与同 Bean 降级补偿任务
     *
     * @param simulateTimeout 是否模拟支付网关延迟引发硬超时降级（默认 true）
     * @return 任务执行（含降级补偿）结果视图对象
     */
    @PostMapping("/trigger/timeout-fallback")
    public Result<JobResultVO> triggerTimeoutFallbackJob(@RequestParam(defaultValue = "true") boolean simulateTimeout) {
        log.info("[LocalJobScheduleController] 触发支付对账超时降级演示任务: simulateTimeout={}", simulateTimeout);
        JobResult result = localJobTemplate.trigger("paymentReconcileFallbackJob", Map.of("simulateTimeout", simulateTimeout));
        return Result.success(JobResultVO.from(result));
    }

    /**
     * 触发无 method 级降级声明任务的全局 LocalJobTimeoutFallbackHandler SPI 降级兜底演示
     *
     * @return 全局 SPI 降级补偿结果视图对象
     */
    @PostMapping("/trigger/global-timeout-fallback")
    public Result<JobResultVO> triggerGlobalTimeoutFallbackJob() {
        log.info("[LocalJobScheduleController] 触发全局 LocalJobTimeoutFallbackHandler SPI 超时降级演示");
        orderTimeoutCancelScenarioJob.setSimulateTimeout(true);
        sampleGlobalTimeoutFallbackHandler.setEnabled(true);
        try {
            JobResult result = localJobTemplate.trigger("orderTimeoutCancelJob");
            return Result.success(JobResultVO.from(result));
        } finally {
            sampleGlobalTimeoutFallbackHandler.setEnabled(false);
            orderTimeoutCancelScenarioJob.setSimulateTimeout(false);
        }
    }
}
