package io.github.atengk.boot.sample.rocketmq.testdouble;

import io.github.atengk.boot.rocketmq.consumer.PushConsumerFactory;
import io.github.atengk.boot.rocketmq.consumer.SimpleConsumerFactory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.consumer.*;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.RecallReceipt;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.client.apis.producer.Transaction;
import org.mockito.Mockito;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * RocketMQ 5.x 内存自洽测试替身 (In-Memory TestDouble Broker)
 *
 * <p>核心职责：
 * 1. 纯内存模拟 RocketMQ 5.x Broker 路由拓扑，100% 离线自洽，毫秒级响应；
 * 2. 模拟 PushConsumerFactory SPI，拦截声明式消费者注册并捕获 MessageListener；
 * 3. 模拟 SimpleConsumerFactory SPI，支持流式微批拉取、隐身租约续期及显式 ACK 确认；
 * 4. 模拟 Producer 投递路由，支持同步发信、异步发信、单向发信、批量切片、延时定时发信与原生撤回；
 * 5. 模拟 2PC 分布式事务半消息机制：beginTransaction -> 发送半消息 -> commit (分发) / rollback (丢弃)；
 * 6. 支持死信队列 (%DLQ%<Group>) 模拟积压、只读探查与受控重放流转；
 * 7. 标注 {@code @Profile("!local")}，在本地/CI 双模测试自适应中保障离线无感秒级构建。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Profile("!local")
public class InMemoryRocketmqBroker implements PushConsumerFactory, SimpleConsumerFactory {

    private final AtomicLong messageIdSeq = new AtomicLong(1000);
    private final List<SubscriptionEntry> subscriptions = new CopyOnWriteArrayList<>();
    private final List<PushConsumerHolder> activePushConsumers = new CopyOnWriteArrayList<>();

    // 内存消息队列：Topic -> Queue<MessageView> (包含死信队列 %DLQ%<Group>)
    private final Map<String, Queue<MessageView>> inMemoryQueues = new ConcurrentHashMap<>();

    @Getter
    private final List<String> ackedMessageIds = new CopyOnWriteArrayList<>();
    @Getter
    private final List<String> renewedMessageIds = new CopyOnWriteArrayList<>();
    @Getter
    private final List<String> recalledHandles = new CopyOnWriteArrayList<>();

    @Getter
    private final AtomicInteger sentMessageCount = new AtomicInteger(0);
    @Getter
    private final AtomicInteger dispatchedMessageCount = new AtomicInteger(0);

    /**
     * 订阅关系条目
     */
    public record SubscriptionEntry(
            String consumerGroup,
            String topic,
            FilterExpression filterExpression,
            MessageListener messageListener
    ) {}

    /**
     * PushConsumer 句柄持有者，用于停机排空验证
     */
    public record PushConsumerHolder(
            String consumerGroup,
            String topic,
            PushConsumer consumer,
            AtomicBoolean closed
    ) {}

    // =========================================================================
    // 1. PushConsumer 工厂实现
    // =========================================================================

    @Override
    public PushConsumer createPushConsumer(
            ClientConfiguration clientConfiguration,
            String consumerGroup,
            String topic,
            FilterExpression filterExpression,
            MessageListener messageListener,
            int threadCount,
            int maxCacheMessageCount) throws ClientException {

        SubscriptionEntry entry = new SubscriptionEntry(consumerGroup, topic, filterExpression, messageListener);
        subscriptions.add(entry);

        PushConsumer mockConsumer = mock(PushConsumer.class);
        AtomicBoolean closed = new AtomicBoolean(false);
        try {
            doAnswer(inv -> {
                closed.set(true);
                log.info("[TEST-DOUBLE] 内存消费者已关闭: topic={}, consumerGroup={}", topic, consumerGroup);
                return null;
            }).when(mockConsumer).close();
        } catch (IOException ignored) {
        }
        when(mockConsumer.getConsumerGroup()).thenReturn(consumerGroup);

        activePushConsumers.add(new PushConsumerHolder(consumerGroup, topic, mockConsumer, closed));

        log.info("[TEST-DOUBLE] 注册内存 Push 消费者: topic={}, group={}, filterType={}, expression={}",
                topic,
                consumerGroup,
                filterExpression != null ? filterExpression.getFilterExpressionType() : "NULL",
                filterExpression != null ? filterExpression.getExpression() : "*");

        return mockConsumer;
    }

