package io.github.atengk.boot.sample.redis.service;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.stream.RedisStreamTemplate;
import io.github.atengk.boot.redis.stream.RedisStreamUtil;
import io.github.atengk.boot.redis.stream.annotation.RedisStreamListener;
import io.github.atengk.boot.redis.stream.model.StreamDeadLetterMessage;
import io.github.atengk.boot.sample.redis.handler.SampleStreamFailureHandler;
import io.github.atengk.boot.sample.redis.model.OrderStreamEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * Redis 5.0+ Stream 消息流场景服务
 * 演示可靠消息追加、静态门面投递、声明式消费组监听、PEL 待决探查、XCLAIM 认领、XTRIM 裁剪与死信流转
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamEventScenarioService {

    public static final String STREAM_ORDER_EVENTS = "stream:order:events";
    public static final String GROUP_ORDER_PROCESSOR = "group:order:processor";

    private final RedisStreamTemplate redisStreamTemplate;
    private final SampleStreamFailureHandler failureHandler;

    private final List<OrderStreamEvent> receivedEvents = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch latch = new CountDownLatch(1);

    /**
     * 基于受管模板 RedisStreamTemplate 发送订单 Stream 事件消息
     *
     * @param event 订单事件
     * @return 消息记录 ID
     */
    public RecordId sendOrderEvent(OrderStreamEvent event) {
        log.info("[StreamEvent] 模板门面投递订单流事件: stream={}, event={}", STREAM_ORDER_EVENTS, event);
        return redisStreamTemplate.send(STREAM_ORDER_EVENTS, event);
    }

    /**
     * 基于静态工具门面 RedisStreamUtil 发送订单 Stream 事件消息
     *
     * @param event 订单事件
     * @return 消息记录 ID
     */
    public RecordId sendOrderEventStatic(OrderStreamEvent event) {
        log.info("[StreamEvent] 静态门面投递订单流事件: stream={}, event={}", STREAM_ORDER_EVENTS, event);
        return RedisStreamUtil.send(STREAM_ORDER_EVENTS, event);
    }

    /**
     * 投递毒丸 Stream 事件演练任务（触发 3 次重试耗尽并转入死信流）
     *
     * @param orderId 毒丸订单编号（建议携带 POISON 标记）
     * @return 消息记录 ID
     */
    public RecordId sendPoisonOrderEvent(String orderId) {
        OrderStreamEvent event = OrderStreamEvent.builder()
                .orderId(orderId)
                .eventType("POISON_EXHAUSTED_TEST")
                .amount(new BigDecimal("999.99"))
                .timestamp(System.currentTimeMillis())
                .build();
        log.info("[StreamEvent] 投递毒丸订单流事件: stream={}, orderId={}", STREAM_ORDER_EVENTS, orderId);
        return redisStreamTemplate.send(STREAM_ORDER_EVENTS, event);
    }

    /**
     * 声明式监听 Stream 消费组消费
     * 遇到毒丸订单事件时抛出业务异常，触发框架内部重试与死信转移
     *
     * @param event 订单事件载荷
     */
    @RedisStreamListener(stream = STREAM_ORDER_EVENTS, group = GROUP_ORDER_PROCESSOR, concurrency = 1, autoAck = true)
    public void onOrderEvent(OrderStreamEvent event) {
        if (event != null && event.getOrderId() != null && event.getOrderId().contains("POISON")) {
            log.error("[StreamEvent] 监听到毒丸流事件，抛出异常触发重试: orderId={}", event.getOrderId());
            throw new BusinessException("Stream 流事件毒丸订单触发模拟异常: orderId=" + event.getOrderId());
        }
        log.info("[StreamEvent] 监听到消费组流事件: orderId={}, eventType={}, amount={}",
                event.getOrderId(), event.getEventType(), event.getAmount());
        receivedEvents.add(event);
        latch.countDown();
    }

    /**
     * 获取已接收事件列表
     */
    public List<OrderStreamEvent> getReceivedEvents() {
        return receivedEvents;
    }

    /**
     * 获取倒计时门闩
     */
    public CountDownLatch getLatch() {
        return latch;
    }

    /**
     * 重置状态（用于测试隔离）
     */
    public void reset(int expectedCount) {
        receivedEvents.clear();
        latch = new CountDownLatch(expectedCount);
    }

    /**
     * 查询 Stream 队列总长度 (XLEN)
     */
    public Long getStreamSize() {
        return redisStreamTemplate.size(STREAM_ORDER_EVENTS);
    }

    /**
     * 裁剪 Stream 长度 (XTRIM)
     */
    public Long trimStream(long maxLen) {
        return redisStreamTemplate.trim(STREAM_ORDER_EVENTS, maxLen);
    }

    /**
     * 查询消费组待决消息概要统计 (XPENDING summary)
     */
    public PendingMessagesSummary getPendingSummary() {
        return redisStreamTemplate.pendingSummary(STREAM_ORDER_EVENTS, GROUP_ORDER_PROCESSOR);
    }

    /**
     * 查询消费组待决消息详细清单 (XPENDING detail)
     */
    public PendingMessages getPendingMessages(long count) {
        return redisStreamTemplate.pending(STREAM_ORDER_EVENTS, GROUP_ORDER_PROCESSOR, Range.unbounded(), count);
    }

    /**
     * 孤儿消息认领转移 (XCLAIM)
     */
    public List<MapRecord<String, Object, Object>> claimOrphan(String newConsumer, Duration minIdleTime, String... recordIds) {
        RecordId[] ids = Arrays.stream(recordIds).map(RecordId::of).toArray(RecordId[]::new);
        return redisStreamTemplate.claim(STREAM_ORDER_EVENTS, GROUP_ORDER_PROCESSOR, newConsumer, minIdleTime, ids);
    }

    /**
     * 查询死信流消息列表
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getDeadLetterRecords(long count) {
        String dlqStream = STREAM_ORDER_EVENTS + ":dlq";
        try {
            List<MapRecord<String, Object, Object>> records = redisStreamTemplate.getRedisTemplate().opsForStream()
                    .range(dlqStream, Range.unbounded());
            if (records == null || records.isEmpty()) {
                return Collections.emptyList();
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (MapRecord<String, Object, Object> record : records) {
                Map<Object, Object> valueMap = record.getValue();
                Map<String, Object> item = new java.util.HashMap<>();
                item.put("recordId", record.getId().getValue());
                valueMap.forEach((k, v) -> item.put(String.valueOf(k), v));
                result.add(item);
                if (result.size() >= count) {
                    break;
                }
            }
            return result;
        } catch (Exception ex) {
            log.warn("[StreamEvent] 读取死信流异常: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 获取最近死信告警快照
     */
    public List<StreamDeadLetterMessage> getRecentAlerts() {
        return failureHandler.getRecentAlerts();
    }

    /**
     * 清空告警快照
     */
    public void clearAlerts() {
        failureHandler.clearAlerts();
    }
}
