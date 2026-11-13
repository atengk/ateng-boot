package io.github.atengk.boot.redis.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
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

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.remove("traceId");
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
    @DisplayName("测试发送消息时自动捕获并注入四维上下文元数据")
    @SuppressWarnings("unchecked")
    void testSendWithFourDimensionalContext() {
        RecordId mockId = RecordId.of("1700000000002-0");
        when(streamOps.add(eq("sample:context-stream"), any(Map.class))).thenReturn(mockId);

        MDC.put("traceId", "trace-xyz-888");
        TenantContextHolder.setTenantId("tenant-007");
        TenantContextHolder.setIgnoreTenant(true);
        UserContextHolder.set("user-operator-99");

        template.send("context-stream", Map.of("action", "testContext"));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq("sample:context-stream"), captor.capture());

        Map<String, Object> captured = captor.getValue();
        assertThat(captured.get("traceId")).isEqualTo("trace-xyz-888");
        assertThat(captured.get(RedisStreamTemplate.META_TENANT_ID)).isEqualTo("tenant-007");
        assertThat(captured.get(RedisStreamTemplate.META_IGNORE_TENANT)).isEqualTo("true");
        assertThat(captured.get(RedisStreamTemplate.META_USER_CTX)).isEqualTo("user-operator-99");
        assertThat(captured.get("action")).isEqualTo("testContext");
    }

    @Test
    @DisplayName("测试便捷消费组读取与确认 (readGroup, ack, getPendingMessages)")
    void testConvenientGroupAndPendingFacades() {
        RecordId id1 = RecordId.of("10-0");
        when(streamOps.acknowledge("sample:s1", "g1", id1)).thenReturn(1L);
        assertThat(template.ack("s1", "g1", "10-0")).isEqualTo(1L);
        assertThat(template.ack("s1", "g1", id1)).isEqualTo(1L);

        PendingMessages mockPending = mock(PendingMessages.class);
        when(streamOps.pending(eq("sample:s1"), eq("g1"), any(Range.class), eq(5L))).thenReturn(mockPending);
        assertThat(template.getPendingMessages("s1", "g1", 5)).isSameAs(mockPending);

        Consumer consumer = Consumer.from("g1", "c1");
        MapRecord<String, Object, Object> record = createMapRecord("sample:s1", id1, Map.of("k", "v"));
        when(streamOps.read(eq(consumer), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(record));

        List<MapRecord<String, Object, Object>> readList = template.readGroup("s1", "g1", "c1", 10, Duration.ofMillis(200));
        assertThat(readList).hasSize(1);
    }

    @Test
    @DisplayName("测试死信流名称解析、键名格式化与死信统计/检视/清空门面")
    void testDeadLetterManagement() {
        assertThat(template.resolveDeadLetterStream("order-events")).isEqualTo("order-events:dlq");
        assertThat(template.resolveDeadLetterStream("order-events:dlq")).isEqualTo("order-events:dlq");
        assertThat(template.formatDeadLetterStreamKey("order-events")).isEqualTo("sample:order-events:dlq");

        when(streamOps.size("sample:order-events:dlq")).thenReturn(3L);
        assertThat(template.deadLetterSize("order-events")).isEqualTo(3L);

        MapRecord<String, Object, Object> d1 = createMapRecord("sample:order-events:dlq", RecordId.of("1-0"), Map.of("k", "1"));
        MapRecord<String, Object, Object> d2 = createMapRecord("sample:order-events:dlq", RecordId.of("2-0"), Map.of("k", "2"));
        when(streamOps.range("sample:order-events:dlq", Range.unbounded())).thenReturn(List.of(d1, d2));

        List<MapRecord<String, Object, Object>> peeked = template.peekDeadLetters("order-events", 1);
        assertThat(peeked).hasSize(1);
        assertThat(peeked.get(0).getId().getValue()).isEqualTo("1-0");

        when(redisTemplate.delete("sample:order-events:dlq")).thenReturn(Boolean.TRUE);
        assertThat(template.clearDeadLetters("order-events")).isTrue();
    }

    @Test
    @DisplayName("测试单条死信受控重放成功：自增 replayCount、投递至原 Stream 并从 DLQ 移除")
    @SuppressWarnings("unchecked")
    void testReplayDeadLetterSuccess() {
        RecordId dlqId = RecordId.of("888-0");
        Map<Object, Object> dlqVal = new HashMap<>();
        dlqVal.put("orderId", "ORD_REPLAY");
        dlqVal.put("replayCount", 1);
        MapRecord<String, Object, Object> dlqRecord = MapRecord.create("sample:re-stream:dlq", dlqVal).withId(dlqId);

        when(streamOps.range("sample:re-stream:dlq", Range.closed("888-0", "888-0"))).thenReturn(List.of(dlqRecord));
        when(streamOps.add(eq("sample:re-stream"), any(Map.class))).thenReturn(RecordId.of("999-0"));
        when(streamOps.delete("sample:re-stream:dlq", dlqId)).thenReturn(1L);

        boolean ok = template.replayDeadLetter("re-stream", "888-0");
        assertThat(ok).isTrue();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq("sample:re-stream"), captor.capture());
        assertThat(captor.getValue().get(RedisStreamTemplate.META_REPLAY_COUNT)).isEqualTo(2);
        verify(streamOps).delete("sample:re-stream:dlq", dlqId);
    }

    @Test
    @DisplayName("测试死信受控重放次数超限时触发防雪崩熔断，抛出 BusinessException")
    void testReplayDeadLetterExceededLimitThrowsException() {
        RecordId dlqId = RecordId.of("777-0");
        Map<Object, Object> dlqVal = new HashMap<>();
        dlqVal.put("orderId", "POISON");
        dlqVal.put("replayCount", 3); // 默认上限为 3
        MapRecord<String, Object, Object> dlqRecord = MapRecord.create("sample:re-stream:dlq", dlqVal).withId(dlqId);

        when(streamOps.range("sample:re-stream:dlq", Range.closed("777-0", "777-0"))).thenReturn(List.of(dlqRecord));

        assertThatThrownBy(() -> template.replayDeadLetter("re-stream", "777-0"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("死信消息重放次数已达上限");
    }

    @Test
    @DisplayName("测试批量受控重放死信消息")
    void testBatchReplayDeadLetters() {
        RecordId d1Id = RecordId.of("1-0");
        MapRecord<String, Object, Object> d1 = createMapRecord("sample:batch-stream:dlq", d1Id,
                Map.of("replayCount", 0, "payload", "msg1"));

        when(streamOps.range("sample:batch-stream:dlq", Range.unbounded())).thenReturn(List.of(d1));
        when(streamOps.range("sample:batch-stream:dlq", Range.closed("1-0", "1-0"))).thenReturn(List.of(d1));
        when(streamOps.add(eq("sample:batch-stream"), any(Map.class))).thenReturn(RecordId.of("10-0"));

        int replayed = template.replayDeadLetters("batch-stream", 5);
        assertThat(replayed).isEqualTo(1);
    }

    @Test
    @DisplayName("测试 claimPendingMessages 自动过滤闲置超时消息并认领")
    void testClaimPendingMessages() {
        RecordId id1 = RecordId.of("100-0");
        PendingMessage pm = mock(PendingMessage.class);
        when(pm.getId()).thenReturn(id1);
        when(pm.getElapsedTimeSinceLastDelivery()).thenReturn(Duration.ofSeconds(120));

        PendingMessages mockPending = new PendingMessages("g1", Range.unbounded(), List.of(pm));
        when(streamOps.pending(eq("sample:claim-stream"), eq("g1"), any(Range.class), eq(10L)))
                .thenReturn(mockPending);

        MapRecord<String, Object, Object> claimedRecord = createMapRecord("sample:claim-stream", id1, Map.of("k", "v"));
        when(streamOps.claim(eq("sample:claim-stream"), eq("g1"), eq("new-c"), eq(Duration.ofSeconds(60)), eq(new RecordId[]{id1})))
                .thenReturn(List.of(claimedRecord));

        List<MapRecord<String, Object, Object>> claimed = template.claimPendingMessages(
                "claim-stream", "g1", "new-c", Duration.ofSeconds(60), 10);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).getId()).isEqualTo(id1);
    }

    @SuppressWarnings("unchecked")
    private static MapRecord<String, Object, Object> createMapRecord(String stream, RecordId id, Map<?, ?> map) {
        return (MapRecord<String, Object, Object>) (MapRecord<?, ?, ?>) MapRecord.create(stream, map).withId(id);
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

    @Test
    @DisplayName("测试 formatStreamKey 规范化无冒号前缀并幂等去重已包含全局前缀")
    void testFormatStreamKeyIdempotentNormalization() {
        RedisProperties props = new RedisProperties();
        props.setKeyPrefix("prod");
        RedisStreamTemplate customTemplate = new RedisStreamTemplate(redisTemplate, null, props);

        assertThat(customTemplate.formatStreamKey("order-stream")).isEqualTo("prod:order-stream");
        assertThat(customTemplate.formatStreamKey("prod:order-stream")).isEqualTo("prod:order-stream");

        assertThat(template.formatStreamKey("sample:order-stream")).isEqualTo("sample:order-stream");
    }
}
