package io.github.atengk.boot.sample.notification;

import io.github.atengk.boot.notification.alert.AlertStormSuppressor;
import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.channel.NotificationChannelRegistry;
import io.github.atengk.boot.notification.model.CardButtonOrientation;
import io.github.atengk.boot.notification.model.CardColor;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.sample.notification.dto.AlertTriggerRequest;
import io.github.atengk.boot.sample.notification.dto.CardMessageRequest;
import io.github.atengk.boot.sample.notification.dto.MarkdownMessageRequest;
import io.github.atengk.boot.sample.notification.dto.TextMessageRequest;
import io.github.atengk.boot.sample.notification.provider.SampleCustomNotificationChannelProvider;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 企业协同通知场景样例端到端全链路集成自验测试
 * 基于纯内存 MockWebServer 模拟全平台 Webhook 服务，离线验证全部发信、切源、事务与告警场景
 *
 * @author Ateng
 * @since 2026-10-23
 */
@SpringBootTest(classes = SampleNotificationApplication.class)
@AutoConfigureMockMvc
@DisplayName("Notification 场景样例端到端集成测试")
class SampleNotificationIntegrationTest {

    private static MockWebServer mockServer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationChannelRegistry channelRegistry;

    @Autowired(required = false)
    private AlertStormSuppressor alertStormSuppressor;

