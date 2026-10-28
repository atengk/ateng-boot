package io.github.atengk.boot.redis.stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisStreamUtil 全局静态门面单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("RedisStreamUtil 全局静态门面单元测试")
class RedisStreamUtilTest {

    private final RedisStreamTemplate streamTemplate = mock(RedisStreamTemplate.class);

    @BeforeEach
    void setUp() {
        RedisStreamUtil.init(streamTemplate);
    }

    @Test
    @DisplayName("测试 send 各种重载静态门面委托")
    void testSendDelegation() {
        Record<String, ?> mockRecord = Record.of(Collections.singletonMap("k", "v")).withStreamKey("stream:test");
        when(streamTemplate.send("stream:test", "hello")).thenReturn(RecordId.of("1-0"));
        when(streamTemplate.send("stream:test", Map.of("k", (Object) "v"))).thenReturn(RecordId.of("2-0"));
        when(streamTemplate.send(mockRecord)).thenReturn(RecordId.of("3-0"));

        assertThat(RedisStreamUtil.send("stream:test", "hello")).isEqualTo(RecordId.of("1-0"));
        assertThat(RedisStreamUtil.send("stream:test", Map.of("k", (Object) "v"))).isEqualTo(RecordId.of("2-0"));
        assertThat(RedisStreamUtil.send(mockRecord)).isEqualTo(RecordId.of("3-0"));
    }

    @Test
    @DisplayName("测试消费组管理与确认应答静态委托")
    void testGroupAndAcknowledgeDelegation() {
        when(streamTemplate.destroyGroup("stream:test", "grp1")).thenReturn(true);
        when(streamTemplate.acknowledge("stream:test", "grp1", RecordId.of("1-0"))).thenReturn(1L);
        when(streamTemplate.acknowledge("stream:test", "grp1", "1-0")).thenReturn(1L);

        RedisStreamUtil.createGroup("stream:test", "grp1");
        verify(streamTemplate).createGroup("stream:test", "grp1");

        RedisStreamUtil.createGroup("stream:test", "grp1", ReadOffset.from("0"), true);
        verify(streamTemplate).createGroup("stream:test", "grp1", ReadOffset.from("0"), true);

        assertThat(RedisStreamUtil.destroyGroup("stream:test", "grp1")).isTrue();
        assertThat(RedisStreamUtil.acknowledge("stream:test", "grp1", RecordId.of("1-0"))).isEqualTo(1L);
        assertThat(RedisStreamUtil.acknowledge("stream:test", "grp1", "1-0")).isEqualTo(1L);
    }

    @Test
    @DisplayName("测试 Pending 检查与 XCLAIM 孤儿认领静态委托")
    void testPendingAndClaimDelegation() {
        PendingMessagesSummary summary = mock(PendingMessagesSummary.class);
        PendingMessages messages = mock(PendingMessages.class);
        when(streamTemplate.pendingSummary("stream:test", "grp1")).thenReturn(summary);
        when(streamTemplate.pending("stream:test", "grp1", Range.unbounded(), 10)).thenReturn(messages);
        when(streamTemplate.claim("stream:test", "grp1", "c2", Duration.ofMinutes(1), RecordId.of("1-0")))
                .thenReturn(Collections.emptyList());

        assertThat(RedisStreamUtil.pendingSummary("stream:test", "grp1")).isSameAs(summary);
        assertThat(RedisStreamUtil.pending("stream:test", "grp1", Range.unbounded(), 10)).isSameAs(messages);
        assertThat(RedisStreamUtil.claim("stream:test", "grp1", "c2", Duration.ofMinutes(1), RecordId.of("1-0"))).isEmpty();
    }

    @Test
    @DisplayName("测试 Stream 裁剪、统计、删除、消费与工具方法委托")
    void testTrimAndSizeDelegation() {
        when(streamTemplate.trim("stream:test", 1000L)).thenReturn(5L);
        when(streamTemplate.trim("stream:test", 1000L, true)).thenReturn(5L);
        when(streamTemplate.size("stream:test")).thenReturn(995L);
        when(streamTemplate.delete("stream:test", RecordId.of("1-0"))).thenReturn(1L);
        when(streamTemplate.delete("stream:test", "1-0")).thenReturn(1L);
        when(streamTemplate.formatStreamKey("test")).thenReturn("sample:test");

        Consumer consumer = Consumer.from("grp", "c1");
        StreamReadOptions options = StreamReadOptions.empty();
        StreamOffset<String> offset = StreamOffset.create("s", ReadOffset.latest());
        when(streamTemplate.read(consumer, options, offset)).thenReturn(Collections.emptyList());

        assertThat(RedisStreamUtil.trim("stream:test", 1000L)).isEqualTo(5L);
        assertThat(RedisStreamUtil.trim("stream:test", 1000L, true)).isEqualTo(5L);
        assertThat(RedisStreamUtil.size("stream:test")).isEqualTo(995L);
        assertThat(RedisStreamUtil.delete("stream:test", RecordId.of("1-0"))).isEqualTo(1L);
        assertThat(RedisStreamUtil.delete("stream:test", "1-0")).isEqualTo(1L);
        assertThat(RedisStreamUtil.formatStreamKey("test")).isEqualTo("sample:test");
        assertThat(RedisStreamUtil.read(consumer, options, offset)).isEmpty();
    }
}
