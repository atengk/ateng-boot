package io.github.atengk.boot.social.cache;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.social.model.StateContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisSocialAuthStateCache 分布式状态缓存行为测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class RedisSocialAuthStateCacheTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisSocialAuthStateCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cache = new RedisSocialAuthStateCache(redisTemplate);
    }

    @Test
    @DisplayName("验证通过 RedisTemplate 存入序列化 JSON 且带有超时")
    void testSet() {
        StateContext context = StateContext.builder()
                .tenantId("tenant_redis")
                .redirectUrl("https://example.com/oauth")
                .extraParams(Map.of("k", "v"))
                .build();

        cache.set("state_123", context, Duration.ofMinutes(10));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("social:state:state_123"), jsonCaptor.capture(), eq(Duration.ofMinutes(10)));

        StateContext deserialized = JsonUtil.parseObject(jsonCaptor.getValue(), StateContext.class);
        assertThat(deserialized.getTenantId()).isEqualTo("tenant_redis");
        assertThat(deserialized.getRedirectUrl()).isEqualTo("https://example.com/oauth");
    }

    @Test
    @DisplayName("验证 getContext 从 Redis 取出并还原 StateContext")
    void testGetContext() {
        StateContext context = StateContext.builder().tenantId("t1").build();
        when(valueOperations.get("social:state:state_456")).thenReturn(JsonUtil.toJsonString(context));

        StateContext restored = cache.getContext("state_456");
        assertThat(restored).isNotNull();
        assertThat(restored.getTenantId()).isEqualTo("t1");

        when(valueOperations.get("social:state:not_exist")).thenReturn(null);
        assertThat(cache.getContext("not_exist")).isNull();
    }

    @Test
    @DisplayName("验证 removeContext 通过 Lua 脚本原子执行并返回 StateContext")
    @SuppressWarnings("unchecked")
    void testRemoveContext() {
        StateContext context = StateContext.builder().redirectUrl("https://deep.link").build();
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("social:state:state_atomic"))))
                .thenReturn(JsonUtil.toJsonString(context));

        StateContext result = cache.removeContext("state_atomic");
        assertThat(result).isNotNull();
        assertThat(result.getRedirectUrl()).isEqualTo("https://deep.link");

        // 若返回 null 说明已被消费
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("social:state:state_atomic"))))
                .thenReturn(null);
        assertThat(cache.removeContext("state_atomic")).isNull();
    }

    @Test
    @DisplayName("验证 contains 检查 Key 是否存在")
    void testContains() {
        when(redisTemplate.hasKey("social:state:s1")).thenReturn(Boolean.TRUE);
        when(redisTemplate.hasKey("social:state:s2")).thenReturn(Boolean.FALSE);

        assertThat(cache.contains("s1")).isTrue();
        assertThat(cache.contains("s2")).isFalse();
        assertThat(cache.contains(null)).isFalse();
    }

    @Test
    @DisplayName("验证 cache 碰撞更新时将新 RedirectUrl 写回 Redis")
    void testCacheCollisionUpdate() {
        // 原有存在无 redirectUrl 的上下文（如仅包含 tenantId）
        StateContext existing = StateContext.builder().tenantId("tenant_abc").build();
        when(valueOperations.get("social:state:s_collision")).thenReturn(JsonUtil.toJsonString(existing));

        // 调用 cache 补充 redirectUrl
        cache.cache("s_collision", "https://app.example.com/deep/landing");

        // 验证将更新后的上下文重新写回 Redis
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("social:state:s_collision"), jsonCaptor.capture(), eq(SocialAuthStateCache.DEFAULT_STATE_TIMEOUT));

        StateContext updated = JsonUtil.parseObject(jsonCaptor.getValue(), StateContext.class);
        assertThat(updated.getTenantId()).isEqualTo("tenant_abc");
        assertThat(updated.getRedirectUrl()).isEqualTo("https://app.example.com/deep/landing");
    }

    @Test
    @DisplayName("验证 get 方法满足 Anduin 契约（存在时返回非空凭据，不存在时返回 null）")
    void testGetContract() {
        // 1. 存在且有 redirectUrl
        StateContext ctxWithUrl = StateContext.builder().redirectUrl("https://example.com").build();
        when(valueOperations.get("social:state:s_url")).thenReturn(JsonUtil.toJsonString(ctxWithUrl));
        assertThat(cache.get("social:state:s_url")).isNull(); // 校验 key 映射
        assertThat(cache.get("s_url")).isEqualTo("https://example.com");

        // 2. 存在但无 redirectUrl -> 保底返回 key 作为有效凭据
        StateContext ctxWithoutUrl = StateContext.builder().tenantId("t_only").build();
        when(valueOperations.get("social:state:s_nourl")).thenReturn(JsonUtil.toJsonString(ctxWithoutUrl));
        assertThat(cache.get("s_nourl")).isEqualTo("s_nourl");

        // 3. 不存在 -> 返回 null
        when(valueOperations.get("social:state:s_none")).thenReturn(null);
        assertThat(cache.get("s_none")).isNull();
    }
}
