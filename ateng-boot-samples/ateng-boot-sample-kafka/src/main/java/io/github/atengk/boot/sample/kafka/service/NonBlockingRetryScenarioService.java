package io.github.atengk.boot.sample.kafka.service;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.kafka.constant.KafkaConstants;
import io.github.atengk.boot.kafka.template.KafkaReliableTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 非阻塞阶梯延迟重试场景服务
 *
 * <p>演示 Spring Kafka 官方 {@link RetryableTopic} 非阻塞阶梯延迟主题流转，
 * 慢依赖失败时分流至重试主题，不阻塞主分区，且全链路穿透四维上下文。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NonBlockingRetryScenarioService {

    public static final String TOPIC_RETRY_MAIN = "sample.retry.main.topic";

    private final KafkaReliableTemplate kafkaReliableTemplate;

    private final AtomicInteger attemptCount = new AtomicInteger(0);
    private final List<RetryRecordLog> retryLogs = Collections.synchronizedList(new ArrayList<>());
    private final List<String> dltRecords = Collections.synchronizedList(new ArrayList<>());

    /**
     * 发送待测试阶梯重试的消息
     */
    public void sendRetryableMessage(String key, String payload) {
        kafkaReliableTemplate.sendReliable(TOPIC_RETRY_MAIN, key, payload);
    }

    /**
     * 声明式非阻塞阶梯重试监听方法
     */
    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 100, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_DELAY_VALUE,
            dltStrategy = DltStrategy.FAIL_ON_ERROR
    )
    @KafkaListener(
            topics = TOPIC_RETRY_MAIN,
            groupId = "sample-nonblocking-retry-group",
            containerFactory = KafkaConstants.DEFAULT_CONTAINER_FACTORY
    )
    public void onMessage(ConsumerRecord<String, String> record,
                          @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String receivedTopic) {
        int attempt = attemptCount.incrementAndGet();
        String traceId = MDC.get("traceId");
        String tenantId = TenantContextHolder.getTenantId();
        String userId = UserContextHolder.get();

        log.info("[NON-BLOCKING-RETRY] 收到消息: topic={}, key={}, attempt={}, traceId={}, tenantId={}",
                receivedTopic, record.key(), attempt, traceId, tenantId);

        retryLogs.add(new RetryRecordLog(receivedTopic, record.key(), record.value(), attempt, traceId, tenantId, userId));

        if (record.value() != null && record.value().contains("FAIL")) {
            throw new RuntimeException("模拟下游服务慢故障，触发非阻塞阶梯重试: attempt=" + attempt);
        }
    }

    @DltHandler
    public void onDlt(ConsumerRecord<String, String> record) {
        log.warn("[NON-BLOCKING-RETRY-DLT] 达到最大重试次数，进入死信: key={}, value={}", record.key(), record.value());
        dltRecords.add(record.key() + ":" + record.value());
    }

    public int getAttemptCount() {
        return attemptCount.get();
    }

    public List<RetryRecordLog> getRetryLogs() {
        return retryLogs;
    }

    public List<String> getDltRecords() {
        return dltRecords;
    }

    public void clear() {
        attemptCount.set(0);
        retryLogs.clear();
        dltRecords.clear();
    }

    public record RetryRecordLog(
            String topic,
            String key,
            String payload,
            int attempt,
            String traceId,
            String tenantId,
            String userId
    ) {}
}
