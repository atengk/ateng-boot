package io.github.atengk.boot.sample.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 场景 4：三阶握手鉴权与 Sa-Token 自动桥接实测
 *
 * @author Ateng
 * @since 2026-10-21
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("场景 4: 三阶握手鉴权与 Sa-Token 自动桥接实测")
class RealtimeAuthScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RealtimeTemplate realtimeTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 辅助方法：通过 Web 登录接口获取真实 Sa-Token 凭据
     */
    private String doLogin(String userId) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/realtime/login?userId=" + userId))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode jsonNode = objectMapper.readTree(response.body());
        assertThat(jsonNode.get("code").asInt()).isEqualTo(200);
        return jsonNode.get("data").asText();
    }

    @Test
    @DisplayName("端到端验证: Sa-Token 登录生成真实凭据，WebSocket 握手成功并正确绑定登录用户")
    void testWebSocketAuthSuccess() throws Exception {
        String token = doLogin("88888");
        assertThat(token).isNotBlank();

        StandardWebSocketClient client = new StandardWebSocketClient();
        AuthTestHandler handler = new AuthTestHandler();

        String url = "ws://127.0.0.1:" + port + "/ws/realtime?token=" + token + "&clientId=auth-client-01&deviceType=default";
        client.execute(handler, url);

        WebSocketSession session = handler.awaitConnection(5, TimeUnit.SECONDS);
        assertThat(session.isOpen()).isTrue();

        // 验证服务端登记的用户为 88888 (防御网络与容器线程调度时序)
        SessionKey key = SessionKey.of("88888", "default", "auth-client-01");
        RealtimeSession realtimeSession = null;
        for (int i = 0; i < 20; i++) {
            realtimeSession = realtimeTemplate.getSession(key);
            if (realtimeSession != null) {
                break;
            }
            Thread.sleep(50);
        }
        if (realtimeSession == null) {
            System.err.println(">>> All sessions registered: " + realtimeTemplate.getAllSessions().stream().map(RealtimeSession::getSessionKey).toList());
        }
        assertThat(realtimeSession).isNotNull();
        assertThat(realtimeSession.getUserId()).isEqualTo("88888");

        session.close();
    }

    @Test
    @DisplayName("端到端验证: 携带非法伪造 Token 发起 WebSocket 握手，握手被拦截拒绝")
    void testWebSocketAuthFailed() throws Exception {
        java.net.URL url = new URI("http://127.0.0.1:" + port + "/ws/realtime?token=illegal-fake-token-xxx&clientId=bad-client-01").toURL();
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Upgrade", "websocket");
        conn.setRequestProperty("Connection", "Upgrade");
        conn.setRequestProperty("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==");
        conn.setRequestProperty("Sec-WebSocket-Version", "13");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);

        int responseCode = conn.getResponseCode();
        assertThat(responseCode).isIn(401, 403);
    }

    @Test
    @DisplayName("端到端验证: SSE 端点携带有效 Token 响应 200，携带非法 Token 响应 401")
    void testSseAuthValidation() throws Exception {
        String validToken = doLogin("66666");

        HttpClient client = HttpClient.newHttpClient();

        // 1. 有效 Token 测试 (SSE 为长连接，需流式获取响应头后即刻关闭)
        HttpRequest validRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/sse/realtime?token=" + validToken + "&clientId=sse-auth-01"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        CompletableFuture<HttpResponse<java.io.InputStream>> future =
                client.sendAsync(validRequest, HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<java.io.InputStream> validResponse = future.get(5, TimeUnit.SECONDS);
        assertThat(validResponse.statusCode()).isEqualTo(200);
        validResponse.body().close();

        // 2. 非法 Token 测试 (401 立即返回短响应)
        HttpRequest invalidRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/sse/realtime?token=illegal-token-bad&clientId=sse-bad-01"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<Void> invalidResponse = client.send(invalidRequest, HttpResponse.BodyHandlers.discarding());
        assertThat(invalidResponse.statusCode()).isEqualTo(401);
    }

    static class AuthTestHandler extends TextWebSocketHandler {
        private final CompletableFuture<WebSocketSession> connected = new CompletableFuture<>();

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            connected.complete(session);
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            connected.completeExceptionally(exception);
        }

        public WebSocketSession awaitConnection(long timeout, TimeUnit unit) throws Exception {
            return connected.get(timeout, unit);
        }
    }
}
