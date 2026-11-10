package io.github.atengk.boot.sample.observability.listener;

import io.github.atengk.boot.observability.event.ActuatorAccessDeniedEvent;
import io.github.atengk.boot.observability.event.HealthProbeDegradedEvent;
import io.github.atengk.boot.observability.event.ThreadPoolRejectedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 运维全生命周期领域事件监听器
 * <p>
 * 监听线程池任务拒绝、探针超时降级与未授权端点访问阻断事件，
 * 并维护最近 20 条事件的内存环形缓冲区，供查询接口读取。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Component
@Slf4j
public class ObservabilityAlertEventListener {

    private static final int MAX_BUFFER_SIZE = 20;
    private final ConcurrentLinkedDeque<Map<String, Object>> recentEvents = new ConcurrentLinkedDeque<>();

    @EventListener
    public void onThreadPoolRejected(ThreadPoolRejectedEvent event) {
        log.error("【运维告警】受管线程池饱和拒绝！池名: {}, 累计拒绝数: {}, 活跃线程: {}/{}, 队列堆积: {}",
                event.getPoolName(), event.getCumulativeRejectedCount(), event.getActiveCount(),
                event.getMaxPoolSize(), event.getQueueSize());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "ThreadPoolRejectedEvent");
        payload.put("timestamp", LocalDateTime.now().toString());
        payload.put("poolName", event.getPoolName());
        payload.put("cumulativeRejectedCount", event.getCumulativeRejectedCount());
        payload.put("activeCount", event.getActiveCount());
        payload.put("queueSize", event.getQueueSize());
        addEvent(payload);
    }

    @EventListener
    public void onHealthProbeDegraded(HealthProbeDegradedEvent event) {
        log.warn("【运维告警】组件健康探针降级！探针: {}, 组件: {}, 状态: {}, 耗时: {}ms, 原因: {}",
                event.getProbeName(), event.getComponentName(), event.getStatus(),
                event.getCostMs(), event.getReason());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "HealthProbeDegradedEvent");
        payload.put("timestamp", LocalDateTime.now().toString());
        payload.put("componentName", event.getComponentName());
        payload.put("status", event.getStatus());
        payload.put("costMs", event.getCostMs());
        payload.put("reason", event.getReason());
        addEvent(payload);
    }

    @EventListener
    public void onAccessDenied(ActuatorAccessDeniedEvent event) {
        log.warn("【安全审计】Actuator 敏感端点未授权访问阻断！URI: {}, 来源 IP: {}, 原因: {}",
                event.getUri(), event.getClientIp(), event.getReason());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "ActuatorAccessDeniedEvent");
        payload.put("timestamp", LocalDateTime.now().toString());
        payload.put("uri", event.getUri());
        payload.put("clientIp", event.getClientIp());
        payload.put("reason", event.getReason());
        addEvent(payload);
    }

    private void addEvent(Map<String, Object> event) {
        recentEvents.addFirst(event);
        while (recentEvents.size() > MAX_BUFFER_SIZE) {
            recentEvents.pollLast();
        }
    }

    /**
     * 获取最近记录的运维领域事件列表
     */
    public List<Map<String, Object>> getRecentEvents() {
        return Collections.unmodifiableList(new ArrayList<>(recentEvents));
    }

    /**
     * 清空事件缓冲区（便于测试）
     */
    public void clear() {
        recentEvents.clear();
    }
}
