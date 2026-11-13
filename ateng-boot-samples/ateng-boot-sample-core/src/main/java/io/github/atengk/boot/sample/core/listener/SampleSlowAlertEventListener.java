package io.github.atengk.boot.sample.core.listener;

import io.github.atengk.boot.core.aspect.SlowAlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 慢调用告警领域事件消费者组件
 * 演示在业务模块中通过 Spring @EventListener 解耦消费 {@link SlowAlertEvent}，实现慢调用遥测收集与告警闭环
 *
 * @author Ateng
 * @since 2026-11-14
 */
@Component
public class SampleSlowAlertEventListener {

    private static final Logger log = LoggerFactory.getLogger(SampleSlowAlertEventListener.class);

    private final AtomicInteger eventCount = new AtomicInteger(0);
    private volatile SlowAlertEvent lastEvent;

    /**
     * 监听并消费方法级慢调用领域事件
     *
     * @param event 慢调用告警领域事件载荷
     */
    @EventListener
    public void onSlowAlertEvent(SlowAlertEvent event) {
        if (event == null) {
            return;
        }
        this.lastEvent = event;
        int count = this.eventCount.incrementAndGet();
        log.info("[SLOW_ALERT_EVENT_CONSUMED] 接收到慢调用领域事件 #{} [method={}#{}, elapsed={}ms, threshold={}ms, traceId={}]",
                count,
                event.getClassName(),
                event.getMethodName(),
                event.getElapsedMs(),
                event.getThresholdMs(),
                event.getTraceId());
    }

    /**
     * 获取累计接收到的慢调用事件数量
     *
     * @return 事件计数
     */
    public int getEventCount() {
        return eventCount.get();
    }

    /**
     * 获取最近一次接收到的慢调用事件载荷
     *
     * @return 最近一次慢调用事件（未接收时为 null）
     */
    public SlowAlertEvent getLastEvent() {
        return lastEvent;
    }

    /**
     * 重置事件计数器与最近事件快照（供测试隔离清理使用）
     */
    public void reset() {
        this.eventCount.set(0);
        this.lastEvent = null;
    }
}