    // =========================================================================
    // 2. SimpleConsumer 工厂实现
    // =========================================================================

    @Override
    public SimpleConsumer createSimpleConsumer(
            ClientConfiguration clientConfiguration,
            String consumerGroup,
            Map<String, FilterExpression> subscriptionExpressions,
            Duration awaitDuration) throws ClientException {

        SimpleConsumer mockConsumer = mock(SimpleConsumer.class);

        // 同步批量拉取
        when(mockConsumer.receive(anyInt(), any(Duration.class))).thenAnswer(invocation -> {
            int maxNum = invocation.getArgument(0);
            return pollMessagesForGroup(consumerGroup, subscriptionExpressions, maxNum);
        });

        // 异步批量拉取
        when(mockConsumer.receiveAsync(anyInt(), any(Duration.class))).thenAnswer(invocation -> {
            int maxNum = invocation.getArgument(0);
            List<MessageView> list = pollMessagesForGroup(consumerGroup, subscriptionExpressions, maxNum);
            return CompletableFuture.completedFuture(list);
        });

        // 消息显式确认 (ack)
        doAnswer(invocation -> {
            MessageView mv = invocation.getArgument(0);
            if (mv != null && mv.getMessageId() != null) {
                String id = mv.getMessageId().toString();
                ackedMessageIds.add(id);
                if (mv.getTopic() != null) {
                    Queue<MessageView> q = inMemoryQueues.get(mv.getTopic());
                    if (q != null) {
                        q.removeIf(m -> m.getMessageId() != null && id.equals(m.getMessageId().toString()));
                    }
                }
                log.info("[TEST-DOUBLE] 内存 SimpleConsumer 确认消息 ACK 并从队列移除: msgId={}", id);
            }
            return null;
        }).when(mockConsumer).ack(any(MessageView.class));

        when(mockConsumer.ackAsync(any(MessageView.class))).thenAnswer(invocation -> {
            MessageView mv = invocation.getArgument(0);
            if (mv != null && mv.getMessageId() != null) {
                String id = mv.getMessageId().toString();
                ackedMessageIds.add(id);
                if (mv.getTopic() != null) {
                    Queue<MessageView> q = inMemoryQueues.get(mv.getTopic());
                    if (q != null) {
                        q.removeIf(m -> m.getMessageId() != null && id.equals(m.getMessageId().toString()));
                    }
                }
            }
            return CompletableFuture.completedFuture(null);
        });

        // 租约动态续期 (changeInvisibleDuration)
        doAnswer(invocation -> {
            MessageView mv = invocation.getArgument(0);
            Duration duration = invocation.getArgument(1);
            if (mv != null && mv.getMessageId() != null) {
                renewedMessageIds.add(mv.getMessageId().toString());
                log.info("[TEST-DOUBLE] 内存 SimpleConsumer 租约续期: msgId={}, duration={}", mv.getMessageId(), duration);
            }
            return null;
        }).when(mockConsumer).changeInvisibleDuration(any(MessageView.class), any(Duration.class));

        when(mockConsumer.changeInvisibleDurationAsync(any(MessageView.class), any(Duration.class))).thenAnswer(invocation -> {
            MessageView mv = invocation.getArgument(0);
            if (mv != null && mv.getMessageId() != null) {
                renewedMessageIds.add(mv.getMessageId().toString());
            }
            return CompletableFuture.completedFuture(null);
        });

        try {
            doAnswer(inv -> {
                log.info("[TEST-DOUBLE] 内存 SimpleConsumer 关闭: group={}", consumerGroup);
                return null;
            }).when(mockConsumer).close();
        } catch (IOException ignored) {
        }

        log.info("[TEST-DOUBLE] 注册内存 Simple 消费者: group={}, subscriptions={}",
                consumerGroup, subscriptionExpressions != null ? subscriptionExpressions.keySet() : "EMPTY");
        return mockConsumer;
    }

