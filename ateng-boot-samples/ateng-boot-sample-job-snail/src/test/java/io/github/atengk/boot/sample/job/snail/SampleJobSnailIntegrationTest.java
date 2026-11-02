package io.github.atengk.boot.sample.job.snail;

import com.aizuda.snailjob.common.core.enums.StatusEnum;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.job.snail.actuator.SnailJobEndpoint;
import io.github.atengk.boot.job.snail.actuator.SnailJobHealthIndicator;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import io.github.atengk.boot.job.snail.drain.GracefulDrainStateMachine;
import io.github.atengk.boot.job.snail.governor.SnailJobRuntimeGovernor;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper.SnailJobTestResult;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper.SnailRetryTestResult;
import io.github.atengk.boot.job.snail.metrics.SnailJobMetricsBinder;
import io.github.atengk.boot.sample.job.snail.handler.AlertDemoJobHandler;
import io.github.atengk.boot.sample.job.snail.handler.MapReduceDemoJobHandler;
import io.github.atengk.boot.sample.job.snail.handler.TimeoutFallbackDemoJobHandler;
import io.github.atengk.boot.sample.job.snail.interceptor.SampleAuditJobInterceptor;
import io.github.atengk.boot.sample.job.snail.service.RetryDemoService;
import io.github.atengk.boot.sample.job.snail.service.SnailJobScenarioService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SnailJob 调度与重试双擎场景工程端到端自验集成测试 (100% 离线脱网沙箱与零 Controller 不变量)
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootTest(classes = SampleJobSnailApplication.class)
class SampleJobSnailIntegrationTest {

    @Autowired
    private SnailJobTestHelper testHelper;

    @Autowired
    private SnailJobScenarioService scenarioService;

    @Autowired
    private RetryDemoService retryDemoService;

    @Autowired
    private AlertDemoJobHandler alertDemoJobHandler;

    @Autowired
    private MapReduceDemoJobHandler mapReduceDemoJobHandler;

    @Autowired
    private TimeoutFallbackDemoJobHandler timeoutFallbackDemoJobHandler;

    @Autowired
    private SampleAuditJobInterceptor auditJobInterceptor;

    @Autowired
    private SnailJobEndpoint snailJobEndpoint;

    @Autowired
    private SnailJobHealthIndicator healthIndicator;

    @Autowired
    private GracefulDrainStateMachine drainStateMachine;

    @Autowired
    private SnailJobRuntimeGovernor runtimeGovernor;

    @Autowired
    private SnailJobMetricsBinder metricsBinder;

    @Autowired
    private MeterRegistry meterRegistry;

    @AfterEach
    void tearDown() {
        SnailJobContextHolder.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
        alertDemoJobHandler.setFaultInjected(false);
        retryDemoService.resetAttemptCount();
        mapReduceDemoJobHandler.reset();
        timeoutFallbackDemoJobHandler.reset();
        auditJobInterceptor.reset();
        runtimeGovernor.resumeAll();
    }

