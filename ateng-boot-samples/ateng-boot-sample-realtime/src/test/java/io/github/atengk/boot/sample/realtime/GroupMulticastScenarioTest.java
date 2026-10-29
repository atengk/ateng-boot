package io.github.atengk.boot.sample.realtime;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场景 3：多端分组协同与房间组播实测
 *
 * @author Ateng
 * @since 2026-10-21
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("场景 3: 多端分组协同与房间组播实测")
class GroupMulticastScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RealtimeTemplate realtimeTemplate;

    @Test
    @DisplayName("端到端验证: 多客户端加入同一房间并通过 RealtimeTemplate.sendToGroup 组播互通")
    void testGroupJoinAndBroadcast() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();

        TestHandler handlerA = new TestHandler();
        TestHandler handlerB = new TestHandler();

        String urlA = "ws://127.0.0.1:" + port + "/ws/realtime?clientId=group-client-A";
        String urlB = "ws://127.0.0.1:" + port + "/ws/realtime?clientId=group-client-B";

        client.execute(handlerA, urlA);
        client.execute(handlerB, urlB);

        WebSocketSession sessionA = handlerA.awaitConnection(5, TimeUnit.SECONDS);
        WebSocketSession sessionB = handlerB.awaitConnection(5, TimeUnit.SECONDS);

        assertThat(sessionA.isOpen()).isTrue();
        assertThat(sessionB.isOpen()).isTrue();

        // 客户端 A 发送加入房间 join-room
        RealtimeMessage<Map<String, Object>> joinA = RealtimeMessage.of("join-room", Map.of("roomId", "room-888"));
        sessionA.sendMessage(new TextMessage(JsonUtil.toJsonString(joinA)));

        // 客户端 B 发送加入房间 join-room
        RealtimeMessage<Map<String, Object>> joinB = RealtimeMessage.of("join-room", Map.of("roomId", "room-888"));
        sessionB.sendMessage(new TextMessage(JsonUtil.toJsonString(joinB)));

        // 确认双方均收到房间加入确认
        String ackA = handlerA.pollMessage(5, TimeUnit.SECONDS);
        String ackB = handlerB.pollMessage(5, TimeUnit.SECONDS);
        assertThat(ackA).contains("room:joined");
        assertThat(ackB).contains("room:joined");

        // 验证当前群组成员数为 2
        assertThat(realtimeTemplate.getGroupMemberCount("room-888")).isEqualTo(2);

        // 通过 RealtimeTemplate 向 room-888 广播群组消息
        RealtimeMessage<String> groupMsg = RealtimeMessage.of("group:notice", "Welcome to room 888!");
        int sentCount = realtimeTemplate.sendToGroup("room-888", groupMsg);
        assertThat(sentCount).isEqualTo(2);

        // 双方均应收到该组播消息
        String msgA = handlerA.pollMessage(5, TimeUnit.SECONDS);
        String msgB = handlerB.pollMessage(5, TimeUnit.SECONDS);
        assertThat(msgA).contains("group:notice").contains("Welcome to room 888!");
        assertThat(msgB).contains("group:notice").contains("Welcome to room 888!");

        sessionA.close();
        sessionB.close();
    }

    @Test
    @DisplayName("端到端验证: 群组成员全集群查询、广播解散通知与生命周期闭环")
    void testGroupDismissalAndMemberLifecycle() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();

        TestHandler handler1 = new TestHandler();
        TestHandler handler2 = new TestHandler();

        String url1 = "ws://127.0.0.1:" + port + "/ws/realtime?clientId=dismiss-client-1";
        String url2 = "ws://127.0.0.1:" + port + "/ws/realtime?clientId=dismiss-client-2";

        client.execute(handler1, url1);
        client.execute(handler2, url2);

        WebSocketSession session1 = handler1.awaitConnection(5, TimeUnit.SECONDS);
        WebSocketSession session2 = handler2.awaitConnection(5, TimeUnit.SECONDS);

        assertThat(session1.isOpen()).isTrue();
        assertThat(session2.isOpen()).isTrue();

        String roomId = "room-dismiss-777";

        // 加入房间
        RealtimeMessage<Map<String, Object>> join1 = RealtimeMessage.of("join-room", Map.of("roomId", roomId));
        session1.sendMessage(new TextMessage(JsonUtil.toJsonString(join1)));
        RealtimeMessage<Map<String, Object>> join2 = RealtimeMessage.of("join-room", Map.of("roomId", roomId));
        session2.sendMessage(new TextMessage(JsonUtil.toJsonString(join2)));

        assertThat(handler1.pollMessage(5, TimeUnit.SECONDS)).contains("room:joined");
        assertThat(handler2.pollMessage(5, TimeUnit.SECONDS)).contains("room:joined");

        // 1. 全集群成员查询与计数验证
        Set<SessionKey> members = realtimeTemplate.getClusterGroupMembers(roomId);
        assertThat(members).hasSize(2);
        assertThat(realtimeTemplate.getClusterGroupMemberCount(roomId)).isEqualTo(2);

        // 2. 广播解散房间
        int dismissed = realtimeTemplate.dismissGroup(roomId);
        assertThat(dismissed).isEqualTo(2);

        // 3. 验证客户端均收到 group_dismissed 广播通知帧
        String dismissNotice1 = handler1.pollMessage(5, TimeUnit.SECONDS);
        String dismissNotice2 = handler2.pollMessage(5, TimeUnit.SECONDS);
        assertThat(dismissNotice1).contains(RealtimeMessage.EVENT_GROUP_DISMISSED).contains(roomId);
        assertThat(dismissNotice2).contains(RealtimeMessage.EVENT_GROUP_DISMISSED).contains(roomId);

        // 4. 验证解散后群组清空
        assertThat(realtimeTemplate.getClusterGroupMemberCount(roomId)).isZero();
        assertThat(realtimeTemplate.getClusterGroupMembers(roomId)).isEmpty();

        session1.close();
        session2.close();
    }

    static class TestHandler extends TextWebSocketHandler {
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