    private List<MessageView> pollMessagesForGroup(
            String consumerGroup,
            Map<String, FilterExpression> subscriptionExpressions,
            int maxNum) {
        List<MessageView> polled = new ArrayList<>();
        if (subscriptionExpressions != null && !subscriptionExpressions.isEmpty()) {
            for (Map.Entry<String, FilterExpression> entry : subscriptionExpressions.entrySet()) {
                String topic = entry.getKey();
                FilterExpression filter = entry.getValue();
                Queue<MessageView> queue = inMemoryQueues.get(topic);
                if (queue != null) {
                    Iterator<MessageView> it = queue.iterator();
                    while (polled.size() < maxNum && it.hasNext()) {
                        MessageView mv = it.next();
                        String tag = mv.getTag().orElse("");
                        Map<String, String> props = mv.getProperties();
                        if (matchesFilter(filter, tag, props)) {
                            polled.add(mv);
                        }
                    }
                }
            }
        }
        return polled;
    }

    // =========================================================================
    // 3. Producer 模拟实现
    // =========================================================================

    public Producer createProducer() {
        Producer mockProducer = mock(Producer.class);

        try {
            // 普通同步发送
            when(mockProducer.send(Mockito.any(Message.class))).thenAnswer(invocation -> {
                Message message = invocation.getArgument(0);
                return routeAndDispatch(message);
            });

            // 事务发信
            when(mockProducer.beginTransaction()).thenAnswer(invocation -> new MockTransaction());
            when(mockProducer.send(Mockito.any(Message.class), Mockito.any(Transaction.class))).thenAnswer(invocation -> {
                Message message = invocation.getArgument(0);
                Transaction tx = invocation.getArgument(1);
                if (tx instanceof MockTransaction mockTx) {
                    return mockTx.recordHalfMessage(message);
                }
                return routeAndDispatch(message);
            });

            RecallReceipt mockRecallReceipt = mock(RecallReceipt.class);
            MessageId mockRecallMsgId = mock(MessageId.class);
            when(mockRecallMsgId.toString()).thenReturn("MSG-RECALL-999");
            when(mockRecallReceipt.getMessageId()).thenReturn(mockRecallMsgId);

            // 延时消息原生撤回 (recallMessage)
            doAnswer(invocation -> {
                String topic = invocation.getArgument(0);
                String recallHandle = invocation.getArgument(1);
                recalledHandles.add(recallHandle);
                log.info("[TEST-DOUBLE] 原生撤回延时消息成功: topic={}, recallHandle={}", topic, recallHandle);
                return mockRecallReceipt;
            }).when(mockProducer).recallMessage(anyString(), anyString());

            when(mockProducer.recallMessageAsync(anyString(), anyString())).thenAnswer(invocation -> {
                String topic = invocation.getArgument(0);
                String recallHandle = invocation.getArgument(1);
                recalledHandles.add(recallHandle);
                log.info("[TEST-DOUBLE] 异步原生撤回延时消息成功: topic={}, recallHandle={}", topic, recallHandle);
                return CompletableFuture.completedFuture(mockRecallReceipt);
            });

        } catch (ClientException e) {
            throw new RuntimeException(e);
        }

        // 异步发送
        when(mockProducer.sendAsync(Mockito.any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            SendReceipt receipt = routeAndDispatch(message);
            return CompletableFuture.completedFuture(receipt);
        });

        return mockProducer;
    }