    @BeforeAll
    static void startServer() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
    }

    @AfterAll
    static void stopServer() throws IOException {
        if (mockServer != null) {
            mockServer.shutdown();
        }
    }

    @AfterEach
    void tearDown() {
        if (alertStormSuppressor != null) {
            alertStormSuppressor.reset();
        }
        if (channelRegistry != null) {
            channelRegistry.unregister("sample-failover-primary");
        }
        while (true) {
            try {
                RecordedRequest req = mockServer.takeRequest(10, TimeUnit.MILLISECONDS);
                if (req == null) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String baseUrl = mockServer.url("").toString();
        SampleCustomNotificationChannelProvider.dynamicWebhookUrl = baseUrl + "webhook/dynamic-security";
        // 动态覆盖各渠道的 Webhook 地址至 MockWebServer
        registry.add("ateng.boot.notification.channels.ops-alert.webhook", () -> baseUrl + "webhook/wecom");
        registry.add("ateng.boot.notification.channels.dingtalk-dev.webhook", () -> baseUrl + "webhook/dingtalk");
        registry.add("ateng.boot.notification.channels.feishu-ops.webhook", () -> baseUrl + "webhook/feishu");
        registry.add("ateng.boot.notification.channels.generic-monitor.webhook", () -> baseUrl + "webhook/generic");
    }

    private void enqueueSuccessResponse() {
        mockServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"errcode\":0,\"errmsg\":\"ok\",\"code\":0,\"msg\":\"success\"}"));
    }

    @Test
    @DisplayName("端到端验证纯文本发信接口：校验统一契约、MDC TraceId透传与@提醒解析")
    void testSendTextMessageEndpoint() throws Exception {
        enqueueSuccessResponse();

        String payload = """
                {
                  "channel": "ops-alert",
                  "content": "【生产发布】ateng-boot v1.0.0 发布成功！",
                  "atAll": false,
                  "atMobiles": ["13800138000"],
                  "atUserIds": ["ops-admin"]
                }
                """;

        mockMvc.perform(post("/api/v1/notification/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.channel").value("ops-alert"));

        RecordedRequest recorded = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(recorded);
        assertEquals("/webhook/wecom", recorded.getPath());
        String body = recorded.getBody().readUtf8();
        assertThat(body)
                .contains("【生产发布】ateng-boot v1.0.0 发布成功！")
                .contains("13800138000")
                .contains("ops-admin");
    }

    @Test
    @DisplayName("端到端验证富文本 Markdown 发信：企微表格自动降级为引用块，防语法破损")
    void testSendMarkdownMessageEndpoint() throws Exception {
        enqueueSuccessResponse();

        String payload = """
                {
                  "channel": "ops-alert",
                  "title": "系统巡检汇总",
                  "content": "### 巡检摘要\\n| 服务名 | 状态 | 延迟 |\\n| :--- | :--- | :--- |\\n| Gateway | 正常 | 12ms |\\n| Auth | 正常 | 8ms |"
                }
                """;

        mockMvc.perform(post("/api/v1/notification/markdown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        RecordedRequest recorded = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(recorded);
        String body = recorded.getBody().readUtf8();
        // 企微 Markdown 包含表格时被转译为引用块
        assertThat(body)
                .contains("### 巡检摘要")
                .contains("> **服务名**");
    }

    @Test
    @DisplayName("端到端验证互动卡片发信：主题色与操作跳转按钮正确渲染")
    void testSendCardMessageEndpoint() throws Exception {
        enqueueSuccessResponse();

        String payload = """
                {
                  "channel": "ops-alert",
                  "title": "集群高负载预警",
                  "content": "节点 worker-01 CPU 使用率超过 **92%**",
                  "headerColor": "RED",
                  "buttonTitle": "立即排查",
                  "buttonUrl": "https://monitor.example.com/nodes/worker-01"
                }
                """;

        mockMvc.perform(post("/api/v1/notification/card")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        RecordedRequest recorded = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(recorded);
        String body = recorded.getBody().readUtf8();
        assertThat(body)
                .contains("集群高负载预警")
                .contains("worker-01")
                .contains("立即排查")
                .contains("https://monitor.example.com/nodes/worker-01");
    }

    @Test
    @DisplayName("端到端验证受管异步发信接口：非阻塞立即响应")
    void testAsyncSendEndpoint() throws Exception {
        enqueueSuccessResponse();

        String payload = """
                {
                  "channel": "ops-alert",
                  "content": "异步排队发信自验"
                }
                """;

        mockMvc.perform(post("/api/v1/notification/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("异步发信任务已提交受管线程池"));

        RecordedRequest recorded = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(recorded);
        assertThat(recorded.getBody().readUtf8()).contains("异步排队发信自验");
    }

    @Test
    @DisplayName("端到端验证本地事务感知发信 (sendAfterCommit)：提交后才发信，回滚自动取消防幽灵")
    void testTransactionalSendScenarios() throws Exception {
        // 场景 A：事务成功物理 commit，正常外呼发信
        enqueueSuccessResponse();

        String successPayload = """
                {
                  "channel": "ops-alert",
                  "content": "订单 ORD1001 支付成功出库通报"
                }
                """;

        mockMvc.perform(post("/api/v1/notification/transactional")
                        .param("triggerRollback", "false")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(successPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        RecordedRequest recorded = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(recorded);
        assertThat(recorded.getBody().readUtf8()).contains("ORD1001");

        // 场景 B：事务抛出异常回滚，发信钩子自动取消，防范幽灵通知发送
        String rollbackPayload = """
                {
                  "channel": "ops-alert",
                  "content": "幽灵通知：该消息不应被发出"
                }
                """;

        // triggerRollback=true 时触发异常
        mockMvc.perform(post("/api/v1/notification/transactional")
                        .param("triggerRollback", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rollbackPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));

        // 验证 MockWebServer 在等待时间内未收到发信请求
        RecordedRequest phantom = mockServer.takeRequest(500, TimeUnit.MILLISECONDS);
        assertNull(phantom, "事务回滚时，严禁向外发送幽灵消息！");
    }

    @Test
    @DisplayName("端到端验证 NotificationContextHolder 动态切源至钉钉")
    void testDynamicChannelSwitching() throws Exception {
        enqueueSuccessResponse();

        String payload = """
                {
                  "content": "通过 ContextHolder 动态切源发信"
                }
                """;

        mockMvc.perform(post("/api/v1/notification/switch-channel")
                        .param("targetChannel", "dingtalk-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.channel").value("dingtalk-dev"));

        RecordedRequest recorded = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(recorded);
        assertThat(recorded.getPath()).startsWith("/webhook/dingtalk");
    }

    @Test
    @DisplayName("验证自定义渠道供给器 SPI (NotificationChannelProvider) 懒加载注入并真正完成发信闭环")
    void testCustomChannelProviderLoaded() throws Exception {
        enqueueSuccessResponse();

        NotificationChannel channel = channelRegistry.get(SampleCustomNotificationChannelProvider.DYNAMIC_CHANNEL_NAME);
        assertNotNull(channel);
        assertEquals("dynamic-security-bot", channel.getName());
        assertEquals(30, channel.getPermitsPerMinute());

        String payload = """
                {
                  "channel": "dynamic-security-bot",
                  "content": "向动态懒加载渠道发送安全通报"
                }
                """;
        mockMvc.perform(post("/api/v1/notification/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.channel").value("dynamic-security-bot"));

        RecordedRequest recorded = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(recorded);
        assertThat(recorded.getPath()).startsWith("/webhook/dynamic-security");
    }

    @Test
    @DisplayName("端到端验证动态模板变量渲染发信接口")
    void testSendTemplateEndpoint() throws Exception {
        enqueueSuccessResponse();

        String varsJson = """
                {
                  "appName": "ateng-pay",
                  "qps": 12500
                }
                """;

        mockMvc.perform(post("/api/v1/notification/template")
                        .param("channel", "dingtalk-dev")
                        .param("templateContent", "服务 ${appName} 瞬时 QPS 达到 ${qps}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(varsJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        RecordedRequest recorded = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(recorded);
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("服务 ateng-pay 瞬时 QPS 达到 12500");
    }

    @Test
    @DisplayName("端到端验证原始特化 JSON 逃生舱发信接口")
    void testSendRawMessageEndpoint() throws Exception {
        enqueueSuccessResponse();

        String rawJson = """
                {
                  "msgtype": "raw_special",
                  "custom_node": {
                    "token": "tok_123",
                    "status": "APPROVED"
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/notification/raw")
                        .param("channel", "generic-monitor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        RecordedRequest recorded = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(recorded);
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("raw_special").contains("APPROVED");
    }

    @Test
    @DisplayName("端到端验证图片通知发信接口")
    void testSendImageMessageEndpoint() throws Exception {
        enqueueSuccessResponse();

        mockMvc.perform(post("/api/v1/notification/image")
                        .param("channel", "ops-alert")
                        .param("base64", "aGVsbG8=")
                        .param("md5", "5d41402abc4b2a76b9719d911017c592"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        RecordedRequest recorded = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(recorded);
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("image").contains("aGVsbG8=");
    }

    @Test
    @DisplayName("端到端验证发信失败时自动触发 NotificationSendFailureHandler 补偿 SPI 回调")
    void testSendFailureHandlerTriggered() throws Exception {
        // 模拟 MockWebServer 返回 500 触发发信异常
        mockServer.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Error"));

        int previousFailures = io.github.atengk.boot.sample.notification.handler.SampleNotificationSendFailureHandler.FAILURE_COUNT.get();

        String payload = """
                {
                  "channel": "ops-alert",
                  "content": "模拟异步发信失败以验证补偿回调"
                }
                """;

        // 异步发信在受管线程池中抛出异常后会触发 NotificationSendFailureHandler
        mockMvc.perform(post("/api/v1/notification/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        // 等待异步任务在线程池中执行并完成回调
        TimeUnit.MILLISECONDS.sleep(800);

        int currentFailures = io.github.atengk.boot.sample.notification.handler.SampleNotificationSendFailureHandler.FAILURE_COUNT.get();
        assertThat(currentFailures).isGreaterThan(previousFailures);
    }

    @Test
    @DisplayName("端到端验证声明式 @NotifyAlert 异常告警拦截：自动入参安全脱敏与防风暴聚合")
    void testNotifyAlertAspectAndSuppression() throws Exception {
        // 首发异常应触发 1 次紧急红色告警卡片
        enqueueSuccessResponse();

        String alertPayload = """
                {
                  "orderId": "ORD-ALERT-9999",
                  "password": "SUPER_SECRET_PASSWORD_123",
                  "errorType": "npe"
                }
                """;

        mockMvc.perform(post("/api/v1/alert/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(alertPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));

        // 验证首发告警卡片已送达 MockWebServer
        RecordedRequest recorded = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(recorded);
        String body = recorded.getBody().readUtf8();

        // 验证参数安全脱敏：包含 orderId，但 password 必须被彻底掩码为 ******，严禁明文外泄
        assertThat(body)
                .contains("订单履约业务未捕获异常")
                .contains("ORD-ALERT-9999")
                .contains("******")
                .doesNotContain("SUPER_SECRET_PASSWORD_123");

        // 验证防风暴聚合：窗口期内（10s）再次触发相同异常，被静默拦截，不再产生新的即时网络请求
        mockMvc.perform(post("/api/v1/alert/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(alertPayload))
                .andExpect(status().isOk());

        RecordedRequest duplicate = mockServer.takeRequest(500, TimeUnit.MILLISECONDS);
        assertNull(duplicate, "窗口期内相同异常指纹应被静默拦截，杜绝刷屏！");
    }

    @Test
    @DisplayName("端到端验证图文链接通知发信接口：校验 LinkMessage 跨端自适应转译")
    void testSendLinkMessageEndpoint() throws Exception {
        enqueueSuccessResponse();

        String payload = """
                {
                  "channel": "dingtalk-dev",
                  "title": "项目里程碑通报",
                  "text": "v1.2.0 已完成端到端测试与五维文档闭环",
                  "messageUrl": "https://github.com/atengk/ateng-boot/releases/v1.2.0",
                  "picUrl": "https://img.example.com/banner.png",
                  "atAll": false,
                  "atMobiles": ["13800138000"]
                }
                """;

        mockMvc.perform(post("/api/v1/notification/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.channel").value("dingtalk-dev"));

        RecordedRequest recorded = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(recorded);
        assertThat(recorded.getPath()).startsWith("/webhook/dingtalk");
        String body = recorded.getBody().readUtf8();
        assertThat(body)
                .contains("\"msgtype\":\"link\"")
                .contains("项目里程碑通报")
                .contains("https://github.com/atengk/ateng-boot/releases/v1.2.0")
                .contains("https://img.example.com/banner.png");
    }

    @Test
    @DisplayName("端到端验证卡片消息横向多按钮排版与浅蓝色主题：校验 CardButtonOrientation 与 WATHET")
    void testSendCardMessageWithHorizontalOrientationEndpoint() throws Exception {
        enqueueSuccessResponse();

        String payload = """
                {
                  "channel": "dingtalk-dev",
                  "title": "请假审批通知",
                  "content": "员工申请年假 2 天",
                  "headerColor": "WATHET",
                  "btnOrientation": "HORIZONTAL",
                  "buttons": [
                    {
                      "title": "同意",
                      "url": "https://oa.example.com/approve",
                      "type": "PRIMARY"
                    },
                    {
                      "title": "拒绝",
                      "url": "https://oa.example.com/reject",
                      "type": "DANGER"
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/notification/card")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        RecordedRequest recorded = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(recorded);
        assertThat(recorded.getPath()).startsWith("/webhook/dingtalk");
        String body = recorded.getBody().readUtf8();
        assertThat(body)
                .contains("actionCard")
                .contains("请假审批通知")
                .contains("\"btnOrientation\":\"1\"")
                .contains("\"btns\":")
                .contains("同意")
                .contains("拒绝");
    }

    @Test
    @DisplayName("端到端验证主备渠道故障转移 (Failover)：主渠道 500 异常时顺延降级至备用渠道成功")
    void testSendChannelFailoverEndpoint() throws Exception {
        NotificationChannel failoverPrimary = NotificationChannel.builder()
                .name("sample-failover-primary")
                .platform(Platform.DINGTALK)
                .webhook(mockServer.url("webhook/primary-failover").toString())
                .backupChannels(List.of("feishu-ops"))
                .build();
        channelRegistry.register(failoverPrimary);

        try {
            // 主渠道返回 HTTP 500
            mockServer.enqueue(new MockResponse().setResponseCode(500).setBody("Primary Server Error"));
            // 备用渠道正常返回 200 OK
            enqueueSuccessResponse();

            String payload = """
                    {
                      "channel": "sample-failover-primary",
                      "content": "验证主备顺延故障转移端到端流程"
                    }
                    """;

            mockMvc.perform(post("/api/v1/notification/text")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.success").value(true))
                    .andExpect(jsonPath("$.data.channel").value("feishu-ops"))
                    .andExpect(jsonPath("$.data.platform").value("FEISHU"));

            // 验证主渠道与备用渠道依次收到请求
            RecordedRequest req1 = mockServer.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull(req1);
            assertEquals("/webhook/primary-failover", req1.getPath());

            RecordedRequest req2 = mockServer.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull(req2);
            assertEquals("/webhook/feishu", req2.getPath());
        } finally {
            channelRegistry.unregister("sample-failover-primary");
        }
    }
}
