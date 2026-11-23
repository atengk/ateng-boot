package io.github.atengk.boot.sms.guard;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.sms.properties.SmsGuardProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于 Redis 的分布式出站黑名单过滤器实现
 * 适用于微服务多实例集群环境，黑名单动态增删与拦截判定全局实时同步
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Slf4j
public class RedisSmsBlacklistProvider implements SmsBlacklistProvider {

    private static final String DEFAULT_REASON = "目标手机号已退订或处于出站安全风控黑名单";
    private static final String REDIS_KEY_BLACKLIST = "ateng:sms:blacklist";

    private final StringRedisTemplate stringRedisTemplate;

    public RedisSmsBlacklistProvider(StringRedisTemplate stringRedisTemplate, Collection<String> initialBlacklist) {
        BusinessAssert.notNull(stringRedisTemplate, "StringRedisTemplate 不能为空");
        this.stringRedisTemplate = stringRedisTemplate;

        // 启动时自动合并 initialBlacklist 初始配置
        if (initialBlacklist != null && !initialBlacklist.isEmpty()) {
            addAll(initialBlacklist);
            log.info("[RedisSmsBlacklistProvider] 成功将初始配置的 {} 条黑名单手机号合并写入 Redis", initialBlacklist.size());
        }
    }

    public RedisSmsBlacklistProvider(StringRedisTemplate stringRedisTemplate, SmsGuardProperties guardProperties) {
        this(stringRedisTemplate, guardProperties != null ? guardProperties.getBlacklist() : Collections.emptyList());
    }

    public RedisSmsBlacklistProvider(StringRedisTemplate stringRedisTemplate) {
        this(stringRedisTemplate, Collections.emptyList());
    }

    @Override
    public boolean isBlacklisted(String phone) {
        if (phone == null || phone.isBlank()) {
            return false;
        }
        try {
            Boolean hasKey = stringRedisTemplate.opsForHash().hasKey(REDIS_KEY_BLACKLIST, phone.trim());
            return Boolean.TRUE.equals(hasKey);
        } catch (Exception ex) {
            log.error("[RedisSmsBlacklistProvider] 读取 Redis 黑名单异常，保底放行: {}", ex.getMessage());
            return false;
        }
    }

    @Override
    public String getReason(String phone) {
        if (phone == null || phone.isBlank()) {
            return DEFAULT_REASON;
        }
        try {
            Object val = stringRedisTemplate.opsForHash().get(REDIS_KEY_BLACKLIST, phone.trim());
            return val != null ? String.valueOf(val) : DEFAULT_REASON;
        } catch (Exception ex) {
            log.error("[RedisSmsBlacklistProvider] 读取 Redis 黑名单原因异常: {}", ex.getMessage());
            return DEFAULT_REASON;
        }
    }

    @Override
    public void add(String phone) {
        add(phone, DEFAULT_REASON);
    }

    @Override
    public void add(String phone, String reason) {
        if (phone != null && !phone.isBlank()) {
            String targetReason = (reason != null && !reason.isBlank()) ? reason.trim() : DEFAULT_REASON;
            stringRedisTemplate.opsForHash().put(REDIS_KEY_BLACKLIST, phone.trim(), targetReason);
        }
    }

    @Override
    public void addAll(Collection<String> phones) {
        if (phones == null || phones.isEmpty()) {
            return;
        }
        Map<String, String> entries = new HashMap<>();
        for (String phone : phones) {
            if (phone != null && !phone.isBlank()) {
                entries.put(phone.trim(), DEFAULT_REASON);
            }
        }
        if (!entries.isEmpty()) {
            stringRedisTemplate.opsForHash().putAll(REDIS_KEY_BLACKLIST, entries);
        }
    }

    @Override
    public void remove(String phone) {
        if (phone != null && !phone.isBlank()) {
            stringRedisTemplate.opsForHash().delete(REDIS_KEY_BLACKLIST, phone.trim());
        }
    }

    @Override
    public void removeAll(Collection<String> phones) {
        if (phones == null || phones.isEmpty()) {
            return;
        }
        Object[] keys = phones.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(String::trim)
                .toArray();
        if (keys.length > 0) {
            stringRedisTemplate.opsForHash().delete(REDIS_KEY_BLACKLIST, keys);
        }
    }

    @Override
    public Set<String> getBlacklist() {
        try {
            Set<Object> keys = stringRedisTemplate.opsForHash().keys(REDIS_KEY_BLACKLIST);
            if (keys == null || keys.isEmpty()) {
                return Collections.emptySet();
            }
            return keys.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
        } catch (Exception ex) {
            log.error("[RedisSmsBlacklistProvider] 获取 Redis 黑名单集合异常: {}", ex.getMessage());
            return Collections.emptySet();
        }
    }

    @Override
    public void clear() {
        stringRedisTemplate.delete(REDIS_KEY_BLACKLIST);
    }

    @Override
    public int size() {
        try {
            Long size = stringRedisTemplate.opsForHash().size(REDIS_KEY_BLACKLIST);
            return size != null ? size.intValue() : 0;
        } catch (Exception ex) {
            log.error("[RedisSmsBlacklistProvider] 获取 Redis 黑名单大小异常: {}", ex.getMessage());
            return 0;
        }
    }
}
