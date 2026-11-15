package io.github.atengk.boot.sample.rabbitmq.offline;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import io.github.atengk.boot.rabbitmq.interceptor.RabbitListenerGlobalAdvice;
import io.github.atengk.boot.sample.rabbitmq.config.RabbitScenarioTopologyConfig;
import io.github.atengk.boot.sample.rabbitmq.consumer.AnonymousBroadcastConsumer;
import io.github.atengk.boot.sample.rabbitmq.consumer.BatchOrderConsumer;
import io.github.atengk.boot.sample.rabbitmq.consumer.DeadLetterAuditConsumer;
import io.github.atengk.boot.sample.rabbitmq.consumer.IdempotentOrderConsumer;
import io.github.atengk.boot.sample.rabbitmq.consumer.LogTopicConsumer;
import io.github.atengk.boot.sample.rabbitmq.consumer.OrderConsumer;
import io.github.atengk.boot.sample.rabbitmq.consumer.OrderDelayConsumer;
import io.github.atengk.boot.sample.rabbitmq.consumer.PoisonPillConsumer;
import io.github.atengk.boot.sample.rabbitmq.consumer.PriorityOrderConsumer;
import io.github.atengk.boot.sample.rabbitmq.model.LogMessage;
import io.github.atengk.boot.sample.rabbitmq.model.OrderCreateMessage;
import io.github.atengk.boot.sample.rabbitmq.model.OrderTimeoutMessage;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * RabbitMQ 离线自洽内存模拟 Broker，用于在无真实服务端环境（非 local Profile）下支撑拓扑声明、消息路由、队列暂存及确认回滚语义
 *
 * @author Ateng
 * @since 2026-11-15
 */
@Slf4j
public class OfflineAmqpBroker {

    private static final int REPLY_CODE_NO_ROUTE = 312;
    private static final String REPLY_TEXT_NO_ROUTE = "NO_ROUTE";

    private final Set<String> declaredExchanges = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, QueueSlot> queues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> consumerTagQueues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, PulledMessage> unackedDeliveries = new ConcurrentHashMap<>();
    private final AtomicLong deliveryTagSequence = new AtomicLong(1L);
    private final AtomicLong ackCount = new AtomicLong(0L);
    private final AtomicLong nackRequeueCount = new AtomicLong(0L);
    private final CopyOnWriteArrayList<DispatchedMessage> sentMessages = new CopyOnWriteArrayList<>();

    private final MessageConverter fallbackConverter = new Jackson2JsonMessageConverter();
    private final ObjectProvider<MessageConverter> messageConverterProvider;
    private final ListableBeanFactory beanFactory;

    private volatile RabbitTemplate.ReturnsCallback returnsCallback;

    public OfflineAmqpBroker(
            ObjectProvider<MessageConverter> messageConverterProvider,
            ListableBeanFactory beanFactory) {
        this.messageConverterProvider = messageConverterProvider;
        this.beanFactory = beanFactory;
    }

    /**
     * 绑定不可路由消息退回回调，由 OfflineRabbitTemplate 在装配阶段注入
     */
    public void setReturnsCallback(RabbitTemplate.ReturnsCallback returnsCallback) {
        this.returnsCallback = returnsCallback;
    }

    /**
     * 声明内存交换机（幂等）
     *
     * @param exchange 交换机名称
     */
    public void declareExchange(String exchange) {
        if (exchange != null && !exchange.isBlank()) {
            declaredExchanges.add(exchange);
        }
    }

    /**
     * 声明内存队列（幂等）并返还当前积压消息数
     *
     * @param queue 队列名称
     * @return 当前队列消息条数
     */
    public int declareQueue(String queue) {
        if (queue == null || queue.isBlank()) {
            return 0;
        }
        return queues.computeIfAbsent(queue, k -> new QueueSlot()).size();
    }

    /**
     * 判断内存队列是否已声明
     *
     * @param queue 队列名称
     * @return 已声明返回 true
     */
    public boolean queueExists(String queue) {
        return queue != null && queues.containsKey(queue);
    }