    @Test
    @DisplayName("1. 基础极简问候任务脱网触发实测 (带 MDC traceId 现场自洽)")
    void testSimpleGreetingJob() {
        SnailJobTestResult testResult = testHelper.triggerJob("simpleGreetingJob", null);

        assertThat(testResult).isNotNull();
        assertThat(testResult.isSuccess()).isTrue();
        assertThat(testResult.result()).isInstanceOf(ExecuteResult.class);
        ExecuteResult result = (ExecuteResult) testResult.result();
        assertThat(result.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(result.getResult().toString()).contains("Greeting executed with traceId:");
        assertThat(SnailJobContextHolder.getContext()).isNull();
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("2. 超时守卫增强任务实测")
    void testTimeoutGuardedJob() {
        SnailJobTestResult testResult = testHelper.triggerJob("timeoutGuardedJob", null);

        assertThat(testResult).isNotNull();
        assertThat(testResult.isSuccess()).isTrue();
        ExecuteResult result = (ExecuteResult) testResult.result();
        assertThat(result.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(result.getResult().toString()).isEqualTo("Timeout guarded job succeeded");
    }

    @Test
    @DisplayName("3. 强类型入参校验任务实测 - 正常参数通过")
    void testUserSyncParamValidationSuccess() {
        String jsonParam = "{\"batchNo\":\"BATCH-20261031\",\"batchSize\":100,\"departmentId\":\"DEPT-01\"}";
        SnailJobTestResult testResult = testHelper.triggerJob("userSyncJob", jsonParam);

        assertThat(testResult).isNotNull();
        assertThat(testResult.isSuccess()).isTrue();
        ExecuteResult result = (ExecuteResult) testResult.result();
        assertThat(result.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(result.getResult().toString()).isEqualTo("Sync completed for batch BATCH-20261031");
    }

    @Test
    @DisplayName("4. 强类型入参校验任务实测 - 校验失败 Fail-Fast")
    void testUserSyncParamValidationFailFast() {
        // 批次为空，batchSize 小于 1
        String invalidJson = "{\"batchNo\":\"\",\"batchSize\":0}";
        SnailJobTestResult testResult = testHelper.triggerJob("userSyncJob", invalidJson);

        assertThat(testResult).isNotNull();
        assertThat(testResult.isSuccess()).isFalse();
        assertThat(testResult.throwable()).isNotNull();
        assertThat(testResult.throwable().getMessage()).containsAnyOf("不能为空", "大于0", "不合法");
    }

    @Test
    @DisplayName("5. 分布式广播分片流式批处理模板实测 (ShardingContext 过滤与统计)")
    void testShardingBatchJob() {
        // 模拟第 0 个分片 (共 2 个分片)
        SnailJobTestResult r0 = testHelper.triggerJob("shardingBatchJob", null, 0, 2);
        assertThat(r0.isSuccess()).isTrue();
        ExecuteResult result0 = (ExecuteResult) r0.result();
        assertThat(result0.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(result0.getResult().toString()).contains("Sharding batch completed");

        // 模拟第 1 个分片 (共 2 个分片)
        SnailJobTestResult r1 = testHelper.triggerJob("shardingBatchJob", null, 1, 2);
        assertThat(r1.isSuccess()).isTrue();
        ExecuteResult result1 = (ExecuteResult) r1.result();
        assertThat(result1.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(result1.getResult().toString()).contains("Sharding batch completed");
    }

    @Test
    @DisplayName("6. 多租户隔离调度任务三模实测 (IGNORE / PARAM / ITERATE)")
    void testTenantJobs() {
        // 模式一：全局忽略多租户
        SnailJobTestResult tr1 = testHelper.triggerJob("globalArchiveJob", null);
        assertThat(tr1.isSuccess()).isTrue();
        ExecuteResult r1 = (ExecuteResult) tr1.result();
        assertThat(r1.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(r1.getResult().toString()).contains("ignoreTenant=true");

        // 模式二：单租户绑定
        SnailJobTestResult tr2 = testHelper.triggerJob("tenantScopedJob", null, 0, 1, "TENANT-999");
        assertThat(tr2.isSuccess()).isTrue();
        ExecuteResult r2 = (ExecuteResult) tr2.result();
        assertThat(r2.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(r2.getResult().toString()).contains("tenant=TENANT-999");

        // 模式三：租户批量遍历执行
        SnailJobTestResult tr3 = testHelper.triggerJob("tenantBatchJob", null);
        assertThat(tr3.isSuccess()).isTrue();
        ExecuteResult r3 = (ExecuteResult) tr3.result();
        assertThat(r3.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(r3.getResult().toString()).contains("Tenant batch finished: total=2, success=2");
    }

    @Test
    @DisplayName("7. 分布式协同重试实测 (声明式注解 / 编程式闭包 / 手动上报)")
    void testRetryDemoService() {
        // 7.1 离线反射驱动声明式重试成功
        SnailRetryTestResult res1 = testHelper.triggerRetry("orderPayRetry", "ORD-1001", 88.0);
        assertThat(res1.isSuccess()).isTrue();
        assertThat(res1.result().toString()).contains("PAY_SUCCESS:ORD-1001");

        // 7.2 模拟重试抛出异常
        SnailRetryTestResult failRes = testHelper.triggerRetry("orderPayRetry", "FAIL_ORDER", 88.0);
        assertThat(failRes.isSuccess()).isFalse();
        assertThat(failRes.throwable()).isNotNull();
        assertThat(failRes.throwable().getMessage()).contains("模拟支付网关网络超时");

        // 7.3 编程式重试闭包
        String res2 = retryDemoService.executeWithProgrammaticRetry("ORD-2002");
        assertThat(res2).isEqualTo("SUCCESS:ORD-2002");

        // 7.4 手动上报（脱网环境保底返回 true）
        boolean reported = retryDemoService.reportFailedOrder("ORD-3003");
        assertThat(reported).isTrue();
    }

    @Test
    @DisplayName("8. 告警防风暴聚合与自愈状态机端到端实测")
    void testAlertDemoJobStateMachine() {
        // 第一次失败：触发首发告警
        alertDemoJobHandler.setFaultInjected(true);
        SnailJobTestResult fail1 = testHelper.triggerJob("alertDemoJob", "FAIL");
        assertThat(fail1.isSuccess()).isTrue();
        ExecuteResult er1 = (ExecuteResult) fail1.result();
        assertThat(er1.getStatus()).isEqualTo(StatusEnum.NO.getStatus());

        // 第二次失败：进入静默窗口抑制
        ExecuteResult er2 = (ExecuteResult) testHelper.triggerJob("alertDemoJob", "FAIL").result();
        assertThat(er2.getStatus()).isEqualTo(StatusEnum.NO.getStatus());

        // 故障恢复：记录自愈现场并上报恢复
        alertDemoJobHandler.setFaultInjected(false);
        ExecuteResult recovery = (ExecuteResult) testHelper.triggerJob("alertDemoJob", "OK").result();
        assertThat(recovery.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
    }

    @Test
    @DisplayName("9. MapReduce 切片拆分与规约汇总任务端到端实测")
    void testMapReduceJob() {
        // 9.1 触发 Map 切片拆分任务
        SnailJobTestResult mapResult = SnailJobTestHelper.triggerMap("mapReduceDemoJob", "splitTask", "{\"dataRange\":\"1-1000\"}");
        assertThat(mapResult).isNotNull();
        assertThat(mapResult.isSuccess()).isTrue();
        ExecuteResult erMap = (ExecuteResult) mapResult.result();
        assertThat(erMap.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(erMap.getResult().toString()).contains("3 subtasks");
        assertThat(mapReduceDemoJobHandler.getMapExecutionCount()).isEqualTo(1);

        // 9.2 触发 Reduce 规约汇总任务
        SnailJobTestResult reduceResult = SnailJobTestHelper.triggerReduce("mapReduceDemoJob", "{\"summaryId\":\"SUM-001\"}");
        assertThat(reduceResult).isNotNull();
        assertThat(reduceResult.isSuccess()).isTrue();
        ExecuteResult erReduce = (ExecuteResult) reduceResult.result();
        assertThat(erReduce.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(erReduce.getResult().toString()).contains("Reduce summary completed");
        assertThat(mapReduceDemoJobHandler.getReduceExecutionCount()).isEqualTo(1);

        // 9.3 触发 MergeReduce 最终合并规约任务
        SnailJobTestResult mergeResult = SnailJobTestHelper.triggerMergeReduce("mapReduceDemoJob", "{\"mergeBatch\":\"MB-999\"}");
        assertThat(mergeResult).isNotNull();
        assertThat(mergeResult.isSuccess()).isTrue();
        ExecuteResult erMerge = (ExecuteResult) mergeResult.result();
        assertThat(erMerge.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(erMerge.getResult().toString()).contains("MergeReduce final aggregation completed");
        assertThat(mapReduceDemoJobHandler.getMergeReduceExecutionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("10. 调度生命周期责任链与金丝雀灰度拦截器实测")
    void testLifecycleInterceptorAndCanaryBypass() {
        // 10.1 正常任务放行并触发三阶拦截生命周期
        auditJobInterceptor.reset();
        SnailJobTestResult normalResult = testHelper.triggerJob("simpleGreetingJob", null);
        assertThat(normalResult.isSuccess()).isTrue();
        assertThat(auditJobInterceptor.getPreHandleCount()).isGreaterThanOrEqualTo(1);
        assertThat(auditJobInterceptor.getPostHandleCount()).isGreaterThanOrEqualTo(1);
        assertThat(auditJobInterceptor.getAfterCompletionCount()).isGreaterThanOrEqualTo(1);
        assertThat(auditJobInterceptor.getCanaryBypassCount()).isEqualTo(0);
        assertThat(auditJobInterceptor.getExecutionDuration("simpleGreetingJob")).isNotNull();

        // 10.2 金丝雀灰度任务命中绕过规则，优雅跳过调度
        SnailJobTestResult canaryResult = testHelper.triggerJob("canaryBypassJob", null);
        assertThat(canaryResult.isSuccess()).isTrue();
        ExecuteResult canaryEr = (ExecuteResult) canaryResult.result();
        assertThat(canaryEr.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(canaryEr.getMessage()).contains("[INTERCEPTED] 任务调度被拦截器跳过执行: SampleAuditJobInterceptor");
        assertThat(auditJobInterceptor.getCanaryBypassCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("11. 本地硬看门狗超时中断与就地降级补偿实测")
    void testTimeoutFallbackCompensation() {
        timeoutFallbackDemoJobHandler.reset();
        SnailJobTestResult testResult = testHelper.triggerJob("timeoutFallbackJob", "PARAM-TIMEOUT-01");

        assertThat(testResult).isNotNull();
        assertThat(testResult.isSuccess()).isTrue();
        ExecuteResult er = (ExecuteResult) testResult.result();
        assertThat(er.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(er.getResult().toString()).contains("Fallback compensation executed successfully: PARAM-TIMEOUT-01");
        assertThat(timeoutFallbackDemoJobHandler.isFallbackExecuted()).isTrue();
        assertThat(timeoutFallbackDemoJobHandler.getExecutionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("12. 节点级运行时动态治理总线 (暂停/冒号分级继承/恢复/全量恢复) 实测")
    void testRuntimeGovernorPauseAndResume() {
        // 12.1 暂停普通任务
        runtimeGovernor.pause("simpleGreetingJob");
        assertThat(runtimeGovernor.isPaused("simpleGreetingJob")).isTrue();
        assertThat(runtimeGovernor.getPausedCount()).isEqualTo(1);

        SnailJobTestResult pausedResult = testHelper.triggerJob("simpleGreetingJob", null);
        assertThat(pausedResult.isSuccess()).isTrue();
        ExecuteResult er1 = (ExecuteResult) pausedResult.result();
        assertThat(er1.getMessage()).contains("[SKIPPED] 任务调度已被节点级运行时治理总线暂停: simpleGreetingJob");

        // 12.2 切片任务自动继承根任务暂停状态 (冒号分级感知)
        runtimeGovernor.pause("mapReduceDemoJob");
        assertThat(runtimeGovernor.isPaused("mapReduceDemoJob:splitTask")).isTrue();

        SnailJobTestResult slicePausedResult = SnailJobTestHelper.triggerMap("mapReduceDemoJob", "splitTask", "test");
        assertThat(slicePausedResult.isSuccess()).isTrue();
        ExecuteResult erSlice = (ExecuteResult) slicePausedResult.result();
        assertThat(erSlice.getMessage()).contains("[SKIPPED] 任务调度已被节点级运行时治理总线暂停: mapReduceDemoJob:splitTask");

        // 12.3 恢复指定任务
        runtimeGovernor.resume("simpleGreetingJob");
        assertThat(runtimeGovernor.isPaused("simpleGreetingJob")).isFalse();

        SnailJobTestResult resumedResult = testHelper.triggerJob("simpleGreetingJob", null);
        assertThat(resumedResult.isSuccess()).isTrue();
        ExecuteResult erResumed = (ExecuteResult) resumedResult.result();
        assertThat(erResumed.getResult().toString()).contains("Greeting executed with traceId:");

        // 12.4 全量恢复 (resumeAll)
        runtimeGovernor.resumeAll();
        assertThat(runtimeGovernor.getPausedCount()).isEqualTo(0);
        assertThat(runtimeGovernor.isPaused("mapReduceDemoJob")).isFalse();
    }

    @Test
    @DisplayName("13. SnailJobScenarioService 专职场景服务编排与端到端实测")
    void testScenarioServiceEndToEnd() {
        // 13.1 资产汇总状态
        Map<String, Object> summary = scenarioService.getStatusSummary();
        assertThat(summary).containsKeys("registeredJobs", "registeredRetries", "pausedJobs", "retryAttempts");
        assertThat((Integer) summary.get("registeredJobs")).isGreaterThan(0);

        // 13.2 极简问候
        SnailJobTestResult r1 = scenarioService.executeSimpleGreeting();
        assertThat(r1.isSuccess()).isTrue();

        // 13.3 强类型用户同步
        SnailJobTestResult r2 = scenarioService.executeUserSync("BATCH-20261102", 50, "DEPT-DEV");
        assertThat(r2.isSuccess()).isTrue();

        // 13.4 分片批处理
        SnailJobTestResult r3 = scenarioService.executeShardingBatch(0, 1);
        assertThat(r3.isSuccess()).isTrue();

        // 13.5 Map 切片与规约聚合场景编排驱动
        SnailJobTestResult rMap = scenarioService.executeMapTask("splitTask", "{\"dataRange\":\"1-1000\"}");
        assertThat(rMap.isSuccess()).isTrue();
        SnailJobTestResult rReduce = scenarioService.executeReduceTask("{\"summaryId\":\"SUM-002\"}");
        assertThat(rReduce.isSuccess()).isTrue();
        SnailJobTestResult rMerge = scenarioService.executeMergeReduceTask("{\"mergeBatch\":\"MB-002\"}");
        assertThat(rMerge.isSuccess()).isTrue();

        // 13.6 超时看门狗与就地降级补偿场景编排驱动
        SnailJobTestResult rTimeout = scenarioService.executeTimeoutFallback("PARAM-SCENARIO-01");
        assertThat(rTimeout.isSuccess()).isTrue();

        // 13.7 多租户批量遍历执行场景编排
        SnailJobTestResult rTenant = scenarioService.executeTenantBatch();
        assertThat(rTenant.isSuccess()).isTrue();

        // 13.8 金丝雀灰度绕过场景编排
        SnailJobTestResult rCanary = scenarioService.executeCanaryBypass();
        assertThat(rCanary.isSuccess()).isTrue();

        // 13.9 运行时治理联动
        assertThat(scenarioService.pauseJob("simpleGreetingJob")).isTrue();
        assertThat(scenarioService.getStatusSummary().get("pausedJobs")).isEqualTo(1);
        assertThat(scenarioService.resumeJob("simpleGreetingJob")).isTrue();
        assertThat(scenarioService.getStatusSummary().get("pausedJobs")).isEqualTo(0);
    }

    @Test
    @DisplayName("14. Actuator 运维管控与健康探针端到端实测")
    void testActuatorEndpoints() {
        // 14.1 SnailJob 健康探针
        Health health = healthIndicator.health();
        assertThat(health).isNotNull();
        assertThat(health.getDetails()).containsKey("server");

        // 14.2 SnailJob 运维端点概览
        Map<String, Object> overview = snailJobEndpoint.listJobs();
        assertThat(overview).containsKeys("server", "enabled", "draining", "inFlightJobs", "inFlightRetries", "pausedJobs", "jobCount");
        assertThat(overview.get("jobCount")).isNotNull();

        // 14.3 平滑排空状态机未处于排空中
        assertThat(drainStateMachine.isDraining()).isFalse();

        // 14.4 Actuator 运行时治理写入操作 (pause / resume)
        Map<String, Object> pauseRes = snailJobEndpoint.manageGovernor("pause", "simpleGreetingJob");
        assertThat(pauseRes.get("success")).isEqualTo(true);
        assertThat(pauseRes.get("paused")).isEqualTo(true);
        assertThat(runtimeGovernor.isPaused("simpleGreetingJob")).isTrue();

        Map<String, Object> resumeRes = snailJobEndpoint.manageGovernor("resume", "simpleGreetingJob");
        assertThat(resumeRes.get("success")).isEqualTo(true);
        assertThat(resumeRes.get("paused")).isEqualTo(false);
        assertThat(runtimeGovernor.isPaused("simpleGreetingJob")).isFalse();
    }
}
