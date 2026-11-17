package io.github.atengk.boot.sample.notification;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.notification.alert.AlertStormSuppressor;
import io.github.atengk.boot.notification.util.NotificationSignUtil;
import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.channel.NotificationChannelRegistry;
import io.github.atengk.boot.notification.model.CardButtonOrientation;
import io.github.atengk.boot.notification.model.CardColor;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.model.NotificationSendResult;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.model.TextMessage;
import io.github.atengk.boot.notification.provider.GenericWebhookNotificationProvider;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import io.github.atengk.boot.sample.notification.dto.AlertTriggerRequest;
import io.github.atengk.boot.sample.notification.dto.CardMessageRequest;
import io.github.atengk.boot.sample.notification.dto.MarkdownMessageRequest;
import io.github.atengk.boot.sample.notification.dto.TextMessageRequest;
import io.github.atengk.boot.sample.notification.provider.SampleCustomNotificationChannelProvider;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 企业协同通知场景样例端到端全链路集成自验测试
 * 结合内置 @Profile("!local") 离线仿真网关与纯内存 MockWebServer，离线验证全平台协议、并发广播、切源、事务与告警场景
 *
 * @author Ateng
 * @since 2026-10-23
 */
@SpringBootTest(classes = SampleNotificationApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@DisplayName("Notification 场景样例端到端集成测试")
class SampleNotificationIntegrationTest {

    private static MockWebServer mockServer;

    @LocalServerPort
    private int serverPort;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationTemplate notificationTemplate;

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
            channelRegistry.unregister("gw-wecom");
            channelRegistry.unregister("gw-dingtalk");
            channelRegistry.unregister("gw-feishu");
            channelRegistry.unregister("gw-generic");
        }
        mockServer.setDispatcher(new QueueDispatcher());
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

        mockMvc.perform(post("/sample/notification/message/text")
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

        mockMvc.perform(post("/sample/notification/message/markdown")
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

        mockMvc.perform(post("/sample/notification/message/card")
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

        mockMvc.perform(post("/sample/notification/transaction/send-async")
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

        mockMvc.perform(post("/sample/notification/transaction/send-after-commit")
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
        mockMvc.perform(post("/sample/notification/transaction/send-after-commit")
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
                  "targetChannel": "dingtalk-dev",
                  "content": "通过 ContextHolder 动态切源发信"
                }
                """;

        mockMvc.perform(post("/sample/notification/channel/switch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true))
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
        mockMvc.perform(post("/sample/notification/message/text")
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

        String templatePayload = """
                {
                  "channel": "dingtalk-dev",
                  "templateContent": "服务 ${appName} 瞬时 QPS 达到 ${qps}",
                  "variables": {
                    "appName": "ateng-pay",
                    "qps": 12500
                  }
                }
                """;

        mockMvc.perform(post("/sample/notification/channel/template")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(templatePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

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
                  "channel": "generic-monitor",
                  "payload": {
                    "msgtype": "raw_special",
                    "custom_node": {
                      "token": "tok_123",
                      "status": "APPROVED"
                    }
                  }
                }
                """;

        mockMvc.perform(post("/sample/notification/message/raw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        RecordedRequest recorded = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(recorded);
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("raw_special").contains("APPROVED");
    }

    @Test
    @DisplayName("端到端验证图片通知发信接口：校验 Base64 与 MD5 载荷")
    void testSendImageMessageEndpoint() throws Exception {
        enqueueSuccessResponse();

        String imagePayload = """
                {
                  "channel": "ops-alert",
                  "base64": "aGVsbG8=",
                  "md5": "5d41402abc4b2a76b9719d911017c592"
                }
                """;

        mockMvc.perform(post("/sample/notification/message/image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(imagePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        RecordedRequest recorded = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(recorded);
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("image").contains("aGVsbG8=");
    }

    @Test
    @DisplayName("端到端验证本地图片文件通知发信接口：校验由路径自动计算 Base64/MD5 并验证非法空入参防卫")
    void testSendImageMessageFromPathEndpoint(@TempDir Path tempDir) throws Exception {
        Path validImg = tempDir.resolve("test-image.png");
        byte[] imgContent = "PNG_FAKE_IMAGE_BYTES_FOR_TEST".getBytes();
        Files.write(validImg, imgContent);

        enqueueSuccessResponse();

        String escapedPath = validImg.toAbsolutePath().toString().replace("\\", "\\\\");
        String imagePayload = String.format("""
                {
                  "channel": "ops-alert",
                  "imagePath": "%s"
                }
                """, escapedPath);

        mockMvc.perform(post("/sample/notification/message/image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(imagePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        RecordedRequest recorded = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(recorded);
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("image");

        // 验证空文件与非法入参校验 Fail-Fast
        String invalidPayload = """
                {
                  "channel": "ops-alert"
                }
                """;
        mockMvc.perform(post("/sample/notification/message/image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
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
        mockMvc.perform(post("/sample/notification/transaction/send-async")
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

        String traceId = mockMvc.perform(post("/sample/notification/alert/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(alertPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andReturn().getResponse().getHeader("X-Trace-Id");
        assertNotNull(traceId);

        // 验证首发告警卡片已送达 MockWebServer
        RecordedRequest recorded = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(recorded);
        String body = recorded.getBody().readUtf8();

        // 验证参数安全脱敏与 @NotifyAlert.detailUrl 链路追踪跳转插值
        assertThat(body)
                .contains("订单履约业务未捕获异常")
                .contains("ORD-ALERT-9999")
                .contains("******")
                .contains("card_action")
                .contains("https://apm.example.com/traces/" + traceId)
                .doesNotContain("${traceId}")
                .doesNotContain("SUPER_SECRET_PASSWORD_123");

        // 验证防风暴聚合：窗口期内（10s）再次触发相同异常，被静默拦截，不再产生新的即时网络请求
        mockMvc.perform(post("/sample/notification/alert/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(alertPayload))
                .andExpect(status().isOk());

        RecordedRequest duplicate = mockServer.takeRequest(500, TimeUnit.MILLISECONDS);
        assertNull(duplicate, "窗口期内相同异常指纹应被静默拦截，杜绝刷屏！");
    }

    @Test
    @DisplayName("端到端验证告警风暴三阶抑制接口：首发即时推送红色卡片、窗口期静默拦截、周期末异步补发橙色聚合卡片")
    void testAlertStormSuppressEndpointAndThreeStageSuppression() throws Exception {
        // 首发紧急告警与周期末汇总告警各需 1 次成功响应
        enqueueSuccessResponse();
        enqueueSuccessResponse();

        String stormPayload = """
                {
                  "orderId": "ORD-STORM-TEST",
                  "password": "SUPER_SECRET_STORM_PWD",
                  "count": 10
                }
                """;

        mockMvc.perform(post("/sample/notification/alert/storm-suppress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stormPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalAttempts").value(10))
                .andExpect(jsonPath("$.data.initialDispatched").value(1))
                .andExpect(jsonPath("$.data.suppressedCount").value(9))
                .andExpect(jsonPath("$.data.suppressWindowSeconds").value(2));

        // 1. 验证首发即时告警卡片（红色 Header）已送达 MockWebServer
        RecordedRequest emergencyReq = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(emergencyReq, "首发瞬时故障应立即推送紧急告警卡片");
        String emergencyBody = emergencyReq.getBody().readUtf8();
        assertThat(emergencyBody)
                .contains("故障风暴瞬时熔断异常")
                .contains("[紧急告警]")
                .contains("******")
                .doesNotContain("SUPER_SECRET_STORM_PWD");

        // 2. 验证周期末汇总通报卡片（橙色 Header）：等待 2 秒抑制窗口到期并由调度器异步补发
        RecordedRequest summaryReq = mockServer.takeRequest(4, TimeUnit.SECONDS);
        assertNotNull(summaryReq, "2 秒抑制窗口到期后应自动补发周期末汇总卡片");
        String summaryBody = summaryReq.getBody().readUtf8();
        assertThat(summaryBody)
                .contains("【告警汇总】故障风暴瞬时熔断异常")
                .contains("累计重复触发了")
                .contains("9");
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

        mockMvc.perform(post("/sample/notification/message/link")
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

        mockMvc.perform(post("/sample/notification/message/card")
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
                      "primaryChannel": "sample-failover-primary",
                      "content": "验证主备顺延故障转移端到端流程"
                    }
                    """;

            mockMvc.perform(post("/sample/notification/channel/failover")
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

    @Test
    @DisplayName("端到端验证 @Profile(\"!local\") 离线仿真网关四平台验签、报文录制查询与清空闭环")
    void testOfflineMockGatewayAndAuditLifecycle() throws Exception {
        mockMvc.perform(delete("/sample/notification/mock-gateway/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        String gatewayBase = "http://localhost:" + serverPort + "/sample/notification/mock-gateway/";
        channelRegistry.register(NotificationChannel.builder()
                .name("gw-wecom")
                .platform(Platform.WECOM)
                .webhook(gatewayBase + "wecom")
                .build());
        channelRegistry.register(NotificationChannel.builder()
                .name("gw-dingtalk")
                .platform(Platform.DINGTALK)
                .webhook(gatewayBase + "dingtalk")
                .secret("SEC_DINGTALK_SECRET_SAMPLE")
                .build());
        channelRegistry.register(NotificationChannel.builder()
                .name("gw-feishu")
                .platform(Platform.FEISHU)
                .webhook(gatewayBase + "feishu")
                .secret("SEC_FEISHU_SECRET_SAMPLE")
                .build());
        channelRegistry.register(NotificationChannel.builder()
                .name("gw-generic")
                .platform(Platform.GENERIC)
                .webhook(gatewayBase + "generic")
                .secret("SEC_GENERIC_SECRET_SAMPLE")
                .build());

        // 1. 真实 HTTP 直连本地仿真网关验证全四端协议（企微 card_action、钉钉无二次编码+非空 singleURL、飞书加签、通用防重放头）
        NotificationSendResult wecomRes = notificationTemplate.send("gw-wecom",
                CardMessage.of("企微网关卡片", "验证必填 card_action"));
        assertTrue(wecomRes.isSuccess());

        NotificationSendResult dingRes = notificationTemplate.send("gw-dingtalk",
                CardMessage.of("钉钉网关卡片", "验证非空 singleURL 与无二次编码加签"));
        assertTrue(dingRes.isSuccess());

        NotificationSendResult feishuRes = notificationTemplate.send("gw-feishu",
                TextMessage.of("飞书网关文本加签验证"));
        assertTrue(feishuRes.isSuccess());

        NotificationSendResult genericRes = notificationTemplate.send("gw-generic",
                TextMessage.of("通用网关防重放头验证"));
        assertTrue(genericRes.isSuccess());

        // 2. 查询录制报文审计记录（验证 4 条记录、签名全部通过、协议字段完整、通用头不含明文密钥）
        String recordsJson = mockMvc.perform(get("/sample/notification/mock-gateway/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].platform").value("WECOM"))
                .andExpect(jsonPath("$.data[0].signatureVerified").value(true))
                .andExpect(jsonPath("$.data[1].platform").value("DINGTALK"))
                .andExpect(jsonPath("$.data[1].signatureVerified").value(true))
                .andExpect(jsonPath("$.data[2].platform").value("FEISHU"))
                .andExpect(jsonPath("$.data[2].signatureVerified").value(true))
                .andExpect(jsonPath("$.data[3].platform").value("GENERIC"))
                .andExpect(jsonPath("$.data[3].signatureVerified").value(true))
                .andReturn().getResponse().getContentAsString();

        JsonNode recordsRoot = JsonUtil.getObjectMapper().readTree(recordsJson).path("data");
        JsonNode wecomCardAction = recordsRoot.get(0).path("payload").path("template_card").path("card_action");
        assertEquals(1, wecomCardAction.path("type").asInt());
        assertThat(wecomCardAction.path("url").asText()).isNotBlank();

        JsonNode dingCard = recordsRoot.get(1).path("payload").path("actionCard");
        assertThat(dingCard.path("singleURL").asText()).isNotBlank();
        assertThat(recordsRoot.get(1).path("queryString").asText()).doesNotContain("%25");

        JsonNode genericHeaders = recordsRoot.get(3).path("headers");
        assertThat(genericHeaders.has(GenericWebhookNotificationProvider.HEADER_WEBHOOK_TIMESTAMP.toLowerCase())).isTrue();
        assertThat(genericHeaders.has(GenericWebhookNotificationProvider.HEADER_SIGNATURE_SHA256.toLowerCase())).isTrue();
        assertThat(genericHeaders.has("x-webhook-secret")).isFalse();

        // 按平台过滤查询
        mockMvc.perform(get("/sample/notification/mock-gateway/records").param("platform", "DINGTALK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].platform").value("DINGTALK"));

        // 清空审计记录
        mockMvc.perform(delete("/sample/notification/mock-gateway/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(4));

        mockMvc.perform(get("/sample/notification/mock-gateway/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // 3. 安全反向校验：通用网关拒绝明文 X-Webhook-Secret 头与过期防重放时间戳，钉钉拒绝二次编码/错误签名，飞书拒绝错误签名
        mockMvc.perform(post("/sample/notification/mock-gateway/generic")
                        .header("X-Webhook-Secret", "SEC_GENERIC_SECRET_SAMPLE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"forbidden-plaintext-header\"}"))
                .andExpect(status().is4xxClientError());

        long expiredTs = System.currentTimeMillis() - 600_000L;
        String expiredBody = "{\"content\":\"expired-replay\"}";
        String expiredSig = NotificationSignUtil.signGenericWebhook("SEC_GENERIC_SECRET_SAMPLE", expiredTs, expiredBody);
        mockMvc.perform(post("/sample/notification/mock-gateway/generic")
                        .header(GenericWebhookNotificationProvider.HEADER_WEBHOOK_TIMESTAMP, String.valueOf(expiredTs))
                        .header(GenericWebhookNotificationProvider.HEADER_SIGNATURE_SHA256, expiredSig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expiredBody))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/sample/notification/mock-gateway/dingtalk?timestamp=1700000000000&sign=bad%252Bsign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"msgtype\":\"text\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errcode").value(310000));

        mockMvc.perform(post("/sample/notification/mock-gateway/feishu")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"msg_type\":\"text\",\"timestamp\":\"1700000000\",\"sign\":\"invalid-sign\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(19021));
    }

    @Test
    @DisplayName("端到端验证多渠道并发广播接口 (/sample/notification/channel/broadcast)：JSR-303 校验、单点 500 隔离与异步广播")
    void testBroadcastChannelEndpointWithFailureIsolation() throws Exception {
        // 1. 非法入参 Fail-Fast（空渠道集合 / 空白正文 -> JSR-303 返回 code=400）
        String invalidBroadcast = """
                {
                  "channels": [],
                  "content": ""
                }
                """;
        mockMvc.perform(post("/sample/notification/channel/broadcast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBroadcast))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 2. 配置 MockWebServer 按路径分发：企微与通用返回 200，飞书返回 HTTP 500 触发单点故障隔离
        mockServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                if (path != null && path.startsWith("/webhook/feishu")) {
                    return new MockResponse().setResponseCode(500)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"code\":500,\"msg\":\"Feishu Gateway Error\"}");
                }
                if (path != null && path.startsWith("/webhook/generic")) {
                    return new MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"status\":\"ok\"}");
                }
                return new MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"errcode\":0,\"errmsg\":\"ok\",\"code\":0,\"msg\":\"success\"}");
            }
        });

        String broadcastPayload = """
                {
                  "channels": ["ops-alert", "feishu-ops", "generic-monitor"],
                  "content": "【全网故障预警】核心支付链路发生局部超时，请各端值班人排查"
                }
                """;

        mockMvc.perform(post("/sample/notification/channel/broadcast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(broadcastPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data['ops-alert'].success").value(true))
                .andExpect(jsonPath("$.data['ops-alert'].platform").value("WECOM"))
                .andExpect(jsonPath("$.data['feishu-ops'].success").value(false))
                .andExpect(jsonPath("$.data['feishu-ops'].platform").value("FEISHU"))
                .andExpect(jsonPath("$.data['feishu-ops'].errorMessage").isNotEmpty())
                .andExpect(jsonPath("$.data['generic-monitor'].success").value(true))
                .andExpect(jsonPath("$.data['generic-monitor'].platform").value("GENERIC"));

        // 3. 验证 async=true 异步并发广播同样保持隔离
        String asyncBroadcastPayload = """
                {
                  "channels": ["ops-alert", "feishu-ops"],
                  "content": "异步广播单点隔离自验",
                  "async": true
                }
                """;
        mockMvc.perform(post("/sample/notification/channel/broadcast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asyncBroadcastPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data['ops-alert'].success").value(true))
                .andExpect(jsonPath("$.data['feishu-ops'].success").value(false));
    }

    @Test
    @DisplayName("端到端验证公网 imageUrl 跨端图片发信：校验钉钉 Markdown 自适应与企微图文转发")
    void testSendImageMessageWithPublicUrlEndpoint() throws Exception {
        enqueueSuccessResponse();
        enqueueSuccessResponse();

        String dingPayload = """
                {
                  "channel": "dingtalk-dev",
                  "imageUrl": "https://img.example.com/alert-banner.png"
                }
                """;
        mockMvc.perform(post("/sample/notification/message/image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dingPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.platform").value("DINGTALK"));

        RecordedRequest dingReq = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(dingReq);
        assertThat(dingReq.getBody().readUtf8())
                .contains("\"msgtype\":\"markdown\"")
                .contains("https://img.example.com/alert-banner.png");

        String wecomPayload = """
                {
                  "channel": "ops-alert",
                  "imageUrl": "https://img.example.com/alert-banner.png"
                }
                """;
        mockMvc.perform(post("/sample/notification/message/image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wecomPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.platform").value("WECOM"));

        RecordedRequest wecomReq = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(wecomReq);
        assertThat(wecomReq.getBody().readUtf8())
                .contains("https://img.example.com/alert-banner.png");
    }
}
