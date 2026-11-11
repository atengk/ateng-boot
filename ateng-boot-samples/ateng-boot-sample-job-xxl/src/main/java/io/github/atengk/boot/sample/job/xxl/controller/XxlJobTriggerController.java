package io.github.atengk.boot.sample.job.xxl.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper.XxlJobTestResult;
import io.github.atengk.boot.sample.job.xxl.dto.UserSyncParam;
import io.github.atengk.boot.sample.job.xxl.handler.OrderReconciliationJobHandler;
import io.github.atengk.boot.sample.job.xxl.vo.JobTriggerVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分布式任务调度手动触发与业务验证控制器
 *
 * <p>提供受管分布式任务的手动即时驱动体验端，涵盖系统预热巡检、强类型 DTO JSR-303 参数防卫、
 * 大数据分片广播批处理、订单对账三阶告警聚合自愈、本地看门狗超时强中断降级补偿、
 * 以及 SaaS 多租户全模态隔离与并发遍历等企业级场景。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/job-xxl/trigger")
@RequiredArgsConstructor
public class XxlJobTriggerController {

    private final OrderReconciliationJobHandler reconciliationJobHandler;

    /**
     * 手动触发系统热点缓存预热与健康巡检任务
     *
     * @return 任务执行诊断结果
     */
    @PostMapping("/warmup")
    public Result<JobTriggerVO> triggerWarmup() {
        log.info("[XxlJobTriggerController] 手动触发系统预热巡检任务: systemWarmupJob");
        XxlJobTestResult result = XxlJobTestHelper.trigger("systemWarmupJob", "warmup-system-cache");
        return Result.success(JobTriggerVO.from(result));
    }

    /**
     * 强类型参数同步任务：接收 JSON 载荷执行 JSR-303 防卫并触发执行
     *
     * @param param 经过 Spring MVC 校验的强类型参数
     * @return 任务执行诊断结果
     */
    @PostMapping("/user-sync")
    public Result<JobTriggerVO> triggerUserSync(@Valid @RequestBody UserSyncParam param) {
        log.info("[XxlJobTriggerController] 手动触发强类型用户同步任务: userSyncJob, 批次={}, 批大小={}",
                param.getBatchNo(), param.getBatchSize());
        XxlJobTestResult result = XxlJobTestHelper.trigger("userSyncJob", param);
        return Result.success(JobTriggerVO.from(result));
    }

    /**
     * 手动模拟大数据分片广播批处理
     *
     * @param shardIndex 当前节点分片序号 (默认 0)
     * @param shardTotal 集群总分片数 (默认 2)
     * @return 任务执行诊断结果
     */
    @PostMapping("/sharding-batch")
    public Result<JobTriggerVO> triggerShardingBatch(
            @RequestParam(defaultValue = "0") int shardIndex,
            @RequestParam(defaultValue = "2") int shardTotal) {
        log.info("[XxlJobTriggerController] 手动模拟分片广播批处理: shardIndex={}/{}", shardIndex, shardTotal);
        XxlJobTestResult result = XxlJobTestHelper.trigger("shardingBatchDeadLetterJob", null, shardIndex, shardTotal, null);
        return Result.success(JobTriggerVO.from(result));
    }

    /**
     * 触发每日订单对账任务：演示告警风暴抑制与故障自愈状态机
     *
     * @param hasDiff 是否模拟对账差错 (true 触发异常与告警, false 正常平账)
     * @return 任务执行诊断结果
     */
    @PostMapping("/reconciliation")
    public Result<JobTriggerVO> triggerReconciliation(@RequestParam(defaultValue = "false") boolean hasDiff) {
        log.info("[XxlJobTriggerController] 手动触发订单对账任务: orderReconciliationJob, hasDiff={}", hasDiff);
        reconciliationJobHandler.setFaultInjected(hasDiff);
        try {
            XxlJobTestResult result = XxlJobTestHelper.trigger("orderReconciliationJob", "hasDiff=" + hasDiff);
            return Result.success(JobTriggerVO.from(result));
        } finally {
            reconciliationJobHandler.setFaultInjected(false);
        }
    }

    /**
     * 触发历史冷数据归档任务：演示本地硬看门狗中断与就地降级补偿
     *
     * @return 任务执行诊断结果
     */
    @PostMapping("/timeout-archive")
    public Result<JobTriggerVO> triggerTimeoutArchive() {
        log.info("[XxlJobTriggerController] 手动触发冷数据归档任务: historyDataArchiveJob (预期触发超时中断与降级)");
        XxlJobTestResult result = XxlJobTestHelper.trigger("historyDataArchiveJob");
        return Result.success(JobTriggerVO.from(result));
    }

    /**
     * 触发全局平台级清理归档：演示多租户 IGNORE 模式
     *
     * @return 任务执行诊断结果
     */
    @PostMapping("/tenant/global")
    public Result<JobTriggerVO> triggerTenantGlobal() {
        log.info("[XxlJobTriggerController] 手动触发多租户全局任务: globalArchiveJob");
        XxlJobTestResult result = XxlJobTestHelper.trigger("globalArchiveJob");
        return Result.success(JobTriggerVO.from(result));
    }

    /**
     * 触发特定租户隔离月结账单任务：演示多租户 PARAM 模式
     *
     * @param tenantId 目标租户标识
     * @return 任务执行诊断结果
     */
    @PostMapping("/tenant/scoped")
    public Result<JobTriggerVO> triggerTenantScoped(@RequestParam(defaultValue = "TENANT-888") String tenantId) {
        log.info("[XxlJobTriggerController] 手动触发单租户隔离任务: tenantScopedJob, tenantId={}", tenantId);
        XxlJobTestResult result = XxlJobTestHelper.trigger("tenantScopedJob", "tenantId=" + tenantId);
        return Result.success(JobTriggerVO.from(result));
    }

    /**
     * 触发多租户全沙箱并发遍历任务：演示多租户 ITERATE 并发遍历模式
     *
     * @return 任务执行诊断结果
     */
    @PostMapping("/tenant/parallel")
    public Result<JobTriggerVO> triggerTenantParallel() {
        log.info("[XxlJobTriggerController] 手动触发多租户并发遍历任务: parallelTenantJob");
        XxlJobTestResult result = XxlJobTestHelper.trigger("parallelTenantJob");
        return Result.success(JobTriggerVO.from(result));
    }
}