    /**
     * 获取内存队列当前消息积压数量
     *
     * @param queue 队列名称
     * @return 队列深度，未声明队列返回 0
     */
    public int getQueueMessageCount(String queue) {
        QueueSlot slot = queue != null ? queues.get(queue) : null;
        return slot != null ? slot.size() : 0;
    }

    /**
     * 向指定内存队列尾部入队一条原始 AMQP GetResponse 消息（若队列尚未声明则自动创建）
     *
     * @param queue    目标队列名称
     * @param response AMQP 拉取响应对象
     */
    public void enqueueRaw(String queue, GetResponse response) {
        Objects.requireNonNull(queue, "queue 不能为空");
        Objects.requireNonNull(response, "response 不能为空");
        QueueSlot slot = queues.computeIfAbsent(queue, k -> new QueueSlot());
        slot.addLast(response);
    }

    /**
     * 构造带标准 x-death 诊断头的死信消息并入队至指定死信队列，便于离线 peek / replay / purge 验证
     *
     * @param dlqQueue       死信队列名称
     * @param originExchange 原始业务交换机
     * @param originQueue    原始业务队列
     * @param reason         死亡原因 (如 rejected / expired / maxlen)
     * @param payload        序列化后的 JSON 字节数组载荷
     * @param extraHeaders   附加业务头 (如 bizKey, X-Replay-Count 等)
     */
    public void enqueueDeadLetter(
            String dlqQueue,
            String originExchange,
            String originQueue,
            String reason,
            byte[] payload,
            Map<String, Object> extraHeaders) {
        Map<String, Object> headers = new HashMap<>();
        if (extraHeaders != null) {
            headers.putAll(extraHeaders);
        }
        Map<String, Object> xDeathEntry = new HashMap<>();
        xDeathEntry.put("queue", originQueue);
        xDeathEntry.put("exchange", originExchange);
        xDeathEntry.put("reason", reason);
        xDeathEntry.put("count", 1L);
        headers.put("x-death", List.of(xDeathEntry));

        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .headers(headers)
                .contentType("application/json")
                .build();
        Envelope envelope = new Envelope(0L, false, originExchange != null ? originExchange : "", originQueue != null ? originQueue : "");
        enqueueRaw(dlqQueue, new GetResponse(envelope, props, payload, getQueueMessageCount(dlqQueue)));
    }

    /**
     * 从指定内存队列头部拉取一条消息，分配单调递增 deliveryTag 并记入未确认集合
     *
     * @param queue 队列名称
     * @return 消息响应，队列为空返回 null
     */
    public GetResponse basicGet(String queue) {
        QueueSlot slot = queue != null ? queues.get(queue) : null;
        if (slot == null) {
            return null;
        }
        GetResponse stored = slot.pollFirst();
        if (stored == null) {
            return null;
        }
        long deliveryTag = deliveryTagSequence.getAndIncrement();
        Envelope originalEnv = stored.getEnvelope();
        Envelope assignedEnv = new Envelope(
                deliveryTag,
                originalEnv != null && originalEnv.isRedeliver(),
                originalEnv != null ? originalEnv.getExchange() : "",
                originalEnv != null ? originalEnv.getRoutingKey() : ""
        );
        GetResponse delivered = new GetResponse(assignedEnv, stored.getProps(), stored.getBody(), slot.size());
        unackedDeliveries.put(deliveryTag, new PulledMessage(queue, stored));
        return delivered;
    }

    /**
     * 确认指定 deliveryTag 消息已完成出队消费
     *
     * @param deliveryTag 投递标识
     * @param multiple    是否批量确认（当前离线实现按单条记录统计）
     */
    public void basicAck(long deliveryTag, boolean multiple) {
        if (multiple) {
            unackedDeliveries.keySet().stream()
                    .filter(tag -> tag <= deliveryTag)
                    .toList()
                    .forEach(tag -> {
                        if (unackedDeliveries.remove(tag) != null) {
                            ackCount.incrementAndGet();
                        }
                    });
            return;
        }
        if (unackedDeliveries.remove(deliveryTag) != null) {
            ackCount.incrementAndGet();
        }
    }

