package io.github.atengk.boot.kafka.helper;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Kafka 死信消息元数据只读诊断工具
 *
 * <p>负责从 Kafka 消息头中安全提取并解析由 Spring Kafka {@link org.springframework.kafka.listener.DeadLetterPublishingRecoverer}
 * 写入的 7 类 DLT 诊断标头，转换为强类型且人类可读的结构化记录 {@link DeadLetterRecord}。
 * 宽容兼容 ByteBuffer 二进制（4/8 字节）与 UTF-8 String 异构形式，杜绝转换崩溃。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public final class KafkaDeadLetterHelper {

    /**
     * 重放标记消息头
     */
    public static final String HEADER_X_DEAD_LETTER_REPLAYED = "X-Dead-Letter-Replayed";

    /**
     * 重放时间戳消息头
     */
    public static final String HEADER_X_REPLAY_TIME = "X-Replay-Time";

    /**
     * 重放计数消息头
     */
    public static final String HEADER_X_REPLAY_COUNT = "X-Replay-Count";

    /**
     * 原始死信主题消息头
     */
    public static final String HEADER_X_ORIGINAL_DLT_TOPIC = "X-Original-Dead-Letter-Topic";

    private KafkaDeadLetterHelper() {
    }

    /**
     * 从消费记录中解析并组装完整的死信结构化诊断记录
     *
     * @param record Kafka 消费记录
     * @return 结构化死信记录，record 为 null 时返回 null
     */
    public static DeadLetterRecord extractDeadLetterRecord(ConsumerRecord<?, ?> record) {
        if (record == null) {
            return null;
        }
        return extractDeadLetterRecord(record.headers());
    }

    /**
     * 从消费记录中解析死信结构化记录（便捷别名）
     */
    public static DeadLetterRecord parse(ConsumerRecord<?, ?> record) {
        return extractDeadLetterRecord(record);
    }

    /**
     * 从消息头集合中解析死信结构化记录（便捷别名）
     */
    public static DeadLetterRecord parse(Headers headers) {
        return extractDeadLetterRecord(headers);
    }

    /**
     * 从消息头集合中解析并组装完整的死信结构化诊断记录
     *
     * @param headers Kafka 消息头集合
     * @return 结构化死信记录，headers 为 null 时返回 null
     */
    public static DeadLetterRecord extractDeadLetterRecord(Headers headers) {
        if (headers == null) {
            return null;
        }

        String originalTopic = getOriginalTopic(headers);
        Integer originalPartition = getOriginalPartition(headers);
        Long originalOffset = getOriginalOffset(headers);
        Long originalTimestamp = getOriginalTimestamp(headers);
        String exceptionClassName = getExceptionClassName(headers);
        String exceptionMessage = getExceptionMessage(headers);
        String exceptionStackTrace = getExceptionStackTrace(headers);
        int replayCount = getReplayCount(headers);
        boolean replayed = isReplayed(headers);

        return new DeadLetterRecord(
                originalTopic,
                originalPartition,
                originalOffset,
                originalTimestamp,
                exceptionClassName,
                exceptionMessage,
                exceptionStackTrace,
                replayCount,
                replayed
        );
    }

    /**
     * 获取死信原始主题
     */
    public static String getOriginalTopic(ConsumerRecord<?, ?> record) {
        return record != null ? getOriginalTopic(record.headers()) : null;
    }

    /**
     * 获取死信原始主题
     */
    public static String getOriginalTopic(Headers headers) {
        return asString(getHeaderValue(headers, KafkaHeaders.DLT_ORIGINAL_TOPIC));
    }

    /**
     * 获取死信原始分区号
     */
    public static Integer getOriginalPartition(ConsumerRecord<?, ?> record) {
        return record != null ? getOriginalPartition(record.headers()) : null;
    }

    /**
     * 获取死信原始分区号
     */
    public static Integer getOriginalPartition(Headers headers) {
        return asInteger(getHeaderValue(headers, KafkaHeaders.DLT_ORIGINAL_PARTITION));
    }

    /**
     * 获取死信原始位移
     */
    public static Long getOriginalOffset(ConsumerRecord<?, ?> record) {
        return record != null ? getOriginalOffset(record.headers()) : null;
    }

    /**
     * 获取死信原始位移
     */
    public static Long getOriginalOffset(Headers headers) {
        return asLong(getHeaderValue(headers, KafkaHeaders.DLT_ORIGINAL_OFFSET));
    }

    /**
     * 获取死信原始时间戳
     */
    public static Long getOriginalTimestamp(ConsumerRecord<?, ?> record) {
        return record != null ? getOriginalTimestamp(record.headers()) : null;
    }

    /**
     * 获取死信原始时间戳
     */
    public static Long getOriginalTimestamp(Headers headers) {
        return asLong(getHeaderValue(headers, KafkaHeaders.DLT_ORIGINAL_TIMESTAMP));
    }

    /**
     * 获取触发死信的异常全限定类名
     */
    public static String getExceptionClassName(ConsumerRecord<?, ?> record) {
        return record != null ? getExceptionClassName(record.headers()) : null;
    }

    /**
     * 获取触发死信的异常全限定类名
     */
    public static String getExceptionClassName(Headers headers) {
        return asString(getHeaderValue(headers, KafkaHeaders.DLT_EXCEPTION_FQCN));
    }

    /**
     * 获取触发死信的异常简要消息
     */
    public static String getExceptionMessage(ConsumerRecord<?, ?> record) {
        return record != null ? getExceptionMessage(record.headers()) : null;
    }

    /**
     * 获取触发死信的异常简要消息
     */
    public static String getExceptionMessage(Headers headers) {
        return asString(getHeaderValue(headers, KafkaHeaders.DLT_EXCEPTION_MESSAGE));
    }

    /**
     * 获取触发死信的异常完整调用堆栈
     */
    public static String getExceptionStackTrace(ConsumerRecord<?, ?> record) {
        return record != null ? getExceptionStackTrace(record.headers()) : null;
    }

    /**
     * 获取触发死信的异常完整调用堆栈
     */
    public static String getExceptionStackTrace(Headers headers) {
        return asString(getHeaderValue(headers, KafkaHeaders.DLT_EXCEPTION_STACKTRACE));
    }

    /**
     * 获取消息当前已重放次数计数（未重放过返回 0）
     */
    public static int getReplayCount(ConsumerRecord<?, ?> record) {
        return record != null ? getReplayCount(record.headers()) : 0;
    }

    /**
     * 获取消息当前已重放次数计数（未重放过返回 0）
     */
    public static int getReplayCount(Headers headers) {
        Integer count = asInteger(getHeaderValue(headers, HEADER_X_REPLAY_COUNT));
        return count != null ? count : 0;
    }

    /**
     * 判断消息是否是由死信队列重放重新入队的消息
     */
    public static boolean isReplayed(ConsumerRecord<?, ?> record) {
        return record != null && isReplayed(record.headers());
    }

    /**
     * 判断消息是否是由死信队列重放重新入队的消息
     */
    public static boolean isReplayed(Headers headers) {
        String val = asString(getHeaderValue(headers, HEADER_X_DEAD_LETTER_REPLAYED));
        return "true".equalsIgnoreCase(val);
    }

    // =========================================================================
    // 内部通用类型安全提取与转译方法
    // =========================================================================

    private static byte[] getHeaderValue(Headers headers, String key) {
        if (headers == null || key == null) {
            return null;
        }
        Header header = headers.lastHeader(key);
        return header != null ? header.value() : null;
    }

    private static String asString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Integer asInteger(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        if (bytes.length == Integer.BYTES) {
            return ByteBuffer.wrap(bytes).getInt();
        }
        try {
            return Integer.parseInt(new String(bytes, StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long asLong(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        if (bytes.length == Long.BYTES) {
            return ByteBuffer.wrap(bytes).getLong();
        }
        try {
            return Long.parseLong(new String(bytes, StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 结构化死信元数据强类型记录模型
     *
     * @param originalTopic       死信原始业务主题
     * @param originalPartition   死信原始分区编号
     * @param originalOffset      死信原始位移
     * @param originalTimestamp   死信原始时间戳
     * @param exceptionClassName  导致死信的异常全限定类名
     * @param exceptionMessage    导致死信的异常摘要
     * @param exceptionStackTrace 导致死信的完整调用堆栈
     * @param replayCount         已重放次数
     * @param isReplayed          是否为重放消息
     */
    public record DeadLetterRecord(
            String originalTopic,
            Integer originalPartition,
            Long originalOffset,
            Long originalTimestamp,
            String exceptionClassName,
            String exceptionMessage,
            String exceptionStackTrace,
            int replayCount,
            boolean isReplayed
    ) {
    }
}
