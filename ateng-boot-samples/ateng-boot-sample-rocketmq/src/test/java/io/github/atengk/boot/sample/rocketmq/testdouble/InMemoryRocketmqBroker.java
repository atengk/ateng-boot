package io.github.atengk.boot.sample.rocketmq.testdouble;

import io.github.atengk.boot.rocketmq.consumer.PushConsumerFactory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.client.apis.producer.Transaction;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RocketMQ 5.x 内存自洽测试替身 (In-Memory TestDouble Broker)
 *
 * <p>核心职责：
 * 1. 纯内存模拟 RocketMQ 5.x Broker 路由拓扑，100% 离线自洽，毫秒级响应；
 * 2. 模拟 PushConsumerFactory SPI，拦截声明式消费者注册并捕获 MessageListener；
 * 3. 模拟 Producer 投递路由，支持同步发信、异步发信、TAG 过滤、SQL92 属性过滤；
 * 4. 模拟 2PC 分布式事务半消息机制：beginTransaction -> 发送半消息 -> commit (分发) / rollback (丢弃)；
 * 5. 全生命周期状态跟踪与停机排空监控。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class InMemoryRocketmqBroker implements PushConsumerFactory {

    private final AtomicLong messageIdSeq = new AtomicLong(1000);
    private final List<SubscriptionEntry> subscriptions = new CopyOnWriteArrayList<>();
    private final List<PushConsumerHolder> activePushConsumers = new CopyOnWriteArrayList<>();

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

        log.info("[TEST-DOUBLE] 注册内存消费者: topic={}, group={}, filterType={}, expression={}",
                topic,
                consumerGroup,
                filterExpression != null ? filterExpression.getFilterExpressionType() : "NULL",
                filterExpression != null ? filterExpression.getExpression() : "*");

        return mockConsumer;
    }

    /**
     * 创建内存 Producer 代理
     */
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
                    entry.messageListener().consume(messageView);
                }
            }
        }
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
            // 支持 "tagA || tagB"
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
            // 简单模拟 SQL92: amount > 100
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

        MessageId mockId = mock(MessageId.class);
        String idStr = "MSG-MOCK-" + messageIdSeq.incrementAndGet();
        when(mockId.toString()).thenReturn(idStr);
        when(mv.getMessageId()).thenReturn(mockId);

        return mv;
    }

    private SendReceipt createSendReceipt() {
        SendReceipt receipt = mock(SendReceipt.class);
        MessageId mockId = mock(MessageId.class);
        String idStr = "MSG-RECEIPT-" + messageIdSeq.incrementAndGet();
        when(mockId.toString()).thenReturn(idStr);
        when(receipt.getMessageId()).thenReturn(mockId);
        return receipt;
    }

    public List<PushConsumerHolder> getActivePushConsumers() {
        return Collections.unmodifiableList(activePushConsumers);
    }

    public void clear() {
        sentMessageCount.set(0);
        dispatchedMessageCount.set(0);
    }
}
