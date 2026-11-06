package io.github.atengk.boot.social.ticket;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.social.model.SocialUser;
import io.github.atengk.boot.social.support.RedisLuaScripts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Collections;

/**
 * 基于 Redis 分布式集群驱动的两阶段绑定票据仓库
 * <p>
 * 依托 Redis 键空间与原生过期机制实现多实例共享与水平扩展；
 * 采用标准 Lua 原子脚本实现消费并删除（GETDEL 语义），保障跨微服务高并发下的绝对原子性与“阅后即焚”防重放。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
public class RedisBindTicketStore implements BindTicketStore {

    private static final String DEFAULT_KEY_PREFIX = "social:bind_ticket:";

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final Duration defaultTimeout;
    private final DefaultRedisScript<String> getAndDeleteScript;

    public RedisBindTicketStore(StringRedisTemplate redisTemplate) {
        this(redisTemplate, DEFAULT_KEY_PREFIX, DEFAULT_TICKET_TIMEOUT);
    }

    public RedisBindTicketStore(StringRedisTemplate redisTemplate, String keyPrefix) {
        this(redisTemplate, keyPrefix, DEFAULT_TICKET_TIMEOUT);
    }

    public RedisBindTicketStore(StringRedisTemplate redisTemplate, String keyPrefix, Duration defaultTimeout) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix != null ? keyPrefix : DEFAULT_KEY_PREFIX;
        this.defaultTimeout = defaultTimeout != null ? defaultTimeout : DEFAULT_TICKET_TIMEOUT;
        this.getAndDeleteScript = new DefaultRedisScript<>(RedisLuaScripts.LUA_GET_AND_DELETE, String.class);
    }

    @Override
    public String createTicket(SocialUser socialUser) {
        return createTicket(socialUser, this.defaultTimeout);
    }

    @Override
    public void save(String bindTicket, SocialUser socialUser, Duration timeout) {
        if (bindTicket == null || bindTicket.isBlank() || socialUser == null) {
            return;
        }
        Duration effectiveTimeout = timeout != null ? timeout : this.defaultTimeout;
        String key = buildKey(bindTicket);
        String json = JsonUtil.toJsonString(socialUser);
        redisTemplate.opsForValue().set(key, json, effectiveTimeout);
    }

    @Override
    public SocialUser remove(String bindTicket) {
        if (bindTicket == null || bindTicket.isBlank()) {
            return null;
        }
        String key = buildKey(bindTicket);
        String json = redisTemplate.execute(getAndDeleteScript, Collections.singletonList(key));
        if (json == null || json.isBlank()) {
            return null;
        }
        return JsonUtil.parseObject(json, SocialUser.class);
    }

    @Override
    public SocialUser get(String bindTicket) {
        if (bindTicket == null || bindTicket.isBlank()) {
            return null;
        }
        String key = buildKey(bindTicket);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return null;
        }
        return JsonUtil.parseObject(json, SocialUser.class);
    }

    @Override
    public boolean contains(String bindTicket) {
        if (bindTicket == null || bindTicket.isBlank()) {
            return false;
        }
        String key = buildKey(bindTicket);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    private String buildKey(String bindTicket) {
        return keyPrefix + bindTicket;
    }
}