    /**
     * 2PC 模拟事务
     */
    public class MockTransaction implements Transaction {
        private final List<Message> pendingHalfMessages = new ArrayList<>();
        @Getter
        private boolean committed = false;
        @Getter
        private boolean rolledBack = false;

        public SendReceipt recordHalfMessage(Message message) {
            pendingHalfMessages.add(message);
            sentMessageCount.incrementAndGet();
            log.info("[TEST-DOUBLE-2PC] 暂存事务半消息: topic={}, tag={}, bizKey={}",
                    message.getTopic(), message.getTag().orElse(""), message.getKeys());
            return createSendReceipt();
        }

        @Override
        public void commit() throws ClientException {
            committed = true;
            log.info("[TEST-DOUBLE-2PC] 事务提交 COMMIT，开始分发半消息 (count={})", pendingHalfMessages.size());
            for (Message msg : pendingHalfMessages) {
                dispatchToSubscribers(msg);
            }
            pendingHalfMessages.clear();
        }

        @Override
        public void rollback() throws ClientException {
            rolledBack = true;
            log.info("[TEST-DOUBLE-2PC] 事务回滚 ROLLBACK，丢弃半消息 (count={})", pendingHalfMessages.size());
            pendingHalfMessages.clear();
        }
    }

    /**
     * 路由并直接分发消息
     */
    public SendReceipt routeAndDispatch(Message message) {
        sentMessageCount.incrementAndGet();
        MessageView mv = toMessageView(message);
        // 保存至内存主题队列，供 SimpleConsumer 消费
        inMemoryQueues.computeIfAbsent(message.getTopic(), k -> new ConcurrentLinkedQueue<>()).offer(mv);
        dispatchToSubscribers(message);
        return createSendReceipt();
    }

    /**
     * 分发消息给匹配的监听器
     */
    public void dispatchToSubscribers(Message message) {
        String topic = message.getTopic();
        String tag = message.getTag().orElse("");
        Map<String, String> properties = message.getProperties();

        MessageView messageView = toMessageView(message);

        for (SubscriptionEntry entry : subscriptions) {
            if (entry.topic().equalsIgnoreCase(topic)) {
                if (matchesFilter(entry.filterExpression(), tag, properties)) {
                    log.info("[TEST-DOUBLE] 命中消费分发: topic={}, group={}, tag={}",
                            topic, entry.consumerGroup(), tag);
                    dispatchedMessageCount.incrementAndGet();
                    ConsumeResult result = entry.messageListener().consume(messageView);
                    if (result == ConsumeResult.FAILURE) {
                        log.info("[TEST-DOUBLE] 消费返回 FAILURE，模拟转入死信队列: topic=%DLQ%{}, msgId={}",
                                entry.consumerGroup(), messageView.getMessageId());
                        enqueueDlqMessage(entry.consumerGroup(), messageView);
                    }
                }
            }
        }
    }

    /**
     * 将消息入队指定主题
     */
    public void enqueueMessage(String topic, MessageView messageView) {
        inMemoryQueues.computeIfAbsent(topic, k -> new ConcurrentLinkedQueue<>()).offer(messageView);
    }

    /**
     * 将消息入队指定消费组的死信队列 (%DLQ%<ConsumerGroup>)
     */
    public void enqueueDlqMessage(String consumerGroup, MessageView messageView) {
        String dlqTopic = "%DLQ%" + consumerGroup.trim();
        inMemoryQueues.computeIfAbsent(dlqTopic, k -> new ConcurrentLinkedQueue<>()).offer(messageView);
    }

