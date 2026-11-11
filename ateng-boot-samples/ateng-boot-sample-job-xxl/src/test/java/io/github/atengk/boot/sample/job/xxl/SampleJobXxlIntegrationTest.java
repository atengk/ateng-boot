package io.github.atengk.boot.sample.job.xxl;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.xxl.job.core.context.XxlJobContext;
import io.github.atengk.boot.job.xxl.actuator.XxlJobEndpoint;
import io.github.atengk.boot.job.xxl.event.XxlJobAlertEvent;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper.XxlJobTestResult;
import io.github.atengk.boot.job.xxl.template.FailedBatch;
import io.github.atengk.boot.job.xxl.template.XxlJobAdminTemplate;
import io.github.atengk.boot.sample.job.xxl.dto.UserSyncParam;
import io.github.atengk.boot.sample.job.xxl.handler.HistoryDataArchiveJobHandler;
import io.github.atengk.boot.sample.job.xxl.handler.OrderReconciliationJobHandler;
import io.github.atengk.boot.sample.job.xxl.handler.ShardingBatchJobHandler;
import io.github.atengk.boot.sample.job.xxl.handler.ShardingBatchJobHandler.MockOrder;
import io.github.atengk.boot.sample.job.xxl.handler.SystemWarmupJobHandler;
import io.github.atengk.boot.sample.job.xxl.handler.TenantJobHandler;
import io.github.atengk.boot.sample.job.xxl.interceptor.CanaryAuditJobInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * XXL-Job 场景样例工程端到端集成实测套件 (双模自适应：离线自洽 Mock Admin / 真实 Local Admin 联调)
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootTest(classes = SampleJobXxlApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SampleJobXxlIntegrationTest.TestAlertEventListener.class)
class SampleJobXxlIntegrationTest {

    private static HttpServer mockAdminServer;
    private static int mockAdminPort;

    @Autowired
    private XxlJobEndpoint xxlJobEndpoint;

    @Autowired
    private XxlJobAdminTemplate adminTemplate;

    @Autowired
    private SystemWarmupJobHandler warmupJobHandler;

    @Autowired
    private OrderReconciliationJobHandler reconciliationJobHandler;

    @Autowired
    private CanaryAuditJobInterceptor canaryInterceptor;

    @Autowired
    private HistoryDataArchiveJobHandler archiveJobHandler;

    @Autowired
    private ShardingBatchJobHandler shardingBatchJobHandler;

    @Autowired
    private TenantJobHandler tenantJobHandler;

    @Autowired
    private TestAlertEventListener eventListener;

    @BeforeAll
    static void startMockAdmin() throws Exception {
        // 如果未激活 local profile，则自动拉起离线轻量 Mock Admin 服务桩
        String activeProfile = System.getProperty("spring.profiles.active", "");
        if (!activeProfile.contains("local")) {
            mockAdminServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            mockAdminPort = mockAdminServer.getAddress().getPort();
            mockAdminServer.createContext("/api", new MockAdminDispatcher());
            mockAdminServer.setExecutor(null);
            mockAdminServer.start();

            // 动态设置调度中心地址指向 Mock 桩
            System.setProperty("ateng.boot.job.xxl.admin-addresses", "http://127.0.0.1:" + mockAdminPort);
            System.setProperty("ateng.boot.job.xxl.port", "0"); // 使用动态随机端口避免端口冲突
        }
    }

    @AfterAll
    static void stopMockAdmin() {
        if (mockAdminServer != null) {
            mockAdminServer.stop(0);
        }
    }

    @Test
    @DisplayName("测试系统预热与问候任务：MDC traceId 透传与正常调度")
    void testSystemWarmupJob() {
        XxlJobTestResult result = XxlJobTestHelper.trigger("systemWarmupJob", "warmup-cache");
        assertTrue(result.isSuccess());
        assertEquals(XxlJobContext.HANDLE_CODE_SUCCESS, result.handleCode());

        // 兼容验证别名任务
        XxlJobTestResult greetingResult = XxlJobTestHelper.trigger("simpleGreetingJob", "ateng-greeting");
        assertTrue(greetingResult.isSuccess());
        assertEquals(XxlJobContext.HANDLE_CODE_SUCCESS, greetingResult.handleCode());
    }