    /**
     * 拒绝消息；当 requeue 为 true 时重新插回原队列头部以保障逆序回滚后的 FIFO 原序
     *
     * @param deliveryTag 投递标识
     * @param multiple    是否批量拒绝
     * @param requeue     是否重回队列头部
     */
    public void basicNack(long deliveryTag, boolean multiple, boolean requeue) {
        if (multiple) {
            unackedDeliveries.keySet().stream()
                    .filter(tag -> tag <= deliveryTag)
                    .sorted((a, b) -> Long.compare(b, a))
                    .toList()
                    .forEach(tag -> handleSingleNack(tag, requeue));
            return;
        }
        handleSingleNack(deliveryTag, requeue);
    }

    private void handleSingleNack(long deliveryTag, boolean requeue) {
        PulledMessage pulled = unackedDeliveries.remove(deliveryTag);
        if (pulled == null) {
            return;
        }
        if (requeue) {
            nackRequeueCount.incrementAndGet();
            QueueSlot slot = queues.get(pulled.queueName());
            if (slot != null) {
                slot.addFirst(pulled.response());
            }
        }
    }

    /**
     * 清空指定队列中的全部消息
     *
     * @param queue 队列名称
     * @return 被清空的消息条数
     */
    public int queuePurge(String queue) {
        QueueSlot slot = queue != null ? queues.get(queue) : null;
        if (slot == null) {
            return 0;
        }
        return slot.purge();
    }

    /**
     * 删除指定内存队列并清理关联的未确认句柄与消费者注册状态
     *
     * @param queue 队列名称
     * @return 删除前队列中残留的消息条数
     */
    public int deleteQueue(String queue) {
        if (queue == null || queue.isBlank()) {
            return 0;
        }
        QueueSlot removed = queues.remove(queue);
        consumerTagQueues.entrySet().removeIf(entry -> queue.equals(entry.getValue()));
        unackedDeliveries.entrySet().removeIf(entry -> queue.equals(entry.getValue().queueName()));
        return removed != null ? removed.purge() : 0;
    }

    /**
     * 注册队列消费者标签，用于跟踪在线消费者数量
     *
     * @param queue       队列名称
     * @param consumerTag 消费者标签
     */
    public void registerConsumer(String queue, String consumerTag) {
        if (queue != null && !queue.isBlank() && consumerTag != null && !consumerTag.isBlank()) {
            consumerTagQueues.put(consumerTag, queue);
        }
    }

    /**
     * 注销指定消费者标签
     *
     * @param consumerTag 消费者标签
     */
    public void cancelConsumer(String consumerTag) {
        if (consumerTag != null) {
            consumerTagQueues.remove(consumerTag);
        }
    }

    /**
     * 获取指定队列当前的在线消费者数量
     *
     * @param queue 队列名称
     * @return 消费者数量
     */
    public int getConsumerCount(String queue) {
        if (queue == null || queue.isBlank()) {
            return 0;
        }
        return (int) consumerTagQueues.values().stream()
                .filter(queue::equals)
                .count();
    }

