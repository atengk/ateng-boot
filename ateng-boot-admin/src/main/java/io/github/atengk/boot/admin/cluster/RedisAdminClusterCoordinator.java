package io.github.atengk.boot.admin.cluster;

import io.github.atengk.boot.admin.silence.SilenceRule;
import io.github.atengk.boot.core.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 {@link StringRedisTemplate} 的 Fail-Open 分布式集群协调器实现
 * <p>
 * 利用 Redis {@code SET NX EX} 与原子 Lua 比较删除实现跨节点防脑裂；
 * 遭遇任何 Redis 连接或超时异常时均打印告警并立即降级放行（Fail-Open），同时静默规则自动切换本地缓存兜底。
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class RedisAdminClusterCoordinator implements AdminClusterCoordinator {

    private static final String RELEASE_LOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT;

    static {
        RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>();
        RELEASE_LOCK_SCRIPT.setScriptText(RELEASE_LOCK_LUA);
        RELEASE_LOCK_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final Clock clock;
    private final InMemoryAdminClusterCoordinator localFallback;

    public RedisAdminClusterCoordinator(StringRedisTemplate redisTemplate, String keyPrefix) {
        this(redisTemplate, keyPrefix, Clock.systemUTC());
    }

    public RedisAdminClusterCoordinator(StringRedisTemplate redisTemplate, String keyPrefix, Clock clock) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.keyPrefix = StringUtils.hasText(keyPrefix) ? keyPrefix.trim() : "ateng:admin:";
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.localFallback = new InMemoryAdminClusterCoordinator(this.clock);
    }

    @Override
    public boolean tryAcquireLock(String lockKey, String ownerId, Duration ttl) {
        if (!StringUtils.hasText(lockKey) || !StringUtils.hasText(ownerId)) {
            return false;
        }
        String fullKey = keyPrefix + "lock:" + lockKey;
        Duration effectiveTtl = (ttl == null || ttl.isNegative() || ttl.isZero()) ? Duration.ofSeconds(30) : ttl;
        try {
            ValueOperations<String, String> ops = redisTemplate.opsForValue();
            Boolean acquired = ops.setIfAbsent(fullKey, ownerId, effectiveTtl);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception ex) {
            log.warn("[Cluster Coordinator] Redis 获取分布式锁失败，触发 Fail-Open 降级放行: key=[{}], err=[{}]",
                    fullKey, ex.getMessage());
            return true;
        }
    }

    @Override
    public void releaseLock(String lockKey, String ownerId) {
        if (!StringUtils.hasText(lockKey) || !StringUtils.hasText(ownerId)) {
            return;
        }
        String fullKey = keyPrefix + "lock:" + lockKey;
        try {
            redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(fullKey), ownerId);
        } catch (Exception ex) {
            log.warn("[Cluster Coordinator] Redis 释放分布式锁异常，依赖 TTL 自动过期（Fail-Open）: key=[{}], err=[{}]",
                    fullKey, ex.getMessage());
        }
    }

    @Override
    public boolean tryMarkAlertDispatched(String dedupKey, Duration ttl) {
        if (!StringUtils.hasText(dedupKey)) {
            return true;
        }
        String fullKey = keyPrefix + "alert:dedup:" + dedupKey;
        Duration effectiveTtl = (ttl == null || ttl.isNegative() || ttl.isZero()) ? Duration.ofSeconds(60) : ttl;
        try {
            ValueOperations<String, String> ops = redisTemplate.opsForValue();
            Boolean first = ops.setIfAbsent(fullKey, "1", effectiveTtl);
            return Boolean.TRUE.equals(first);
        } catch (Exception ex) {
            log.warn("[Cluster Coordinator] Redis 告警去重标记失败，触发 Fail-Open 降级放行告警: key=[{}], err=[{}]",
                    fullKey, ex.getMessage());
            return true;
        }
    }

    @Override
    public void saveSilenceRule(SilenceRule rule) {
        if (rule == null || !StringUtils.hasText(rule.getId())) {
            return;
        }
        localFallback.saveSilenceRule(rule);
        String hashKey = silenceRulesHashKey();
        try {
            String json = JsonUtil.toJsonString(rule);
            redisTemplate.opsForHash().put(hashKey, rule.getId(), json);
        } catch (Exception ex) {
            log.warn("[Cluster Coordinator] Redis 写入静默规则异常，已降级保留至本机内存缓存: ruleId=[{}], err=[{}]",
                    rule.getId(), ex.getMessage());
        }
    }

    @Override
    public void deleteSilenceRule(String ruleId) {
        if (!StringUtils.hasText(ruleId)) {
            return;
        }
        localFallback.deleteSilenceRule(ruleId);
        String hashKey = silenceRulesHashKey();
        try {
            redisTemplate.opsForHash().delete(hashKey, ruleId);
        } catch (Exception ex) {
            log.warn("[Cluster Coordinator] Redis 删除静默规则异常，已清理本机缓存: ruleId=[{}], err=[{}]",
                    ruleId, ex.getMessage());
        }
    }

    @Override
    public List<SilenceRule> listSilenceRules() {
        String hashKey = silenceRulesHashKey();
        try {
            SilenceSnapshot snapshot = loadAndPruneSilenceRules(hashKey);
            localFallback.replaceSilenceRules(snapshot.active());
            return snapshot.active();
        } catch (Exception ex) {
            log.warn("[Cluster Coordinator] Redis 读取静默规则异常，降级回退本机内存静默规则（Fail-Open）: err=[{}]",
                    ex.getMessage());
            return localFallback.listSilenceRules();
        }
    }

    @Override
    public int pruneExpiredSilenceRules() {
        String hashKey = silenceRulesHashKey();
        try {
            SilenceSnapshot snapshot = loadAndPruneSilenceRules(hashKey);
            localFallback.replaceSilenceRules(snapshot.active());
            return snapshot.prunedCount();
        } catch (Exception ex) {
            log.warn("[Cluster Coordinator] Redis 清理过期静默规则异常，降级清理本机内存规则（Fail-Open）: err=[{}]",
                    ex.getMessage());
            return localFallback.pruneExpiredSilenceRules();
        }
    }

    private SilenceSnapshot loadAndPruneSilenceRules(String hashKey) {
        HashOperations<String, String, String> ops = redisTemplate.opsForHash();
        Map<String, String> entries = ops.entries(hashKey);
        if (entries == null || entries.isEmpty()) {
            return new SilenceSnapshot(Collections.emptyList(), 0);
        }
        Instant now = clock.instant();
        List<SilenceRule> active = new ArrayList<>();
        List<String> expiredIds = new ArrayList<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            SilenceRule parsed = parseSilenceRule(entry.getValue());
            if (parsed == null || parsed.isExpired(now)) {
                expiredIds.add(entry.getKey());
            } else {
                active.add(parsed);
            }
        }
        if (!expiredIds.isEmpty()) {
            ops.delete(hashKey, expiredIds.toArray());
        }
        active.sort(Comparator.comparing(
                SilenceRule::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())
        ));
        return new SilenceSnapshot(List.copyOf(active), expiredIds.size());
    }

    private record SilenceSnapshot(List<SilenceRule> active, int prunedCount) {
    }

    private SilenceRule parseSilenceRule(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return JsonUtil.parseObject(json, SilenceRule.class);
        } catch (Exception ex) {
            log.debug("[Cluster Coordinator] 反序列化 Redis 静默规则失败，跳过脏记录: {}", ex.getMessage());
            return null;
        }
    }

    private String silenceRulesHashKey() {
        return keyPrefix + "silence:rules";
    }
}
