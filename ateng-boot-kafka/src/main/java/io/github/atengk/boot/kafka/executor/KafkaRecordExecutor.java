package io.github.atengk.boot.kafka.executor;

import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.kafka.constant.KafkaConstants;
import io.github.atengk.boot.kafka.helper.KafkaMessageHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Kafka 批量消息多租户安全隔离与容错执行器
 *
 * <p>攻克 Kafka 批拉取模式下的多租户串门与单条故障阻断整批的业界痛点：
 * <ul>
 *   <li>支持生成批次全局 TraceId 与闭包运行；</li>
 *   <li>提供 {@link #runInContext(ConsumerRecord, Consumer)} 原子闭包，单条消费前激活四维上下文，并在 {@code finally} 中绝对可靠清理现场；</li>
 *   <li>提供 {@link #processBatchSafely(Iterable, BiConsumer, BiConsumer)} 容错遍历执行，单条记录失败回调 {@code failureHandler}，不阻断剩余正常记录处理。</li>
 * </ul>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class KafkaRecordExecutor {

    /**
     * 生成批次全局 TraceId（前缀 "batch-"）
     *
     * @return 批次全局 TraceId
     */
    public static String generateBatchTraceId() {
        return "batch-" + IdUtil.fastSimpleUUID();
    }

    /**
     * 在单条记录的四维上下文中安全闭包式执行逻辑，消费完成后在 finally 中绝对可靠清理现场，彻底杜绝多租户串门
     *
     * @param record   单条消费记录
     * @param consumer 业务消费逻辑
     * @param <K>      Key 泛型
     * @param <V>      Value 泛型
     */
    public static <K, V> void runInContext(ConsumerRecord<K, V> record, Consumer<ConsumerRecord<K, V>> consumer) {
        if (record == null || consumer == null) {
            return;
        }
        Map<String, Object> headerMap = extractHeaderMap(record);
        try (MessageContextPropagator.ContextScope ignored = MessageContextPropagator.activate(headerMap)) {
            consumer.accept(record);
        }
    }

    /**
     * 在单条记录的四维上下文中安全闭包式执行逻辑（带 Value 解构），消费完成后在 finally 中绝对可靠清理现场
     *
     * @param record     单条消费记录
     * @param biConsumer 业务消费逻辑（入参为 record 和 record.value()）
     * @param <K>        Key 泛型
     * @param <V>        Value 泛型
     */
    public static <K, V> void runInContext(ConsumerRecord<K, V> record, BiConsumer<ConsumerRecord<K, V>, V> biConsumer) {
        if (record == null || biConsumer == null) {
            return;
        }
        Map<String, Object> headerMap = extractHeaderMap(record);
        try (MessageContextPropagator.ContextScope ignored = MessageContextPropagator.activate(headerMap)) {
            biConsumer.accept(record, record.value());
        }
    }

    /**
     * 在单条记录的四维上下文中安全闭包式执行带返回值的逻辑
     *
     * @param record   单条消费记录
     * @param function 业务执行逻辑
     * @param <K>      Key 泛型
     * @param <V>      Value 泛型
     * @param <R>      返回值泛型
     * @return 执行结果
     */
    public static <K, V, R> R callInContext(ConsumerRecord<K, V> record, Function<ConsumerRecord<K, V>, R> function) {
        if (record == null || function == null) {
            return null;
        }
        Map<String, Object> headerMap = extractHeaderMap(record);
        try (MessageContextPropagator.ContextScope ignored = MessageContextPropagator.activate(headerMap)) {
            return function.apply(record);
        }
    }

    /**
     * 在单条记录的四维上下文中安全闭包式执行带返回值的逻辑（带 Value 解构）
     *
     * @param record     单条消费记录
     * @param biFunction 业务执行逻辑
     * @param <K>        Key 泛型
     * @param <V>        Value 泛型
     * @param <R>        返回值泛型
     * @return 执行结果
     */
    public static <K, V, R> R callInContext(ConsumerRecord<K, V> record, BiFunction<ConsumerRecord<K, V>, V, R> biFunction) {
        if (record == null || biFunction == null) {
            return null;
        }
        Map<String, Object> headerMap = extractHeaderMap(record);
        try (MessageContextPropagator.ContextScope ignored = MessageContextPropagator.activate(headerMap)) {
            return biFunction.apply(record, record.value());
        }
    }

    /**
     * 批量记录容错安全遍历（带 record 和 value 双入参），单条执行前后自动隔离清理上下文，单条失败回调 failureHandler，不阻断批次其余记录
     *
     * @param records        批量消费记录
     * @param processor      单条业务处理器（入参为 record, value）
     * @param failureHandler 单条失败异常回调（入参为 failedRecord, exception），可为 null
     * @param <K>            Key 泛型
     * @param <V>            Value 泛型
     */
    public static <K, V> void processBatchSafely(Iterable<ConsumerRecord<K, V>> records,
                                                 BiConsumer<ConsumerRecord<K, V>, V> processor,
                                                 BiConsumer<ConsumerRecord<K, V>, Exception> failureHandler) {
        if (records == null || processor == null) {
            return;
        }
        for (ConsumerRecord<K, V> record : records) {
            if (record == null) {
                continue;
            }
            try {
                runInContext(record, processor);
            } catch (Exception ex) {
                handleRecordFailure(record, ex, failureHandler);
            }
        }
    }

    /**
     * 批量记录容错安全遍历（单入参 record），单条执行前后自动隔离清理上下文，单条失败回调 failureHandler，不阻断批次其余记录
     *
     * @param records        批量消费记录
     * @param processor      单条业务处理器（入参为 record）
     * @param failureHandler 单条失败异常回调（入参为 failedRecord, exception），可为 null
     * @param <K>            Key 泛型
     * @param <V>            Value 泛型
     */
    public static <K, V> void processBatchSafely(Iterable<ConsumerRecord<K, V>> records,
                                                 Consumer<ConsumerRecord<K, V>> processor,
                                                 BiConsumer<ConsumerRecord<K, V>, Exception> failureHandler) {
        if (records == null || processor == null) {
            return;
        }
        for (ConsumerRecord<K, V> record : records) {
            if (record == null) {
                continue;
            }
            try {
                runInContext(record, processor);
            } catch (Exception ex) {
                handleRecordFailure(record, ex, failureHandler);
            }
        }
    }

    /**
     * 批量记录容错安全遍历（带 record 和 value 双入参），默认仅记录错误日志，不阻断剩余记录
     *
     * @param records   批量消费记录
     * @param processor 单条业务处理器
     * @param <K>       Key 泛型
     * @param <V>       Value 泛型
     */
    public static <K, V> void processBatchSafely(Iterable<ConsumerRecord<K, V>> records,
                                                 BiConsumer<ConsumerRecord<K, V>, V> processor) {
        processBatchSafely(records, processor, null);
    }

    /**
     * 批量记录容错安全遍历（单入参 record），默认仅记录错误日志，不阻断剩余记录
     *
     * @param records   批量消费记录
     * @param processor 单条业务处理器
     * @param <K>       Key 泛型
     * @param <V>       Value 泛型
     */
    public static <K, V> void processBatchSafely(Iterable<ConsumerRecord<K, V>> records,
                                                 Consumer<ConsumerRecord<K, V>> processor) {
        processBatchSafely(records, processor, null);
    }

    private static <K, V> void handleRecordFailure(ConsumerRecord<K, V> record,
                                                   Exception ex,
                                                   BiConsumer<ConsumerRecord<K, V>, Exception> failureHandler) {
        if (failureHandler != null) {
            try {
                failureHandler.accept(record, ex);
            } catch (Throwable handlerEx) {
                log.error("[MQ-CONSUME-BATCH] failureHandler 执行次生异常: topic={} partition={} offset={} key={}",
                        record.topic(), record.partition(), record.offset(), record.key(), handlerEx);
            }
        } else {
            if (KafkaMessageHelper.isIdempotentException(ex)) {
                log.warn("{} mq=kafka topic={} partition={} offset={} key={} 消息已幂等处理，跳过后续执行",
                        KafkaConstants.LOG_IDEMPOTENT_DUPLICATE_PREFIX,
                        record.topic(), record.partition(), record.offset(), record.key());
            } else {
                log.error("[MQ-CONSUME-BATCH] 批量单条记录处理异常且未指定 failureHandler: topic={} partition={} offset={} key={}",
                        record.topic(), record.partition(), record.offset(), record.key(), ex);
            }
        }
    }

    private static <K, V> Map<String, Object> extractHeaderMap(ConsumerRecord<K, V> record) {
        return KafkaMessageHelper.extractAndEnsureHeaders(record);
    }
}