    @Test
    @DisplayName("测试强类型入参：合法参数正常反序列化并执行通过")
    void testUserSyncParamValidationSuccess() {
        String validJson = "{\"batchNo\":\"BATCH-20261031-001\",\"batchSize\":500,\"tenantId\":\"T1001\"}";
        XxlJobTestResult result = XxlJobTestHelper.trigger("userSyncJob", validJson);
        assertTrue(result.isSuccess());
        assertEquals(XxlJobContext.HANDLE_CODE_SUCCESS, result.handleCode());
    }

    @Test
    @DisplayName("测试强类型入参：非法参数触发 JSR-303 Fail-Fast 校验熔断")
    void testUserSyncParamValidationFailure() {
        // 批大小超过上限 1000
        String invalidJson = "{\"batchNo\":\"BATCH-002\",\"batchSize\":9999}";
        XxlJobTestResult result = XxlJobTestHelper.trigger("userSyncJob", invalidJson);
        assertFalse(result.isSuccess());
        assertNotNull(result.throwable());
        assertTrue(result.throwable().getMessage().contains("单批同步上限不能超过 1000"));
    }

    @Test
    @DisplayName("测试大数据分片批处理：ShardingContext 自动注入与流式消费")
    void testShardingBatchJobStreaming() {
        XxlJobTestResult result = XxlJobTestHelper.trigger("shardingBatchJob", null, 0, 2);
        assertTrue(result.isSuccess());
        assertEquals(XxlJobContext.HANDLE_CODE_SUCCESS, result.handleCode());
        assertTrue(result.handleMsg().contains("累计消费"));
    }

    @Test
    @DisplayName("测试多租户模式：跨租户全局归档与参数绑定租户隔离")
    void testTenantModes() {
        // 1. 全局任务：tenantMode = IGNORE
        XxlJobTestResult globalResult = XxlJobTestHelper.trigger("globalArchiveJob");
        assertTrue(globalResult.isSuccess());

        // 2. 租户隔离任务：tenantMode = PARAM，自动解析绑定
        XxlJobTestResult tenantResult = XxlJobTestHelper.trigger("tenantScopedJob", "tenantId=TENANT-888");
        assertTrue(tenantResult.isSuccess());
    }

    @Test
    @DisplayName("测试三阶告警风暴抑制与自愈状态机端到端全真演练")
    void testAlertStormSuppressionAndSelfHealing() {
        eventListener.clearEvents();

        // 1. 注入对账差错故障，触发首次执行 -> 应发布首发告警卡片 (INITIAL_FAILURE)
        reconciliationJobHandler.setFaultInjected(true);
        XxlJobTestHelper.trigger("orderReconciliationJob");
        assertEquals(1, eventListener.getCapturedEvents().size());
        assertEquals(XxlJobAlertEvent.AlertType.INITIAL_FAILURE, eventListener.getCapturedEvents().get(0).getAlertType());

        // 2. 连续失败第 2、3 次 -> 达到阈值 3，应发布周期聚合摘要卡片 (FAILURE_SUMMARY)
        XxlJobTestHelper.trigger("orderReconciliationJob");
        XxlJobTestHelper.trigger("orderReconciliationJob");
        assertEquals(2, eventListener.getCapturedEvents().size());
        assertEquals(XxlJobAlertEvent.AlertType.FAILURE_SUMMARY, eventListener.getCapturedEvents().get(1).getAlertType());

        // 3. 解除故障并成功执行 -> 应发布自愈恢复卡片 (RECOVERY)
        reconciliationJobHandler.setFaultInjected(false);
        XxlJobTestHelper.trigger("orderReconciliationJob");
        assertEquals(3, eventListener.getCapturedEvents().size());
        assertEquals(XxlJobAlertEvent.AlertType.RECOVERY, eventListener.getCapturedEvents().get(2).getAlertType());
    }

