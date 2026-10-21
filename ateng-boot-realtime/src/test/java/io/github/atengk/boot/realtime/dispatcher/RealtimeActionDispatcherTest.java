package io.github.atengk.boot.realtime.dispatcher;

import io.github.atengk.boot.realtime.annotation.RealtimeAction;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import lombok.Data;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 客户端上行消息分发调度引擎单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("RealtimeMessageDispatcher 上行路由分发测试")
class RealtimeActionDispatcherTest {

    @Data
    public static class ChatPayload {
        private String content;
        private Long targetUserId;
    }

    public static class SampleActionService {
        private final List<String> handledEvents = new ArrayList<>();
        private ChatPayload lastPayload;
        private SessionKey lastSessionKey;

        @RealtimeAction("chat:send")
        public void onChat(RealtimeSession session, ChatPayload payload) {
            handledEvents.add("chat:send");
            this.lastPayload = payload;
            this.lastSessionKey = session.getSessionKey();
        }

        @RealtimeAction("query:status")
        public RealtimeMessage<String> onQueryStatus(RealtimeSession session) {
            handledEvents.add("query:status");
            return RealtimeMessage.of("query:status:reply", "ONLINE");
        }
    }

    @Test
    @DisplayName("自动扫描 @RealtimeAction 并完成反射路由与参数注入")
    void testActionScanningAndDispatch() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        SampleActionService service = new SampleActionService();
        context.registerBean("sampleActionService", SampleActionService.class, () -> service);
        context.refresh();

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        RealtimeMessageDispatcher dispatcher = new RealtimeMessageDispatcher(
                beanFactory.getBeanProvider(RealtimeMessageListener.class)
        );
        dispatcher.setApplicationContext(context);
        dispatcher.afterSingletonsInstantiated();

        SessionKey key = SessionKey.of("user-100", "web", "client-100");
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        RealtimeSession session = RealtimeSession.ofWebSocket(key, ws);

        // 1. 发送 chat:send 消息
        RealtimeMessage<?> chatMsg = RealtimeMessage.of("chat:send", Map.of("content", "hello world", "targetUserId", 200L));
        boolean handled = dispatcher.dispatch(session, chatMsg);

        assertThat(handled).isTrue();
        assertThat(service.handledEvents).contains("chat:send");
        assertThat(service.lastSessionKey).isEqualTo(key);
        assertThat(service.lastPayload).isNotNull();
        assertThat(service.lastPayload.getContent()).isEqualTo("hello world");
        assertThat(service.lastPayload.getTargetUserId()).isEqualTo(200L);

        // 2. 发送带返回值的 query:status 消息，自动回执 reply 消息
        RealtimeMessage<?> queryMsg = RealtimeMessage.of("query:status", null);
        boolean queryHandled = dispatcher.dispatch(session, queryMsg);

        assertThat(queryHandled).isTrue();
        assertThat(service.handledEvents).contains("query:status");
        verify(ws, times(1)).sendMessage(any());
    }

    @Test
    @DisplayName("未匹配注解时委托 RealtimeMessageListener SPI 兜底处理")
    void testFallbackToMessageListener() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        List<String> listenerEvents = new ArrayList<>();
        RealtimeMessageListener listener = (sess, msg) -> listenerEvents.add(msg.getEvent());
        beanFactory.addBean("customListener", listener);

        RealtimeMessageDispatcher dispatcher = new RealtimeMessageDispatcher(
                beanFactory.getBeanProvider(RealtimeMessageListener.class)
        );

        SessionKey key = SessionKey.of("u1", "c1");
        WebSocketSession ws = mock(WebSocketSession.class);
        RealtimeSession session = RealtimeSession.ofWebSocket(key, ws);

        RealtimeMessage<?> unmapped = RealtimeMessage.of("unmapped:event", "data");
        boolean handled = dispatcher.dispatch(session, unmapped);

        assertThat(handled).isTrue();
        assertThat(listenerEvents).containsExactly("unmapped:event");
    }

    @Test
    @DisplayName("既无注解也无监听器时安全处理并返回 false")
    void testNoMatchReturnsFalse() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        RealtimeMessageDispatcher dispatcher = new RealtimeMessageDispatcher(
                beanFactory.getBeanProvider(RealtimeMessageListener.class)
        );

        SessionKey key = SessionKey.of("u1", "c1");
        WebSocketSession ws = mock(WebSocketSession.class);
        RealtimeSession session = RealtimeSession.ofWebSocket(key, ws);

        RealtimeMessage<?> unmapped = RealtimeMessage.of("no:match", "data");
        boolean handled = dispatcher.dispatch(session, unmapped);

        assertThat(handled).isFalse();
    }
}
