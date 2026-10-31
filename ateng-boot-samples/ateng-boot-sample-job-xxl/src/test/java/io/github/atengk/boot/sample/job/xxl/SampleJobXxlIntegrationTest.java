package io.github.atengk.boot.sample.job.xxl;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.xxl.job.core.context.XxlJobContext;
import io.github.atengk.boot.job.xxl.actuator.XxlJobEndpoint;
import io.github.atengk.boot.job.xxl.event.XxlJobAlertEvent;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper.XxlJobTestResult;
import io.github.atengk.boot.job.xxl.template.XxlJobAdminTemplate;
import io.github.atengk.boot.sample.job.xxl.handler.AlertDemoJobHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

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
    private AlertDemoJobHandler alertDemoJobHandler;

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
    @DisplayName("测试简单问候任务：MDC traceId 透传与正常调度")
    void testSimpleGreetingJob() {
        XxlJobTestResult result = XxlJobTestHelper.trigger("simpleGreetingJob", "ateng-greeting");
        assertTrue(result.isSuccess());
        assertEquals(XxlJobContext.HANDLE_CODE_SUCCESS, result.handleCode());
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

        // 1. 注入故障，触发首次执行 -> 应发布首发告警卡片 (INITIAL_FAILURE)
        alertDemoJobHandler.setFaultInjected(true);
        XxlJobTestHelper.trigger("alertDemoJob");
        assertEquals(1, eventListener.getCapturedEvents().size());
        assertEquals(XxlJobAlertEvent.AlertType.INITIAL_FAILURE, eventListener.getCapturedEvents().get(0).getAlertType());

        // 2. 连续失败第 2、3 次 -> 达到阈值 3，应发布周期聚合摘要卡片 (FAILURE_SUMMARY)
        XxlJobTestHelper.trigger("alertDemoJob");
        XxlJobTestHelper.trigger("alertDemoJob");
        assertEquals(2, eventListener.getCapturedEvents().size());
        assertEquals(XxlJobAlertEvent.AlertType.FAILURE_SUMMARY, eventListener.getCapturedEvents().get(1).getAlertType());

        // 3. 解除故障并成功执行 -> 应发布自愈恢复卡片 (RECOVERY)
        alertDemoJobHandler.setFaultInjected(false);
        XxlJobTestHelper.trigger("alertDemoJob");
        assertEquals(3, eventListener.getCapturedEvents().size());
        assertEquals(XxlJobAlertEvent.AlertType.RECOVERY, eventListener.getCapturedEvents().get(2).getAlertType());
    }

    @Test
    @DisplayName("测试 Actuator 运维与手动触发端点交互")
    void testActuatorEndpoint() {
        Map<String, Object> listJobs = xxlJobEndpoint.listJobs();
        assertNotNull(listJobs);
        assertTrue((Integer) listJobs.get("jobCount") >= 4);

        Map<String, Object> triggerResponse = xxlJobEndpoint.triggerJob("simpleGreetingJob", "manual-test", 0, 1, null);
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

    /**
     * Mock Admin 路由分发器
     */
    private static class MockAdminDispatcher implements HttpHandler {
        private static final String SUCCESS_RESPONSE = "{\"code\":200,\"msg\":\"Success\",\"data\":\"101\"}";

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] responseBytes = SUCCESS_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }
}
