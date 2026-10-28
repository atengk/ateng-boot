package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.exception.NotificationSendException;
import io.github.atengk.boot.notification.model.CardButton;
import io.github.atengk.boot.notification.model.CardColor;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.model.NotificationSendResult;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.model.TextMessage;
import io.github.atengk.boot.notification.provider.FeishuNotificationProvider;
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
 * 飞书群机器人平台驱动端到端集成测试（基于纯内存 MockWebServer）
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("飞书群机器人驱动端到端测试")
class FeishuNotificationProviderIntegrationTest {

    private MockWebServer server;
    private FeishuNotificationProvider provider;
    private RestClient restClient;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        provider = new FeishuNotificationProvider();

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
    @DisplayName("测试带加签 Secret 发送纯文本并校验请求体内置 timestamp 与 sign 字段")
    void testSendTextMessageWithSecret() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"code\":0,\"msg\":\"success\"}")
                .addHeader("Content-Type", "application/json"));

        String webhook = server.url("/open-apis/bot/v2/hook/feishu_token").toString();
        NotificationChannel channel = NotificationChannel.builder()
                .name("feishu-signed")
                .platform(Platform.FEISHU)
                .webhook(webhook)
                .secret("feishu_secret_123")
                .build();

        TextMessage message = TextMessage.of("飞书加签消息");

        NotificationSendResult result = provider.send(channel, message, restClient);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getChannel()).isEqualTo("feishu-signed");
        assertThat(result.getPlatform()).isEqualTo(Platform.FEISHU);

        RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");

        String requestBody = request.getBody().readUtf8();
        assertThat(requestBody)
                .contains("\"timestamp\":")
                .contains("\"sign\":")
                .contains("\"msg_type\":\"text\"")
                .contains("飞书加签消息");
    }

    @Test
    @DisplayName("测试发送 CardMessage 构建飞书 Interactive v2 卡片")
    void testSendCardMessage() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"code\":0,\"msg\":\"success\"}")
                .addHeader("Content-Type", "application/json"));

        String webhook = server.url("/open-apis/bot/v2/hook/feishu_card").toString();
        NotificationChannel channel = NotificationChannel.builder()
                .name("feishu-card")
                .platform(Platform.FEISHU)
                .webhook(webhook)
                .build();

        CardMessage card = CardMessage.of("发布部署成功", "应用 ateng-boot 生产环境已发布至最新版本")
                .headerColor(CardColor.GREEN)
                .button(CardButton.primary("查看流水线", "https://ci.example.com/build/100"));

        NotificationSendResult result = provider.send(channel, card, restClient);
        assertThat(result.isSuccess()).isTrue();

        RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        String requestBody = request.getBody().readUtf8();
        assertThat(requestBody)
                .contains("\"msg_type\":\"interactive\"")
                .contains("\"card\":")
                .contains("发布部署成功")
                .contains("查看流水线");
    }

    @Test
    @DisplayName("测试飞书签名校验失败 (code=19001) 业务异常转译")
    void testSignFailure() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"code\":19001,\"msg\":\"sign match fail\"}")
                .addHeader("Content-Type", "application/json"));

        String webhook = server.url("/open-apis/bot/v2/hook/feishu_err").toString();
        NotificationChannel channel = NotificationChannel.builder()
                .name("feishu-err")
                .platform(Platform.FEISHU)
                .webhook(webhook)
                .build();

        TextMessage message = TextMessage.of("hello");

        assertThatThrownBy(() -> provider.send(channel, message, restClient))
                .isInstanceOf(NotificationSendException.class)
                .satisfies(ex -> {
                    NotificationSendException sex = (NotificationSendException) ex;
                    assertThat(sex.getResultCode()).isEqualTo(NotificationResultCode.NOTIFICATION_AUTHENTICATION_ERROR);
                    assertThat(sex.getRawResponse()).contains("19001");
                });
    }

    @Test
    @DisplayName("测试飞书频控拦截 (code=99991400) 业务异常转译")
    void testRateLimit() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"code\":99991400,\"msg\":\"request frequency trigger limit\"}")
                .addHeader("Content-Type", "application/json"));

        String webhook = server.url("/open-apis/bot/v2/hook/feishu_limit").toString();
        NotificationChannel channel = NotificationChannel.builder()
                .name("feishu-limit")
                .platform(Platform.FEISHU)
                .webhook(webhook)
                .build();

        TextMessage message = TextMessage.of("hello");

        assertThatThrownBy(() -> provider.send(channel, message, restClient))
                .isInstanceOf(NotificationSendException.class)
                .satisfies(ex -> {
                    NotificationSendException sex = (NotificationSendException) ex;
                    assertThat(sex.getResultCode()).isEqualTo(NotificationResultCode.NOTIFICATION_RATE_LIMIT_EXCEEDED);
                    assertThat(sex.getRawResponse()).contains("99991400");
                });
    }
}