    @Test
    @DisplayName("测试 Actuator 运维与手动触发端点交互")
    void testActuatorEndpoint() {
        Map<String, Object> listJobs = xxlJobEndpoint.listJobs();
        assertNotNull(listJobs);
        assertTrue((Integer) listJobs.get("jobCount") >= 4);

        Map<String, Object> triggerResponse = xxlJobEndpoint.triggerJob("systemWarmupJob", "manual-test", 0, 1, null);
        assertEquals(true, triggerResponse.get("success"));
        assertEquals(200, triggerResponse.get("handleCode"));
    }

    @Test
    @DisplayName("测试 OpenAPI 编程式管理门面集群交互")
    void testOpenApiAdminTemplate() {
        assertNotNull(adminTemplate);
        assertTrue(adminTemplate.getEndpointCount() >= 1);
        assertDoesNotThrow(() -> adminTemplate.triggerJob(101, "test-param"));
    }

    @Test
    @DisplayName("测试强类型 POJO 入参：对象自动 JSON 序列化并完成强类型绑定与 JSR-303 防卫")
    void testUserSyncPojoBinding() {
        UserSyncParam param = new UserSyncParam();
        param.setBatchNo("BATCH-POJO-20261102");
        param.setBatchSize(200);
        param.setTenantId("TENANT-POJO-888");

        XxlJobTestResult result = XxlJobTestHelper.trigger("userSyncJob", param);
        assertTrue(result.isSuccess());
        assertEquals(XxlJobContext.HANDLE_CODE_SUCCESS, result.handleCode());
        assertTrue(result.handleMsg().contains("200"));
    }

    @Test
    @DisplayName("测试生命周期拦截器：灰度跳过拦截上报成功与审计事件终态归档")
    void testLifecycleInterceptorCanarySkip() {
        canaryInterceptor.clear();

        // 1. 灰度拦截跳过任务：preHandle 返回 false，框架优雅终止执行并直接报告 200 SUCCESS
        XxlJobTestResult skipResult = XxlJobTestHelper.trigger("canarySkipJob");
        assertTrue(skipResult.isSuccess());
        assertEquals(XxlJobContext.HANDLE_CODE_SUCCESS, skipResult.handleCode());
        assertTrue(skipResult.handleMsg().contains("[INTERCEPTED]"));
        assertEquals(1, canaryInterceptor.getSkippedCount());

        // 2. 正常任务：preHandle 准入放行，正常执行业务并在 afterCompletion 完成终态审计
        XxlJobTestResult normalResult = XxlJobTestHelper.trigger("canaryNormalJob");
        assertTrue(normalResult.isSuccess());
        assertEquals(XxlJobContext.HANDLE_CODE_SUCCESS, normalResult.handleCode());
        assertTrue(canaryInterceptor.getAuditedJobs().contains("canaryNormalJob"));
        assertTrue(canaryInterceptor.getCompletedCount() >= 1);
    }

