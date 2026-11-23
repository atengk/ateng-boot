package io.github.atengk.boot.ai.cache;

import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于 ReentrantLock 的单机内存双级响应缓存实现
 * <p>
 * 结合有界 LRU 哈希容器、绝对过期 TTL 与按租户/作用域隔离的二次向量检索索引，
 * 全程采用 {@link ReentrantLock} 同步，杜绝 JDK 21+ 虚拟线程 Carrier Pinning。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-22
 */
public class InMemoryAiCacheStore implements AiCacheStore {

    private static final int DEFAULT_MAX_ENTRIES = 1000;

    private final int maxEntries;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, AiCacheEntry> entries;
    private final Map<String, Set<String>> tenantIndex = new HashMap<>();
    private final Map<String, Set<String>> scopeIndex = new HashMap<>();

    public InMemoryAiCacheStore() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public InMemoryAiCacheStore(int maxEntries) {
        this.maxEntries = maxEntries > 0 ? maxEntries : DEFAULT_MAX_ENTRIES;
        int initialCapacity = Math.min(256, Math.max(16, this.maxEntries));
        this.entries = new LinkedHashMap<>(initialCapacity, 0.75f, true);
    }

    @Override
    public Optional<AiCacheEntry> getExact(String exactKey) {
        if (!StringUtils.hasText(exactKey)) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        lock.lock();
        try {
            AiCacheEntry entry = entries.get(exactKey);
            if (entry == null) {
                return Optional.empty();
            }
            if (entry.isExpired(now)) {
                removeEntryLocked(exactKey);
                return Optional.empty();
            }
            return Optional.of(entry);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public Optional<AiCacheLookupResult> searchSemantic(String scopeKey, float[] queryVector, double similarityThreshold) {
        if (!StringUtils.hasText(scopeKey) || queryVector == null || queryVector.length == 0) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        lock.lock();
        try {
            Set<String> keys = scopeIndex.get(scopeKey);
            if (keys == null || keys.isEmpty()) {
                return Optional.empty();
            }
            AiCacheEntry bestEntry = null;
            double bestScore = similarityThreshold;
            List<String> expiredKeys = null;

            for (String key : keys) {
                AiCacheEntry entry = entries.get(key);
                if (entry == null || entry.isExpired(now)) {
                    if (expiredKeys == null) {
                        expiredKeys = new ArrayList<>();
                    }
                    expiredKeys.add(key);
                    continue;
                }
                if (entry.getEmbedding() == null || entry.getEmbedding().length == 0) {
                    continue;
                }
                double similarity = AiCacheStore.cosineSimilarity(queryVector, entry.getEmbedding());
                if (similarity >= bestScore) {
                    if (bestEntry == null || similarity > bestScore || Double.compare(similarity, bestScore) == 0) {
                        bestScore = similarity;
                        bestEntry = entry;
                    }
                }
            }

            if (expiredKeys != null) {
                for (String expiredKey : expiredKeys) {
                    removeEntryLocked(expiredKey);
                }
            }

            if (bestEntry == null) {
                return Optional.empty();
            }
            // 更新 LRU 访问序
            entries.get(bestEntry.getExactKey());
            return Optional.of(AiCacheLookupResult.builder()
                    .matchType(AiCacheLookupResult.MatchType.SEMANTIC)
                    .responseText(bestEntry.getResponseText())
                    .similarity(bestScore)
                    .exactKey(bestEntry.getExactKey())
                    .scopeKey(bestEntry.getScopeKey())
                    .tenantId(bestEntry.getTenantId())
                    .modelName(bestEntry.getModelName())
                    .build());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void put(AiCacheEntry entry, Duration ttl) {
        if (entry == null || !StringUtils.hasText(entry.getExactKey())) {
            return;
        }
        long now = System.currentTimeMillis();
        long expiresAt = (ttl != null && !ttl.isNegative() && !ttl.isZero()) ? now + ttl.toMillis() : 0L;
        AiCacheEntry stored = AiCacheEntry.builder()
                .exactKey(entry.getExactKey())
                .scopeKey(entry.getScopeKey())
                .tenantId(normalizeTenant(entry.getTenantId()))
                .modelName(entry.getModelName())
                .queryText(entry.getQueryText())
                .responseText(entry.getResponseText())
                .embedding(entry.getEmbedding())
                .createdAtEpochMs(entry.getCreatedAtEpochMs() > 0 ? entry.getCreatedAtEpochMs() : now)
                .expiresAtEpochMs(entry.getExpiresAtEpochMs() > 0 ? entry.getExpiresAtEpochMs() : expiresAt)
                .build();

        lock.lock();
        try {
            if (entries.containsKey(stored.getExactKey())) {
                removeEntryLocked(stored.getExactKey());
            }
            entries.put(stored.getExactKey(), stored);
            tenantIndex.computeIfAbsent(stored.getTenantId(), k -> new LinkedHashSet<>()).add(stored.getExactKey());
            if (StringUtils.hasText(stored.getScopeKey())) {
                scopeIndex.computeIfAbsent(stored.getScopeKey(), k -> new LinkedHashSet<>()).add(stored.getExactKey());
            }
            evictOverflowLocked();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void evictByTenant(String tenantId) {
        String cleanTenant = normalizeTenant(tenantId);
        lock.lock();
        try {
            Set<String> keys = tenantIndex.remove(cleanTenant);
            if (keys == null || keys.isEmpty()) {
                return;
            }
            for (String key : new ArrayList<>(keys)) {
                removeEntryLocked(key);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void clearAll() {
        lock.lock();
        try {
            entries.clear();
            tenantIndex.clear();
            scopeIndex.clear();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public long size() {
        long now = System.currentTimeMillis();
        lock.lock();
        try {
            pruneExpiredLocked(now);
            return entries.size();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public boolean isDistributed() {
        return false;
    }

    private void evictOverflowLocked() {
        if (entries.size() <= maxEntries) {
            return;
        }
        Iterator<Map.Entry<String, AiCacheEntry>> iterator = entries.entrySet().iterator();
        while (entries.size() > maxEntries && iterator.hasNext()) {
            Map.Entry<String, AiCacheEntry> eldest = iterator.next();
            iterator.remove();
            detachIndexesLocked(eldest.getValue());
        }
    }

    private void pruneExpiredLocked(long now) {
        Iterator<Map.Entry<String, AiCacheEntry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, AiCacheEntry> entry = iterator.next();
            if (entry.getValue().isExpired(now)) {
                iterator.remove();
                detachIndexesLocked(entry.getValue());
            }
        }
    }

    private void removeEntryLocked(String exactKey) {
        AiCacheEntry removed = entries.remove(exactKey);
        if (removed != null) {
            detachIndexesLocked(removed);
        }
    }

    private void detachIndexesLocked(AiCacheEntry entry) {
        if (entry == null) {
            return;
        }
        String tenant = normalizeTenant(entry.getTenantId());
        Set<String> tKeys = tenantIndex.get(tenant);
        if (tKeys != null) {
            tKeys.remove(entry.getExactKey());
            if (tKeys.isEmpty()) {
                tenantIndex.remove(tenant);
            }
        }
        if (StringUtils.hasText(entry.getScopeKey())) {
            Set<String> sKeys = scopeIndex.get(entry.getScopeKey());
            if (sKeys != null) {
                sKeys.remove(entry.getExactKey());
                if (sKeys.isEmpty()) {
                    scopeIndex.remove(entry.getScopeKey());
                }
            }
        }
    }

    private static String normalizeTenant(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.trim() : "default";
    }
}
