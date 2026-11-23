package io.github.atengk.boot.realtime.spi;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Redisson 分布式有界离线消息存储单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("RedissonRealtimeOfflineMessageProvider 分布式离线存储测试")
class RedissonRealtimeOfflineMessageProviderTest {

    private RedissonClient redissonClient;
    private RealtimeProperties properties;
    private RedissonRealtimeOfflineMessageProvider provider;

    @SuppressWarnings("unchecked")
    private RScoredSortedSet<String> mockScoredSet = mock(RScoredSortedSet.class);
    private RScript mockScript = mock(RScript.class);

    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        properties = new RealtimeProperties();
        properties.getOffline().setEnabled(true);
        properties.getOffline().setMaxMessagesPerUser(3);
        properties.getOffline().setTtl(Duration.ofDays(7));

        when(redissonClient.<String>getScoredSortedSet(startsWith("ateng:realtime:offline:"), any(StringCodec.class)))
                .thenReturn(mockScoredSet);
        when(redissonClient.getScript(any(StringCodec.class))).thenReturn(mockScript);

        provider = new RedissonRealtimeOfflineMessageProvider(redissonClient, properties);
    }

    @Test
    @DisplayName("暂存消息: 单次 RScript Lua 原子执行 ZADD + ZREMRANGEBYRANK + PEXPIRE")
    void testSaveOfflineMessageWithFifoTrimming() {
        RealtimeMessage<String> msg = RealtimeMessage.of("notice", "hello offline");
        msg.setTimestamp(1700000000000L);

        when(mockScript.eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.INTEGER), anyList(), any(Object[].class)))
                .thenReturn(1L);

        provider.saveOfflineMessage("user-100", msg);

        ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Object>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(mockScript, times(1)).eval(
                eq(RScript.Mode.READ_WRITE),
                scriptCaptor.capture(),
                eq(RScript.ReturnType.INTEGER),
                keysCaptor.capture(),
                eq(String.valueOf(1700000000000L)),
                contains("hello offline"),
                eq("3"),
                eq(String.valueOf(Duration.ofDays(7).toMillis()))
        );

        String lua = scriptCaptor.getValue();
        assertThat(lua).contains("ZADD", "ZREMRANGEBYRANK", "PEXPIRE");
        assertThat(keysCaptor.getValue()).containsExactly("ateng:realtime:offline:user-100");

        // 严禁再拆分为多 RTT 的分步 add/expire/removeRangeByRank 调用
        verify(mockScoredSet, never()).add(anyDouble(), anyString());
        verify(mockScoredSet, never()).expire(any(Duration.class));
        verify(mockScoredSet, never()).removeRangeByRank(anyInt(), anyInt());
    }

    @Test
    @DisplayName("边界配额与零 TTL: maxMessages<=0 与 ttl<=0 时向 Lua 传递 0 以跳过裁切与过期设置")
    void testSaveOfflineMessageBoundaryMaxAndZeroTtl() {
        properties.getOffline().setMaxMessagesPerUser(0);
        properties.getOffline().setTtl(Duration.ZERO);
        provider = new RedissonRealtimeOfflineMessageProvider(redissonClient, properties);

        RealtimeMessage<String> msg = RealtimeMessage.of("notice", "boundary payload");
        msg.setTimestamp(1700000000123L);
        when(mockScript.eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.INTEGER), anyList(), any(Object[].class)))
                .thenReturn(1L);

        provider.saveOfflineMessage("user-boundary", msg);

        verify(mockScript, times(1)).eval(
                eq(RScript.Mode.READ_WRITE),
                anyString(),
                eq(RScript.ReturnType.INTEGER),
                eq(List.of("ateng:realtime:offline:user-boundary")),
                eq("1700000000123"),
                contains("boundary payload"),
                eq("0"),
                eq("0")
        );
    }

    @Test
    @DisplayName("拉取并清空: 一次性读取并物理删除 Redis 键")
    void testPullAndClearOfflineMessages() {
        RealtimeMessage<String> m1 = RealtimeMessage.of("chat", "m1");
        m1.setTimestamp(1000L);
        RealtimeMessage<String> m2 = RealtimeMessage.of("chat", "m2");
        m2.setTimestamp(2000L);

        when(mockScoredSet.readAll()).thenReturn(List.of(
                JsonUtil.toJsonString(m1),
                JsonUtil.toJsonString(m2)
        ));

        List<RealtimeMessage<?>> messages = provider.pullAndClearOfflineMessages("user-200");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getId()).isEqualTo(m1.getId());
        assertThat(messages.get(1).getId()).isEqualTo(m2.getId());

        // 验证物理清空
        verify(mockScoredSet).delete();
    }

    @Test
    @DisplayName("只读获取与时间戳范围重放: 支持 W3C SSE Last-Event-ID 断点续传")
    void testGetOfflineMessagesSince() {
        RealtimeMessage<String> m3 = RealtimeMessage.of("event", "m3");
        m3.setTimestamp(3000L);

        when(mockScoredSet.valueRange(eq(2000.0), eq(false), eq(Double.POSITIVE_INFINITY), eq(false)))
                .thenReturn(List.of(JsonUtil.toJsonString(m3)));

        // 使用数值时间戳查询
        List<RealtimeMessage<?>> result = provider.getOfflineMessagesSince("user-300", 2000L);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(m3.getId());

        // 验证为只读操作，绝不删除 Redis 键
        verify(mockScoredSet, never()).delete();
    }

    @Test
    @DisplayName("字符串 Last-Event-ID 解析重放测试")
    void testGetOfflineMessagesSinceString() {
        RealtimeMessage<String> m1 = RealtimeMessage.of("event", "m1");
        m1.setTimestamp(1000L);
        RealtimeMessage<String> m2 = RealtimeMessage.of("event", "m2");
        m2.setTimestamp(2000L);

        when(mockScoredSet.readAll()).thenReturn(List.of(
                JsonUtil.toJsonString(m1),
                JsonUtil.toJsonString(m2)
        ));
        when(mockScoredSet.valueRange(eq(1000.0), eq(false), eq(Double.POSITIVE_INFINITY), eq(false)))
                .thenReturn(List.of(JsonUtil.toJsonString(m2)));

        // 1. 传入纯数字时间戳字符串 "1000"
        List<RealtimeMessage<?>> res1 = provider.getOfflineMessagesSince("user-400", "1000");
        assertThat(res1).hasSize(1);
        assertThat(res1.get(0).getId()).isEqualTo(m2.getId());

        // 2. 传入前一条消息的 UUID
        List<RealtimeMessage<?>> res2 = provider.getOfflineMessagesSince("user-400", m1.getId());
        assertThat(res2).hasSize(1);
        assertThat(res2.get(0).getId()).isEqualTo(m2.getId());

        // 3. 传入空字符串退化为全量只读
        List<RealtimeMessage<?>> resEmpty = provider.getOfflineMessagesSince("user-400", "");
        assertThat(resEmpty).hasSize(2);
    }

    @Test
    @DisplayName("计数与手动清空测试")
    void testCountAndClear() {
        when(mockScoredSet.size()).thenReturn(5);

        assertThat(provider.getOfflineMessageCount("user-500")).isEqualTo(5);

        provider.clearOfflineMessages("user-500");
        verify(mockScoredSet).delete();
    }

    @Test
    @DisplayName("空入参与异常防卫测试")
    void testDefensiveHandling() {
        provider.saveOfflineMessage(null, null);
        provider.saveOfflineMessage("user", null);
        provider.saveOfflineMessage(null, RealtimeMessage.of("t", "d"));

        assertThat(provider.pullAndClearOfflineMessages(null)).isEmpty();
        assertThat(provider.getOfflineMessages(null)).isEmpty();
        assertThat(provider.getOfflineMessagesSince(null, 100L)).isEmpty();
        assertThat(provider.getOfflineMessageCount(null)).isZero();

        // 模拟 Redis 抛异常
        when(mockScoredSet.readAll()).thenThrow(new RuntimeException("Redis error"));
        assertThat(provider.getOfflineMessages("user-err")).isEmpty();
    }
}
