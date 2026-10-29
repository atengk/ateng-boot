package io.github.atengk.boot.realtime.websocket;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.dispatcher.RealtimeBinaryMessageListener;
import io.github.atengk.boot.realtime.dispatcher.RealtimeMessageDispatcher;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 核心原生 WebSocket 处理器单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("AtengWebSocketHandler 消息处理器测试")
class AtengWebSocketHandlerTest {

    private final AtengWebSocketHandler handler = new AtengWebSocketHandler();

    @Test
    void testPlainPingPong() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-001");

        handler.handleTextMessage(session, new TextMessage("ping"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(1)).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).isEqualTo("pong");
    }

    @Test
    void testJsonPingPong() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-002");

        RealtimeMessage<String> pingMessage = RealtimeMessage.ping();
        handler.handleTextMessage(session, new TextMessage(JsonUtil.toJsonString(pingMessage)));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(1)).sendMessage(captor.capture());
        RealtimeMessage<?> pongResponse = JsonUtil.parseObject(captor.getValue().getPayload(), RealtimeMessage.class);
        assertThat(pongResponse).isNotNull();
        assertThat(pongResponse.getEvent()).isEqualTo(RealtimeMessage.EVENT_PONG);
        assertThat(pongResponse.getData()).isEqualTo("pong");
    }

    @Test
    void testNormalMessagePassThrough() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-003");

        RealtimeMessage<String> bizMessage = RealtimeMessage.of("chat:send", "hello");
        handler.handleTextMessage(session, new TextMessage(JsonUtil.toJsonString(bizMessage)));

        verify(session, never()).sendMessage(any());
    }

    @Test
    void testLifecycleMethodsDoNotThrow() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-004");

        handler.afterConnectionEstablished(session);
        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[]{1, 2, 3})));
        handler.handlePongMessage(session, new PongMessage(ByteBuffer.wrap(new byte[]{0})));
        handler.handleTransportError(session, new RuntimeException("network glitch"));
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(handler.supportsPartialMessages()).isFalse();
    }

    @Test
    @DisplayName("WebSocket 会话自动注册、活跃时间刷新与上行 Action 分发")
    void testSessionManagerAutoRegistrationAndDispatch() throws Exception {
        RealtimeSessionManager sessionManager = new RealtimeSessionManager();
        RealtimeMessageDispatcher dispatcher = mock(RealtimeMessageDispatcher.class);
        AtengWebSocketHandler managedHandler = new AtengWebSocketHandler(sessionManager, dispatcher);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-sess-100");
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        SessionKey key = SessionKey.of("user-ws", "pc", "client-ws");
        attrs.put(AtengWebSocketHandler.SESSION_KEY_ATTR, key);
        attrs.put(AtengWebSocketHandler.TENANT_ID_ATTR, 555L);
        when(session.getAttributes()).thenReturn(attrs);

        // 连接建立
        managedHandler.afterConnectionEstablished(session);
        RealtimeSession realtimeSession = sessionManager.getSession(key);
        assertThat(realtimeSession).isNotNull();
        assertThat(realtimeSession.getTenantId()).isEqualTo(555L);
        assertThat(sessionManager.getOnlineCount()).isEqualTo(1);

        // 接收 Pong 协议帧刷新活跃时间
        managedHandler.handlePongMessage(session, new PongMessage(ByteBuffer.wrap(new byte[]{0})));

        // 发送普通业务消息，触发 dispatcher.dispatch()
        managedHandler.handleTextMessage(session, new TextMessage("{\"event\":\"order:pay\",\"data\":\"100\"}"));
        verify(dispatcher, times(1)).dispatch(eq(realtimeSession), any(RealtimeMessage.class));

        // 连接断开
        managedHandler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertThat(sessionManager.getSession(key)).isNull();
        assertThat(sessionManager.getOnlineCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("限流开启时单秒超限触发 429 丢弃警告，持续恶意刷量触发 4005 强制切断")
    void testRateLimitEscalationAndClosure() throws Exception {
        io.github.atengk.boot.realtime.properties.RealtimeProperties props = new io.github.atengk.boot.realtime.properties.RealtimeProperties();
        props.getWebsocket().getRateLimit().setEnabled(true);
        props.getWebsocket().getRateLimit().setMaxRequestsPerSecond(3);
        props.getWebsocket().getRateLimit().setConsecutiveViolationsBeforeClose(2);

        RealtimeSessionManager sessionManager = new RealtimeSessionManager(props);
        RealtimeMessageDispatcher dispatcher = mock(RealtimeMessageDispatcher.class);
        AtengWebSocketHandler managedHandler = new AtengWebSocketHandler(sessionManager, dispatcher, props);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("rate-sess-001");
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        SessionKey key = SessionKey.of("user-rate", "pc", "client-rate");
        attrs.put(AtengWebSocketHandler.SESSION_KEY_ATTR, key);
        when(session.getAttributes()).thenReturn(attrs);

        // 1. 建立连接，注入确定性时钟限流器验证阶梯行为
        managedHandler.afterConnectionEstablished(session);
        RealtimeSession realtimeSession = sessionManager.getSession(key);
        assertThat(realtimeSession).isNotNull();
        java.util.concurrent.atomic.AtomicLong testClock = new java.util.concurrent.atomic.AtomicLong(1000000L);
        realtimeSession.setRateLimiter(new io.github.atengk.boot.realtime.ratelimit.SessionRateLimiter(3, 2, testClock::get));
        assertThat(realtimeSession.getRateLimiter()).isNotNull();

        // 2. 前 3 次请求放行（分发器被调用 3 次）
        TextMessage msg = new TextMessage("{\"event\":\"test:event\",\"data\":\"data\"}");
        for (int i = 0; i < 3; i++) {
            managedHandler.handleTextMessage(session, msg);
        }
        verify(dispatcher, times(3)).dispatch(eq(realtimeSession), any(RealtimeMessage.class));

        // 3. 第 4 次请求超限 (第 1 次违规) -> 丢弃且不下发给 dispatcher，发送 429 警告帧
        managedHandler.handleTextMessage(session, msg);
        verify(dispatcher, times(3)).dispatch(eq(realtimeSession), any(RealtimeMessage.class)); // 依然是 3 次
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("429");
        assertThat(realtimeSession.isOpen()).isTrue();

        // 4. 第 5 次请求超限 (第 2 次违规，达到阈值 2) -> 触发 4005 RATE_LIMIT_EXCEEDED 强制断开连接
        managedHandler.handleTextMessage(session, msg);
        verify(session, atLeastOnce()).close(eq(io.github.atengk.boot.realtime.constant.RealtimeCloseStatus.RATE_LIMIT_EXCEEDED));
    }

    @Test
    @DisplayName("心跳检测优先于限流: 高频字符串 ping 与 JSON ping 不消耗业务限流额度")
    void testPingHeartbeatDoesNotConsumeRateLimitQuota() throws Exception {
        io.github.atengk.boot.realtime.properties.RealtimeProperties props = new io.github.atengk.boot.realtime.properties.RealtimeProperties();
        props.getWebsocket().getRateLimit().setEnabled(true);
        props.getWebsocket().getRateLimit().setMaxRequestsPerSecond(2);
        props.getWebsocket().getRateLimit().setConsecutiveViolationsBeforeClose(2);

        RealtimeSessionManager sessionManager = new RealtimeSessionManager(props);
        RealtimeMessageDispatcher dispatcher = mock(RealtimeMessageDispatcher.class);
        AtengWebSocketHandler managedHandler = new AtengWebSocketHandler(sessionManager, dispatcher, props);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("rate-ping-001");
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        SessionKey key = SessionKey.of("user-ping", "pc", "client-ping");
        attrs.put(AtengWebSocketHandler.SESSION_KEY_ATTR, key);
        when(session.getAttributes()).thenReturn(attrs);

        managedHandler.afterConnectionEstablished(session);
        RealtimeSession realtimeSession = sessionManager.getSession(key);
        assertThat(realtimeSession).isNotNull();

        // 1. 发送 10 次字符串 ping 和 10 次 JSON ping
        TextMessage stringPing = new TextMessage("ping");
        TextMessage jsonPing = new TextMessage(JsonUtil.toJsonString(RealtimeMessage.ping()));
        for (int i = 0; i < 10; i++) {
            managedHandler.handleTextMessage(session, stringPing);
            managedHandler.handleTextMessage(session, jsonPing);
        }

        // 心跳不经过 dispatcher，不消耗限流额度，连接正常存活且未收到 429
        verify(dispatcher, never()).dispatch(any(), any());
        verify(session, never()).close(any());

        // 2. 紧接着发送 2 次业务消息，依然可以全量正常放行（未被心跳占用额度）
        TextMessage bizMsg = new TextMessage("{\"event\":\"chat:send\",\"data\":\"msg\"}");
        managedHandler.handleTextMessage(session, bizMsg);
        managedHandler.handleTextMessage(session, bizMsg);
        verify(dispatcher, times(2)).dispatch(eq(realtimeSession), any(RealtimeMessage.class));
    }

    @Test
    @DisplayName("限流 429 告警帧网络发送失败时不崩溃抛出异常，保持会话稳定")
    void testRateLimitWarningSendFailureDoesNotCrashSocket() throws Exception {
        io.github.atengk.boot.realtime.properties.RealtimeProperties props = new io.github.atengk.boot.realtime.properties.RealtimeProperties();
        props.getWebsocket().getRateLimit().setEnabled(true);
        props.getWebsocket().getRateLimit().setMaxRequestsPerSecond(1);
        props.getWebsocket().getRateLimit().setConsecutiveViolationsBeforeClose(5);

        RealtimeSessionManager sessionManager = new RealtimeSessionManager(props);
        RealtimeMessageDispatcher dispatcher = mock(RealtimeMessageDispatcher.class);
        AtengWebSocketHandler managedHandler = new AtengWebSocketHandler(sessionManager, dispatcher, props);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("rate-warn-err-001");
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        SessionKey key = SessionKey.of("user-warn-err", "pc", "client-warn-err");
        attrs.put(AtengWebSocketHandler.SESSION_KEY_ATTR, key);
        when(session.getAttributes()).thenReturn(attrs);

        managedHandler.afterConnectionEstablished(session);
        RealtimeSession realtimeSession = sessionManager.getSession(key);
        assertThat(realtimeSession).isNotNull();

        TextMessage bizMsg = new TextMessage("{\"event\":\"test:event\",\"data\":\"data\"}");
        // 第一次放行
        managedHandler.handleTextMessage(session, bizMsg);

        // 第二次超限触发 WARNING，模拟网络写失败抛出 IOException
        doThrow(new IOException("Simulated network send broken pipe"))
                .when(session).sendMessage(argThat(m -> ((TextMessage) m).getPayload().contains("429")));

        // 不应向上抛出 IOException 导致 WebSocket 连接崩溃
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
            managedHandler.handleTextMessage(session, bizMsg);
        });
    }

    @Test
    @DisplayName("正交二进制帧上行分发: 正确委托 RealtimeBinaryMessageListener 且刷新活跃时间")
    void testBinaryMessageDelegatedToListenerAndRefreshesActiveTime() throws Exception {
        RealtimeSessionManager sessionManager = new RealtimeSessionManager();
        RealtimeMessageDispatcher dispatcher = mock(RealtimeMessageDispatcher.class);

        List<byte[]> receivedPayloads = new ArrayList<>();
        List<RealtimeSession> receivedSessions = new ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<String> capturedTraceId = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> capturedUser = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Long> capturedTenant = new java.util.concurrent.atomic.AtomicReference<>();
        RealtimeBinaryMessageListener listener = (sess, payload) -> {
            receivedSessions.add(sess);
            receivedPayloads.add(payload);
            capturedTraceId.set(MDC.get("traceId"));
            capturedUser.set(UserContextHolder.get());
            capturedTenant.set(TenantContextHolder.getTenantIdAsLong());
        };

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("binaryListener", listener);

        AtengWebSocketHandler managedHandler = new AtengWebSocketHandler(
                sessionManager,
                dispatcher,
                null,
                beanFactory.getBeanProvider(RealtimeBinaryMessageListener.class)
        );

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-bin-001");
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        SessionKey key = SessionKey.of("user-audio", "app", "client-bin-01");
        attrs.put(AtengWebSocketHandler.SESSION_KEY_ATTR, key);
        attrs.put(AtengWebSocketHandler.TENANT_ID_ATTR, 777L);
        when(session.getAttributes()).thenReturn(attrs);

        // 1. 连接建立
        managedHandler.afterConnectionEstablished(session);
        RealtimeSession realtimeSession = sessionManager.getSession(key);
        assertThat(realtimeSession).isNotNull();

        // 2. 模拟发送二进制音频帧 (byte[]{1, 2, 3, 4, 5})
        byte[] audioBytes = new byte[]{1, 2, 3, 4, 5};
        BinaryMessage binaryMessage = new BinaryMessage(ByteBuffer.wrap(audioBytes));
        managedHandler.handleBinaryMessage(session, binaryMessage);

        // 3. 验证二进制监听器被精确调用，数据一致
        assertThat(receivedSessions).containsExactly(realtimeSession);
        assertThat(receivedPayloads).hasSize(1);
        assertThat(receivedPayloads.get(0)).containsExactly(audioBytes);

        // 4. 验证四维上下文全链路透传与执行后确定性清场
        assertThat(capturedTraceId.get()).isNotBlank();
        assertThat(capturedUser.get()).isEqualTo("user-audio");
        assertThat(capturedTenant.get()).isEqualTo(777L);
        assertThat(MDC.get("traceId")).isNull();
        assertThat(UserContextHolder.get()).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();

        // 4. 验证与 JSON 注解路由正交解耦（dispatcher 从未被调用）
        verify(dispatcher, never()).dispatch(any(), any());
    }

    @Test
    @DisplayName("未配置 RealtimeBinaryMessageListener 时安全忽略二进制消息且不抛异常")
    void testBinaryMessageWithoutListenerDoesNotThrow() throws Exception {
        RealtimeSessionManager sessionManager = new RealtimeSessionManager();
        AtengWebSocketHandler managedHandler = new AtengWebSocketHandler(sessionManager);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-bin-002");
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        SessionKey key = SessionKey.of("user-bin-2", "app", "client-bin-02");
        attrs.put(AtengWebSocketHandler.SESSION_KEY_ATTR, key);
        when(session.getAttributes()).thenReturn(attrs);

        managedHandler.afterConnectionEstablished(session);
        RealtimeSession realtimeSession = sessionManager.getSession(key);
        assertThat(realtimeSession).isNotNull();

        // 发送二进制消息
        managedHandler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[]{99, 100})));
        assertThat(realtimeSession.isOpen()).isTrue();
    }

    @Test
    @DisplayName("单个 RealtimeBinaryMessageListener 抛出异常时不阻断其他监听器执行")
    void testBinaryMessageListenerExceptionDoesNotAffectOtherListeners() throws Exception {
        RealtimeSessionManager sessionManager = new RealtimeSessionManager();
        List<byte[]> receivedBySecondListener = new ArrayList<>();

        RealtimeBinaryMessageListener failingListener = (sess, payload) -> {
            throw new RuntimeException("Simulated audio decoder crash");
        };
        RealtimeBinaryMessageListener normalListener = (sess, payload) -> {
            receivedBySecondListener.add(payload);
        };

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("failingListener", failingListener);
        beanFactory.addBean("normalListener", normalListener);

        AtengWebSocketHandler managedHandler = new AtengWebSocketHandler(
                sessionManager,
                null,
                null,
                beanFactory.getBeanProvider(RealtimeBinaryMessageListener.class)
        );

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-bin-003");
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        SessionKey key = SessionKey.of("user-bin-3", "app", "client-bin-03");
        attrs.put(AtengWebSocketHandler.SESSION_KEY_ATTR, key);
        when(session.getAttributes()).thenReturn(attrs);

        managedHandler.afterConnectionEstablished(session);
        byte[] payload = new byte[]{7, 8, 9};
        managedHandler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(payload)));

        // 第二个监听器依然能成功消费，异常被安全捕获
        assertThat(receivedBySecondListener).hasSize(1);
        assertThat(receivedBySecondListener.get(0)).containsExactly(payload);
    }
}
