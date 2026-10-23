package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.exception.NotificationSendException;
import io.github.atengk.boot.notification.model.CardButton;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.model.MarkdownMessage;
import io.github.atengk.boot.notification.model.NotificationSendResult;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.provider.WeComNotificationProvider;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 企业微信群机器人平台驱动端到端集成测试（基于纯内存 MockWebServer）
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("企业微信群机器人驱动端到端测试")
class WeComNotificationProviderIntegrationTest {

    private MockWebServer server;
    private WeComNotificationProvider provider;
    private RestClient restClient;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        provider = new WeComNotificationProvider();

        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        restClient = RestClient.builder().requestFactory(factory).build();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("测试发送 Markdown 消息并验证表格自动降级转译为引用块")
    void testSendMarkdownWithTableSanitization() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"errcode\":0,\"errmsg\":\"ok\"}")
                .addHeader("Content-Type", "application/json"));

        String webhook = server.url("/cgi-bin/webhook/send?key=wecom_key_123").toString();
        NotificationChannel channel = NotificationChannel.builder()
                .name("wecom-ops")
                .platform(Platform.WECOM)
                .webhook(webhook)
                .build();

        String rawContent = """
                ### 实时指标
                | 节点 | 状态 |
                | :--- | :--- |
                | node-1 | 正常 |
                """;

        MarkdownMessage message = MarkdownMessage.of("监控日报", rawContent);

        NotificationSendResult result = provider.send(channel, message, restClient);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getChannel()).isEqualTo("wecom-ops");
        assertThat(result.getPlatform()).isEqualTo(Platform.WECOM);

        RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).contains("key=wecom_key_123");

        String requestBody = request.getBody().readUtf8();
        assertThat(requestBody)
                .contains("\"msgtype\":\"markdown\"")
                .contains("> **节点**: node-1")
                .doesNotContain("| :--- |");
    }

    @Test
    @DisplayName("测试发送 CardMessage 转译为企微 template_card")
    void testSendCardMessage() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"errcode\":0,\"errmsg\":\"ok\"}")
                .addHeader("Content-Type", "application/json"));

        String webhook = server.url("/cgi-bin/webhook/send?key=wecom_key_card").toString();
        NotificationChannel channel = NotificationChannel.builder()
                .name("wecom-card")
                .platform(Platform.WECOM)
                .webhook(webhook)
                .build();

        CardMessage card = CardMessage.of("工单待审批", "张三提交了请假申请")
                .button(CardButton.primary("前往审批", "https://oa.example.com/approve"));

        NotificationSendResult result = provider.send(channel, card, restClient);
        assertThat(result.isSuccess()).isTrue();

        RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        String requestBody = request.getBody().readUtf8();
        assertThat(requestBody)
                .contains("\"msgtype\":\"template_card\"")
                .contains("\"card_type\":\"text_notice\"")
                .contains("工单待审批")
                .contains("前往审批");
    }

    @Test
    @DisplayName("测试企微鉴权失败 (errcode=93000) 业务异常转译")
    void testAuthFailure() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"errcode\":93000,\"errmsg\":\"invalid webhook url\"}")
                .addHeader("Content-Type", "application/json"));

        String webhook = server.url("/cgi-bin/webhook/send?key=invalid").toString();
        NotificationChannel channel = NotificationChannel.builder()
                .name("wecom-invalid")
                .platform(Platform.WECOM)
                .webhook(webhook)
                .build();

        MarkdownMessage message = MarkdownMessage.of("hello");

        assertThatThrownBy(() -> provider.send(channel, message, restClient))
                .isInstanceOf(NotificationSendException.class)
                .satisfies(ex -> {
                    NotificationSendException sex = (NotificationSendException) ex;
                    assertThat(sex.getResultCode()).isEqualTo(NotificationResultCode.NOTIFICATION_AUTHENTICATION_ERROR);
                    assertThat(sex.getRawResponse()).contains("93000");
                });
    }

    @Test
    @DisplayName("测试企微频控拦截 (errcode=45009) 业务异常转译")
    void testRateLimit() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"errcode\":45009,\"errmsg\":\"api freq out of limit\"}")
                .addHeader("Content-Type", "application/json"));

        String webhook = server.url("/cgi-bin/webhook/send?key=test").toString();
        NotificationChannel channel = NotificationChannel.builder()
                .name("wecom-limit")
                .platform(Platform.WECOM)
                .webhook(webhook)
                .build();

        MarkdownMessage message = MarkdownMessage.of("hello");

        assertThatThrownBy(() -> provider.send(channel, message, restClient))
                .isInstanceOf(NotificationSendException.class)
                .satisfies(ex -> {
                    NotificationSendException sex = (NotificationSendException) ex;
                    assertThat(sex.getResultCode()).isEqualTo(NotificationResultCode.NOTIFICATION_RATE_LIMIT_EXCEEDED);
                    assertThat(sex.getRawResponse()).contains("45009");
                });
    }
}
