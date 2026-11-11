package io.github.atengk.boot.sample.job.snail;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import io.github.atengk.boot.job.snail.governor.SnailJobRuntimeGovernor;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper;
import io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate;
import io.github.atengk.boot.sample.job.snail.config.SampleJobSnailTestConfig;
import io.github.atengk.boot.sample.job.snail.handler.HistoryDataArchiveJobHandler;
import io.github.atengk.boot.sample.job.snail.handler.LogisticsBatchJobHandler;
import io.github.atengk.boot.sample.job.snail.handler.OrderReconciliationJobHandler;
import io.github.atengk.boot.sample.job.snail.service.RetryDemoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SnailJob 样例工程 5 大专职领域 Controller 全场景 MockMvc 自动化回归测试
 *
 * <p>100% 覆盖 5 大 Controller 全部 HTTP 端点，断言 HTTP 200 响应、
 * JSR-303 参数校验快速失败、强类型 {@code Result<T>} 契约与零实体泄露。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@SpringBootTest(classes = SampleJobSnailApplication.class)
@AutoConfigureMockMvc
@Import(SampleJobSnailTestConfig.class)
class SampleJobSnailWebControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SnailJobRuntimeGovernor runtimeGovernor;

    @Autowired
    private RetryDemoService retryDemoService;

    @Autowired
    private OrderReconciliationJobHandler reconciliationJobHandler;

    @Autowired
    private LogisticsBatchJobHandler logisticsBatchJobHandler;

    @Autowired
    private HistoryDataArchiveJobHandler archiveJobHandler;

    @Autowired
    private SnailJobAdminTemplate adminTemplate;

    @AfterEach
    void tearDown() {
        SnailJobContextHolder.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
        runtimeGovernor.resumeAll();
        retryDemoService.resetAttemptCount();
        reconciliationJobHandler.setFaultInjected(false);
        logisticsBatchJobHandler.setFaultInjected(false);
        logisticsBatchJobHandler.clearDeadLetterBatches();
        archiveJobHandler.reset();
        SnailJobTestHelper.clear();
    }

    // =========================================================================
    // 1. SnailJobTriggerController 测试套件
    // =========================================================================

    @Test
    @DisplayName("1.1 触发系统冷启动热点预热与健康巡检任务")
    void testTriggerWarmup() throws Exception {
        mockMvc.perform(post("/sample/job-snail/trigger/warmup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.jobName").value("systemWarmupJob"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("1.2 触发强类型入参用户同步任务 - 校验通过")
    void testTriggerUserSyncSuccess() throws Exception {
        String json = "{\"batchNo\":\"BATCH-20261111\",\"batchSize\":100,\"departmentId\":\"DEPT-SALES\"}";
        mockMvc.perform(post("/sample/job-snail/trigger/user-sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.jobName").value("userSyncJob"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("1.3 触发强类型入参用户同步任务 - JSR-303 快速失败")
    void testTriggerUserSyncValidationFailFast() throws Exception {
        String invalidJson = "{\"batchNo\":\"\",\"batchSize\":0}";
        mockMvc.perform(post("/sample/job-snail/trigger/user-sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", not(200)));
    }

    @Test
    @DisplayName("1.4 触发跨商户账单 MapReduce 切片拆分任务 (含 JSR-303 快速失败)")
    void testTriggerBillSettlementMap() throws Exception {
        String json = "{\"dataRange\":\"1-500\",\"billingCycle\":\"2026-11\"}";
        mockMvc.perform(post("/sample/job-snail/trigger/bill-settlement/map")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.jobName").value("billSettlementJob:splitTask"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        // 越界参数 JSR-303 拦截
        mockMvc.perform(post("/sample/job-snail/trigger/bill-settlement/map")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataRange\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", not(200)));
    }

    @Test
    @DisplayName("1.5 触发跨商户账单 MapReduce 规约汇总任务")
    void testTriggerBillSettlementReduce() throws Exception {
        mockMvc.perform(post("/sample/job-snail/trigger/bill-settlement/reduce")
                        .param("param", "summary-batch-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.jobName").value("billSettlementJob:reduce"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("1.6 触发跨商户账单 MapReduce 全局合并规约任务")
    void testTriggerBillSettlementMergeReduce() throws Exception {
        mockMvc.perform(post("/sample/job-snail/trigger/bill-settlement/merge-reduce")
                        .param("param", "global-merge-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.jobName").value("billSettlementJob:mergeReduce"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("1.7 触发订单对账差错告警与平账自愈演练")
    void testTriggerReconciliation() throws Exception {
        // 第一次：成功分支平账
        mockMvc.perform(post("/sample/job-snail/trigger/reconciliation")
                        .param("faultInjected", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.statusCode").value(1));

        // 第二次：注入故障差错分支 (真实返回 FAILURE / 0)
        mockMvc.perform(post("/sample/job-snail/trigger/reconciliation")
                        .param("faultInjected", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.jobName").value("orderReconciliationJob"))
                .andExpect(jsonPath("$.data.status").value("FAILURE"))
                .andExpect(jsonPath("$.data.statusCode").value(0));

        // 第三次：无故障恢复（验证无残留脏状态影响）
        mockMvc.perform(post("/sample/job-snail/trigger/reconciliation")
                        .param("faultInjected", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("1.8 触发历史数据归档超时看门狗中断与就地降级补偿")
    void testTriggerTimeoutArchive() throws Exception {
        mockMvc.perform(post("/sample/job-snail/trigger/timeout-archive")
                        .param("param", "archive-202611"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.jobName").value("historyDataArchiveJob"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("1.9 触发 VIP 专线金丝雀灰度绕过探测任务")
    void testTriggerCanaryProbe() throws Exception {
        mockMvc.perform(post("/sample/job-snail/trigger/canary-probe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.jobName").value("vipChannelProbeJob"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("1.10 触发物流广播分片流式批处理任务 (含参数越界快速失败)")
    void testTriggerLogisticsBatch() throws Exception {
        mockMvc.perform(post("/sample/job-snail/trigger/logistics-batch")
                        .param("shardIndex", "0")
                        .param("shardTotal", "2")
                        .param("injectFault", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.jobName").value("logisticsStatusSyncJob"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        // 分片总数非法 (< 1) 快速失败
        mockMvc.perform(post("/sample/job-snail/trigger/logistics-batch")
                        .param("shardIndex", "0")
                        .param("shardTotal", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", not(200)));
    }

    @Test
    @DisplayName("1.11 触发多租户全模态隔离任务 (scoped, iterate, global)")
    void testTriggerTenantModes() throws Exception {
        // 单租户绑定
        mockMvc.perform(post("/sample/job-snail/trigger/tenant/scoped")
                        .param("tenantId", "tenant-hangzhou"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        // 租户沙箱遍历
        mockMvc.perform(post("/sample/job-snail/trigger/tenant/iterate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        // 全局忽略租户
        mockMvc.perform(post("/sample/job-snail/trigger/tenant/global"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    // =========================================================================
    // 2. SnailJobRetryController 测试套件
    // =========================================================================

    @Test
    @DisplayName("2.1 触发声明式 @AtengSnailRetry 订单支付重试 (正常/超时转重试/JSR-303 拦截)")
    void testRetryPayDeclarative() throws Exception {
        String json = "{\"orderId\":\"ORD-PAY-1001\",\"amount\":99.9}";
        mockMvc.perform(post("/sample/job-snail/retry/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value("ORD-PAY-1001"))
                .andExpect(jsonPath("$.data.mode").value("DECLARATIVE"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        // 模拟支付网关超时，触发异常捕获并进入重试管道
        mockMvc.perform(post("/sample/job-snail/retry/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"FAIL_ORDER\",\"amount\":88.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value("FAIL_ORDER"))
                .andExpect(jsonPath("$.data.status").value("RETRY_TRIGGERED"));

        // 金额越界与订单号缺失 JSR-303 快速失败
        mockMvc.perform(post("/sample/job-snail/retry/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"\",\"amount\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", not(200)));
    }

    @Test
    @DisplayName("2.2 触发 SnailRetryTemplate 编程式幂等重试闭包")
    void testRetryPayProgrammatic() throws Exception {
        mockMvc.perform(post("/sample/job-snail/retry/pay/programmatic")
                        .param("orderId", "ORD-PROG-2002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value("ORD-PROG-2002"))
                .andExpect(jsonPath("$.data.mode").value("PROGRAMMATIC"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("2.3 触发失败手动上报门面")
    void testRetryPayManualReport() throws Exception {
        mockMvc.perform(post("/sample/job-snail/retry/pay/manual-report")
                        .param("orderId", "ORD-MANUAL-3003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value("ORD-MANUAL-3003"))
                .andExpect(jsonPath("$.data.mode").value("MANUAL_REPORT"));
    }

    @Test
    @DisplayName("2.4 查询重试统计状态")
    void testGetRetryStats() throws Exception {
        mockMvc.perform(get("/sample/job-snail/retry/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.scene").value("orderPayRetry"))
                .andExpect(jsonPath("$.data.attemptCount").isNumber());
    }

    // =========================================================================
    // 3. SnailJobGovernorController 测试套件
    // =========================================================================

    @Test
    @DisplayName("3.1 节点级运行时动态治理全流程 (pause/resume/resume-all/paused-list)")
    void testGovernorLifecycle() throws Exception {
        // 1. 暂停指定任务
        mockMvc.perform(post("/sample/job-snail/governor/pause/systemWarmupJob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.targetJob").value("systemWarmupJob"))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.pausedCount").value(1));

        // 2. 查询暂停清单
        mockMvc.perform(get("/sample/job-snail/governor/paused-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.pausedCount").value(1))
                .andExpect(jsonPath("$.data.pausedJobs", hasItem("systemWarmupJob")));

        // 3. 恢复指定任务
        mockMvc.perform(post("/sample/job-snail/governor/resume/systemWarmupJob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.pausedCount").value(0));

        // 4. 一键恢复全部
        runtimeGovernor.pause("billSettlementJob");
        mockMvc.perform(post("/sample/job-snail/governor/resume-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.pausedCount").value(0));
    }

    // =========================================================================
    // 4. SnailJobAdminController 测试套件
    // =========================================================================

    @Test
    @DisplayName("4.1 调度中心任务管理 OpenAPI (新增/更新/查询/即时触发/删除/JSR-303 防卫)")
    void testAdminJobOperations() throws Exception {
        // 新增任务
        String json = "{\"jobName\":\"orderSyncJob\",\"executorInfo\":\"syncHandler\",\"triggerType\":1,\"triggerInterval\":\"0/30 * * * * ?\",\"description\":\"test job\"}";
        mockMvc.perform(post("/sample/job-snail/admin/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(1001));

        // 更新任务 (必须携带 id)
        String updateJson = "{\"id\":1001,\"jobName\":\"orderSyncJob\",\"executorInfo\":\"syncHandler\",\"triggerType\":1,\"triggerInterval\":\"0/45 * * * * ?\",\"routeKey\":1,\"description\":\"updated job\"}";
        mockMvc.perform(put("/sample/job-snail/admin/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 新增任务 JSR-303 校验失败 & 更新缺 id 快速失败
        mockMvc.perform(post("/sample/job-snail/admin/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobName\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", not(200)));

        mockMvc.perform(put("/sample/job-snail/admin/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", not(200)));

        // 非法路径参数 jobId=0 快速失败
        mockMvc.perform(get("/sample/job-snail/admin/jobs/0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", not(200)));

        // 查询任务详情
        mockMvc.perform(get("/sample/job-snail/admin/jobs/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.jobName").value("mockSystemWarmupJob"));

        // 即时触发任务
        mockMvc.perform(post("/sample/job-snail/admin/jobs/1001/trigger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 删除任务
        mockMvc.perform(delete("/sample/job-snail/admin/jobs/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("4.2 调度中心工作流 DAG OpenAPI (触发/启停/查询批次/删除)")
    void testAdminWorkflowOperations() throws Exception {
        // 即时触发工作流
        mockMvc.perform(post("/sample/job-snail/admin/workflows/2001/trigger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 启用工作流
        mockMvc.perform(post("/sample/job-snail/admin/workflows/2001/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 停用工作流
        mockMvc.perform(post("/sample/job-snail/admin/workflows/2001/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 查询工作流批次详情
        mockMvc.perform(get("/sample/job-snail/admin/workflows/batch/2001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(2001));

        // 删除工作流
        mockMvc.perform(delete("/sample/job-snail/admin/workflows/2001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("4.4 调度中心 OpenAPI 上游返回 null/false 时拒绝兜底伪造并 Fail-Fast")
    void testAdminUpstreamFailureDoesNotForgeSuccess() throws Exception {
        if (!Mockito.mockingDetails(adminTemplate).isMock()) {
            return;
        }
        try {
            Mockito.doReturn(false).when(adminTemplate).triggerJob(9999L);
            Mockito.doReturn(null).when(adminTemplate).getJobDetail(9999L);

            mockMvc.perform(post("/sample/job-snail/admin/jobs/9999/trigger"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code", not(200)));

            mockMvc.perform(get("/sample/job-snail/admin/jobs/9999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code", not(200)));
        } finally {
            Mockito.doReturn(true).when(adminTemplate).triggerJob(9999L);
        }
    }

    @Test
    @DisplayName("4.3 调度中心重试任务运维 OpenAPI (查询/触发/更新状态)")
    void testAdminRetryOperations() throws Exception {
        // 查询重试任务
        mockMvc.perform(get("/sample/job-snail/admin/retry/888"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sceneName").value("orderPayRetry"));

        // 运维触发重试
        mockMvc.perform(post("/sample/job-snail/admin/retry/888/trigger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 更新重试状态
        mockMvc.perform(post("/sample/job-snail/admin/retry/888/status")
                        .param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    // =========================================================================
    // 5. SnailJobDeadLetterController 测试套件
    // =========================================================================

    @Test
    @DisplayName("5.1 死信批次切片回查、受控补偿重放与现场清理全流程")
    void testDeadLetterController() throws Exception {
        // 先触发一次带故障的物流广播分片，产生死信切片
        mockMvc.perform(post("/sample/job-snail/trigger/logistics-batch")
                        .param("shardIndex", "0")
                        .param("shardTotal", "1")
                        .param("injectFault", "true"))
                .andExpect(status().isOk());

        // 1. 查询死信切片清单
        mockMvc.perform(get("/sample/job-snail/dead-letter/batches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(1))));

        // 2. 受控重放补偿死信切片
        mockMvc.perform(post("/sample/job-snail/dead-letter/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", greaterThanOrEqualTo(1)));

        // 3. 再次查询死信切片清单 (已被清理为空)
        mockMvc.perform(get("/sample/job-snail/dead-letter/batches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(0)));

        // 4. 清空接口显式调用
        mockMvc.perform(delete("/sample/job-snail/dead-letter/batches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }
}
