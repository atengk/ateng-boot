package io.github.atengk.boot.realtime.dispatcher;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.annotation.RealtimeAction;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.socket.TextMessage;
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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserProfile {
        private String userId;
        private String name;
    }

    public static class SampleActionService {
        private final List<String> handledEvents = new ArrayList<>();
        private ChatPayload lastPayload;
        private SessionKey lastSessionKey;
        private String capturedContext;

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

        @RealtimeAction("user:profile")
        public Result<UserProfile> onGetUserProfile(RealtimeSession session) {
            handledEvents.add("user:profile");
            return Result.success(new UserProfile("u100", "Alice"));
        }

        @RealtimeAction("dto:echo")
        public UserProfile onEchoDto(UserProfile payload) {
            handledEvents.add("dto:echo");
            return payload;
        }

        @RealtimeAction("biz:error")
        public String onBizError() {
            handledEvents.add("biz:error");
            throw new BusinessException(40001, "订单已锁定不可取消");
        }

        @RealtimeAction("context:inspect")
        public String onInspectContext() {
            handledEvents.add("context:inspect");
            String traceId = MDC.get("traceId");
            String userId = UserContextHolder.getUserId();
            String tenantId = TenantContextHolder.getTenantId();
            this.capturedContext = traceId + "|" + userId + "|" + tenantId;
            return "OK";
        }

        @RealtimeAction("context:error")
        public void onContextError() {
            handledEvents.add("context:error");
            String traceId = MDC.get("traceId");
            String userId = UserContextHolder.getUserId();
            String tenantId = TenantContextHolder.getTenantId();
            this.capturedContext = traceId + "|" + userId + "|" + tenantId;
            throw new BusinessException(50001, "校验失败");
        }

        @RealtimeAction("return:null")
        public String onReturnNull() {
            handledEvents.add("return:null");
            return null;
        }

        @RealtimeAction("system:error")
        public void onSystemError() {
            handledEvents.add("system:error");
            throw new IllegalStateException("Database connection corrupted");
        }
    }

    @AfterEach
    void tearDown() {
        MDC.remove("traceId");
        UserContextHolder.clear();
        TenantContextHolder.clear();
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
    @DisplayName("双向 Request-Response: Action 返回 Result 或 DTO 时自动回发应答帧并对齐 ID")
    void testBidirectionalReplyWithResultAndDto() throws Exception {
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

        SessionKey key = SessionKey.of("user-200", "web", "client-200");
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        RealtimeSession session = RealtimeSession.ofWebSocket(key, ws);

        // 1. 测试返回 Result<UserProfile>
        RealtimeMessage<?> profileReq = RealtimeMessage.of("req-user-123", "user:profile", null);
        boolean handledProfile = dispatcher.dispatch(session, profileReq);
        assertThat(handledProfile).isTrue();

        ArgumentCaptor<TextMessage> textMessageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, times(1)).sendMessage(textMessageCaptor.capture());

        RealtimeMessage<?> profileReply = JsonUtil.parseObject(textMessageCaptor.getValue().getPayload(), RealtimeMessage.class);
        assertThat(profileReply).isNotNull();
        assertThat(profileReply.getId()).isEqualTo("req-user-123");
        assertThat(profileReply.getEvent()).isEqualTo("user:profile:reply");
        assertThat(profileReply.getData()).isNotNull();

        // 2. 测试返回普通 DTO 对象
        reset(ws);
        when(ws.isOpen()).thenReturn(true);

        UserProfile inputDto = new UserProfile("u300", "Bob");
        RealtimeMessage<?> dtoReq = RealtimeMessage.of("req-dto-456", "dto:echo", inputDto);
        boolean handledDto = dispatcher.dispatch(session, dtoReq);
        assertThat(handledDto).isTrue();

        verify(ws, times(1)).sendMessage(textMessageCaptor.capture());
        RealtimeMessage<?> dtoReply = JsonUtil.parseObject(textMessageCaptor.getValue().getPayload(), RealtimeMessage.class);
        assertThat(dtoReply).isNotNull();
        assertThat(dtoReply.getId()).isEqualTo("req-dto-456");
        assertThat(dtoReply.getEvent()).isEqualTo("dto:echo:reply");
    }

    @Test
    @DisplayName("双向 Request-Response: 拦截 BusinessException 并转译为 Result.failure 语义化应答")
    void testBusinessExceptionTranslation() throws Exception {
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

        SessionKey key = SessionKey.of("user-300", "web", "client-300");
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        RealtimeSession session = RealtimeSession.ofWebSocket(key, ws);

        RealtimeMessage<?> bizReq = RealtimeMessage.of("req-err-789", "biz:error", null);
        boolean handled = dispatcher.dispatch(session, bizReq);

        // 请求被拦截转译消费，连接不崩溃中断
        assertThat(handled).isTrue();

        ArgumentCaptor<TextMessage> textMessageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, times(1)).sendMessage(textMessageCaptor.capture());

        RealtimeMessage<?> errorReply = JsonUtil.parseObject(textMessageCaptor.getValue().getPayload(), RealtimeMessage.class);
        assertThat(errorReply).isNotNull();
        assertThat(errorReply.getId()).isEqualTo("req-err-789");
        assertThat(errorReply.getEvent()).isEqualTo("biz:error:reply");

        // 验证 data 反序列化为 Result
        Result<?> result = JsonUtil.parseObject(JsonUtil.toJsonString(errorReply.getData()), Result.class);
        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(40001);
        assertThat(result.getMsg()).isEqualTo("订单已锁定不可取消");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("上下文贯通与零污染清理: 验证 MDC traceId、UserContext、TenantContext 注入与 finally 清理")
    void testContextPropagationAndZeroContaminationCleanup() {
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

        SessionKey key = SessionKey.of("alice", "web", "client-alice");
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        RealtimeSession session = RealtimeSession.ofWebSocket(key, ws, 2026L, Map.of("traceId", "trace-attr-001"));

        // 1. 正常执行时上下文透传测试
        RealtimeMessage<?> msg = RealtimeMessage.of("req-ctx-001", "context:inspect", null);
        msg.setTraceId("trace-msg-999"); // 消息显式指定 traceId
        boolean handled = dispatcher.dispatch(session, msg);

        assertThat(handled).isTrue();
        assertThat(service.capturedContext).isEqualTo("trace-msg-999|alice|2026");

        // 验证执行后上下文绝对清理
        assertThat(MDC.get("traceId")).isNull();
        assertThat(UserContextHolder.getUserId()).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();

        // 2. 异常抛出分支上下文透传与 finally 清理测试
        RealtimeMessage<?> errContextMsg = RealtimeMessage.of("req-ctx-002", "context:error", null);
        errContextMsg.setTraceId("trace-err-888");
        boolean errHandled = dispatcher.dispatch(session, errContextMsg);

        assertThat(errHandled).isTrue();
        assertThat(service.capturedContext).isEqualTo("trace-err-888|alice|2026");

        // 验证异常后上下文依然绝对清理
        assertThat(MDC.get("traceId")).isNull();
        assertThat(UserContextHolder.getUserId()).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    @DisplayName("void 与 null 返回值不触发自动回发应答")
    void testVoidAndNullReturnDoesNotReply() throws Exception {
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

        SessionKey key = SessionKey.of("user-null", "web", "client-null");
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        RealtimeSession session = RealtimeSession.ofWebSocket(key, ws);

        // 1. 测试返回 null
        RealtimeMessage<?> nullMsg = RealtimeMessage.of("req-null", "return:null", null);
        boolean handledNull = dispatcher.dispatch(session, nullMsg);
        assertThat(handledNull).isTrue();
        verify(ws, never()).sendMessage(any());

        // 2. 测试返回 void (chat:send)
        RealtimeMessage<?> voidMsg = RealtimeMessage.of("req-void", "chat:send", Map.of("content", "test"));
        boolean handledVoid = dispatcher.dispatch(session, voidMsg);
        assertThat(handledVoid).isTrue();
        verify(ws, never()).sendMessage(any());
    }

    @Test
    @DisplayName("系统未捕获非受控异常时返回 false 并绝对清理上下文")
    void testSystemExceptionReturnsFalseAndCleansContext() throws Exception {
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

        SessionKey key = SessionKey.of("user-sys", "web", "client-sys");
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        RealtimeSession session = RealtimeSession.ofWebSocket(key, ws);

        RealtimeMessage<?> msg = RealtimeMessage.of("req-sys-001", "system:error", null);
        boolean handled = dispatcher.dispatch(session, msg);

        // 系统异常时返回 false
        assertThat(handled).isFalse();
        // 未回发业务消息
        verify(ws, never()).sendMessage(any());

        // 验证即使发生系统未受控异常，上下文也必须绝对清理
        assertThat(MDC.get("traceId")).isNull();
        assertThat(UserContextHolder.getUserId()).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    @DisplayName("上下文缺省提取策略: 从 Session Attributes 提取 TraceId 与从 Message 提取 TenantId")
    void testFallbackTraceAndTenantFromSessionAndMessage() {
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

        SessionKey key = SessionKey.of("bob", "web", "client-bob");
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        // Session 中无 tenantId，但 attributes 中有 traceId
        RealtimeSession session = RealtimeSession.ofWebSocket(key, ws, null, Map.of("traceId", "trace-attr-777"));

        RealtimeMessage<?> msg = RealtimeMessage.of("req-ctx-003", "context:inspect", null);
        msg.setTraceId(null); // 无显式 traceId
        msg.setTenantId("tenant-from-msg-888"); // 从消息中传递 tenantId

        boolean handled = dispatcher.dispatch(session, msg);
        assertThat(handled).isTrue();
        assertThat(service.capturedContext).isEqualTo("trace-attr-777|bob|tenant-from-msg-888");

        // 验证上下文已清理
        assertThat(MDC.get("traceId")).isNull();
        assertThat(UserContextHolder.getUserId()).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
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
