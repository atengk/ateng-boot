package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.exception.NotificationSendException;
import io.github.atengk.boot.notification.model.MarkdownMessage;
import io.github.atengk.boot.notification.model.NotificationSendResult;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.model.TextMessage;
import io.github.atengk.boot.notification.provider.DingTalkNotificationProvider;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 钉钉群机器人平台驱动端到端集成测试（基于纯内存 MockWebServer）
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("钉钉群机器人驱动端到端测试")
class DingTalkNotificationProviderIntegrationTest {

    private MockWebServer server;
    private DingTalkNotificationProvider provider;
    private RestClient restClient;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        provider = new DingTalkNotificationProvider();

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
    @DisplayName("测试带加签 Secret 发送纯文本并校验 URL Query 签名参数与请求体")
    void testSendTextMessageWithSecret() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"errcode\":0,\"errmsg\":\"ok\"}")
                .addHeader("Content-Type", "application/json"));

        String webhook = server.url("/robot/send?access_token=test_token").toString();
        NotificationChannel channel = NotificationChannel.builder()
                .name("dingtalk-signed")
                .platform(Platform.DINGTALK)
                .webhook(webhook)
                .secret("test-secret-123456")
                .build();

        TextMessage message = TextMessage.of("钉钉加签自动化告警测试");
        message.atAll();

        NotificationSendResult result = provider.send(channel, message, restClient);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getChannel()).isEqualTo("dingtalk-signed");
        assertThat(result.getPlatform()).isEqualTo(Platform.DINGTALK);
        assertThat(result.getCostDuration()).isNotNull();

        RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath())
                .contains("access_token=test_token")
                .contains("&timestamp=")
                .contains("&sign=");

        String requestBody = request.getBody().readUtf8();
        assertThat(requestBody)
                .contains("\"msgtype\":\"text\"")
                .contains("钉钉加签自动化告警测试")
                .contains("\"isAtAll\":true");
    }

    @Test
    @DisplayName("测试无加签 Secret 发送 Markdown 消息")
    void testSendMarkdownWithoutSecret() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"errcode\":0,\"errmsg\":\"ok\"}")
                .addHeader("Content-Type", "application/json"));

        String webhook = server.url("/robot/send?access_token=no_sign_token").toString();
        NotificationChannel channel = NotificationChannel.builder()
                .name("dingtalk-nosign")
                .platform(Platform.DINGTALK)
                .webhook(webhook)
                .build();

        MarkdownMessage message = MarkdownMessage.of("告警标题", "### 系统负载异常\n> CPU 使用率达到 95%");

        NotificationSendResult result = provider.send(channel, message, restClient);
        assertThat(result.isSuccess()).isTrue();

        RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).doesNotContain("timestamp=").doesNotContain("sign=");

        String requestBody = request.getBody().readUtf8();
        assertThat(requestBody)
                .contains("\"msgtype\":\"markdown\"")
                .contains("告警标题")
                .contains("CPU 使用率达到 95%");
    }

    @Test
    @DisplayName("测试平台返回鉴权失败 (errcode=300001) 转译为业务异常")
    void testAuthFailureResponse() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"errcode\":300001,\"errmsg\":\"token is not exist\"}")
                .addHeader("Content-Type", "application/json"));

        String webhook = server.url("/robot/send?access_token=invalid_token").toString();
        NotificationChannel channel = NotificationChannel.builder()
                .name("dingtalk-auth-fail")
                .platform(Platform.DINGTALK)
                .webhook(webhook)
                .build();

        TextMessage message = TextMessage.of("测试消息");

        assertThatThrownBy(() -> provider.send(channel, message, restClient))
                .isInstanceOf(NotificationSendException.class)
                .satisfies(ex -> {
                    NotificationSendException sex = (NotificationSendException) ex;
                    assertThat(sex.getResultCode()).isEqualTo(NotificationResultCode.NOTIFICATION_AUTHENTICATION_ERROR);
                    assertThat(sex.getRawResponse()).contains("300001");
                });
    }

    @Test
    @DisplayName("测试平台返回频控拦截 (errcode=130101) 转译为频控异常")
    void testRateLimitResponse() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"errcode\":130101,\"errmsg\":\"send too fast, please try later\"}")
                .addHeader("Content-Type", "application/json"));

        String webhook = server.url("/robot/send?access_token=test_token").toString();
        NotificationChannel channel = NotificationChannel.builder()
                .name("dingtalk-rate-limit")
                .platform(Platform.DINGTALK)
                .webhook(webhook)
                .build();

        TextMessage message = TextMessage.of("测试消息");

        assertThatThrownBy(() -> provider.send(channel, message, restClient))
                .isInstanceOf(NotificationSendException.class)
                .satisfies(ex -> {
                    NotificationSendException sex = (NotificationSendException) ex;
                    assertThat(sex.getResultCode()).isEqualTo(NotificationResultCode.NOTIFICATION_RATE_LIMIT_EXCEEDED);
                    assertThat(sex.getRawResponse()).contains("130101");
                });
    }

    @Test
    @DisplayName("测试平台 HTTP 500 异常转译")
    void testHttp500Response() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"));

        String webhook = server.url("/robot/send?access_token=test_token").toString();
        NotificationChannel channel = NotificationChannel.builder()
                .name("dingtalk-500")
                .platform(Platform.DINGTALK)
                .webhook(webhook)
                .build();

        TextMessage message = TextMessage.of("测试消息");

        assertThatThrownBy(() -> provider.send(channel, message, restClient))
                .isInstanceOf(NotificationSendException.class)
                .satisfies(ex -> {
                    NotificationSendException sex = (NotificationSendException) ex;
                    assertThat(sex.getResultCode()).isEqualTo(NotificationResultCode.NOTIFICATION_SEND_ERROR);
                });
    }
}
