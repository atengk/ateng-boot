package io.github.atengk.boot.sample.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.constant.RealtimeCloseStatus;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.ratelimit.SessionRateLimiter;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import io.github.atengk.boot.sample.realtime.handler.AudioBinaryStreamListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场景 1：WebSocket 客户端端到端上行交互实测
 *
 * @author Ateng
 * @since 2026-10-21
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("场景 1: WebSocket 客户端端到端上行交互实测")
class WebSocketActionScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RealtimeSessionManager sessionManager;

    @Autowired
    private AudioBinaryStreamListener audioListener;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("端到端验证: 客户端上行发送 chat 动作，服务端 @RealtimeAction 成功拦截并回执")
    void testWebSocketInboundActionAndAck() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        TestWebSocketHandler clientHandler = new TestWebSocketHandler();

        String url = "ws://127.0.0.1:" + port + "/ws/realtime?clientId=client-ws-01";
        client.execute(clientHandler, url);

        WebSocketSession session = clientHandler.awaitConnection(5, TimeUnit.SECONDS);
        assertThat(session.isOpen()).isTrue();

        // 构造上行聊天消息
        RealtimeMessage<Map<String, Object>> chatMsg = RealtimeMessage.of("chat", Map.of("content", "Hello Ateng Boot!"));
        session.sendMessage(new TextMessage(JsonUtil.toJsonString(chatMsg)));

        // 等待接收服务端的 chat:ack 回执响应
        String receivedPayload = clientHandler.pollMessage(5, TimeUnit.SECONDS);
        assertThat(receivedPayload).isNotNull();
        assertThat(receivedPayload).contains("chat:ack");
        assertThat(receivedPayload).contains("Message received from");

        session.close();
    }

    @Test
    @DisplayName("端到端验证: 声明式双向 Request-Response 自动应答回发与 BusinessException 转译")
    void testBidirectionalRequestResponseAndExceptionTranslation() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        TestWebSocketHandler clientHandler = new TestWebSocketHandler();

        String url = "ws://127.0.0.1:" + port + "/ws/realtime?clientId=client-req-resp";
        client.execute(clientHandler, url);

        WebSocketSession session = clientHandler.awaitConnection(5, TimeUnit.SECONDS);
        assertThat(session.isOpen()).isTrue();

        // 1. 发起正常业务请求 (order:query)
        RealtimeMessage<Map<String, Object>> queryReq = RealtimeMessage.of(
                "req-order-101",
                "order:query",
                Map.of("orderId", "ORD-888999")
        );
        session.sendMessage(new TextMessage(JsonUtil.toJsonString(queryReq)));

        // 验证接收到匹配请求 ID 的 order:query:reply 应答帧
        String queryReplyText = clientHandler.pollMessage(5, TimeUnit.SECONDS);
        assertThat(queryReplyText).isNotNull();

        JsonNode queryReplyNode = objectMapper.readTree(queryReplyText);
        assertThat(queryReplyNode.get("id").asText()).isEqualTo("req-order-101");
        assertThat(queryReplyNode.get("event").asText()).isEqualTo("order:query:reply");
        assertThat(queryReplyNode.get("data").get("code").asInt()).isEqualTo(200);
        assertThat(queryReplyNode.get("data").get("data").get("orderId").asText()).isEqualTo("ORD-888999");
        assertThat(queryReplyNode.get("data").get("data").get("status").asText()).isEqualTo("PAID");

        // 2. 发起触发业务受控异常的请求 (order:cancel)
        RealtimeMessage<Map<String, Object>> cancelReq = RealtimeMessage.of(
                "req-cancel-202",
                "order:cancel",
                Map.of("orderId", "ORD-888999")
        );
        session.sendMessage(new TextMessage(JsonUtil.toJsonString(cancelReq)));

        // 验证接收到包含 Result.failure 转译的 order:cancel:reply 应答帧
        String cancelReplyText = clientHandler.pollMessage(5, TimeUnit.SECONDS);
        assertThat(cancelReplyText).isNotNull();

        JsonNode cancelReplyNode = objectMapper.readTree(cancelReplyText);
        assertThat(cancelReplyNode.get("id").asText()).isEqualTo("req-cancel-202");
        assertThat(cancelReplyNode.get("event").asText()).isEqualTo("order:cancel:reply");
        assertThat(cancelReplyNode.get("data").get("code").asInt()).isEqualTo(40001);
        assertThat(cancelReplyNode.get("data").get("msg").asText()).contains("ORD-888999");
        assertThat(cancelReplyNode.get("data").get("msg").asText()).contains("已锁定不可取消");

        // 连接依然保持正常健康未被掐断
        assertThat(session.isOpen()).isTrue();

        session.close();
    }

    @Test
    @DisplayName("端到端验证: 客户端上行原始二进制音频帧直达 RealtimeBinaryMessageListener SPI")
    void testBinaryFrameIngestionViaListener() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        TestWebSocketHandler clientHandler = new TestWebSocketHandler();

        String url = "ws://127.0.0.1:" + port + "/ws/realtime?clientId=client-audio-01";
        client.execute(clientHandler, url);

        WebSocketSession session = clientHandler.awaitConnection(5, TimeUnit.SECONDS);
        assertThat(session.isOpen()).isTrue();

        audioListener.clear();

        // 发送原生二进制音频帧
        byte[] pcmData = new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, (byte) 0xAA, (byte) 0xFF};
        session.sendMessage(new BinaryMessage(ByteBuffer.wrap(pcmData)));

        // 验证服务端 RealtimeBinaryMessageListener 精确收到该分片
        byte[] receivedChunk = audioListener.pollChunk(5, TimeUnit.SECONDS);
        assertThat(receivedChunk).isNotNull();
        assertThat(receivedChunk).containsExactly(pcmData);

        session.close();
    }

    @Test
    @DisplayName("端到端验证: 会话级上行流量限流防刷、429 告警下发与持续洪峰 4005 阶梯断开")
    void testInSessionRateLimitingAndSteppedDisconnection() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        TestWebSocketHandler clientHandler = new TestWebSocketHandler();

        String clientId = "client-flood-01";
        String url = "ws://127.0.0.1:" + port + "/ws/realtime?clientId=" + clientId;
        client.execute(clientHandler, url);

        WebSocketSession session = clientHandler.awaitConnection(5, TimeUnit.SECONDS);
        assertThat(session.isOpen()).isTrue();

        // 获取对应的受管会话并注入确定性时钟限流器 (阈值: 3 req/s, 连续违规 2 次切断)
        SessionKey key = SessionKey.anonymous(clientId);
        RealtimeSession realtimeSession = null;
        for (int i = 0; i < 20; i++) {
            realtimeSession = sessionManager.getSession(key);
            if (realtimeSession != null) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(realtimeSession).isNotNull();

        AtomicLong clock = new AtomicLong(10_000_000L);
        realtimeSession.setRateLimiter(new SessionRateLimiter(3, 2, clock::get));

        TextMessage pingMsg = new TextMessage(JsonUtil.toJsonString(RealtimeMessage.of("chat", Map.of("content", "flood"))));

        // 1. 前 3 次正常放行
        for (int i = 0; i < 3; i++) {
            session.sendMessage(pingMsg);
            String ack = clientHandler.pollMessage(3, TimeUnit.SECONDS);
            assertThat(ack).contains("chat:ack");
        }

        // 2. 第 4 次请求超限 (第 1 次违规) -> 下发 429 警告帧，会话保持开启
        session.sendMessage(pingMsg);
        String warningMsg = clientHandler.pollMessage(3, TimeUnit.SECONDS);
        assertThat(warningMsg).isNotNull();
        assertThat(warningMsg).contains("429");
        assertThat(warningMsg).contains("Rate limit exceeded");
        assertThat(session.isOpen()).isTrue();

        // 3. 第 5 次请求持续超限 (第 2 次违规，触达上限) -> 服务端触发 4005 强制切断
        session.sendMessage(pingMsg);
        CloseStatus closeStatus = clientHandler.awaitClose(5, TimeUnit.SECONDS);
        assertThat(closeStatus).isNotNull();
        assertThat(closeStatus.getCode()).isEqualTo(RealtimeCloseStatus.RATE_LIMIT_EXCEEDED.getCode());
    }

    static class TestWebSocketHandler extends TextWebSocketHandler {
        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        private final CompletableFuture<WebSocketSession> connected = new CompletableFuture<>();
        private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            connected.complete(session);
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            queue.offer(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(status);
        }

        public WebSocketSession awaitConnection(long timeout, TimeUnit unit) throws Exception {
            return connected.get(timeout, unit);
        }

        public String pollMessage(long timeout, TimeUnit unit) throws InterruptedException {
            return queue.poll(timeout, unit);
        }

        public CloseStatus awaitClose(long timeout, TimeUnit unit) throws Exception {
            return closed.get(timeout, unit);
        }
    }
}
