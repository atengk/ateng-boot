package io.github.atengk.boot.redis.atomic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分布式原子计数、发号与滑动窗口工具单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisAtomicUtil 单元测试")
class RedisAtomicUtilTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private ZSetOperations<String, Object> zSetOperations;

    private RedisAtomicUtil atomicUtil;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        atomicUtil = new RedisAtomicUtil(redisTemplate);
    }

    @Test
    @DisplayName("测试原子自增与自减操作")
    void testIncrementAndDecrement() {
        when(valueOperations.increment("counter:hits", 1L)).thenReturn(101L);
        when(valueOperations.increment("counter:hits", 5L)).thenReturn(106L);
        when(valueOperations.decrement("counter:stock", 1L)).thenReturn(99L);
        when(valueOperations.decrement("counter:stock", 2L)).thenReturn(97L);

        assertThat(atomicUtil.increment("counter:hits")).isEqualTo(101L);
        assertThat(atomicUtil.increment("counter:hits", 5L)).isEqualTo(106L);
        assertThat(atomicUtil.decrement("counter:stock")).isEqualTo(99L);
        assertThat(atomicUtil.decrement("counter:stock", 2L)).isEqualTo(97L);
    }

    @Test
    @DisplayName("测试带 TTL 的原子递增操作")
    void testIncrementWithTtl() {
        when(redisTemplate.execute(any(RedisScript.class), any(RedisSerializer.class), isNull(), anyList(), eq("1"), eq("300")))
                .thenReturn(1L);

        Long val = atomicUtil.increment("counter:code", 1L, Duration.ofMinutes(5));
        assertThat(val).isEqualTo(1L);
        verify(redisTemplate).execute(any(RedisScript.class), any(RedisSerializer.class), isNull(), anyList(), eq("1"), eq("300"));
    }

    @Test
    @DisplayName("测试 get 与 set 操作")
    void testGetAndSet() {
        when(valueOperations.get("num:test")).thenReturn(42L);

        assertThat(atomicUtil.get("num:test")).isEqualTo(42L);

        atomicUtil.set("num:test", 100L);
        verify(valueOperations).set("num:test", 100L);

        atomicUtil.set("num:test", 200L, Duration.ofSeconds(30));
        verify(valueOperations).set("num:test", 200L, Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("测试基于日期的业务递增流水号发号 (generateDailySeq)")
    void testGenerateDailySeq() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        when(redisTemplate.execute(any(RedisScript.class), any(RedisSerializer.class), isNull(), anyList(), eq(String.valueOf(Duration.ofDays(2).toSeconds()))))
                .thenReturn(1L);

        String seq1 = atomicUtil.generateDailySeq("ORD", 6);
        assertThat(seq1).isEqualTo("ORD" + today + "000001");

        when(redisTemplate.execute(any(RedisScript.class), any(RedisSerializer.class), isNull(), anyList(), eq(String.valueOf(Duration.ofDays(2).toSeconds()))))
                .thenReturn(123L);
        String seq123 = atomicUtil.generateDailySeq("ORD", 6);
        assertThat(seq123).isEqualTo("ORD" + today + "000123");
    }

    @Test
    @DisplayName("测试滑动窗口频控校验 (slidingWindowCheck) 放行与拦截")
    void testSlidingWindowCheck() {
        // 场景 1：Lua 脚本返回 1L -> 放行
        when(redisTemplate.execute(any(RedisScript.class), any(RedisSerializer.class), isNull(), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(1L);
        boolean allowed = atomicUtil.slidingWindowCheck("ratelimit:ip:127.0.0.1", 5, Duration.ofSeconds(10));
        assertThat(allowed).isTrue();
        assertThat(atomicUtil.isAllowedBySlidingWindow("ratelimit:ip:127.0.0.1", 5, Duration.ofSeconds(10))).isTrue();

        // 场景 2：Lua 脚本返回 0L -> 拦截拒绝
        when(redisTemplate.execute(any(RedisScript.class), any(RedisSerializer.class), isNull(), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(0L);
        boolean blocked = atomicUtil.slidingWindowCheck("ratelimit:ip:127.0.0.1", 5, Duration.ofSeconds(10));
        assertThat(blocked).isFalse();
    }

    @Test
    @DisplayName("测试获取滑动窗口内请求数 (getSlidingWindowCount)")
    void testGetSlidingWindowCount() {
        when(zSetOperations.zCard("sliding:count")).thenReturn(8L);

        long count = atomicUtil.getSlidingWindowCount("sliding:count", Duration.ofMinutes(1));
        assertThat(count).isEqualTo(8L);
    }

    @Test
    @DisplayName("测试入参防御性校验")
    void testValidation() {
        assertThatThrownBy(() -> atomicUtil.increment(""))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> atomicUtil.generateDailySeq("", 6))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> atomicUtil.generateDailySeq("ORD", -1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> atomicUtil.slidingWindowCheck("key", 0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