    /**
     * 模拟发送派发：完成 Confirm Future、记录发信轨迹、触发场景消费或不可路由退回回调
     *
     * @param exchange        目标交换机
     * @param routingKey      路由键
     * @param message         Spring AMQP 消息
     * @param correlationData 发布确认关联数据
     */
    public void dispatch(String exchange, String routingKey, Message message, CorrelationData correlationData) {
        if (correlationData != null) {
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
        }
        sentMessages.add(new DispatchedMessage(exchange, routingKey, message));

        MessageConverter converter = resolveConverter();
        if (RabbitScenarioTopologyConfig.EXCHANGE_ORDER_DIRECT.equals(exchange)) {
            routeOrderDirect(exchange, routingKey, message, converter);
        } else if (RabbitScenarioTopologyConfig.EXCHANGE_LOG_TOPIC.equals(exchange)) {
            routeLogTopic(exchange, routingKey, message, converter);
        } else if (RabbitScenarioTopologyConfig.EXCHANGE_ORDER_DELAY.equals(exchange)
                && RabbitScenarioTopologyConfig.ROUTING_KEY_ORDER_TIMEOUT.equals(routingKey)) {
            OrderTimeoutMessage payload = (OrderTimeoutMessage) converter.fromMessage(message);
            withBean(OrderDelayConsumer.class, consumer -> consumer.onTimeout(payload));
        } else if (RabbitScenarioTopologyConfig.EXCHANGE_BATCH_DIRECT.equals(exchange)
                && RabbitScenarioTopologyConfig.ROUTING_KEY_BATCH_ORDER.equals(routingKey)) {
            OrderCreateMessage payload = (OrderCreateMessage) converter.fromMessage(message);
            withBean(BatchOrderConsumer.class, consumer -> consumer.onBatch(List.of(payload)));
        } else if (RabbitScenarioTopologyConfig.EXCHANGE_PRIORITY_DIRECT.equals(exchange)
                && RabbitScenarioTopologyConfig.ROUTING_KEY_PRIORITY_ORDER.equals(routingKey)) {
            OrderCreateMessage payload = (OrderCreateMessage) converter.fromMessage(message);
            withBean(PriorityOrderConsumer.class,
                    consumer -> consumer.onPriorityOrder(payload, message.getMessageProperties().getPriority()));
        } else if (RabbitScenarioTopologyConfig.EXCHANGE_BROADCAST_FANOUT.equals(exchange)) {
            String payload = decodePlainText(message);
            withBean(AnonymousBroadcastConsumer.class, consumer -> consumer.onBroadcast(payload));
        } else if (RabbitScenarioTopologyConfig.EXCHANGE_BIZ_DIRECT.equals(exchange)
                && RabbitScenarioTopologyConfig.ROUTING_KEY_BIZ.equals(routingKey)) {
            routeBizWithDlqFallback(exchange, message, converter);
        } else {
            notifyUnroutable(exchange, routingKey, message);
        }
    }

    private void routeOrderDirect(String exchange, String routingKey, Message message, MessageConverter converter) {
        if (RabbitScenarioTopologyConfig.ROUTING_KEY_ORDER_CREATE.equals(routingKey)) {
            OrderCreateMessage payload = (OrderCreateMessage) converter.fromMessage(message);
            String traceId = extractHeader(message, "X-Trace-Id");
            String tenantId = extractHeader(message, "X-Tenant-Id");
            withBean(OrderConsumer.class, consumer -> consumer.onOrderCreate(payload, traceId, tenantId));
            return;
        }
        if (RabbitScenarioTopologyConfig.ROUTING_KEY_ORDER_IDEMPOTENT.equals(routingKey)) {
            IdempotentOrderConsumer consumer = getBeanOrNull(IdempotentOrderConsumer.class);
            if (consumer == null) {
                return;
            }
            String payload = String.valueOf(converter.fromMessage(message));
            String bizKey = extractHeader(message, "X-Biz-Key");
            RabbitListenerGlobalAdvice advice = getBeanOrNull(RabbitListenerGlobalAdvice.class);
            if (advice == null) {
                consumer.onConsume(payload, bizKey);
                return;
            }
            MethodInvocation invocation = OfflineAmqpProxyFactory.createMethodInvocation(
                    new Object[]{message},
                    () -> {
                        consumer.onConsume(payload, bizKey);
                        return null;
                    }
            );
            try {
                advice.invoke(invocation);
            } catch (Throwable t) {
                throw new IllegalStateException("RabbitListenerGlobalAdvice 未能正常静默确认消费", t);
            }
            return;
        }
        notifyUnroutable(exchange, routingKey, message);
    }

    private void routeLogTopic(String exchange, String routingKey, Message message, MessageConverter converter) {
        if ("log.error".equals(routingKey) || (routingKey != null && routingKey.startsWith("log.error."))) {
            LogMessage payload = (LogMessage) converter.fromMessage(message);
            withBean(LogTopicConsumer.class, consumer -> consumer.onErrorMessage(payload));
            return;
        }
        notifyUnroutable(exchange, routingKey, message);
    }

