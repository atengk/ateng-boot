package io.github.atengk.boot.redis.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 生产级 Redis 5.0+ Stream 流式消息操作门面
 * 提供流消息投递、消费组生命周期管控、确认应答 (XACK)、死信流转及流维护运维能力
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class RedisStreamTemplate {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamTemplate.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final RedisProperties redisProperties;

    public RedisStreamTemplate(RedisTemplate<String, Object> redisTemplate) {
        this(redisTemplate, null, null);
    }

    public RedisStreamTemplate(RedisTemplate<String, Object> redisTemplate,
                               ObjectMapper objectMapper,
                               RedisProperties redisProperties) {
        Assert.notNull(redisTemplate, "redisTemplate must not be null");
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : RedisSerializerFactory.createSafeObjectMapper();
        this.redisProperties = redisProperties;
    }

    /**
     * 向指定 Stream 发送业务载荷消息
     *
     * @param stream Stream 键名
     * @param payload 业务载荷对象（支持 Map 或任意 POJO）
     * @return 自动生成的 RecordId
     */
    public RecordId send(String stream, Object payload) {
        Assert.hasText(stream, "stream must not be blank");
        Assert.notNull(payload, "payload must not be null");

        String fullStreamKey = formatStreamKey(stream);
        Map<String, Object> content = new HashMap<>();

        if (payload instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (k != null && v != null) {
                    content.put(String.valueOf(k), v);
                }
            });
        } else {
            content.put("payload", payload);
        }

        String traceId = MDC.get("traceId");
        if (StringUtils.hasText(traceId)) {
            content.put("traceId", traceId);
        }
        content.put("timestamp", System.currentTimeMillis());

        try {
            RecordId recordId = redisTemplate.opsForStream().add(fullStreamKey, content);
            log.debug("[RedisStream] 成功投递消息至流: stream={}, recordId={}", fullStreamKey, recordId);
            return recordId;
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "投递 Redis Stream 消息底层异常: stream=" + fullStreamKey + ", error=" + ex.getMessage(), ex);
        }
    }

    /**
     * 向指定 Stream 发送 Map 结构体消息
     *
     * @param stream Stream 键名
     * @param content Map 载荷
     * @return 自动生成的 RecordId
     */
    public RecordId send(String stream, Map<String, Object> content) {
        return send(stream, (Object) content);
    }

    /**
     * 发送原生 Spring Data Redis Record
     *
     * @param record 原生 Record
     * @return 自动生成的 RecordId
     */
    public RecordId send(Record<String, ?> record) {
        Assert.notNull(record, "record must not be null");
        try {
            return redisTemplate.opsForStream().add(record);
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "投递 Redis Stream 原生 Record 底层异常: stream=" + record.getStream() + ", error=" + ex.getMessage(), ex);
        }
    }

    /**
     * 幂等自愈创建消费组（默认 MKSTREAM 自动创建流，从 0 位点起始）
     *
     * @param stream Stream 键名
     * @param group 消费组名称
     */
    public void createGroup(String stream, String group) {
        createGroup(stream, group, ReadOffset.from("0"), true);
    }

    /**
     * 全参数创建消费组
     *
     * @param stream Stream 键名
     * @param group 消费组名称
     * @param readOffset 起始位点
     * @param mkStream 是否在流不存在时自动创建流 (MKSTREAM)
     */
    public void createGroup(String stream, String group, ReadOffset readOffset, boolean mkStream) {
        Assert.hasText(stream, "stream must not be blank");
        Assert.hasText(group, "group must not be blank");

        String fullStreamKey = formatStreamKey(stream);
        try {
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                Assert.state(connection != null, "Redis connection must not be null");
                Assert.state(connection.streamCommands() != null, "Redis streamCommands must not be null");
                RedisSerializer<String> stringSerializer = redisTemplate.getStringSerializer();
                byte[] rawKey = stringSerializer != null
                        ? stringSerializer.serialize(fullStreamKey)
                        : fullStreamKey.getBytes(StandardCharsets.UTF_8);
                connection.streamCommands().xGroupCreate(
                        rawKey,
                        group,
                        readOffset != null ? readOffset : ReadOffset.from("0"),
                        mkStream
                );
                return null;
            });
            log.info("[RedisStream] 成功创建消费组: stream={}, group={}, mkStream={}", fullStreamKey, group, mkStream);
        } catch (Exception ex) {
            String message = ex.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                log.debug("[RedisStream] 消费组已存在，跳过自愈创建: stream={}, group={}", fullStreamKey, group);
            } else {
                throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                        "创建 Redis Stream 消费组失败: stream=" + fullStreamKey + ", group=" + group, ex);
            }
        }
    }

    /**
     * 销毁指定消费组
     *
     * @param stream Stream 键名
     * @param group 消费组名称
     * @return 是否成功销毁
     */
    public Boolean destroyGroup(String stream, String group) {
        Assert.hasText(stream, "stream must not be blank");
        Assert.hasText(group, "group must not be blank");

        String fullStreamKey = formatStreamKey(stream);
        try {
            return redisTemplate.execute((RedisCallback<Boolean>) connection -> {
                RedisSerializer<String> stringSerializer = redisTemplate.getStringSerializer();
                byte[] rawKey = stringSerializer != null
                        ? stringSerializer.serialize(fullStreamKey)
                        : fullStreamKey.getBytes(StandardCharsets.UTF_8);
                return connection.streamCommands().xGroupDestroy(rawKey, group);
            });
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "销毁 Redis Stream 消费组失败: stream=" + fullStreamKey + ", group=" + group, ex);
        }
    }

    /**
     * 确认应答消息 (XACK)
     *
     * @param stream Stream 键名
     * @param group 消费组名称
     * @param recordIds 待确认的消息 RecordId 列表
     * @return 成功确认的消息条数
     */
    public Long acknowledge(String stream, String group, RecordId... recordIds) {
        Assert.hasText(stream, "stream must not be blank");
        Assert.hasText(group, "group must not be blank");
        Assert.notEmpty(recordIds, "recordIds must not be empty");

        String fullStreamKey = formatStreamKey(stream);
        try {
            Long count = redisTemplate.opsForStream().acknowledge(fullStreamKey, group, recordIds);
            log.debug("[RedisStream] 消息确认完成 (XACK): stream={}, group={}, count={}", fullStreamKey, group, count);
            return count;
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "确认 Redis Stream 消息失败: stream=" + fullStreamKey + ", group=" + group, ex);
        }
    }

    /**
     * 基于字符串 ID 确认应答消息 (XACK)
     *
     * @param stream Stream 键名
     * @param group 消费组名称
     * @param recordIds 待确认的消息 ID 字符串列表
     * @return 成功确认的消息条数
     */
    public Long acknowledge(String stream, String group, String... recordIds) {
        Assert.notEmpty(recordIds, "recordIds must not be empty");
        RecordId[] ids = Arrays.stream(recordIds).map(RecordId::of).toArray(RecordId[]::new);
        return acknowledge(stream, group, ids);
    }

    /**
     * 查询指定消费组的 Pending 积压消息概要统计 (XPENDING key group)
     *
     * @param stream Stream 键名
     * @param group 消费组名称
     * @return Pending 消息摘要统计对象
     */
    public PendingMessagesSummary pendingSummary(String stream, String group) {
        Assert.hasText(stream, "stream must not be blank");
        Assert.hasText(group, "group must not be blank");
        String fullStreamKey = formatStreamKey(stream);
        try {
            return redisTemplate.opsForStream().pending(fullStreamKey, group);
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "查询 Redis Stream Pending 积压概要失败: stream=" + fullStreamKey + ", group=" + group, ex);
        }
    }

    /**
     * 分页范围查询指定消费组的 Pending 积压消息详细清单 (XPENDING key group [ID-start ID-end count])
     *
     * @param stream Stream 键名
     * @param group 消费组名称
     * @param range 范围区间 (例如 Range.unbounded())
     * @param count 期望拉取数量上限
     * @return 详细 PendingMessages 消息集
     */
    public PendingMessages pending(String stream, String group, Range<?> range, long count) {
        Assert.hasText(stream, "stream must not be blank");
        Assert.hasText(group, "group must not be blank");
        Assert.isTrue(count > 0, "count must be greater than 0");
        String fullStreamKey = formatStreamKey(stream);
        try {
            Range<?> safeRange = range != null ? range : Range.unbounded();
            return redisTemplate.opsForStream().pending(fullStreamKey, group, safeRange, count);
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "查询 Redis Stream Pending 积压明细失败: stream=" + fullStreamKey + ", group=" + group, ex);
        }
    }

    /**
     * 孤儿消息认领转移 (XCLAIM)
     * 将其他消费者因宕机等原因闲置超时未确认的孤儿消息，强制转移给新的消费者处理
     *
     * @param stream Stream 键名
     * @param group 消费组名称
     * @param newConsumer 新接管消费者名称
     * @param minIdleTime 最小闲置超时时长
     * @param recordIds 待认领的消息 ID 列表
     * @return 成功认领的消息列表
     */
    public List<MapRecord<String, Object, Object>> claim(String stream, String group, String newConsumer,
                                                         Duration minIdleTime, RecordId... recordIds) {
        Assert.hasText(stream, "stream must not be blank");
        Assert.hasText(group, "group must not be blank");
        Assert.hasText(newConsumer, "newConsumer must not be blank");
        Assert.notNull(minIdleTime, "minIdleTime must not be null");
        Assert.notEmpty(recordIds, "recordIds must not be empty");
        String fullStreamKey = formatStreamKey(stream);
        try {
            return redisTemplate.opsForStream().claim(fullStreamKey, group, newConsumer, minIdleTime, recordIds);
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "认领 Redis Stream 孤儿消息失败: stream=" + fullStreamKey + ", group=" + group + ", newConsumer=" + newConsumer, ex);
        }
    }

    /**
     * 从 Stream 中物理删除一条或多条消息
     *
     * @param stream Stream 键名
     * @param recordIds 待删除的消息 RecordId 列表
     * @return 删除条数
     */
    public Long delete(String stream, RecordId... recordIds) {
        Assert.hasText(stream, "stream must not be blank");
        Assert.notEmpty(recordIds, "recordIds must not be empty");

        String fullStreamKey = formatStreamKey(stream);
        try {
            return redisTemplate.opsForStream().delete(fullStreamKey, recordIds);
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "删除 Redis Stream 消息失败: stream=" + fullStreamKey, ex);
        }
    }

    /**
     * 基于字符串 ID 从 Stream 中删除一条或多条消息
     *
     * @param stream Stream 键名
     * @param recordIds 待删除的消息 ID 字符串
     * @return 删除条数
     */
    public Long delete(String stream, String... recordIds) {
        Assert.notEmpty(recordIds, "recordIds must not be empty");
        RecordId[] ids = Arrays.stream(recordIds).map(RecordId::of).toArray(RecordId[]::new);
        return delete(stream, ids);
    }

    /**
     * 精确截断修剪 Stream 长度 (XTRIM)
     *
     * @param stream Stream 键名
     * @param count 保留的最大消息条数
     * @return 被修剪移出的消息条数
     */
    public Long trim(String stream, long count) {
        return trim(stream, count, false);
    }

    /**
     * 截断修剪 Stream 长度 (XTRIM)
     *
     * @param stream Stream 键名
     * @param count 保留的最大消息条数
     * @param approximateTrimming 是否采用近似修剪 (~) 以优化性能
     * @return 被修剪移出的消息条数
     */
    public Long trim(String stream, long count, boolean approximateTrimming) {
        Assert.hasText(stream, "stream must not be blank");
        String fullStreamKey = formatStreamKey(stream);
        try {
            return redisTemplate.opsForStream().trim(fullStreamKey, count, approximateTrimming);
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "修剪 Redis Stream 失败: stream=" + fullStreamKey, ex);
        }
    }

    /**
     * 获取指定 Stream 当前消息总条数 (XLEN)
     *
     * @param stream Stream 键名
     * @return 消息总条数
     */
    public Long size(String stream) {
        Assert.hasText(stream, "stream must not be blank");
        String fullStreamKey = formatStreamKey(stream);
        try {
            return redisTemplate.opsForStream().size(fullStreamKey);
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "获取 Redis Stream 长度失败: stream=" + fullStreamKey, ex);
        }
    }

    /**
     * 消费组拉取消息 (XREADGROUP)
     *
     * @param consumer 消费者标识
     * @param readOptions 拉取选项（count, block 等）
     * @param streamOffset 流偏移量配置
     * @return 拉取到的 MapRecord 列表
     */
    @SuppressWarnings("unchecked")
    public List<MapRecord<String, Object, Object>> read(Consumer consumer,
                                                        StreamReadOptions readOptions,
                                                        StreamOffset<String> streamOffset) {
        Assert.notNull(consumer, "consumer must not be null");
        Assert.notNull(readOptions, "readOptions must not be null");
        Assert.notNull(streamOffset, "streamOffset must not be null");

        try {
            List records = redisTemplate.opsForStream()
                    .read(consumer, readOptions, streamOffset);
            return records != null ? (List<MapRecord<String, Object, Object>>) records : new ArrayList<>();
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "消费拉取 Redis Stream 异常: stream=" + streamOffset.getKey() + ", error=" + ex.getMessage(), ex);
        }
    }

    /**
     * 将消费失败的毒丸消息转移至死信流 ({stream}:dlq)，并从原始消费组中执行 XACK 释放
     *
     * @param stream 原始 Stream 键名
     * @param group 消费组名称
     * @param consumer 消费者名称
     * @param record 原始消息 Record
     * @param retryCount 重试尝试次数
     * @param lastException 最终异常
     * @return 死信消息生成的 RecordId
     */
    public RecordId transferToDeadLetter(String stream,
                                         String group,
                                         String consumer,
                                         MapRecord<String, ?, ?> record,
                                         int retryCount,
                                         Throwable lastException) {
        Assert.hasText(stream, "stream must not be blank");
        Assert.notNull(record, "record must not be null");

        String dlqSuffix = (redisProperties != null && redisProperties.getStream() != null)
                ? redisProperties.getStream().getDlqSuffix()
                : ":dlq";
        String dlqStream = stream + dlqSuffix;

        Map<String, Object> dlqContent = new HashMap<>();
        dlqContent.put("stream", stream);
        dlqContent.put("group", group);
        dlqContent.put("consumer", consumer);
        dlqContent.put("recordId", record.getId().getValue());
        dlqContent.put("payload", record.getValue());
        dlqContent.put("retryCount", retryCount);
        dlqContent.put("failedTime", LocalDateTime.now().toString());

        if (lastException != null) {
            dlqContent.put("exceptionClass", lastException.getClass().getName());
            dlqContent.put("exceptionMessage", lastException.getMessage());
        }

        // 1. 写入死信队列
        RecordId dlqId = send(dlqStream, dlqContent);

        // 2. 确认原消息以从原 PEL 列表中安全移出
        if (StringUtils.hasText(group)) {
            try {
                acknowledge(stream, group, record.getId());
            } catch (Exception ex) {
                log.warn("[RedisStream] 死信转移后确认原消息失败: stream={}, group={}, id={}",
                        stream, group, record.getId(), ex);
            }
        }

        return dlqId;
    }

    /**
     * 格式化生成包含全局 KeyPrefix 的完整 Stream Key
     *
     * @param stream 业务 Stream 键名
     * @return 完整 Redis Key
     */
    public String formatStreamKey(String stream) {
        Assert.hasText(stream, "stream must not be blank");
        String prefix = redisProperties != null ? redisProperties.getKeyPrefix() : null;
        if (StringUtils.hasText(prefix)) {
            String trimmedPrefix = prefix.trim();
            if (!trimmedPrefix.endsWith(":")) {
                trimmedPrefix += ":";
            }
            if (!stream.startsWith(trimmedPrefix)) {
                return trimmedPrefix + stream;
            }
        }
        return stream;
    }

    public RedisTemplate<String, Object> getRedisTemplate() {
        return redisTemplate;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public RedisProperties getRedisProperties() {
        return redisProperties;
    }
}