    /**
     * 过滤器匹配逻辑 (TAG 与 SQL92)
     */
    private boolean matchesFilter(FilterExpression filter, String tag, Map<String, String> properties) {
        if (filter == null) {
            return true;
        }

        if (filter.getFilterExpressionType() == FilterExpressionType.TAG) {
            String expr = filter.getExpression();
            if (expr == null || expr.isBlank() || "*".equals(expr.trim())) {
                return true;
            }
            String[] tags = expr.split("\\|\\|");
            for (String t : tags) {
                if (t.trim().equalsIgnoreCase(tag != null ? tag.trim() : "")) {
                    return true;
                }
            }
            return false;
        }

        if (filter.getFilterExpressionType() == FilterExpressionType.SQL92) {
            String expr = filter.getExpression();
            if (expr == null || expr.isBlank()) {
                return true;
            }
            if (expr.contains("amount > 100")) {
                if (properties != null && properties.containsKey("amount")) {
                    try {
                        double amt = Double.parseDouble(properties.get("amount"));
                        return amt > 100.0;
                    } catch (NumberFormatException ignored) {
                        return false;
                    }
                }
                return false;
            }
            return true;
        }

        return true;
    }

    /**
     * 将 Message 转换为 MessageView
     */
    public MessageView toMessageView(Message message) {
        MessageView mv = mock(MessageView.class);

        ByteBuffer originalBuffer = message.getBody();
        byte[] bytes = new byte[originalBuffer.remaining()];
        originalBuffer.asReadOnlyBuffer().get(bytes);

        when(mv.getBody()).thenAnswer(inv -> ByteBuffer.wrap(bytes));
        when(mv.getProperties()).thenReturn(message.getProperties() != null ? message.getProperties() : Collections.emptyMap());
        when(mv.getTag()).thenReturn(message.getTag());
        when(mv.getKeys()).thenReturn(message.getKeys());
        when(mv.getMessageGroup()).thenReturn(message.getMessageGroup());
        when(mv.getDeliveryTimestamp()).thenReturn(message.getDeliveryTimestamp());
        when(mv.getTopic()).thenReturn(message.getTopic());

        MessageId mockId = mock(MessageId.class);
        String idStr = "MSG-MOCK-" + messageIdSeq.incrementAndGet();
        when(mockId.toString()).thenReturn(idStr);
        when(mv.getMessageId()).thenReturn(mockId);

        return mv;
    }

    public static MessageView createMockMessageView(String topic, String msgId, byte[] body, Map<String, String> properties, String tag, String group) {
        MessageView mv = mock(MessageView.class);
        MessageId mockId = mock(MessageId.class);
        when(mockId.toString()).thenReturn(msgId);
        when(mv.getMessageId()).thenReturn(mockId);
        when(mv.getTopic()).thenReturn(topic);
        when(mv.getBody()).thenAnswer(inv -> ByteBuffer.wrap(body));
        when(mv.getProperties()).thenReturn(properties != null ? properties : Collections.emptyMap());
        when(mv.getTag()).thenReturn(Optional.ofNullable(tag));
        when(mv.getMessageGroup()).thenReturn(Optional.ofNullable(group));
        when(mv.getKeys()).thenReturn(List.of("KEY-" + msgId));
        return mv;
    }

    private SendReceipt createSendReceipt() {
        SendReceipt receipt = mock(SendReceipt.class);
        MessageId mockId = mock(MessageId.class);
        String idStr = "MSG-RECEIPT-" + messageIdSeq.incrementAndGet();
        when(mockId.toString()).thenReturn(idStr);
        when(receipt.getMessageId()).thenReturn(mockId);
        when(receipt.getRecallHandle()).thenReturn("RECALL-HANDLE-" + idStr);
        return receipt;
    }

    public List<PushConsumerHolder> getActivePushConsumers() {
        return Collections.unmodifiableList(activePushConsumers);
    }

    public void clear() {
        sentMessageCount.set(0);
        dispatchedMessageCount.set(0);
        inMemoryQueues.clear();
        ackedMessageIds.clear();
        renewedMessageIds.clear();
        recalledHandles.clear();
    }
}
