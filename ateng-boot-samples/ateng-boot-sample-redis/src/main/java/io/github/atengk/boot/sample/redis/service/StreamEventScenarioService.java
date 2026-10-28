package io.github.atengk.boot.sample.redis.service;

import io.github.atengk.boot.redis.stream.RedisStreamTemplate;
import io.github.atengk.boot.redis.stream.annotation.RedisStreamListener;
import io.github.atengk.boot.sample.redis.model.OrderStreamEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * Redis 5.0+ Stream 消息流场景服务
 * 演示可靠消息追加、声明式消费组监听、自动确认 XACK 与状态追踪
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
    private final List<OrderStreamEvent> receivedEvents = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch latch = new CountDownLatch(1);

    /**
     * 发送订单 Stream 事件消息
     *
     * @param event 订单事件
     * @return 消息记录 ID
     */
    public RecordId sendOrderEvent(OrderStreamEvent event) {
        log.info("[StreamEvent] 投递订单流事件: stream={}, event={}", STREAM_ORDER_EVENTS, event);
        return redisStreamTemplate.send(STREAM_ORDER_EVENTS, event);
    }

    /**
     * 声明式监听 Stream 消费组消费
     *
     * @param event 订单事件载荷
     */
    @RedisStreamListener(stream = STREAM_ORDER_EVENTS, group = GROUP_ORDER_PROCESSOR, concurrency = 1, autoAck = true)
    public void onOrderEvent(OrderStreamEvent event) {
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
     * 查询 Stream 队列总长度
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
}