    @Test
    @DisplayName("测试 Actuator 端点动态暂停与恢复任务执行")
    void testDynamicNodePauseAndResume() {
        // 1. 正常执行
        XxlJobTestResult initialResult = XxlJobTestHelper.trigger("pausableTaskJob");
        assertTrue(initialResult.isSuccess());
        assertEquals("可暂停任务正常执行完成", initialResult.handleMsg());

        // 2. 通过 Actuator 端点暂停该任务
        Map<String, Object> pauseResp = xxlJobEndpoint.operateJob("pausableTaskJob", "pause");
        assertEquals(true, pauseResp.get("success"));
        assertEquals(true, pauseResp.get("paused"));

        // 3. 暂停状态下触发：切面拦截跳过执行并返回 200 SUCCESS
        XxlJobTestResult pausedResult = XxlJobTestHelper.trigger("pausableTaskJob");
        assertTrue(pausedResult.isSuccess());
        assertTrue(pausedResult.handleMsg().contains("[SKIPPED]"));

        // 4. 通过 Actuator 端点恢复该任务
        Map<String, Object> resumeResp = xxlJobEndpoint.operateJob("pausableTaskJob", "resume");
        assertEquals(true, resumeResp.get("success"));
        assertEquals(false, resumeResp.get("paused"));

        // 5. 恢复后正常触发
        XxlJobTestResult resumedResult = XxlJobTestHelper.trigger("pausableTaskJob");
        assertTrue(resumedResult.isSuccess());
        assertEquals("可暂停任务正常执行完成", resumedResult.handleMsg());
    }

    @Test
    @DisplayName("测试分片批处理：结构化流式进度打卡与切片异常死信补偿回调")
    void testShardingBatchDeadLetterCallback() {
        shardingBatchJobHandler.clearDeadLetters();

        XxlJobTestResult result = XxlJobTestHelper.trigger("shardingBatchDeadLetterJob", null, 0, 1);
        assertTrue(result.isSuccess());
        assertEquals(XxlJobContext.HANDLE_CODE_SUCCESS, result.handleCode());

        List<FailedBatch<MockOrder>> deadLetters = shardingBatchJobHandler.getDeadLetters();
        assertEquals(1, deadLetters.size());
        FailedBatch<MockOrder> failedBatch = deadLetters.get(0);
        assertEquals(5, failedBatch.getItems().size());
        assertNotNull(failedBatch.getCause());
        assertTrue(failedBatch.getCause().getMessage().contains("模拟业务处理失败"));
        assertTrue(result.handleMsg().contains("死信收集 1 批"));
    }

    @Test
    @DisplayName("测试多租户并发遍历：受管线程池背压与租户上下文独立沙箱隔离")
    void testParallelTenantIteration() {
        tenantJobHandler.clearParallelProcessedTenants();

        XxlJobTestResult result = XxlJobTestHelper.trigger("parallelTenantJob");
        assertTrue(result.isSuccess());
        assertEquals(XxlJobContext.HANDLE_CODE_SUCCESS, result.handleCode());

        List<String> processedTenants = tenantJobHandler.getParallelProcessedTenants();
        assertEquals(3, processedTenants.size());
        assertTrue(processedTenants.containsAll(List.of("tenant-1001", "tenant-1002", "tenant-1003")));
    }

    @Test
    @DisplayName("测试本地硬看门狗超时中断与同 Bean 就地降级补偿")
    void testWatchdogTimeoutFallback() throws InterruptedException {
        archiveJobHandler.reset();

        XxlJobTestResult result = XxlJobTestHelper.trigger("historyDataArchiveJob");
        assertTrue(result.isSuccess());
        assertEquals(XxlJobContext.HANDLE_CODE_SUCCESS, result.handleCode());

        // 等待异步受管线程池中的降级补偿方法完成
        boolean reached = archiveJobHandler.getFallbackLatch().await(3, TimeUnit.SECONDS);
        assertTrue(reached);
        assertTrue(archiveJobHandler.isFallbackInvoked());
        assertTrue(result.handleMsg().contains("historyDataArchiveJob"));
    }

    /**
     * 告警领域事件捕获监听器
     */
    @Component
    public static class TestAlertEventListener {
        private final List<XxlJobAlertEvent> capturedEvents = new CopyOnWriteArrayList<>();

        @EventListener
        public void onAlert(XxlJobAlertEvent event) {
            capturedEvents.add(event);
        }

        public List<XxlJobAlertEvent> getCapturedEvents() {
            return capturedEvents;
        }

        public void clearEvents() {
            capturedEvents.clear();
        }
    }
}
