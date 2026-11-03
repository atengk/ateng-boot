package io.github.atengk.boot.social.cache;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.social.model.StateContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Collections;

/**
 * 基于 Redis 分布式集群驱动的防伪 State 状态缓存实现
 * <p>
 * 依托 Redis 键空间与原生过期机制实现多实例共享与水平扩展；
 * 采用标准 Lua 原子脚本实现消费并删除（GETDEL 语义），保障跨微服务高并发下的绝对原子性与“阅后即焚”防重放。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
public class RedisSocialAuthStateCache implements SocialAuthStateCache {

    private static final String DEFAULT_KEY_PREFIX = "social:state:";

    /**
     * 原子获取并删除的 Lua 脚本（兼容全版本 Redis，保障绝对原子性）
     */
    private static final String LUA_GET_AND_DELETE = """
            local val = redis.call('GET', KEYS[1])
            if val then
                redis.call('DEL', KEYS[1])
            end
            return val
            """;

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final DefaultRedisScript<String> getAndDeleteScript;

    public RedisSocialAuthStateCache(StringRedisTemplate redisTemplate) {
        this(redisTemplate, DEFAULT_KEY_PREFIX);
    }

    public RedisSocialAuthStateCache(StringRedisTemplate redisTemplate, String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix != null ? keyPrefix : DEFAULT_KEY_PREFIX;
        this.getAndDeleteScript = new DefaultRedisScript<>(LUA_GET_AND_DELETE, String.class);
    }

    @Override
    public void set(String state, StateContext context, Duration timeout) {
        if (state == null || state.isBlank() || context == null) {
            return;
        }
        Duration effectiveTimeout = timeout != null ? timeout : DEFAULT_STATE_TIMEOUT;
        String key = buildKey(state);
        String json = JsonUtil.toJsonString(context);
        redisTemplate.opsForValue().set(key, json, effectiveTimeout);
    }

    @Override
    public StateContext getContext(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String key = buildKey(state);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return null;
        }
        return JsonUtil.parseObject(json, StateContext.class);
    }

    @Override
    public StateContext removeContext(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String key = buildKey(state);
        String json = redisTemplate.execute(getAndDeleteScript, Collections.singletonList(key));
        if (json == null || json.isBlank()) {
            return null;
        }
        return JsonUtil.parseObject(json, StateContext.class);
    }

    @Override
    public boolean contains(String state) {
        if (state == null || state.isBlank()) {
            return false;
        }
        String key = buildKey(state);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    private String buildKey(String state) {
        return keyPrefix + state;
    }
}
