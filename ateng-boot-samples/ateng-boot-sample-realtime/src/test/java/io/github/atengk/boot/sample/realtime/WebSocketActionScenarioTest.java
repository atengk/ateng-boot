package io.github.atengk.boot.sample.realtime;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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

    static class TestWebSocketHandler extends TextWebSocketHandler {
        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        private final CompletableFuture<WebSocketSession> connected = new CompletableFuture<>();

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            connected.complete(session);
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            queue.offer(message.getPayload());
        }

        public WebSocketSession awaitConnection(long timeout, TimeUnit unit) throws Exception {
            return connected.get(timeout, unit);
        }

        public String pollMessage(long timeout, TimeUnit unit) throws InterruptedException {
            return queue.poll(timeout, unit);
        }
    }
}
