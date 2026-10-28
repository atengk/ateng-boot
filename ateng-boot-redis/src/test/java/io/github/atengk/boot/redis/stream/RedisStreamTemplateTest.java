package io.github.atengk.boot.redis.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands;
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
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisStreamTemplate 单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("RedisStreamTemplate 单元测试")
class RedisStreamTemplateTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
    private final ObjectMapper objectMapper = RedisSerializerFactory.createSafeObjectMapper();

    private RedisProperties properties;
    private RedisStreamTemplate template;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(redisTemplate.getStringSerializer()).thenReturn(new StringRedisSerializer());

        properties = new RedisProperties();
        properties.setKeyPrefix("sample:");
        template = new RedisStreamTemplate(redisTemplate, objectMapper, properties);
    }

    @Test
    @DisplayName("测试发送 POJO 载荷消息，自动添加 traceId 与时间戳并追加到 Stream")
    void testSendObjectPayload() {
        RecordId mockId = RecordId.of("1700000000000-0");
        when(streamOps.add(eq("sample:order-events"), any(Map.class))).thenReturn(mockId);

        MDC.put("traceId", "test-trace-123");
        try {
            RecordId recordId = template.send("order-events", Map.of("orderId", "ORD_100", "amount", 99.9));
            assertThat(recordId).isEqualTo(mockId);
            verify(streamOps).add(eq("sample:order-events"), any(Map.class));
        } finally {
            MDC.remove("traceId");
        }
    }

    @Test
    @DisplayName("测试发送原生 Record 对象")
    void testSendNativeRecord() {
        RecordId mockId = RecordId.of("1700000000001-0");
        Record<String, ?> record = Record.of(Collections.singletonMap("msg", "hello")).withStreamKey("test-stream");
        when(streamOps.add(record)).thenReturn(mockId);

        RecordId result = template.send(record);
        assertThat(result).isEqualTo(mockId);
    }

    @Test
    @DisplayName("测试幂等自愈创建消费组：支持 MKSTREAM 且自愈吸收 BUSYGROUP 错误")
    void testCreateGroupWithBusyGroupIgnored() {
        doAnswer(inv -> {
            RedisCallback<?> cb = inv.getArgument(0);
            RedisConnection conn = mock(RedisConnection.class);
            RedisStreamCommands commands = mock(RedisStreamCommands.class);
            when(conn.streamCommands()).thenReturn(commands);
            when(commands.xGroupCreate(any(), eq("order-group"), any(ReadOffset.class), anyBoolean()))
                    .thenThrow(new InvalidDataAccessApiUsageException("BUSYGROUP Consumer Group name already exists"));
            return cb.doInRedis(conn);
        }).when(redisTemplate).execute(any(RedisCallback.class));

        // BUSYGROUP should be absorbed without throwing exception
        template.createGroup("order-stream", "order-group");
    }

    @Test
    @DisplayName("测试创建消费组非 BUSYGROUP 异常应转译为 BusinessException")
    void testCreateGroupThrowsBusinessExceptionOnFatalError() {
        doAnswer(inv -> {
            RedisCallback<?> cb = inv.getArgument(0);
            RedisConnection conn = mock(RedisConnection.class);
            RedisStreamCommands commands = mock(RedisStreamCommands.class);
            when(conn.streamCommands()).thenReturn(commands);
            when(commands.xGroupCreate(any(), any(), any(), anyBoolean()))
                    .thenThrow(new RuntimeException("Connection refused"));
            return cb.doInRedis(conn);
        }).when(redisTemplate).execute(any(RedisCallback.class));

        assertThatThrownBy(() -> template.createGroup("order-stream", "order-group"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
    }

    @Test
    @DisplayName("测试销毁消费组 (XGROUP DESTROY)")
    void testDestroyGroup() {
        doAnswer(inv -> {
            RedisCallback<?> cb = inv.getArgument(0);
            RedisConnection conn = mock(RedisConnection.class);
            RedisStreamCommands commands = mock(RedisStreamCommands.class);
            when(conn.streamCommands()).thenReturn(commands);
            when(commands.xGroupDestroy(any(), eq("test-group"))).thenReturn(true);
            return cb.doInRedis(conn);
        }).when(redisTemplate).execute(any(RedisCallback.class));

        Boolean destroyed = template.destroyGroup("order-stream", "test-group");
        assertThat(destroyed).isTrue();
    }

    @Test
    @DisplayName("测试确认消息 (XACK) 与根据字符串 ID 确认")
    void testAcknowledge() {
        RecordId id1 = RecordId.of("100-0");
        RecordId id2 = RecordId.of("100-1");
        when(streamOps.acknowledge("sample:event-stream", "event-group", id1, id2)).thenReturn(2L);

        Long count = template.acknowledge("event-stream", "event-group", id1, id2);
        assertThat(count).isEqualTo(2L);

        when(streamOps.acknowledge(eq("sample:event-stream"), eq("event-group"), any(RecordId[].class))).thenReturn(1L);
        Long countStr = template.acknowledge("event-stream", "event-group", "100-0");
        assertThat(countStr).isEqualTo(1L);
    }

    @Test
    @DisplayName("测试消息物理删除 (XDEL)")
    void testDelete() {
        RecordId id = RecordId.of("200-0");
        when(streamOps.delete("sample:del-stream", id)).thenReturn(1L);

        Long deleted = template.delete("del-stream", id);
        assertThat(deleted).isEqualTo(1L);
    }

    @Test
    @DisplayName("测试修剪 (XTRIM) 与长度查询 (XLEN)")
    void testTrimAndSize() {
        when(streamOps.trim("sample:log-stream", 1000L, true)).thenReturn(50L);
        when(streamOps.size("sample:log-stream")).thenReturn(1000L);

        assertThat(template.trim("log-stream", 1000L, true)).isEqualTo(50L);
        assertThat(template.size("log-stream")).isEqualTo(1000L);
    }

    @Test
    @DisplayName("测试死信流转 (transferToDeadLetter)：转存至 {stream}:dlq 并确认原消息")
    void testTransferToDeadLetter() {
        RecordId origId = RecordId.of("500-0");
        RecordId dlqId = RecordId.of("600-0");

        Map<Object, Object> valueMap = new HashMap<>();
        valueMap.put("orderId", "ORD_FAIL");
        MapRecord<String, Object, Object> record = MapRecord.create("sample:task-stream", valueMap).withId(origId);

        when(streamOps.add(eq("sample:task-stream:dlq"), any(Map.class))).thenReturn(dlqId);
        when(streamOps.acknowledge("sample:task-stream", "task-group", origId)).thenReturn(1L);

        RecordId resultId = template.transferToDeadLetter("task-stream", "task-group", "consumer-1",
                record, 3, new IllegalStateException("下游服务崩溃"));

        assertThat(resultId).isEqualTo(dlqId);
        verify(streamOps).add(eq("sample:task-stream:dlq"), any(Map.class));
        verify(streamOps).acknowledge("sample:task-stream", "task-group", origId);
    }

    @Test
    @DisplayName("测试消费拉取 (read) 契约")
    void testReadRecords() {
        Consumer consumer = Consumer.from("grp", "c1");
        StreamReadOptions options = StreamReadOptions.empty().count(5);
        StreamOffset<String> offset = StreamOffset.create("sample:in-stream", ReadOffset.lastConsumed());

        Map<Object, Object> recordMap = new HashMap<>();
        recordMap.put("k", "v");
        MapRecord<String, Object, Object> record = MapRecord.create("sample:in-stream", recordMap)
                .withId(RecordId.of("1-0"));
        when(streamOps.read(consumer, options, offset)).thenReturn(List.of(record));

        List<MapRecord<String, Object, Object>> records = template.read(consumer, options, offset);
        assertThat(records).hasSize(1);
    }

    @Test
    @DisplayName("测试查询 Pending 概要与明细 (XPENDING)")
    void testPendingSummaryAndMessages() {
        PendingMessagesSummary mockSummary = mock(PendingMessagesSummary.class);
        when(streamOps.pending("sample:order-stream", "order-group")).thenReturn(mockSummary);

        PendingMessagesSummary summary = template.pendingSummary("order-stream", "order-group");
        assertThat(summary).isSameAs(mockSummary);

        PendingMessages mockMessages = mock(PendingMessages.class);
        when(streamOps.pending(eq("sample:order-stream"), eq("order-group"), any(Range.class), eq(10L)))
                .thenReturn(mockMessages);

        PendingMessages messages = template.pending("order-stream", "order-group", Range.unbounded(), 10L);
        assertThat(messages).isSameAs(mockMessages);
    }

    @Test
    @DisplayName("测试孤儿消息认领转移 (XCLAIM)")
    void testClaim() {
        RecordId recordId = RecordId.of("100-0");
        Map<Object, Object> valueMap = new HashMap<>();
        valueMap.put("orderId", "ORD_CLAIM");
        MapRecord<String, Object, Object> record = MapRecord.create("sample:task-stream", valueMap).withId(recordId);

        when(streamOps.claim(eq("sample:task-stream"), eq("task-group"), eq("consumer-2"),
                eq(Duration.ofMinutes(1)), eq(new RecordId[]{recordId})))
                .thenReturn(List.of(record));

        List<MapRecord<String, Object, Object>> claimed = template.claim("task-stream", "task-group", "consumer-2",
                Duration.ofMinutes(1), recordId);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).getId()).isEqualTo(recordId);
    }

    @Test
    @DisplayName("测试销毁消费组时序列化器为 null 时的平滑降级与底层异常转译")
    void testDestroyGroupFallbackAndException() {
        when(redisTemplate.getStringSerializer()).thenReturn(null);
        doAnswer(inv -> {
            RedisCallback<?> cb = inv.getArgument(0);
            RedisConnection conn = mock(RedisConnection.class);
            RedisStreamCommands commands = mock(RedisStreamCommands.class);
            when(conn.streamCommands()).thenReturn(commands);
            when(commands.xGroupDestroy(any(), eq("test-group"))).thenReturn(true);
            return cb.doInRedis(conn);
        }).when(redisTemplate).execute(any(RedisCallback.class));

        Boolean destroyed = template.destroyGroup("order-stream", "test-group");
        assertThat(destroyed).isTrue();

        // 异常转译测试
        when(redisTemplate.execute(any(RedisCallback.class))).thenThrow(new RuntimeException("Redis connection lost"));
        assertThatThrownBy(() -> template.destroyGroup("order-stream", "test-group"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
    }

    @Test
    @DisplayName("测试输入参数非空与非空白前置卫语句防卫")
    void testInputValidations() {
        assertThatThrownBy(() -> template.send("", "payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stream must not be blank");

        assertThatThrownBy(() -> template.send("s", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload must not be null");

        assertThatThrownBy(() -> template.createGroup("", "g"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> template.createGroup("s", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
