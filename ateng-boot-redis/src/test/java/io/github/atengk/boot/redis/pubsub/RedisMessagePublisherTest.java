package io.github.atengk.boot.redis.pubsub;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主题消息发布与动态监听门面 RedisMessagePublisher 单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisMessagePublisher 单元测试")
class RedisMessagePublisherTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private RedisMessageListenerContainer listenerContainer;

    private RedisMessagePublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new RedisMessagePublisher(redisTemplate, listenerContainer);
    }

    @Test
    @DisplayName("测试按频道名称发布广播消息")
    void testPublishByChannel() {
        when(redisTemplate.convertAndSend("sys:notice", "Hello Redis")).thenReturn(3L);

        Long count = publisher.publish("sys:notice", "Hello Redis");
        assertThat(count).isEqualTo(3L);
        verify(redisTemplate).convertAndSend("sys:notice", "Hello Redis");
    }

    @Test
    @DisplayName("测试按 Topic 发布广播消息")
    void testPublishByTopic() {
        ChannelTopic topic = new ChannelTopic("cache:refresh");
        when(redisTemplate.convertAndSend("cache:refresh", "user:1001")).thenReturn(1L);

        Long count = publisher.publish(topic, "user:1001");
        assertThat(count).isEqualTo(1L);
        verify(redisTemplate).convertAndSend("cache:refresh", "user:1001");
    }

    @Test
    @DisplayName("测试发布入参防御性校验")
    void testPublishValidation() {
        assertThatThrownBy(() -> publisher.publish("", "data"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> publisher.publish("channel", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("测试动态订阅与退订 MessageListener")
    void testSubscribeAndUnsubscribe() {
        MessageListener listener = (message, pattern) -> {};

        publisher.subscribe("sys:broadcast", listener);
        verify(listenerContainer).addMessageListener(eq(listener), any(ChannelTopic.class));

        ChannelTopic topic = new ChannelTopic("sys:topic");
        publisher.subscribe(topic, listener);
        verify(listenerContainer).addMessageListener(eq(listener), eq(topic));

        publisher.unsubscribe("sys:broadcast", listener);
        verify(listenerContainer).removeMessageListener(eq(listener), any(ChannelTopic.class));
    }

    @Test
    @DisplayName("测试单参数函数式 Consumer 动态订阅与接收消息")
    void testConsumerSubscribe() {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        ArgumentCaptor<MessageListener> captor = ArgumentCaptor.forClass(MessageListener.class);

        publisher.subscribe("cache:invalidate", msg -> receivedBody.set(msg));

        verify(listenerContainer).addMessageListener(captor.capture(), any(ChannelTopic.class));
        MessageListener capturedListener = captor.getValue();
        assertThat(capturedListener).isNotNull();

        Message dummyMessage = new DefaultMessage(
                "cache:invalidate".getBytes(StandardCharsets.UTF_8),
                "ORDER-10086".getBytes(StandardCharsets.UTF_8)
        );
        capturedListener.onMessage(dummyMessage, null);
        assertThat(receivedBody.get()).isEqualTo("ORDER-10086");
    }

    @Test
    @DisplayName("测试双参数函数式 BiConsumer 动态订阅与接收消息")
    void testFunctionalSubscribe() {
        AtomicReference<String> receivedChannel = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();

        ArgumentCaptor<MessageListener> captor = ArgumentCaptor.forClass(MessageListener.class);

        publisher.subscribeChannel("cluster:evict", (ch, msg) -> {
            receivedChannel.set(ch);
            receivedBody.set(msg);
        });

        verify(listenerContainer).addMessageListener(captor.capture(), any(ChannelTopic.class));
        MessageListener capturedListener = captor.getValue();
        assertThat(capturedListener).isNotNull();

        // 模拟 Redis 回调 MessageListener
        Message dummyMessage = new DefaultMessage(
                "cluster:evict".getBytes(StandardCharsets.UTF_8),
                "USER-99999".getBytes(StandardCharsets.UTF_8)
        );
        capturedListener.onMessage(dummyMessage, null);

        assertThat(receivedChannel.get()).isEqualTo("cluster:evict");
        assertThat(receivedBody.get()).isEqualTo("USER-99999");
    }

    @Test
    @DisplayName("测试无容器时动态订阅抛出状态异常")
    void testSubscribeWithoutContainer() {
        RedisMessagePublisher publisherWithoutContainer = new RedisMessagePublisher(redisTemplate, null);
        assertThat(publisherWithoutContainer.getListenerContainer()).isNull();

        assertThatThrownBy(() -> publisherWithoutContainer.subscribe("test", (msg) -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RedisMessageListenerContainer 未注入");
    }
}
