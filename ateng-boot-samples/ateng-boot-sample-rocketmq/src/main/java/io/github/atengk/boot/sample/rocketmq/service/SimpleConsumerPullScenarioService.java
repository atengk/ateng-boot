package io.github.atengk.boot.sample.rocketmq.service;

import io.github.atengk.boot.rocketmq.template.RocketmqSimpleConsumerTemplate;
import io.github.atengk.boot.sample.rocketmq.dto.SimpleMessageVO;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 消费端 SimpleConsumer 流式拉取与租约控制场景业务服务
 *
 * <p>演示微批长轮询流式拉取、隐身不可见租约续期、显式 ACK 确认以及高阶函数式消费闭包。
 * 内部维护有界底层 {@link MessageView} 短期句柄缓存，打通跨无状态 HTTP 请求的微批拉取与租约控制。</p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimpleConsumerPullScenarioService {

    private final RocketmqSimpleConsumerTemplate simpleConsumerTemplate;

    private static final int MAX_CACHED_HANDLES = 1000;
    private final Map<String, CachedMessageHandle> cachedHandles = new ConcurrentHashMap<>();

    @Getter
    private final List<OrderMessage> consumedOrders = new CopyOnWriteArrayList<>();

    public record CachedMessageHandle(
            String messageId,
            String group,
            String topic,
            MessageView messageView,
            long cachedTimestamp) {}

    public List<MessageView> pullMessages(String topic, String group, int maxNum, Duration invisibleDuration) {
        return pullMessages(topic, group, "*", maxNum, invisibleDuration);
    }

    public List<MessageView> pullMessages(String topic, String group, String tag, int maxNum, Duration invisibleDuration) {
        log.info("[SIMPLE-PULL-SCENARIO] 流式批量拉取消息: topic={}, group={}, tag={}, maxNum={}, invisibleDuration={}",
                topic, group, tag, maxNum, invisibleDuration);
        List<MessageView> messages = simpleConsumerTemplate.receive(topic, group, tag, maxNum, invisibleDuration);
        if (messages != null && !messages.isEmpty()) {
            for (MessageView msg : messages) {
                cacheMessage(group, msg);
            }
        }
        return messages != null ? messages : Collections.emptyList();
    }

    public void renewLease(String group, MessageView messageView, Duration newDuration) {
        log.info("[SIMPLE-PULL-SCENARIO] 动态续期不可见租约: group={}, messageId={}, newDuration={}",
                group, messageView.getMessageId(), newDuration);
        simpleConsumerTemplate.changeInvisibleDuration(group, messageView, newDuration);
    }

    public void renewLease(String group, String messageId, Duration newDuration) {
        CachedMessageHandle handle = cachedHandles.get(messageId);
        if (handle == null) {
            throw new IllegalArgumentException("未找到消息句柄或不可见租约已失效: " + messageId);
        }
        String targetGroup = StringUtils.hasText(group) ? group : handle.group();
        renewLease(targetGroup, handle.messageView(), newDuration);
    }

    public void acknowledge(String group, MessageView messageView) {
        log.info("[SIMPLE-PULL-SCENARIO] 显式确认已消费消息: group={}, messageId={}", group, messageView.getMessageId());
        simpleConsumerTemplate.ack(group, messageView);
        if (messageView.getMessageId() != null) {
            removeCachedHandle(messageView.getMessageId().toString());
        }
    }

    public void acknowledge(String group, String messageId) {
        CachedMessageHandle handle = cachedHandles.get(messageId);
        if (handle == null) {
            throw new IllegalArgumentException("未找到消息句柄或已被确认: " + messageId);
        }
        String targetGroup = StringUtils.hasText(group) ? group : handle.group();
        acknowledge(targetGroup, handle.messageView());
    }

    public <T> int pullAndExecute(String topic, String group, Class<T> payloadClass, Consumer<T> consumer) {
        return pullAndExecute(topic, group, "*", payloadClass, consumer);
    }

    public <T> int pullAndExecute(String topic, String group, String tag, Class<T> payloadClass, Consumer<T> consumer) {
        log.info("[SIMPLE-PULL-SCENARIO] 执行高阶推断反序列化消费闭包: topic={}, group={}, tag={}", topic, group, tag);
        return simpleConsumerTemplate.receiveAndExecute(
                topic,
                group,
                tag,
                10,
                Duration.ofSeconds(30),
                payloadClass,
                (item, view) -> consumer.accept(item));
    }

    public int pullAndExecuteOrders(String topic, String group, String tag, int maxNum, Duration invisibleDuration) {
        log.info("[SIMPLE-PULL-SCENARIO] 执行订单闭包消费并自动确认: topic={}, group={}, tag={}, maxNum={}",
                topic, group, tag, maxNum);
        return simpleConsumerTemplate.receiveAndExecute(
                topic,
                group,
                tag,
                maxNum,
                invisibleDuration,
                OrderMessage.class,
                (order, view) -> {
                    log.info("[SIMPLE-PULL-SCENARIO] 消费闭包处理订单: orderId={}, amount={}",
                            order.getOrderId(), order.getAmount());
                    consumedOrders.add(order);
                });
    }

    // =========================================================================
    // 句柄缓存治理
    // =========================================================================

    public void cacheMessage(String group, MessageView messageView) {
        if (messageView == null || messageView.getMessageId() == null) {
            return;
        }
        String messageId = messageView.getMessageId().toString();
        if (cachedHandles.size() >= MAX_CACHED_HANDLES) {
            // 简单淘汰最旧的时间戳句柄
            cachedHandles.entrySet().stream()
                    .min(Comparator.comparingLong(e -> e.getValue().cachedTimestamp()))
                    .ifPresent(oldest -> cachedHandles.remove(oldest.getKey()));
        }
        cachedHandles.put(messageId, new CachedMessageHandle(
                messageId,
                group,
                messageView.getTopic(),
                messageView,
                System.currentTimeMillis()
        ));
    }

    public CachedMessageHandle getCachedHandle(String messageId) {
        return cachedHandles.get(messageId);
    }

    public CachedMessageHandle removeCachedHandle(String messageId) {
        return cachedHandles.remove(messageId);
    }

    public List<CachedMessageHandle> getAllCachedHandles() {
        return new ArrayList<>(cachedHandles.values());
    }

    public void clearCache() {
        cachedHandles.clear();
        consumedOrders.clear();
        log.info("[SIMPLE-PULL-SCENARIO] 已重置清理 SimpleConsumer 句柄缓存与闭包消费记录");
    }

    public static SimpleMessageVO convertToVO(MessageView mv) {
        if (mv == null) {
            return null;
        }
        String body = null;
        try {
            ByteBuffer buffer = mv.getBody();
            if (buffer != null) {
                ByteBuffer duplicate = buffer.duplicate();
                byte[] bytes = new byte[duplicate.remaining()];
                duplicate.get(bytes);
                body = new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception ex) {
            log.warn("[SIMPLE-PULL-SCENARIO] 消息体解析为 UTF-8 字符串失败: {}", ex.getMessage());
        }

        return SimpleMessageVO.builder()
                .messageId(mv.getMessageId() != null ? mv.getMessageId().toString() : null)
                .topic(mv.getTopic())
                .tag(mv.getTag().orElse(null))
                .keys(mv.getKeys() != null ? new ArrayList<>(mv.getKeys()) : Collections.emptyList())
                .messageGroup(mv.getMessageGroup().orElse(null))
                .body(body)
                .bornTimestamp(mv.getBornTimestamp())
                .deliveryAttempt(mv.getDeliveryAttempt())
                .build();
    }
}
