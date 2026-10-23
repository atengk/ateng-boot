package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.exception.NotificationSendException;
import io.github.atengk.boot.notification.model.NotificationSendResult;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.model.TextMessage;
import io.github.atengk.boot.notification.provider.GenericWebhookNotificationProvider;
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
 * 通用 Webhook 平台驱动端到端集成测试（基于纯内存 MockWebServer）
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("通用 Webhook 驱动端到端测试")
class GenericWebhookNotificationProviderIntegrationTest {

    private MockWebServer server;
    private GenericWebhookNotificationProvider provider;
    private RestClient restClient;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        provider = new GenericWebhookNotificationProvider();

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
    @DisplayName("测试通用 Webhook 发送并校验 X-Webhook-Secret 与 X-Signature-SHA256 请求头")
    void testSendWithSecretHeaders() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"status\":\"success\"}")
                .addHeader("Content-Type", "application/json"));

        String webhook = server.url("/api/v1/webhook").toString();
        NotificationChannel channel = NotificationChannel.builder()
                .name("generic-channel")
                .platform(Platform.GENERIC)
                .webhook(webhook)
                .secret("my_super_secret_key")
                .build();

        TextMessage message = TextMessage.of("通用告警通知");

        NotificationSendResult result = provider.send(channel, message, restClient);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getChannel()).isEqualTo("generic-channel");
        assertThat(result.getPlatform()).isEqualTo(Platform.GENERIC);

        RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getHeader(GenericWebhookNotificationProvider.HEADER_WEBHOOK_SECRET))
                .isEqualTo("my_super_secret_key");
        assertThat(request.getHeader(GenericWebhookNotificationProvider.HEADER_SIGNATURE_SHA256))
                .isNotBlank()
                .hasSize(64);

        String requestBody = request.getBody().readUtf8();
        assertThat(requestBody)
                .contains("\"msgType\":\"text\"")
                .contains("通用告警通知");
    }

    @Test
    @DisplayName("测试 HTTP 401 鉴权失败异常转译")
    void testHttp401Unauthorized() {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Unauthorized\"}"));

        String webhook = server.url("/api/v1/webhook").toString();
        NotificationChannel channel = NotificationChannel.builder()
                .name("generic-401")
                .platform(Platform.GENERIC)
                .webhook(webhook)
                .build();

        TextMessage message = TextMessage.of("hello");

        assertThatThrownBy(() -> provider.send(channel, message, restClient))
                .isInstanceOf(NotificationSendException.class)
                .satisfies(ex -> {
                    NotificationSendException sex = (NotificationSendException) ex;
                    assertThat(sex.getResultCode()).isEqualTo(NotificationResultCode.NOTIFICATION_AUTHENTICATION_ERROR);
                });
    }

    @Test
    @DisplayName("测试 HTTP 429 频控超限异常转译")
    void testHttp429TooManyRequests() {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Too Many Requests\"}"));

        String webhook = server.url("/api/v1/webhook").toString();
        NotificationChannel channel = NotificationChannel.builder()
                .name("generic-429")
                .platform(Platform.GENERIC)
                .webhook(webhook)
                .build();

        TextMessage message = TextMessage.of("hello");

        assertThatThrownBy(() -> provider.send(channel, message, restClient))
                .isInstanceOf(NotificationSendException.class)
                .satisfies(ex -> {
                    NotificationSendException sex = (NotificationSendException) ex;
                    assertThat(sex.getResultCode()).isEqualTo(NotificationResultCode.NOTIFICATION_RATE_LIMIT_EXCEEDED);
                });
    }
}
