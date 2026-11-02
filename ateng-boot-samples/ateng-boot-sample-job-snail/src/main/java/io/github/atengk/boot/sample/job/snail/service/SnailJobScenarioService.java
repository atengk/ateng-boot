package io.github.atengk.boot.sample.job.snail.service;

import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.job.snail.governor.SnailJobRuntimeGovernor;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper.SnailJobTestResult;
import io.github.atengk.boot.sample.job.snail.dto.UserSyncParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SnailJob 调度与重试业务场景端到端编排服务
 *
 * <p>遵循非 Web 中间件底座“零 Controller 不变量”，将所有任务调度触发、分片处理、
 * MapReduce 切片、超时降级与运行时动态治理场景收敛于专职服务中，
 * 供集成测试与离线自验端到端驱动。</p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnailJobScenarioService {

    private final SnailJobTestHelper testHelper;
    private final SnailJobRuntimeGovernor governor;
    private final RetryDemoService retryDemoService;

    /**
     * 执行极简问候任务并返回执行摘要
     *
     * @return 执行诊断结果
     */
    public SnailJobTestResult executeSimpleGreeting() {
        log.info("[SnailJobScenarioService] 触发极简问候场景");
        return testHelper.triggerJob("simpleGreetingJob", null);
    }

    /**
     * 执行强类型入参校验同步用户任务
     *
     * @param batchNo      批次编号
     * @param batchSize    每批条数
     * @param departmentId 部门编号
     * @return 执行诊断结果
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
     * 执行分布式广播分片流式批处理
     *
     * @param shardIndex 当前分片索引
     * @param shardTotal 分片总数
     * @return 执行诊断结果
     */
    public SnailJobTestResult executeShardingBatch(int shardIndex, int shardTotal) {
        log.info("[SnailJobScenarioService] 触发广播分片场景: shard={}/{}", shardIndex, shardTotal);
        return testHelper.triggerJob("shardingBatchJob", null, shardIndex, shardTotal);
    }

    /**
     * 执行 Map 切片拆分任务
     *
     * @param taskName 切片子任务名称
     * @param param    切片入参
     * @return 执行诊断结果
     */
    public SnailJobTestResult executeMapTask(String taskName, Object param) {
        log.info("[SnailJobScenarioService] 触发 Map 切片拆分场景: taskName={}", taskName);
        return SnailJobTestHelper.triggerMap("mapReduceDemoJob", taskName, param);
    }

    /**
     * 执行 Reduce 规约汇总任务
     *
     * @param param 规约入参
     * @return 执行诊断结果
     */
    public SnailJobTestResult executeReduceTask(Object param) {
        log.info("[SnailJobScenarioService] 触发 Reduce 规约汇总场景");
        return SnailJobTestHelper.triggerReduce("mapReduceDemoJob", param);
    }

    /**
     * 执行超时看门狗硬中断与就地降级补偿任务
     *
     * @param param 任务入参
     * @return 执行诊断结果
     */
    public SnailJobTestResult executeTimeoutFallback(String param) {
        log.info("[SnailJobScenarioService] 触发任务超时硬中断与就地降级补偿场景");
        return testHelper.triggerJob("timeoutFallbackJob", param);
    }

    /**
     * 节点级运行时治理：暂停任务
     *
     * @param jobName 任务名称
     * @return 是否成功暂停
     */
    public boolean pauseJob(String jobName) {
        log.info("[SnailJobScenarioService] 运行时治理暂停任务: {}", jobName);
        return governor.pause(jobName);
    }

    /**
     * 节点级运行时治理：恢复任务
     *
     * @param jobName 任务名称
     * @return 是否成功恢复
     */
    public boolean resumeJob(String jobName) {
        log.info("[SnailJobScenarioService] 运行时治理恢复任务: {}", jobName);
        return governor.resume(jobName);
    }

    /**
     * 获取当前节点调度与重试资产运行态汇总
     *
     * @return 运行态概览
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
