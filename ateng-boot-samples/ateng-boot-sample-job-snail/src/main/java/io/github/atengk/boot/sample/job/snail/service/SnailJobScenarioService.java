package io.github.atengk.boot.sample.job.snail.service;

import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.job.snail.governor.SnailJobRuntimeGovernor;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper.SnailJobTestResult;
import io.github.atengk.boot.sample.job.snail.dto.UserSyncParam;
import io.github.atengk.boot.sample.job.snail.handler.HistoryDataArchiveJobHandler;
import io.github.atengk.boot.sample.job.snail.handler.LogisticsBatchJobHandler;
import io.github.atengk.boot.sample.job.snail.handler.OrderReconciliationJobHandler;
import io.github.atengk.boot.sample.job.snail.vo.JobTriggerVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SnailJob 调度与重试业务场景端到端编排服务
 *
 * <p>为 Controller 接入层与端到端集成测试提供高内聚的业务场景驱动能力。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnailJobScenarioService {

    private final SnailJobTestHelper testHelper;
    private final SnailJobRuntimeGovernor governor;
    private final RetryDemoService retryDemoService;
    private final OrderReconciliationJobHandler orderReconciliationJobHandler;
    private final LogisticsBatchJobHandler logisticsBatchJobHandler;
    private final HistoryDataArchiveJobHandler historyDataArchiveJobHandler;

    /**
     * 执行系统冷启动热点缓存预热与健康巡检任务
     */
    public SnailJobTestResult executeSystemWarmup() {
        log.info("[SnailJobScenarioService] 触发系统预热与健康巡检场景");
        return testHelper.triggerJob("systemWarmupJob", null);
    }

    /**
     * 执行强类型入参校验同步用户任务
     */
    public SnailJobTestResult executeUserSync(String batchNo, Integer batchSize, String departmentId) {
        UserSyncParam param = UserSyncParam.builder()
                .batchNo(batchNo)
                .batchSize(batchSize)
                .departmentId(departmentId)
                .build();
        String json = JsonUtil.toJsonString(param);
        log.info("[SnailJobScenarioService] 触发用户同步场景: {}", json);
        return testHelper.triggerJob("userSyncJob", json);
    }

    /**
     * 执行物流在途轨迹广播分片流式批处理
     */
    public SnailJobTestResult executeLogisticsBatch(int shardIndex, int shardTotal, boolean injectFault) {
        log.info("[SnailJobScenarioService] 触发物流广播分片场景: shard={}/{}, injectFault={}", shardIndex, shardTotal, injectFault);
        if (logisticsBatchJobHandler != null) {
            logisticsBatchJobHandler.setFaultInjected(injectFault);
        }
        return testHelper.triggerJob("logisticsStatusSyncJob", injectFault ? "FAIL" : null, shardIndex, shardTotal);
    }

    /**
     * 执行 Map 切片拆分任务
     */
    public SnailJobTestResult executeMapTask(String taskName, Object param) {
        log.info("[SnailJobScenarioService] 触发 Map 切片拆分场景: taskName={}", taskName);
        return SnailJobTestHelper.triggerMap("billSettlementJob", taskName, param);
    }

    /**
     * 执行 Reduce 规约汇总任务
     */
    public SnailJobTestResult executeReduceTask(Object param) {
        log.info("[SnailJobScenarioService] 触发 Reduce 规约汇总场景");
        return SnailJobTestHelper.triggerReduce("billSettlementJob", param);
    }

    /**
     * 执行 MergeReduce 最终合并规约任务
     */
    public SnailJobTestResult executeMergeReduceTask(Object param) {
        log.info("[SnailJobScenarioService] 触发 MergeReduce 最终合并规约场景");
        return SnailJobTestHelper.triggerMergeReduce("billSettlementJob", param);
    }

    /**
     * 执行多租户隔离批量遍历任务场景
     */
    public SnailJobTestResult executeTenantBatch() {
        log.info("[SnailJobScenarioService] 触发多租户隔离批量遍历场景");
        return testHelper.triggerJob("tenantBatchJob", null);
    }

    /**
     * 执行单租户限定任务场景
     */
    public SnailJobTestResult executeTenantScoped(String tenantId) {
        log.info("[SnailJobScenarioService] 触发单租户限定场景: tenantId={}", tenantId);
        return testHelper.triggerJob("tenantScopedJob", null, 0, 1, tenantId);
    }

    /**
     * 执行全局忽略租户归档任务场景
     */
    public SnailJobTestResult executeGlobalArchive() {
        log.info("[SnailJobScenarioService] 触发全局忽略租户归档场景");
        return testHelper.triggerJob("globalArchiveJob", null);
    }

    /**
     * 执行订单对账差错告警与平账自愈场景
     */
    public SnailJobTestResult executeReconciliationSimulation(boolean injectFault) {
        log.info("[SnailJobScenarioService] 触发订单对账告警平账自愈场景: injectFault={}", injectFault);
        if (orderReconciliationJobHandler != null) {
            orderReconciliationJobHandler.setFaultInjected(injectFault);
        }
        return testHelper.triggerJob("orderReconciliationJob", injectFault ? "FAIL" : "OK");
    }

    /**
     * 执行 VIP 专线金丝雀灰度绕过探测场景
     */
    public SnailJobTestResult executeCanaryProbe() {
        log.info("[SnailJobScenarioService] 触发 VIP 专线金丝雀灰度探测场景");
        return testHelper.triggerJob("vipChannelProbeJob", null);
    }

    /**
     * 执行历史冷数据归档超时看门狗硬中断与就地降级补偿任务
     */
    public SnailJobTestResult executeHistoryDataArchive(String param) {
        log.info("[SnailJobScenarioService] 触发历史数据归档超时硬中断与就地降级补偿场景");
        return testHelper.triggerJob("historyDataArchiveJob", param);
    }

    /**
     * 节点级运行时治理：暂停任务
     */
    public boolean pauseJob(String jobName) {
        log.info("[SnailJobScenarioService] 运行时治理暂停任务: {}", jobName);
        return governor.pause(jobName);
    }

    /**
     * 节点级运行时治理：恢复任务
     */
    public boolean resumeJob(String jobName) {
        log.info("[SnailJobScenarioService] 运行时治理恢复任务: {}", jobName);
        return governor.resume(jobName);
    }

    /**
     * 节点级运行时治理：恢复全部任务
     */
    public void resumeAll() {
        log.info("[SnailJobScenarioService] 运行时治理一键恢复全部任务");
        governor.resumeAll();
    }

    /**
     * 将 SnailJobTestResult 适配转译为对外统一的 JobTriggerVO
     */
    public JobTriggerVO toTriggerVO(String jobName, SnailJobTestResult result) {
        boolean success = result.isSuccess();
        Integer statusCode = success ? 200 : 500;
        String message = "Executed";
        if (result.result() instanceof ExecuteResult er) {
            statusCode = er.getStatus();
            message = er.getMessage() != null ? er.getMessage() : (success ? "Success" : "Failed");
        } else if (result.throwable() != null) {
            message = result.throwable().getMessage();
        }
        return JobTriggerVO.builder()
                .jobName(jobName)
                .status(success ? "SUCCESS" : "FAILURE")
                .statusCode(statusCode)
                .traceId(result.traceId())
                .elapsedMs(result.durationMs())
                .message(message)
                .build();
    }

    /**
     * 获取当前节点调度与重试资产运行态汇总
     */
    public Map<String, Object> getStatusSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("registeredJobs", testHelper.getRegisteredJobNames().size());
        summary.put("registeredRetries", testHelper.getRegisteredRetryScenes().size());
        summary.put("pausedJobs", governor.getPausedCount());
        summary.put("retryAttempts", retryDemoService.getAttemptCount());
        return summary;
    }
}
