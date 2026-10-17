package io.github.atengk.boot.satoken.dao;

import cn.dev33.satoken.dao.SaTokenDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SaTokenRedisDao 单元测试
 * 验证纯净字符串会话读写、TTL 保留更新、游标 SCAN 匹配增量检索杜绝 KEYS * 阻塞
 *
 * @author Ateng
 * @since 2026-10-17
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SaTokenRedisDao 单元测试")
class SaTokenRedisDaoTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private RedisSerializer<String> stringSerializer;

    private SaTokenRedisDao redisDao;

    @BeforeEach
    void setUp() {
        redisDao = new SaTokenRedisDao(stringRedisTemplate);
    }

    @Test
    @DisplayName("get: 读取字符串会话值")
    void testGet() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("token:123")).thenReturn("session-json-data");

        String val = redisDao.get("token:123");
        assertThat(val).isEqualTo("session-json-data");
        verify(valueOperations).get("token:123");
    }

    @Test
    @DisplayName("set: 带有超时的存入")
    void testSetWithTimeout() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        redisDao.set("token:123", "data", 3600);
        verify(valueOperations).set("token:123", "data", 3600, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("set: 永不过期的存入")
    void testSetNeverExpire() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        redisDao.set("token:123", "data", SaTokenDao.NEVER_EXPIRE);
        verify(valueOperations).set("token:123", "data");
    }

    @Test
    @DisplayName("set: 忽略 0 或负数超时")
    void testSetZeroTimeout() {
        redisDao.set("token:123", "data", 0);
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName("delete: 删除指定键")
    void testDelete() {
        redisDao.delete("token:123");
        verify(stringRedisTemplate).delete("token:123");
    }

    @Test
    @DisplayName("getTimeout: 获取剩余超时时间")
    void testGetTimeout() {
        when(stringRedisTemplate.getExpire("token:123", TimeUnit.SECONDS)).thenReturn(1800L);

        long timeout = redisDao.getTimeout("token:123");
        assertThat(timeout).isEqualTo(1800L);
    }

    @Test
    @DisplayName("updateTimeout: 续期与持久化")
    void testUpdateTimeout() {
        // 普通续期
        redisDao.updateTimeout("token:123", 7200);
        verify(stringRedisTemplate).expire("token:123", 7200, TimeUnit.SECONDS);

        // 设置为永不过期
        when(stringRedisTemplate.getExpire("token:persist", TimeUnit.SECONDS)).thenReturn(300L);
        redisDao.updateTimeout("token:persist", SaTokenDao.NEVER_EXPIRE);
        verify(stringRedisTemplate).persist("token:persist");
    }

    @Test
    @DisplayName("searchData: 游标增量扫描与分页过滤，杜绝高并发 KEYS * 阻塞")
    void testSearchData() {
        when(stringRedisTemplate.getStringSerializer()).thenReturn(stringSerializer);
        when(stringSerializer.deserialize(any(byte[].class))).thenReturn("satoken:login:token:1", "satoken:login:token:2");

        when(stringRedisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            org.springframework.data.redis.connection.RedisConnection connection = mock(org.springframework.data.redis.connection.RedisConnection.class);
            org.springframework.data.redis.connection.RedisKeyCommands keyCommands = mock(org.springframework.data.redis.connection.RedisKeyCommands.class);
            @SuppressWarnings("unchecked")
            Cursor<byte[]> cursor = mock(Cursor.class);

            when(connection.keyCommands()).thenReturn(keyCommands);
            when(keyCommands.scan(any(org.springframework.data.redis.core.ScanOptions.class))).thenReturn(cursor);
            when(cursor.hasNext()).thenReturn(true, true, false);
            when(cursor.next()).thenReturn("token:1".getBytes(), "token:2".getBytes());

            return callback.doInRedis(connection);
        });

        List<String> result = redisDao.searchData("satoken:login:", "token", 0, 10, true);
        assertThat(result).hasSize(2);
        assertThat(result).contains("satoken:login:token:1", "satoken:login:token:2");
    }
}
