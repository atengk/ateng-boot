package io.github.atengk.boot.realtime.spi;

import io.github.atengk.boot.realtime.group.RealtimeGroupManager;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 实时通信离线消息 SPI 与防 OOM 配额治理测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("RealtimeOfflineMessageProvider 离线消息 SPI 与防 OOM 配额测试")
class RealtimeOfflineMessageProviderTest {

    @Test
    @DisplayName("NoOpProvider 保底防御: 丢弃暂存且保底返回空列表与零计数")
    void testNoOpProvider() {
        NoOpRealtimeOfflineMessageProvider provider = new NoOpRealtimeOfflineMessageProvider();
        RealtimeMessage<String> message = RealtimeMessage.ofPersistent("chat", "hello");

        // 暂存不抛出异常
        provider.saveOfflineMessage("user-100", message);

        // 计数必须为 0
        assertThat(provider.getOfflineMessageCount("user-100")).isZero();

        // 拉取必须为空集合且不为 null
        List<RealtimeMessage<?>> messages = provider.pullAndClearOfflineMessages("user-100");
        assertThat(messages).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("InMemoryProvider 基础暂存与拉取清空闭环")
    void testInMemoryProviderBasic() {
        InMemoryRealtimeOfflineMessageProvider provider =
                new InMemoryRealtimeOfflineMessageProvider(10, Duration.ofDays(1));

        RealtimeMessage<String> msg1 = RealtimeMessage.ofPersistent("event1", "data1");
        RealtimeMessage<String> msg2 = RealtimeMessage.ofPersistent("event2", "data2");

        provider.saveOfflineMessage("user-101", msg1);
        provider.saveOfflineMessage("user-101", msg2);

        assertThat(provider.getOfflineMessageCount("user-101")).isEqualTo(2);

        // 一次性拉取清空
        List<RealtimeMessage<?>> pulled = provider.pullAndClearOfflineMessages("user-101");
        assertThat(pulled).hasSize(2).containsExactly(msg1, msg2);

        // 再次拉取已清空
        assertThat(provider.getOfflineMessageCount("user-101")).isZero();
        assertThat(provider.pullAndClearOfflineMessages("user-101")).isEmpty();
    }

    @Test
    @DisplayName("防 OOM 配额治理: 超出单用户上限时执行 FIFO 淘汰最早消息")
    void testInMemoryFifoEviction() {
        // 单用户配额上限设为 3
        InMemoryRealtimeOfflineMessageProvider provider =
                new InMemoryRealtimeOfflineMessageProvider(3, Duration.ofDays(1));

        for (int i = 1; i <= 5; i++) {
            provider.saveOfflineMessage("user-102", RealtimeMessage.ofPersistent("chat", "msg-" + i));
        }

        // 当前保留总数不能超过 3
        assertThat(provider.getOfflineMessageCount("user-102")).isEqualTo(3);

        List<RealtimeMessage<?>> messages = provider.pullAndClearOfflineMessages("user-102");
        assertThat(messages).hasSize(3);
        // 验证最早的 msg-1, msg-2 已被 FIFO 淘汰，仅保留 msg-3, msg-4, msg-5
        assertThat(messages.get(0).getData()).isEqualTo("msg-3");
        assertThat(messages.get(1).getData()).isEqualTo("msg-4");
        assertThat(messages.get(2).getData()).isEqualTo("msg-5");
    }

    @Test
    @DisplayName("TTL 过期判定: 过期离线消息拉取时安全过滤丢弃")
    void testInMemoryTtlExpiration() {
        // TTL 设为 50ms
        InMemoryRealtimeOfflineMessageProvider provider =
                new InMemoryRealtimeOfflineMessageProvider(10, Duration.ofMillis(50));

        RealtimeMessage<String> expiredMsg = RealtimeMessage.ofPersistent("notice", "expired");
        // 模拟 100ms 以前的历史消息
        expiredMsg.setTimestamp(System.currentTimeMillis() - 100);

        RealtimeMessage<String> freshMsg = RealtimeMessage.ofPersistent("notice", "fresh");
        freshMsg.setTimestamp(System.currentTimeMillis());

        provider.saveOfflineMessage("user-103", expiredMsg);
        provider.saveOfflineMessage("user-103", freshMsg);

        List<RealtimeMessage<?>> validMessages = provider.pullAndClearOfflineMessages("user-103");
        // 过期消息应被过滤，仅返回 freshMsg
        assertThat(validMessages).hasSize(1);
        assertThat(validMessages.get(0).getData()).isEqualTo("fresh");
    }

    @Test
    @DisplayName("空安全防御: null 参数调用安全保底，不产生空指针异常")
    void testNullSafety() {
        InMemoryRealtimeOfflineMessageProvider provider =
                new InMemoryRealtimeOfflineMessageProvider(10, Duration.ofDays(1));

        // null 参存储安全返回
        provider.saveOfflineMessage(null, null);
        provider.saveOfflineMessage("user-null", null);
        provider.saveOfflineMessage(null, RealtimeMessage.of("a", "b"));

        // null 参拉取返回空集合
        assertThat(provider.pullAndClearOfflineMessages(null)).isEmpty();
        assertThat(provider.getOfflineMessageCount(null)).isZero();
    }

    @Test
    @DisplayName("RealtimeTemplate 门面联动: 用户在线直发不暂存，用户离线持久化消息触发暂存")
    void testTemplateIntegrationWithOffline() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        RealtimeSessionManager sessionManager = new RealtimeSessionManager(properties);
        RealtimeGroupManager groupManager = new RealtimeGroupManager();
        RealtimeOfflineMessageProvider mockProvider = mock(RealtimeOfflineMessageProvider.class);

        RealtimeTemplate template = new RealtimeTemplate(
                sessionManager, groupManager, properties, null, mockProvider);

        // 1. 用户离线且为普通瞬态消息 -> 不暂存
        RealtimeMessage<String> transientMsg = RealtimeMessage.of("notice", "transient");
        int sentOfflineTransient = template.sendToUser("user-offline", transientMsg);
        assertThat(sentOfflineTransient).isZero();
        verify(mockProvider, never()).saveOfflineMessage(any(), any());

        // 2. 用户离线且为持久化消息 (persistent=true) -> 触发离线存储暂存
        RealtimeMessage<String> persistentMsg = RealtimeMessage.ofPersistent("notice", "important");
        int sentOfflinePersistent = template.sendToUser("user-offline", persistentMsg);
        assertThat(sentOfflinePersistent).isZero();
        verify(mockProvider, times(1)).saveOfflineMessage("user-offline", persistentMsg);

        // 3. 用户在线 (有活跃会话) 且为持久化消息 -> 直接投递，不触发离线暂存
        WebSocketSession mockWs = mock(WebSocketSession.class);
        when(mockWs.isOpen()).thenReturn(true);
        SessionKey key = SessionKey.of("user-online", "client-1");
        sessionManager.registerSession(RealtimeSession.ofWebSocket(key, mockWs));

        int sentOnline = template.sendToUser("user-online", persistentMsg);
        assertThat(sentOnline).isEqualTo(1);
        verify(mockWs, times(1)).sendMessage(any(TextMessage.class));
        verify(mockProvider, never()).saveOfflineMessage("user-online", persistentMsg);

        // 4. 对称门面方法代理验证
        template.pullAndClearOfflineMessages("user-offline");
        verify(mockProvider, times(1)).pullAndClearOfflineMessages("user-offline");

        template.getOfflineMessageCount("user-offline");
        verify(mockProvider, times(1)).getOfflineMessageCount("user-offline");
    }
}
