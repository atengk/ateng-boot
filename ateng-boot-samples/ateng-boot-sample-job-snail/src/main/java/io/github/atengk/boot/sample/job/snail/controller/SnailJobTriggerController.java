package io.github.atengk.boot.sample.job.snail.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper.SnailJobTestResult;
import io.github.atengk.boot.sample.job.snail.dto.BillSettlementRequestDTO;
import io.github.atengk.boot.sample.job.snail.dto.UserSyncParam;
import io.github.atengk.boot.sample.job.snail.service.SnailJobScenarioService;
import io.github.atengk.boot.sample.job.snail.vo.JobTriggerVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * SnailJob 调度触发与切片规约演练领域控制器
 *
 * <p>提供系统预热巡检、参数防卫校验、MapReduce 切片规约、订单对账平账自愈、
 * 超时降级补偿、金丝雀灰度拦截、广播分片流式批处理与多租户全模态隔离的 REST 体验端点。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/job-snail/trigger")
@RequiredArgsConstructor
public class SnailJobTriggerController {

    private final SnailJobScenarioService scenarioService;

    /**
     * 触发系统冷启动热点缓存预热与健康巡检任务
     */
    @PostMapping("/warmup")
    public Result<JobTriggerVO> triggerWarmup() {
        SnailJobTestResult result = scenarioService.executeSystemWarmup();
        return Result.success(scenarioService.toTriggerVO("systemWarmupJob", result));
    }

    /**
     * 触发强类型入参校验同步用户任务 (JSR-303 防卫)
     */
    @PostMapping("/user-sync")
    public Result<JobTriggerVO> triggerUserSync(@Valid @RequestBody UserSyncParam param) {
        SnailJobTestResult result = scenarioService.executeUserSync(param.getBatchNo(), param.getBatchSize(), param.getDepartmentId());
        return Result.success(scenarioService.toTriggerVO("userSyncJob", result));
    }

    /**
     * 触发跨商户月度账单 MapReduce 切片拆分任务
     */
    @PostMapping("/bill-settlement/map")
    public Result<JobTriggerVO> triggerBillSettlementMap(@Valid @RequestBody BillSettlementRequestDTO request) {
        SnailJobTestResult result = scenarioService.executeMapTask("splitTask", request.getDataRange());
        return Result.success(scenarioService.toTriggerVO("billSettlementJob:splitTask", result));
    }

    /**
     * 触发跨商户月度账单 MapReduce 规约汇总任务
     */
    @PostMapping("/bill-settlement/reduce")
    public Result<JobTriggerVO> triggerBillSettlementReduce(@RequestParam(defaultValue = "summary") String param) {
        SnailJobTestResult result = scenarioService.executeReduceTask(param);
        return Result.success(scenarioService.toTriggerVO("billSettlementJob:reduce", result));
    }

    /**
     * 触发跨商户月度账单 MapReduce 全局合并规约任务
     */
    @PostMapping("/bill-settlement/merge-reduce")
    public Result<JobTriggerVO> triggerBillSettlementMergeReduce(@RequestParam(defaultValue = "global-merge") String param) {
        SnailJobTestResult result = scenarioService.executeMergeReduceTask(param);
        return Result.success(scenarioService.toTriggerVO("billSettlementJob:mergeReduce", result));
    }

    /**
     * 触发订单对账差错告警与平账自愈演练
     */
    @PostMapping("/reconciliation")
    public Result<JobTriggerVO> triggerReconciliation(@RequestParam(defaultValue = "false") boolean faultInjected) {
        SnailJobTestResult result = scenarioService.executeReconciliationSimulation(faultInjected);
        return Result.success(scenarioService.toTriggerVO("orderReconciliationJob", result));
    }

    /**
     * 触发海量历史数据归档超时看门狗中断与就地降级补偿
     */
    @PostMapping("/timeout-archive")
    public Result<JobTriggerVO> triggerTimeoutArchive(@RequestParam(defaultValue = "archive-batch-2026") String param) {
        SnailJobTestResult result = scenarioService.executeHistoryDataArchive(param);
        return Result.success(scenarioService.toTriggerVO("historyDataArchiveJob", result));
    }

    /**
     * 触发 VIP 专线金丝雀灰度绕过探测任务
     */
    @PostMapping("/canary-probe")
    public Result<JobTriggerVO> triggerCanaryProbe() {
        SnailJobTestResult result = scenarioService.executeCanaryProbe();
        return Result.success(scenarioService.toTriggerVO("vipChannelProbeJob", result));
    }

    /**
     * 触发物流在途轨迹多节点广播分片流式批处理任务
     */
    @PostMapping("/logistics-batch")
    public Result<JobTriggerVO> triggerLogisticsBatch(
            @RequestParam(defaultValue = "0") int shardIndex,
            @RequestParam(defaultValue = "1") int shardTotal,
            @RequestParam(defaultValue = "false") boolean injectFault) {
        SnailJobTestResult result = scenarioService.executeLogisticsBatch(shardIndex, shardTotal, injectFault);
        return Result.success(scenarioService.toTriggerVO("logisticsStatusSyncJob", result));
    }

    /**
     * 触发单租户限定沙箱调度任务
     */
    @PostMapping("/tenant/scoped")
    public Result<JobTriggerVO> triggerTenantScoped(@RequestParam(defaultValue = "tenant-shanghai") String tenantId) {
        SnailJobTestResult result = scenarioService.executeTenantScoped(tenantId);
        return Result.success(scenarioService.toTriggerVO("tenantScopedJob", result));
    }

    /**
     * 触发多租户沙箱隔离批量遍历任务
     */
    @PostMapping("/tenant/iterate")
    public Result<JobTriggerVO> triggerTenantIterate() {
        SnailJobTestResult result = scenarioService.executeTenantBatch();
        return Result.success(scenarioService.toTriggerVO("tenantBatchJob", result));
    }

    /**
     * 触发全局平台忽略租户归档任务
     */
    @PostMapping("/tenant/global")
    public Result<JobTriggerVO> triggerTenantGlobal() {
        SnailJobTestResult result = scenarioService.executeGlobalArchive();
        return Result.success(scenarioService.toTriggerVO("globalArchiveJob", result));
    }
}
