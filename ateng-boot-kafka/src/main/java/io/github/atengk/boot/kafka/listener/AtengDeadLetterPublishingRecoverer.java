package io.github.atengk.boot.kafka.listener;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.kafka.constant.KafkaConstants;
import io.github.atengk.boot.kafka.helper.KafkaMessageHelper;
import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka 生产级死信发布恢复器
 *
 * <p>继承 Spring Kafka {@link DeadLetterPublishingRecoverer}，提供以下关键治理：
 * <ul>
 *   <li><b>死信终点不变量 (Terminal Invariant)</b>：若当前消息已处于死信 Topic（以 {@code .DLT} 结尾），消费失败仅记录报警，坚决禁止二次转发，彻底封死互咬死循环；</li>
 *   <li><b>幂等静默提交 (Silent Offset Commit)</b>：拦截 {@link IdempotentException}，输出 {@code [MQ-IDEMPOTENT-DUPLICATE]} 警告并静默提交 Offset，阻断重试且不向死信 Topic 投递；</li>
 *   <li><b>死信转发告警</b>：向死信 Topic 投递时保留原始 Headers 并输出 {@code [MQ-DLT-FORWARD]} 高危告警日志。</li>
 * </ul>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class AtengDeadLetterPublishingRecoverer extends DeadLetterPublishingRecoverer {

    private final AtengKafkaProperties properties;

    public AtengDeadLetterPublishingRecoverer(KafkaOperations<?, ?> template, AtengKafkaProperties properties) {
        super(template, (record, exception) -> resolveDestination(record, exception, properties));
        this.properties = properties;
    }

    /**
     * 目标分区解析与防卫判定（基于当前实例配置）
     *
     * @param record 消费记录
     * @param exception 捕获异常
     * @return 目标死信分区或 null（静默提交/终点拦截）
     */
    public TopicPartition resolveDestination(ConsumerRecord<?, ?> record, Exception exception) {
        return resolveDestination(record, exception, this.properties);
    }

    /**
     * 目标分区解析与防卫判定
     */
    public static TopicPartition resolveDestination(ConsumerRecord<?, ?> record, Exception exception, AtengKafkaProperties properties) {
        if (record == null) {
            return null;
        }

        String dltSuffix = (properties != null && properties.getConsumer().getDltSuffix() != null)
                ? properties.getConsumer().getDltSuffix()
                : KafkaConstants.DEFAULT_DLT_SUFFIX;

        Map<String, Object> headerMap = extractHeaders(record);
        String bizKey = MessageContextPropagator.extractString(headerMap, MessageHeaderConstants.BIZ_KEY);
        String traceId = MessageContextPropagator.extractString(headerMap, MessageHeaderConstants.TRACE_ID);
        if (bizKey == null && record.key() != null) {
            bizKey = String.valueOf(record.key());
        }

        // 1. 幂等异常静默提交：不发 DLT，返回 null 由 DefaultErrorHandler 正常提交 Offset
        if (isIdempotentException(exception)) {
            log.warn("{} mq=kafka topic={} partition={} offset={} bizKey={} traceId={} 消息已幂等处理，执行静默提交",
                    KafkaConstants.LOG_IDEMPOTENT_DUPLICATE_PREFIX,
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    bizKey,
                    traceId);
            return null;
        }

        // 2. 守卫 DLT 终点不变量：已在 DLT 的消息发生异常禁止再次转发 DLT
        if (record.topic() != null && record.topic().endsWith(dltSuffix)) {
            log.error("[MQ-DLT-TERMINAL] 死信队列消费失败，守卫终点不变量，不再转发死信: topic={} partition={} offset={} bizKey={} traceId={} error={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    bizKey,
                    traceId,
                    exception != null ? exception.getMessage() : "Unknown error");
            return null;
        }

        // 3. 正常转发至 *.DLT Topic 并输出 [MQ-DLT-FORWARD] 高危告警
        String dltTopic = record.topic() + dltSuffix;
        log.error("{} mq=kafka originTopic={} dltTopic={} partition={} offset={} bizKey={} traceId={} error={}",
                KafkaConstants.LOG_DLT_FORWARD_PREFIX,
                record.topic(),
                dltTopic,
                record.partition(),
                record.offset(),
                bizKey,
                traceId,
                exception != null ? exception.getMessage() : "Unknown error");

        // 分区指定为 -1，由 Kafka Producer 依据 Key Hash 或轮询自动分配分区
        return new TopicPartition(dltTopic, -1);
    }

    private static boolean isIdempotentException(Throwable throwable) {
        return KafkaMessageHelper.isIdempotentException(throwable);
    }

    private static Map<String, Object> extractHeaders(ConsumerRecord<?, ?> record) {
        Map<String, Object> map = new HashMap<>();
        if (record.headers() != null) {
            for (Header header : record.headers()) {
                if (header != null && header.key() != null) {
                    map.put(header.key(), header.value());
                }
            }
        }
        return map;
    }
}
