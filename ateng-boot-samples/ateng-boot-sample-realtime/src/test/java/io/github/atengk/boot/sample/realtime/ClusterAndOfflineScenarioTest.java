package io.github.atengk.boot.sample.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.realtime.constant.RealtimeCloseStatus;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场景 5：分布式扩展、离线消息暂存补发与 Sa-Token 封禁跨网切断实测
 *
 * @author Ateng
 * @since 2026-10-21
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("场景 5: 分布式扩展、离线消息暂存补发与 Sa-Token 封禁切断实测")
class ClusterAndOfflineScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RealtimeTemplate realtimeTemplate;

    @Autowired
    private RealtimeSessionManager sessionManager;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        if (sessionManager != null) {
            for (RealtimeSession session : sessionManager.getAllSessions()) {
                try {
                    sessionManager.removeAndClose(session.getSessionKey(), RealtimeCloseStatus.NORMAL);
                } catch (Throwable ignored) {
                }
            }
        }
        if (realtimeTemplate != null) {
            realtimeTemplate.clearOfflineMessages("user-offline-sample");
            realtimeTemplate.clearOfflineMessages("user-satoken-ban");
            realtimeTemplate.clearOfflineMessages("user-zset-test");
        }
    }

    private String doLogin(String userId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/realtime/login?userId=" + userId))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode node = objectMapper.readTree(response.body());
        return node.get("data").asText();
    }

    @Test
    @DisplayName("端到端验证: 用户离线发送持久化消息 -> 自动进入离线暂存 -> 重新上线一键拉取补发")
    void testOfflineMessageLifecycle() throws Exception {
        String testUserId = "user-offline-sample";

        // 1. 发送持久化离线消息
        HttpRequest sendReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/realtime/send-offline-persistent?userId=" + testUserId + "&message=TaskCompleted"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> sendResp = httpClient.send(sendReq, HttpResponse.BodyHandlers.ofString());
        assertThat(sendResp.statusCode()).isEqualTo(200);

        // 2. 检查离线消息总数
        HttpRequest countReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/realtime/offline-count?userId=" + testUserId))
                .GET()
                .build();
        HttpResponse<String> countResp = httpClient.send(countReq, HttpResponse.BodyHandlers.ofString());
        JsonNode countNode = objectMapper.readTree(countResp.body());
        assertThat(countNode.get("data").asInt()).isEqualTo(1);

        // 3. 一次性拉取清空离线消息
        HttpRequest pullReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/realtime/offline-messages?userId=" + testUserId))
                .GET()
                .build();
        HttpResponse<String> pullResp = httpClient.send(pullReq, HttpResponse.BodyHandlers.ofString());
        JsonNode pullNode = objectMapper.readTree(pullResp.body());
        JsonNode dataArray = pullNode.get("data");
        assertThat(dataArray.isArray()).isTrue();
        assertThat(dataArray.size()).isEqualTo(1);
        assertThat(dataArray.get(0).get("data").asText()).isEqualTo("TaskCompleted");

        // 4. 再次查询计数已归零
        HttpResponse<String> countAgainResp = httpClient.send(countReq, HttpResponse.BodyHandlers.ofString());
        JsonNode countAgainNode = objectMapper.readTree(countAgainResp.body());
        assertThat(countAgainNode.get("data").asInt()).isZero();
    }

    @Test
    @DisplayName("端到端验证: 集群度量统计 API 正常聚合与单机保底响应")
    void testClusterMetricsApi() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/realtime/cluster-metrics"))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode node = objectMapper.readTree(resp.body());
        assertThat(node.get("code").asInt()).isEqualTo(200);
        assertThat(node.get("data").asInt()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("端到端验证: Sa-Token 调用 kickout 触发长连接切断并捕获 4002 状态码")
    void testSaTokenKickoutDisconnect() throws Exception {
        String testUserId = "user-satoken-ban";
        String token = doLogin(testUserId);
        assertThat(token).isNotBlank();

        StandardWebSocketClient client = new StandardWebSocketClient();
        KickoutHandler handler = new KickoutHandler();
        String url = "ws://127.0.0.1:" + port + "/ws/realtime?token=" + token + "&clientId=c-ban&deviceType=default";
        client.execute(handler, url);

        WebSocketSession session = handler.awaitConnection(5, TimeUnit.SECONDS);
        assertThat(session.isOpen()).isTrue();

        // 验证连接已进入 sessionManager
        SessionKey key = SessionKey.of(testUserId, "default", "c-ban");
        RealtimeSession realtimeSession = null;
        for (int i = 0; i < 20; i++) {
            realtimeSession = realtimeTemplate.getSession(key);
            if (realtimeSession != null) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(realtimeSession).isNotNull();

        // 触发 Sa-Token 强退
        HttpRequest kickoutReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/realtime/sa-kickout?userId=" + testUserId))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> kickoutResp = httpClient.send(kickoutReq, HttpResponse.BodyHandlers.ofString());
        assertThat(kickoutResp.statusCode()).isEqualTo(200);

        // 验证客户端收到服务端下发的 4002 关闭帧
        CloseStatus closeStatus = handler.awaitClose(5, TimeUnit.SECONDS);
        assertThat(closeStatus).isNotNull();
        assertThat(closeStatus.getCode()).isEqualTo(RealtimeCloseStatus.USER_KICKED_OUT.getCode());
    }

    @Test
    @DisplayName("端到端验证: 离线存储 ZSet 结构非破坏性窥探与清空生命周期")
    void testZSetStoragePeekAndFifoEviction() {
        String testUserId = "user-zset-test";
        realtimeTemplate.clearOfflineMessages(testUserId);

        // 1. 发送 3 条持久化离线消息
        realtimeTemplate.sendToUser(testUserId, io.github.atengk.boot.realtime.model.RealtimeMessage.ofPersistent("evt:1", "Payload 1"));
        realtimeTemplate.sendToUser(testUserId, io.github.atengk.boot.realtime.model.RealtimeMessage.ofPersistent("evt:2", "Payload 2"));
        realtimeTemplate.sendToUser(testUserId, io.github.atengk.boot.realtime.model.RealtimeMessage.ofPersistent("evt:3", "Payload 3"));

        assertThat(realtimeTemplate.getOfflineMessageCount(testUserId)).isEqualTo(3);

        // 2. 只读窥探：消息按时间序返回，底层存储不减少
        java.util.List<io.github.atengk.boot.realtime.model.RealtimeMessage<?>> peeked = realtimeTemplate.getOfflineMessages(testUserId);
        assertThat(peeked).hasSize(3);
        assertThat(peeked.get(0).getEvent()).isEqualTo("evt:1");
        assertThat(peeked.get(1).getEvent()).isEqualTo("evt:2");
        assertThat(peeked.get(2).getEvent()).isEqualTo("evt:3");
        assertThat(realtimeTemplate.getOfflineMessageCount(testUserId)).isEqualTo(3);

        // 3. 一次性消费拉取并清空
        java.util.List<io.github.atengk.boot.realtime.model.RealtimeMessage<?>> pulled = realtimeTemplate.pullAndClearOfflineMessages(testUserId);
        assertThat(pulled).hasSize(3);
        assertThat(realtimeTemplate.getOfflineMessageCount(testUserId)).isZero();

        // 4. FIFO 淘汰验证：默认最大保留 100 条离线消息
        String fifoUserId = "user-zset-fifo";
        realtimeTemplate.clearOfflineMessages(fifoUserId);
        try {
            for (int i = 0; i < 105; i++) {
                realtimeTemplate.sendToUser(fifoUserId, io.github.atengk.boot.realtime.model.RealtimeMessage.ofPersistent("evt:" + i, "Payload-" + i));
            }
            assertThat(realtimeTemplate.getOfflineMessageCount(fifoUserId)).isEqualTo(100);
            java.util.List<io.github.atengk.boot.realtime.model.RealtimeMessage<?>> retained = realtimeTemplate.getOfflineMessages(fifoUserId);
            assertThat(retained).hasSize(100);
            assertThat(retained.get(0).getData()).isEqualTo("Payload-5");
            assertThat(retained.get(99).getData()).isEqualTo("Payload-104");
        } finally {
            realtimeTemplate.clearOfflineMessages(fifoUserId);
        }
    }

    private static class KickoutHandler extends TextWebSocketHandler {
        private final CompletableFuture<WebSocketSession> connectFuture = new CompletableFuture<>();
        private final CompletableFuture<CloseStatus> closeFuture = new CompletableFuture<>();

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            connectFuture.complete(session);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closeFuture.complete(status);
        }

        public WebSocketSession awaitConnection(long timeout, TimeUnit unit) throws Exception {
            return connectFuture.get(timeout, unit);
        }

        public CloseStatus awaitClose(long timeout, TimeUnit unit) throws Exception {
            return closeFuture.get(timeout, unit);
        }
    }
}
