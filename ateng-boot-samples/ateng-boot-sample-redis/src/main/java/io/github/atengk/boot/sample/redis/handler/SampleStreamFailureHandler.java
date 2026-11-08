package io.github.atengk.boot.sample.redis.handler;

import io.github.atengk.boot.redis.stream.model.StreamDeadLetterMessage;
import io.github.atengk.boot.redis.stream.spi.StreamFailureHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 样例工程 Stream 消息流消费失败终态处理器 SPI 实现
 * 维护有界环形缓冲（容量上限 50 条），捕获重试耗尽后的死信快照并提供运维探针查询
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Component
public class SampleStreamFailureHandler implements StreamFailureHandler {

    private static final int MAX_CAPACITY = 50;
    private final Queue<StreamDeadLetterMessage> recentAlerts = new ConcurrentLinkedQueue<>();

    @Override
    public void onFailure(StreamDeadLetterMessage deadLetterMessage) {
        log.warn("[StreamAlert] 捕获 Stream 消费重试耗尽告警: stream={}, group={}, consumer={}, recordId={}, error={}",
                deadLetterMessage.getStream(), deadLetterMessage.getGroup(), deadLetterMessage.getConsumer(),
                deadLetterMessage.getRecordId(), deadLetterMessage.getExceptionMessage());
        while (recentAlerts.size() >= MAX_CAPACITY) {
            recentAlerts.poll();
        }
        recentAlerts.offer(deadLetterMessage);
    }

    /**
     * 获取最近死信告警快照列表
     *
     * @return 告警消息列表
     */
    public List<StreamDeadLetterMessage> getRecentAlerts() {
        return Collections.unmodifiableList(new ArrayList<>(recentAlerts));
    }

    /**
     * 清空告警快照
     */
    public void clearAlerts() {
        recentAlerts.clear();
    }
}
