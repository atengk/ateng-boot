package io.github.atengk.boot.redis.stream;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.Record;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 生产级 Redis 5.0+ Stream 消息流全局静态操作门面
 * 静态委托给受管单例 RedisStreamTemplate，提供轻量无侵入调用范式
 *
 * @author Ateng
 * @since 2026-10-27
 */
public final class RedisStreamUtil {

    private static volatile RedisStreamTemplate staticTemplate;

    private RedisStreamUtil() {
    }

    /**
     * 容器初始化绑定受管模板单例
     *
     * @param template 受管 RedisStreamTemplate 实例
     */
    public static void init(RedisStreamTemplate template) {
        Assert.notNull(template, "RedisStreamTemplate must not be null");
        staticTemplate = template;
    }

    private static RedisStreamTemplate getTemplate() {
        RedisStreamTemplate template = staticTemplate;
        if (template == null) {
            throw new IllegalStateException("RedisStreamTemplate尚未初始化，请确保应用已启用 Stream 自动装配");
        }
        return template;
    }

    public static RecordId send(String stream, Object payload) {
        return getTemplate().send(stream, payload);
    }

    public static RecordId send(String stream, Map<String, Object> content) {
        return getTemplate().send(stream, content);
    }

    public static RecordId send(Record<String, ?> record) {
        return getTemplate().send(record);
    }

    public static void createGroup(String stream, String group) {
        getTemplate().createGroup(stream, group);
    }

    public static void createGroup(String stream, String group, ReadOffset readOffset, boolean mkStream) {
        getTemplate().createGroup(stream, group, readOffset, mkStream);
    }

    public static Boolean destroyGroup(String stream, String group) {
        return getTemplate().destroyGroup(stream, group);
    }

    public static Long acknowledge(String stream, String group, RecordId... recordIds) {
        return getTemplate().acknowledge(stream, group, recordIds);
    }

    public static Long acknowledge(String stream, String group, String... recordIds) {
        return getTemplate().acknowledge(stream, group, recordIds);
    }

    public static List<MapRecord<String, Object, Object>> read(Consumer consumer,
                                                               StreamReadOptions readOptions,
                                                               StreamOffset<String> streamOffset) {
        return getTemplate().read(consumer, readOptions, streamOffset);
    }

    public static PendingMessagesSummary pendingSummary(String stream, String group) {
        return getTemplate().pendingSummary(stream, group);
    }

    public static PendingMessages pending(String stream, String group, Range<?> range, long count) {
        return getTemplate().pending(stream, group, range, count);
    }

    public static List<MapRecord<String, Object, Object>> claim(String stream, String group, String newConsumer,
                                                                Duration minIdleTime, RecordId... recordIds) {
        return getTemplate().claim(stream, group, newConsumer, minIdleTime, recordIds);
    }

    public static Long trim(String stream, long maxLen) {
        return getTemplate().trim(stream, maxLen);
    }

    public static Long trim(String stream, long maxLen, boolean approximate) {
        return getTemplate().trim(stream, maxLen, approximate);
    }

    public static Long size(String stream) {
        return getTemplate().size(stream);
    }

    public static Long delete(String stream, RecordId... recordIds) {
        return getTemplate().delete(stream, recordIds);
    }

    public static Long delete(String stream, String... recordIds) {
        return getTemplate().delete(stream, recordIds);
    }

    public static RecordId transferToDeadLetter(String stream, String group, String consumer,
                                                MapRecord<String, ?, ?> record, int retryCount, Throwable lastException) {
        return getTemplate().transferToDeadLetter(stream, group, consumer, record, retryCount, lastException);
    }

    public static String formatStreamKey(String stream) {
        return getTemplate().formatStreamKey(stream);
    }
}
