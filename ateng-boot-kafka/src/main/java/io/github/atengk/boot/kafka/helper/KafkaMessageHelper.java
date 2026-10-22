package io.github.atengk.boot.kafka.helper;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.core.util.IdUtil;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka 消息与上下文辅助工具类
 *
 * <p>提供入站消息 Header 提取、bizKey 自适应补全回写以及幂等异常因果链递归解包检测等通用辅助能力。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
public final class KafkaMessageHelper {

    private KafkaMessageHelper() {
    }

    /**
     * 提取 ConsumerRecord Headers 映射并在缺失 bizKey 时自适应补全回写至 record.headers()
     *
     * @param record Kafka 消费记录
     * @param <K>    Key 泛型
     * @param <V>    Value 泛型
     * @return 包含所有 Header 键值的 Map
     */
    public static <K, V> Map<String, Object> extractAndEnsureHeaders(ConsumerRecord<K, V> record) {
        Map<String, Object> headerMap = new HashMap<>();
        if (record == null) {
            return headerMap;
        }

        if (record.headers() != null) {
            for (Header header : record.headers()) {
                if (header != null && header.key() != null) {
                    headerMap.put(header.key(), header.value());
                }
            }
        }

        // 提取并补齐 bizKey
        String bizKey = MessageContextPropagator.extractString(headerMap, MessageHeaderConstants.BIZ_KEY);
        if (bizKey == null || bizKey.isBlank()) {
            if (record.key() != null) {
                bizKey = String.valueOf(record.key());
            } else {
                String traceId = MessageContextPropagator.extractString(headerMap, MessageHeaderConstants.TRACE_ID);
                bizKey = (traceId != null && !traceId.isBlank()) ? traceId : IdUtil.fastSimpleUUID();
            }
            if (record.headers() != null) {
                record.headers().add(new RecordHeader(MessageHeaderConstants.BIZ_KEY, bizKey.getBytes(StandardCharsets.UTF_8)));
            }
            headerMap.put(MessageHeaderConstants.BIZ_KEY, bizKey);
        }

        return headerMap;
    }

    /**
     * 递归遍历异常因果链，判断是否包含 {@link IdempotentException} 幂等冲突异常
     *
     * @param throwable 待检测异常
     * @return 若因果链中包含幂等异常返回 true，否则返回 false
     */
    public static boolean isIdempotentException(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 30) {
            if (current instanceof IdempotentException) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }
}
