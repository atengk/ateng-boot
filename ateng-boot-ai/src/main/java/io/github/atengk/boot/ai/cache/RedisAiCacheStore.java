package io.github.atengk.boot.ai.cache;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.core.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;

/**
 * 基于 StringRedisTemplate 与 Core JsonUtil 的分布式双级响应缓存存储实现
 * <p>
 * 采用原生 TTL 自动淘汰与租户/作用域二级 Set 索引，严禁使用阻塞性的 {@code KEYS *} 全库扫描，
 * 并通过与 RAG 知识库 {@code EmbeddingStore} 物理隔离的键前缀保证零串库污染。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-22
 */
public class RedisAiCacheStore implements AiCacheStore {

    private static final Logger log = LoggerFactory.getLogger(RedisAiCacheStore.class);

    private static final String DEFAULT_KEY_PREFIX = "ateng:ai:cache:";

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final Duration defaultTtl;
    private final int maxEntries;

    public RedisAiCacheStore(StringRedisTemplate redisTemplate, String keyPrefix, Duration defaultTtl) {
        this(redisTemplate, keyPrefix, defaultTtl, 1000);
    }

    public RedisAiCacheStore(StringRedisTemplate redisTemplate, String keyPrefix, Duration defaultTtl, int maxEntries) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "StringRedisTemplate 不能为空");
        String prefix = StringUtils.hasText(keyPrefix) ? keyPrefix.trim() : DEFAULT_KEY_PREFIX;
        this.keyPrefix = prefix.endsWith(":") ? prefix : prefix + ":";
        this.defaultTtl = (defaultTtl != null && !defaultTtl.isNegative() && !defaultTtl.isZero())
                ? defaultTtl
                : Duration.ofHours(1);
        this.maxEntries = maxEntries > 0 ? maxEntries : 1000;
    }

    @Override
    public Optional<AiCacheEntry> getExact(String exactKey) {
        if (!StringUtils.hasText(exactKey)) {
            return Optional.empty();
        }
        String key = entryKey(exactKey);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (!StringUtils.hasText(json)) {
                return Optional.empty();
            }
            AiCacheEntry entry = JsonUtil.parseObject(json, AiCacheEntry.class);
            if (entry == null || entry.isExpired(System.currentTimeMillis())) {
                redisTemplate.delete(key);
                return Optional.empty();
            }
            return Optional.of(entry);
        } catch (Exception e) {
            log.warn("[AI-CACHE-REDIS] 读取精确缓存失败 [key={}]: {}", key, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<AiCacheLookupResult> searchSemantic(String scopeKey, float[] queryVector, double similarityThreshold) {
        if (!StringUtils.hasText(scopeKey) || queryVector == null || queryVector.length == 0) {
            return Optional.empty();
        }
        String sKey = scopeIndexKey(scopeKey);
        try {
            Set<String> memberKeys = redisTemplate.opsForSet().members(sKey);
            if (memberKeys == null || memberKeys.isEmpty()) {
                return Optional.empty();
            }
            List<String> exactKeys = new ArrayList<>(memberKeys);
            List<String> redisKeys = exactKeys.stream().map(this::entryKey).toList();
            List<String> payloads = redisTemplate.opsForValue().multiGet(redisKeys);
            if (payloads == null || payloads.isEmpty()) {
                return Optional.empty();
            }

            long now = System.currentTimeMillis();
            AiCacheEntry bestEntry = null;
            double bestScore = similarityThreshold;
            List<String> staleExactKeys = null;

            for (int i = 0; i < exactKeys.size() && i < payloads.size(); i++) {
                String exactKey = exactKeys.get(i);
                String json = payloads.get(i);
                if (!StringUtils.hasText(json)) {
                    if (staleExactKeys == null) {
                        staleExactKeys = new ArrayList<>();
                    }
                    staleExactKeys.add(exactKey);
                    continue;
                }
                AiCacheEntry entry = JsonUtil.parseObject(json, AiCacheEntry.class);
                if (entry == null || entry.isExpired(now)) {
                    if (staleExactKeys == null) {
                        staleExactKeys = new ArrayList<>();
                    }
                    staleExactKeys.add(exactKey);
                    continue;
                }
                if (entry.getEmbedding() == null || entry.getEmbedding().length == 0) {
                    continue;
                }
                double similarity = AiCacheStore.cosineSimilarity(queryVector, entry.getEmbedding());
                if (similarity >= bestScore && (bestEntry == null || similarity > bestScore)) {
                    bestScore = similarity;
                    bestEntry = entry;
                }
            }

            if (staleExactKeys != null && !staleExactKeys.isEmpty()) {
                redisTemplate.opsForSet().remove(sKey, staleExactKeys.toArray());
            }

            if (bestEntry == null) {
                return Optional.empty();
            }
            return Optional.of(AiCacheLookupResult.builder()
                    .matchType(AiCacheLookupResult.MatchType.SEMANTIC)
                    .responseText(bestEntry.getResponseText())
                    .similarity(bestScore)
                    .exactKey(bestEntry.getExactKey())
                    .scopeKey(bestEntry.getScopeKey())
                    .tenantId(bestEntry.getTenantId())
                    .modelName(bestEntry.getModelName())
                    .build());
        } catch (Exception e) {
            log.warn("[AI-CACHE-REDIS] 执行语义向量检索失败 [scope={}]: {}", scopeKey, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public void put(AiCacheEntry entry, Duration ttl) {
        if (entry == null || !StringUtils.hasText(entry.getExactKey())) {
            return;
        }
        Duration effectiveTtl = (ttl != null && !ttl.isNegative() && !ttl.isZero()) ? ttl : defaultTtl;
        long now = System.currentTimeMillis();
        String tenant = normalizeTenant(entry.getTenantId());
        AiCacheEntry stored = AiCacheEntry.builder()
                .exactKey(entry.getExactKey())
                .scopeKey(entry.getScopeKey())
                .tenantId(tenant)
                .modelName(entry.getModelName())
                .queryText(entry.getQueryText())
                .responseText(entry.getResponseText())
                .embedding(entry.getEmbedding())
                .createdAtEpochMs(entry.getCreatedAtEpochMs() > 0 ? entry.getCreatedAtEpochMs() : now)
                .expiresAtEpochMs(entry.getExpiresAtEpochMs() > 0 ? entry.getExpiresAtEpochMs() : now + effectiveTtl.toMillis())
                .build();

        String eKey = entryKey(stored.getExactKey());
        try {
            String json = JsonUtil.toJsonString(stored);
            redisTemplate.opsForValue().set(eKey, json, effectiveTtl);

            String tIndexKey = tenantIndexKey(tenant);
            redisTemplate.opsForSet().add(tIndexKey, stored.getExactKey());
            extendTtlIfNeeded(tIndexKey, effectiveTtl);

            String registryKey = tenantsRegistryKey();
            redisTemplate.opsForSet().add(registryKey, tenant);
            extendTtlIfNeeded(registryKey, effectiveTtl);

            if (StringUtils.hasText(stored.getScopeKey())) {
                String sIndexKey = scopeIndexKey(stored.getScopeKey());
                Long scopeSize = redisTemplate.opsForSet().size(sIndexKey);
                if (scopeSize != null && scopeSize >= maxEntries) {
                    String popped = redisTemplate.opsForSet().pop(sIndexKey);
                    if (StringUtils.hasText(popped) && !popped.equals(stored.getExactKey())) {
                        getExact(popped).ifPresent(evicted ->
                                redisTemplate.opsForSet().remove(tenantIndexKey(evicted.getTenantId()), popped));
                        redisTemplate.delete(entryKey(popped));
                    }
                }
                redisTemplate.opsForSet().add(sIndexKey, stored.getExactKey());
                extendTtlIfNeeded(sIndexKey, effectiveTtl);
            }
        } catch (Exception e) {
            throw new AiException(AiResultCode.AI_MEMORY_STORE_ERROR,
                    "向 Redis 写入 AI 响应缓存失败 [key=" + eKey + "]: " + e.getMessage(), e);
        }
    }

    @Override
    public void evictByTenant(String tenantId) {
        String tenant = normalizeTenant(tenantId);
        String tIndexKey = tenantIndexKey(tenant);
        try {
            Set<String> exactKeys = redisTemplate.opsForSet().members(tIndexKey);
            if (exactKeys != null && !exactKeys.isEmpty()) {
                List<String> keysToDelete = exactKeys.stream().map(this::entryKey).toList();
                redisTemplate.delete(keysToDelete);
            }
            redisTemplate.delete(tIndexKey);
            redisTemplate.opsForSet().remove(tenantsRegistryKey(), tenant);
        } catch (Exception e) {
            throw new AiException(AiResultCode.AI_MEMORY_STORE_ERROR,
                    "从 Redis 驱逐租户 AI 响应缓存失败 [tenant=" + tenant + "]: " + e.getMessage(), e);
        }
    }

    @Override
    public void clearAll() {
        try {
            Set<String> tenants = redisTemplate.opsForSet().members(tenantsRegistryKey());
            if (tenants != null) {
                for (String tenant : tenants) {
                    evictByTenant(tenant);
                }
            }
            redisTemplate.delete(tenantsRegistryKey());
        } catch (AiException ae) {
            throw ae;
        } catch (Exception e) {
            throw new AiException(AiResultCode.AI_MEMORY_STORE_ERROR,
                    "清空 Redis 全部 AI 响应缓存失败: " + e.getMessage(), e);
        }
    }

    @Override
    public long size() {
        try {
            Set<String> tenants = redisTemplate.opsForSet().members(tenantsRegistryKey());
            if (tenants == null || tenants.isEmpty()) {
                return 0L;
            }
            long total = 0L;
            for (String tenant : tenants) {
                Long count = redisTemplate.opsForSet().size(tenantIndexKey(tenant));
                if (count != null) {
                    total += count;
                }
            }
            return total;
        } catch (Exception e) {
            log.warn("[AI-CACHE-REDIS] 统计缓存数量失败: {}", e.getMessage(), e);
            return 0L;
        }
    }

    private void extendTtlIfNeeded(String key, Duration targetTtl) {
        Long currentExpireSec = redisTemplate.getExpire(key);
        long targetSec = Math.max(1L, targetTtl.getSeconds());
        if (currentExpireSec == null || currentExpireSec < 0 || currentExpireSec < targetSec) {
            redisTemplate.expire(key, targetTtl);
        }
    }

    @Override
    public boolean isDistributed() {
        return true;
    }

    public String entryKey(String exactKey) {
        return keyPrefix + "entry:" + exactKey;
    }

    public String tenantIndexKey(String tenantId) {
        return keyPrefix + "tenant:" + normalizeTenant(tenantId);
    }

    public String scopeIndexKey(String scopeKey) {
        return keyPrefix + "scope:" + scopeKey;
    }

    public String tenantsRegistryKey() {
        return keyPrefix + "tenants";
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    private static String normalizeTenant(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.trim() : "default";
    }
}
