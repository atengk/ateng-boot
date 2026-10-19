package io.github.atengk.boot.redis.pubsub.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.redis.pubsub.annotation.ChannelTopicListener;
import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * 声明式 Pub/Sub 广播订阅调度器 ChannelTopicBootstrap 单元测试
 *
 * @author Ateng
 * @since 2026-10-19
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChannelTopicBootstrap 声明式 Pub/Sub 订阅调度器单元测试")
class ChannelTopicBootstrapTest {

    @Mock
    private RedisMessageListenerContainer listenerContainer;

    private ObjectMapper objectMapper;
    private ChannelTopicBootstrap bootstrap;

    static class BroadcastEvent {
        private String eventId;
        private String eventType;
        private String message;

        public BroadcastEvent() {
        }

        public BroadcastEvent(String eventId, String eventType, String message) {
            this.eventId = eventId;
            this.eventType = eventType;
            this.message = message;
        }

        public String getEventId() {
            return eventId;
        }

        public void setEventId(String eventId) {
            this.eventId = eventId;
        }

        public String getEventType() {
            return eventType;
        }

        public void setEventType(String eventType) {
            this.eventType = eventType;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    static class SinglePojoService {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<BroadcastEvent> receivedEvent = new AtomicReference<>();
        final AtomicReference<String> capturedTraceId = new AtomicReference<>();

        @ChannelTopicListener(topics = "topic:event:single")
        public void onEvent(BroadcastEvent event) {
            receivedEvent.set(event);
            capturedTraceId.set(MDC.get("traceId"));
            latch.countDown();
        }
    }

    static class SingleStringService {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> receivedContent = new AtomicReference<>();

        @ChannelTopicListener(topics = "topic:string:single")
        public void onStringMessage(String content) {
            receivedContent.set(content);
            latch.countDown();
        }
    }

    static class SingleRawByteService {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<byte[]> receivedBytes = new AtomicReference<>();

        @ChannelTopicListener(topics = "topic:bytes:single")
        public void onRawBytes(byte[] bytes) {
            receivedBytes.set(bytes);
            latch.countDown();
        }
    }

    static class SingleMessageService {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Message> receivedMessage = new AtomicReference<>();

        @ChannelTopicListener(topics = "topic:message:single")
        public void onRedisMessage(Message message) {
            receivedMessage.set(message);
            latch.countDown();
        }
    }

    static class TwoParamService {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<BroadcastEvent> receivedEvent = new AtomicReference<>();
        final AtomicReference<String> receivedChannel = new AtomicReference<>();

        @ChannelTopicListener(topics = "topic:event:two")
        public void onEventWithChannel(BroadcastEvent event, String channel) {
            receivedEvent.set(event);
            receivedChannel.set(channel);
            latch.countDown();
        }
    }

    static class ThreeParamPatternService {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<BroadcastEvent> receivedEvent = new AtomicReference<>();
        final AtomicReference<String> receivedChannel = new AtomicReference<>();
        final AtomicReference<String> receivedPattern = new AtomicReference<>();

        @ChannelTopicListener(patterns = "topic:event:*")
        public void onPatternEvent(BroadcastEvent event, String channel, String pattern) {
            receivedEvent.set(event);
            receivedChannel.set(channel);
            receivedPattern.set(pattern);
            latch.countDown();
        }
    }

    static class PlaceholderService {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> receivedMessage = new AtomicReference<>();

        @ChannelTopicListener(topics = "${app.broadcast.topic:default:topic}")
        public void onPlaceholderMessage(String message) {
            receivedMessage.set(message);
            latch.countDown();
        }
    }

    static class SingleTopicAliasService {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> receivedMessage = new AtomicReference<>();

        @ChannelTopicListener(topic = "topic:alias:single")
        public void onMessage(String msg) {
            receivedMessage.set(msg);
            latch.countDown();
        }
    }

    static class InvalidParamCountService {
        @ChannelTopicListener(topics = "invalid:topic")
        public void onInvalid(String p1, String p2, String p3, String p4) {
            // 参数超过 3 个，应在注册时抛出异常
        }
    }

    @BeforeEach
    void setUp() {
        objectMapper = RedisSerializerFactory.createSafeObjectMapper();
        bootstrap = new ChannelTopicBootstrap(listenerContainer, objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (bootstrap != null) {
            bootstrap.destroy();
        }
        MDC.clear();
    }

    @Test
    @DisplayName("测试单参数自定义 POJO 反序列化及 MDC traceId 自动注入")
    void testSinglePojoDeserializationAndMdcTraceId() throws Exception {
        SinglePojoService service = new SinglePojoService();
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("singlePojoService", SinglePojoService.class, () -> service);
        context.refresh();

        bootstrap.setApplicationContext(context);
        bootstrap.afterSingletonsInstantiated();

        ArgumentCaptor<MessageListener> listenerCaptor = ArgumentCaptor.forClass(MessageListener.class);
        ArgumentCaptor<Collection> topicCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(listenerContainer, atLeastOnce()).addMessageListener(listenerCaptor.capture(), topicCaptor.capture());

        MessageListener listener = listenerCaptor.getValue();
        assertThat(listener).isNotNull();

        BroadcastEvent event = new BroadcastEvent("EVT-1001", "ORDER_CREATED", "订单支付成功广播");
        byte[] body = objectMapper.writeValueAsBytes(event);
        byte[] channel = "topic:event:single".getBytes(StandardCharsets.UTF_8);

        // 模拟 Redis 消息到达
        listener.onMessage(new DefaultMessage(channel, body), null);

        boolean finished = service.latch.await(3, TimeUnit.SECONDS);
        assertThat(finished).isTrue();
        assertThat(service.receivedEvent.get()).isNotNull();
        assertThat(service.receivedEvent.get().getEventId()).isEqualTo("EVT-1001");
        assertThat(service.receivedEvent.get().getEventType()).isEqualTo("ORDER_CREATED");
        assertThat(service.capturedTraceId.get()).isNotBlank();
        // 验证消费后主线程或当前线程 MDC 保持干净
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("测试单参数 String 纯文本消息消费")
    void testSingleStringMessage() throws Exception {
        SingleStringService service = new SingleStringService();
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("singleStringService", SingleStringService.class, () -> service);
        context.refresh();

        bootstrap.setApplicationContext(context);
        bootstrap.afterSingletonsInstantiated();

        ArgumentCaptor<MessageListener> listenerCaptor = ArgumentCaptor.forClass(MessageListener.class);
        verify(listenerContainer, atLeastOnce()).addMessageListener(listenerCaptor.capture(), any(Collection.class));

        MessageListener listener = listenerCaptor.getValue();
        byte[] body = "Hello Redis PubSub".getBytes(StandardCharsets.UTF_8);
        byte[] channel = "topic:string:single".getBytes(StandardCharsets.UTF_8);

        listener.onMessage(new DefaultMessage(channel, body), null);

        boolean finished = service.latch.await(3, TimeUnit.SECONDS);
        assertThat(finished).isTrue();
        assertThat(service.receivedContent.get()).isEqualTo("Hello Redis PubSub");
    }

    @Test
    @DisplayName("测试单参数 byte[] 原始字节流与 Message 原生对象消费")
    void testSingleRawBytesAndMessage() throws Exception {
        SingleRawByteService byteService = new SingleRawByteService();
        SingleMessageService messageService = new SingleMessageService();
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("byteService", SingleRawByteService.class, () -> byteService);
        context.registerBean("messageService", SingleMessageService.class, () -> messageService);
        context.refresh();

        bootstrap.setApplicationContext(context);
        bootstrap.afterSingletonsInstantiated();

        ArgumentCaptor<MessageListener> listenerCaptor = ArgumentCaptor.forClass(MessageListener.class);
        verify(listenerContainer, atLeastOnce()).addMessageListener(listenerCaptor.capture(), any(Collection.class));

        List<MessageListener> listeners = listenerCaptor.getAllValues();
        byte[] body = "raw-binary-data".getBytes(StandardCharsets.UTF_8);
        byte[] channelBytes = "topic:bytes:single".getBytes(StandardCharsets.UTF_8);
        byte[] channelMessage = "topic:message:single".getBytes(StandardCharsets.UTF_8);

        for (MessageListener listener : listeners) {
            listener.onMessage(new DefaultMessage(channelBytes, body), null);
            listener.onMessage(new DefaultMessage(channelMessage, body), null);
        }

        assertThat(byteService.latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(messageService.latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(byteService.receivedBytes.get()).isEqualTo(body);
        assertThat(messageService.receivedMessage.get().getBody()).isEqualTo(body);
    }

    @Test
    @DisplayName("测试双参数 (payload, channel) 消费")
    void testTwoParamEventAndChannel() throws Exception {
        TwoParamService service = new TwoParamService();
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("twoParamService", TwoParamService.class, () -> service);
        context.refresh();

        bootstrap.setApplicationContext(context);
        bootstrap.afterSingletonsInstantiated();

        ArgumentCaptor<MessageListener> listenerCaptor = ArgumentCaptor.forClass(MessageListener.class);
        verify(listenerContainer, atLeastOnce()).addMessageListener(listenerCaptor.capture(), any(Collection.class));

        MessageListener listener = listenerCaptor.getValue();
        BroadcastEvent event = new BroadcastEvent("EVT-2002", "USER_REGISTERED", "新用户注册成功");
        byte[] body = objectMapper.writeValueAsBytes(event);
        byte[] channel = "topic:event:two".getBytes(StandardCharsets.UTF_8);

        listener.onMessage(new DefaultMessage(channel, body), null);

        boolean finished = service.latch.await(3, TimeUnit.SECONDS);
        assertThat(finished).isTrue();
        assertThat(service.receivedEvent.get().getEventId()).isEqualTo("EVT-2002");
        assertThat(service.receivedChannel.get()).isEqualTo("topic:event:two");
    }

    @Test
    @DisplayName("测试通配模式 patterns 及三参数 (payload, channel, pattern) 消费")
    void testThreeParamPatternMatching() throws Exception {
        ThreeParamPatternService service = new ThreeParamPatternService();
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("threeParamPatternService", ThreeParamPatternService.class, () -> service);
        context.refresh();

        bootstrap.setApplicationContext(context);
        bootstrap.afterSingletonsInstantiated();

        ArgumentCaptor<MessageListener> listenerCaptor = ArgumentCaptor.forClass(MessageListener.class);
        ArgumentCaptor<Collection<Topic>> topicsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(listenerContainer, atLeastOnce()).addMessageListener(listenerCaptor.capture(), topicsCaptor.capture());

        Collection<Topic> registeredTopics = topicsCaptor.getValue();
        assertThat(registeredTopics).anyMatch(t -> t instanceof PatternTopic && t.getTopic().equals("topic:event:*"));

        MessageListener listener = listenerCaptor.getValue();
        BroadcastEvent event = new BroadcastEvent("EVT-3003", "STOCK_WARNING", "库存警戒广播");
        byte[] body = objectMapper.writeValueAsBytes(event);
        byte[] channel = "topic:event:stock".getBytes(StandardCharsets.UTF_8);
        byte[] pattern = "topic:event:*".getBytes(StandardCharsets.UTF_8);

        listener.onMessage(new DefaultMessage(channel, body), pattern);

        boolean finished = service.latch.await(3, TimeUnit.SECONDS);
        assertThat(finished).isTrue();
        assertThat(service.receivedEvent.get().getEventId()).isEqualTo("EVT-3003");
        assertThat(service.receivedChannel.get()).isEqualTo("topic:event:stock");
        assertThat(service.receivedPattern.get()).isEqualTo("topic:event:*");
    }

    @Test
    @DisplayName("测试 ${...} 占位符解析")
    void testPlaceholderResolution() throws Exception {
        PlaceholderService service = new PlaceholderService();
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("placeholderService", PlaceholderService.class, () -> service);
        context.refresh();

        bootstrap.setApplicationContext(context);
        bootstrap.setEmbeddedValueResolver(str -> {
            if ("${app.broadcast.topic:default:topic}".equals(str)) {
                return "resolved:app:broadcast:topic";
            }
            return str;
        });
        bootstrap.afterSingletonsInstantiated();

        ArgumentCaptor<Collection<Topic>> topicsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(listenerContainer, atLeastOnce()).addMessageListener(any(MessageListener.class), topicsCaptor.capture());

        Collection<Topic> registeredTopics = topicsCaptor.getValue();
        assertThat(registeredTopics).anyMatch(t -> t instanceof ChannelTopic && t.getTopic().equals("resolved:app:broadcast:topic"));
    }

    @Test
    @DisplayName("测试超过 3 个参数时快速失败抛出 IllegalArgumentException")
    void testInvalidParamCountFailFast() {
        InvalidParamCountService service = new InvalidParamCountService();
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("invalidService", InvalidParamCountService.class, () -> service);
        context.refresh();

        bootstrap.setApplicationContext(context);
        assertThatThrownBy(() -> bootstrap.afterSingletonsInstantiated())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多支持 3 个参数");
    }

    @Test
    @DisplayName("测试 topic() 单数形式别名属性解析与消息分发")
    void testSingleTopicAliasResolution() throws Exception {
        SingleTopicAliasService service = new SingleTopicAliasService();
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("aliasService", SingleTopicAliasService.class, () -> service);
        context.refresh();

        bootstrap.setApplicationContext(context);
        bootstrap.afterSingletonsInstantiated();

        ArgumentCaptor<MessageListener> listenerCaptor = ArgumentCaptor.forClass(MessageListener.class);
        ArgumentCaptor<Collection<Topic>> topicsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(listenerContainer, atLeastOnce()).addMessageListener(listenerCaptor.capture(), topicsCaptor.capture());

        Collection<Topic> registeredTopics = topicsCaptor.getValue();
        assertThat(registeredTopics).anyMatch(t -> t instanceof ChannelTopic && t.getTopic().equals("topic:alias:single"));

        MessageListener listener = listenerCaptor.getValue();
        byte[] body = "Hello Alias Topic".getBytes(StandardCharsets.UTF_8);
        byte[] channel = "topic:alias:single".getBytes(StandardCharsets.UTF_8);

        listener.onMessage(new DefaultMessage(channel, body), null);

        boolean finished = service.latch.await(3, TimeUnit.SECONDS);
        assertThat(finished).isTrue();
        assertThat(service.receivedMessage.get()).isEqualTo("Hello Alias Topic");
    }
}