    private void routeBizWithDlqFallback(String exchange, Message message, MessageConverter converter) {
        PoisonPillConsumer poisonConsumer = getBeanOrNull(PoisonPillConsumer.class);
        if (poisonConsumer == null) {
            return;
        }
        String payload = String.valueOf(converter.fromMessage(message));
        try {
            poisonConsumer.onBizMessage(payload);
        } catch (Exception ex) {
            MessageProperties dlProps = new MessageProperties();
            Map<String, Object> deathHeader = new HashMap<>();
            deathHeader.put("queue", RabbitScenarioTopologyConfig.QUEUE_BIZ_MAIN);
            deathHeader.put("exchange", exchange);
            deathHeader.put("reason", "rejected");
            deathHeader.put("count", 1L);
            deathHeader.put("time", new Date());
            dlProps.setHeader("x-death", List.of(deathHeader));

            Message dlMessage = new Message(message.getBody(), dlProps);
            withBean(DeadLetterAuditConsumer.class, audit -> audit.onDeadLetter(dlMessage));
        }
    }

    private void notifyUnroutable(String exchange, String routingKey, Message message) {
        log.debug("[OFFLINE-BROKER] 消息不可路由，触发退回回调: exchange={}, routingKey={}", exchange, routingKey);
        RabbitTemplate.ReturnsCallback callback = this.returnsCallback;
        if (callback != null) {
            callback.returnedMessage(new ReturnedMessage(
                    message,
                    REPLY_CODE_NO_ROUTE,
                    REPLY_TEXT_NO_ROUTE,
                    exchange != null ? exchange : "",
                    routingKey != null ? routingKey : ""
            ));
        }
    }

    private <T> T getBeanOrNull(Class<T> beanType) {
        return beanFactory != null ? beanFactory.getBeanProvider(beanType).getIfAvailable() : null;
    }

    private <T> void withBean(Class<T> beanType, Consumer<T> action) {
        if (beanFactory != null) {
            beanFactory.getBeanProvider(beanType).ifAvailable(action);
        }
    }

    private MessageConverter resolveConverter() {
        MessageConverter converter = messageConverterProvider != null ? messageConverterProvider.getIfAvailable() : null;
        return converter != null ? converter : fallbackConverter;
    }

    private static String extractHeader(Message message, String headerName) {
        Object val = message.getMessageProperties().getHeaders().get(headerName);
        return val != null ? String.valueOf(val) : null;
    }

    private static String decodePlainText(Message message) {
        String text = new String(message.getBody(), StandardCharsets.UTF_8);
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    /**
     * 获取离线 Broker 记录的全部外发消息轨迹（只读快照）
     *
     * @return 发送记录列表
     */
    public List<DispatchedMessage> getSentMessages() {
        return List.copyOf(sentMessages);
    }

    /**
     * 获取累计已确认出队（basicAck）的消息数
     *
     * @return ACK 总数
     */
    public long getAckCount() {
        return ackCount.get();
    }

    /**
     * 获取累计拒绝并重回队列（basicNack requeue=true）的次数
     *
     * @return 重回队列计数
     */
    public long getNackRequeueCount() {
        return nackRequeueCount.get();
    }

    /**
     * 清空运行期消息轨迹、未确认句柄与各队列积压（保留已声明的拓扑键名）
     */
    public void clear() {
        queues.values().forEach(QueueSlot::purge);
        consumerTagQueues.clear();
        unackedDeliveries.clear();
        sentMessages.clear();
        ackCount.set(0L);
        nackRequeueCount.set(0L);
    }

    /**
     * 离线外发消息快照记录
     */
    public record DispatchedMessage(String exchange, String routingKey, Message message) {
    }

    private record PulledMessage(String queueName, GetResponse response) {
    }

    private static final class QueueSlot {
        private final ReentrantLock lock = new ReentrantLock();
        private final Deque<GetResponse> entries = new ArrayDeque<>();

        void addLast(GetResponse response) {
            lock.lock();
            try {
                entries.addLast(response);
            } finally {
                lock.unlock();
            }
        }

        void addFirst(GetResponse response) {
            lock.lock();
            try {
                entries.addFirst(response);
            } finally {
                lock.unlock();
            }
        }

        GetResponse pollFirst() {
            lock.lock();
            try {
                return entries.pollFirst();
            } finally {
                lock.unlock();
            }
        }

        int purge() {
            lock.lock();
            try {
                int count = entries.size();
                entries.clear();
                return count;
            } finally {
                lock.unlock();
            }
        }

        int size() {
            lock.lock();
            try {
                return entries.size();
            } finally {
                lock.unlock();
            }
        }
    }
}
