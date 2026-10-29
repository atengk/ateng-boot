package io.github.atengk.boot.realtime.spi;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
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

    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        properties = new RealtimeProperties();
        properties.getOffline().setEnabled(true);
        properties.getOffline().setMaxMessagesPerUser(3);
        properties.getOffline().setTtl(Duration.ofDays(7));

        when(redissonClient.<String>getScoredSortedSet(startsWith("ateng:realtime:offline:"), any(StringCodec.class)))
                .thenReturn(mockScoredSet);

        provider = new RedissonRealtimeOfflineMessageProvider(redissonClient, properties);
    }

    @Test
    @DisplayName("暂存消息: 写入 ZSet、设置 TTL 并触发 FIFO 淘汰截断")
    void testSaveOfflineMessageWithFifoTrimming() {
        RealtimeMessage<String> msg = RealtimeMessage.of("notice", "hello offline");
        msg.setTimestamp(1700000000000L);

        // 模拟当前容量超限 (4 > 3)，需要淘汰 1 条
        when(mockScoredSet.size()).thenReturn(4);

        provider.saveOfflineMessage("user-100", msg);

        // 验证写入 score 与 json
        verify(mockScoredSet).add(eq(1700000000000.0), contains("hello offline"));
        // 验证设置键级 TTL
        verify(mockScoredSet).expire(Duration.ofDays(7));
        // 验证原子 FIFO 截断最早消息: removeRangeByRank(0, 0)
        verify(mockScoredSet).removeRangeByRank(0, 0);
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
