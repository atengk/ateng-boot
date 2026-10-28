package io.github.atengk.boot.satoken.cache;

import io.github.atengk.boot.redis.cache.spi.TwoLevelCache;
import io.github.atengk.boot.redis.cache.twolevel.TwoLevelCacheManager;
import io.github.atengk.boot.redis.cache.twolevel.l1.L1MemoryCache;
import io.github.atengk.boot.satoken.cluster.broadcaster.SaTokenClusterBroadcaster;
import io.github.atengk.boot.satoken.cluster.enums.SaTokenClusterEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 高性能两级级联权限与角色即时热刷新治理服务
 * <p>
 * 采用自适应适配器模式（Adaptive Adapter Pattern）：
 * 1. 当容器中检测到 {@link TwoLevelCacheManager} 时，100% 委派给 {@link TwoLevelCache}，
 *    全面复用其 64-条带并发锁（消除队头阻塞）、本地 LRU/Caffeine 微缓存、防雪崩 Jitter TTL、防穿透哨兵与 Redis Pub/Sub 广播驱逐；
 * 2. 当容器未注入 {@link TwoLevelCacheManager} 但存在 {@link StringRedisTemplate} 时，降级为常规单层 Redis 缓存；
 * 3. 当未引入 Redis 的纯单机/H2 单测环境下，平滑回退至内置有界 LRU 内存缓存，保障 100% 离线自洽。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
public class PermissionCacheService {

    public static final String CACHE_SPACE_NAME = "satoken:permissions";
    public static final String CACHE_PREFIX = "ateng:satoken:cache:";
    public static final String USER_ROLES_PREFIX = CACHE_PREFIX + "user-roles:";
    public static final String ROLE_PERMS_PREFIX = CACHE_PREFIX + "role-perms:";
    public static final String USER_PERMS_PREFIX = CACHE_PREFIX + "user-perms:";

    private final ObjectProvider<TwoLevelCacheManager> twoLevelCacheManagerProvider;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectProvider<SaTokenClusterBroadcaster> broadcasterProvider;

    private volatile PermissionCacheAdapter adapter;

    public PermissionCacheService() {
        this(null, null, null);
    }

    public PermissionCacheService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this(null, redisTemplateProvider, null);
    }

    public PermissionCacheService(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                  ObjectProvider<SaTokenClusterBroadcaster> broadcasterProvider) {
        this(null, redisTemplateProvider, broadcasterProvider);
    }

    public PermissionCacheService(ObjectProvider<TwoLevelCacheManager> twoLevelCacheManagerProvider,
                                  ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                  ObjectProvider<SaTokenClusterBroadcaster> broadcasterProvider) {
        this.twoLevelCacheManagerProvider = twoLevelCacheManagerProvider;
        this.redisTemplateProvider = redisTemplateProvider;
        this.broadcasterProvider = broadcasterProvider;
    }

    /**
     * 适配器显式注入构造器（专供单元测试与定制化适配）
     *
     * @param adapter 权限缓存适配器
     */
    public PermissionCacheService(PermissionCacheAdapter adapter) {
        this.twoLevelCacheManagerProvider = null;
        this.redisTemplateProvider = null;
        this.broadcasterProvider = null;
        this.adapter = adapter;
    }

    /**
     * 读取指定缓存 key 的字符串值
     *
     * @param key 缓存键
     * @return 缓存值，不存在或已过期返回 null
     */
    public String get(String key) {
        if (key == null) {
            return null;
        }
        return getAdapter().get(key);
    }

    /**
     * 写入指定缓存 key 与过期时间
     *
     * @param key            缓存键
     * @param value          缓存值
     * @param timeoutSeconds 过期秒数
     */
    public void set(String key, String value, long timeoutSeconds) {
        if (key == null) {
            return;
        }
        getAdapter().set(key, value, timeoutSeconds);
    }

    /**
     * 写入指定缓存 key 与过期时间（Duration 强类型重载）
     *
     * @param key     缓存键
     * @param value   缓存值
     * @param timeout 过期时间
     */
    public void set(String key, String value, Duration timeout) {
        if (key == null) {
            return;
        }
        long seconds = (timeout != null && !timeout.isZero() && !timeout.isNegative()) ? timeout.toSeconds() : -1;
        set(key, value, seconds);
    }

    /**
     * 删除指定缓存键
     *
     * @param key 缓存键
     */
    public void delete(String key) {
        if (key == null) {
            return;
        }
        getAdapter().delete(key);
    }

    /**
     * 即时刷新指定用户的角色与直属权限缓存
     *
     * @param userId 用户唯一标识
     */
    public void refreshUserPermissions(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        String userRolesKey = USER_ROLES_PREFIX + userId;
        String userPermsKey = USER_PERMS_PREFIX + userId;

        delete(userRolesKey);
        delete(userPermsKey);
        clearLocalUserCache(userId);
        log.info("即时清理用户权限缓存完成: userId={}", userId);

        if (broadcasterProvider != null) {
            broadcasterProvider.ifAvailable(b -> b.broadcast(SaTokenClusterEventType.USER_PERMISSION_REFRESH, userId));
        }
    }

    /**
     * 即时刷新指定角色关联的权限码集合缓存
     *
     * @param roleCode 角色编码
     */
    public void refreshRolePermissions(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return;
        }
        String rolePermsKey = ROLE_PERMS_PREFIX + roleCode;
        delete(rolePermsKey);
        clearLocalRoleCache(roleCode);
        log.info("即时清理角色权限缓存完成: roleCode={}", roleCode);

        if (broadcasterProvider != null) {
            broadcasterProvider.ifAvailable(b -> b.broadcast(SaTokenClusterEventType.ROLE_PERMISSION_REFRESH, roleCode));
        }
    }

    /**
     * 清理全域两级权限与角色缓存
     */
    public void clearAll() {
        getAdapter().clearAll();
        log.info("全域两级权限缓存已全部清空");

        if (broadcasterProvider != null) {
            broadcasterProvider.ifAvailable(b -> b.broadcast(SaTokenClusterEventType.ALL_CACHE_CLEAR, null));
        }
    }

    /**
     * 精准清理指定用户的本地内存权限缓存（异地节点协同）
     *
     * @param userId 用户唯一标识
     */
    public void clearLocalUserCache(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        getAdapter().evictLocal(USER_ROLES_PREFIX + userId);
        getAdapter().evictLocal(USER_PERMS_PREFIX + userId);
    }

    /**
     * 精准清理指定角色的本地内存权限缓存（异地节点协同）
     *
     * @param roleCode 角色编码
     */
    public void clearLocalRoleCache(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return;
        }
        getAdapter().evictLocal(ROLE_PERMS_PREFIX + roleCode);
    }

    /**
     * 清理本地所有内存权限缓存（异地节点协同）
     */
    public void clearLocalAllCache() {
        getAdapter().clearLocal();
    }

    /**
     * 清理指定用户的角色与直属权限缓存（别名方法）
     *
     * @param userId 用户唯一标识
     */
    public void clearUserCache(String userId) {
        refreshUserPermissions(userId);
    }

    /**
     * 清理指定用户的角色与直属权限缓存（Long 用户标识重载）
     *
     * @param userId 用户唯一标识
     */
    public void clearUserCache(Long userId) {
        if (userId != null) {
            refreshUserPermissions(String.valueOf(userId));
        }
    }

    /**
     * 清理指定角色的权限码集合缓存（别名方法）
     *
     * @param roleCode 角色编码
     */
    public void clearRoleCache(String roleCode) {
        refreshRolePermissions(roleCode);
    }

    /**
     * 清理全域两级权限与角色缓存（别名方法）
     */
    public void clearAllCache() {
        clearAll();
    }

    /**
     * 获取底层本地内存缓存容器大小（辅助调试与监控）
     *
     * @return 本地缓存项数量
     */
    public int getLocalCacheSize() {
        return getAdapter().getLocalCacheSize();
    }

    /**
     * 获取当前生效的权限缓存适配器（双重检查加锁单例懒加载）
     */
    public PermissionCacheAdapter getAdapter() {
        PermissionCacheAdapter current = this.adapter;
        if (current == null) {
            synchronized (this) {
                current = this.adapter;
                if (current == null) {
                    this.adapter = current = resolveAdapter();
                }
            }
        }
        return current;
    }

    private PermissionCacheAdapter resolveAdapter() {
        if (twoLevelCacheManagerProvider != null) {
            try {
                TwoLevelCacheManager manager = twoLevelCacheManagerProvider.getIfAvailable();
                if (manager != null) {
                    TwoLevelCache tlc = manager.getTwoLevelCache(CACHE_SPACE_NAME);
                    if (tlc != null) {
                        log.info("PermissionCacheService 成功启用 TwoLevelCacheManager 两级权限缓存空间 [{}]", CACHE_SPACE_NAME);
                        return new TwoLevelPermissionCacheAdapter(tlc);
                    }
                }
            } catch (Throwable t) {
                log.warn("无法装载 TwoLevelCacheManager，降级检查其他缓存模式: {}", t.getMessage());
            }
        }

        if (redisTemplateProvider != null) {
            try {
                StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
                if (redisTemplate != null) {
                    log.info("PermissionCacheService 启用 StringRedisTemplate 单层分布式权限缓存");
                    return new RedisPermissionCacheAdapter(redisTemplate);
                }
            } catch (Throwable t) {
                log.warn("无法装载 StringRedisTemplate，降级为内存模式: {}", t.getMessage());
            }
        }

        log.info("PermissionCacheService 启用原生 JVM 有界内存缓存降级模式");
        return new InMemoryPermissionCacheAdapter();
    }

    /**
     * 权限缓存底层存储适配契约
     */
    public interface PermissionCacheAdapter {
        String get(String key);
        void set(String key, String value, long timeoutSeconds);
        void delete(String key);
        void clearAll();
        void evictLocal(String key);
        void clearLocal();
        int getLocalCacheSize();
    }

    /**
     * ateng-boot-redis 两级缓存适配器（L1 本地内存 + L2 Redis + 64条带锁 + Pub/Sub 广播驱逐）
     */
    public static class TwoLevelPermissionCacheAdapter implements PermissionCacheAdapter {
        private final TwoLevelCache twoLevelCache;

        public TwoLevelPermissionCacheAdapter(TwoLevelCache twoLevelCache) {
            this.twoLevelCache = twoLevelCache;
        }

        @Override
        public String get(String key) {
            return twoLevelCache.get(key, String.class);
        }

        @Override
        public void set(String key, String value, long timeoutSeconds) {
            if (timeoutSeconds > 0) {
                twoLevelCache.put(key, value, Duration.ofSeconds(timeoutSeconds));
            } else {
                twoLevelCache.put(key, value);
            }
        }

        @Override
        public void delete(String key) {
            twoLevelCache.evict(key);
        }

        @Override
        public void clearAll() {
            twoLevelCache.clear();
        }

        @Override
        public void evictLocal(String key) {
            twoLevelCache.evictLocal(key);
        }

        @Override
        public void clearLocal() {
            twoLevelCache.clearLocal();
        }

        @Override
        public int getLocalCacheSize() {
            if (twoLevelCache.getNativeCache() instanceof L1MemoryCache l1) {
                return (int) l1.size();
            }
            return 0;
        }

        public TwoLevelCache getTwoLevelCache() {
            return twoLevelCache;
        }
    }

    /**
     * 基于 StringRedisTemplate 的常规单层分布式缓存适配器（带本地 L1 LRU 微缓存防穿透）
     */
    public static class RedisPermissionCacheAdapter implements PermissionCacheAdapter {
        private final StringRedisTemplate redisTemplate;
        private final InMemoryPermissionCacheAdapter localFallback = new InMemoryPermissionCacheAdapter();

        public RedisPermissionCacheAdapter(StringRedisTemplate redisTemplate) {
            this.redisTemplate = redisTemplate;
        }

        @Override
        public String get(String key) {
            if (key == null) {
                return null;
            }
            // 1. 优先读取进程内 L1 微缓存
            String localVal = localFallback.get(key);
            if (localVal != null) {
                return localVal;
            }
            // 2. 本地未命中，穿透读取 L2 Redis
            String remoteVal = redisTemplate.opsForValue().get(key);
            if (remoteVal != null) {
                // 回填本地 L1 微缓存（5 秒短 TTL）
                localFallback.set(key, remoteVal, 5);
            }
            return remoteVal;
        }

        @Override
        public void set(String key, String value, long timeoutSeconds) {
            if (key == null) {
                return;
            }
            if (timeoutSeconds > 0) {
                redisTemplate.opsForValue().set(key, value, timeoutSeconds, TimeUnit.SECONDS);
            } else {
                redisTemplate.opsForValue().set(key, value);
            }
            // 同步写入本地 L1 微缓存（最长 5 秒短 TTL 防脱靶）
            long localTtl = timeoutSeconds > 0 ? Math.min(timeoutSeconds, 5) : 5;
            localFallback.set(key, value, localTtl);
        }

        @Override
        public void delete(String key) {
            if (key == null) {
                return;
            }
            redisTemplate.delete(key);
            localFallback.delete(key);
        }

        @Override
        public void clearAll() {
            ScanOptions scanOptions = ScanOptions.scanOptions().match(CACHE_PREFIX + "*").count(1000).build();
            Set<String> keys = new HashSet<>();
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                try (Cursor<byte[]> cursor = connection.keyCommands().scan(scanOptions)) {
                    while (cursor.hasNext()) {
                        String k = redisTemplate.getStringSerializer().deserialize(cursor.next());
                        if (k != null && !k.isBlank()) {
                            keys.add(k);
                        }
                    }
                } catch (Exception e) {
                    log.warn("清理权限缓存游标扫描异常: ", e);
                }
                return null;
            });
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
            localFallback.clearAll();
        }

        @Override
        public void evictLocal(String key) {
            localFallback.evictLocal(key);
        }

        @Override
        public void clearLocal() {
            localFallback.clearLocal();
        }

        @Override
        public int getLocalCacheSize() {
            return localFallback.getLocalCacheSize();
        }
    }

    /**
     * 原生 JVM 有界 LRU 内存缓存适配器（无 Redis 单机/单测离线自洽模式）
     */
    public static class InMemoryPermissionCacheAdapter implements PermissionCacheAdapter {
        private static final int MAX_LOCAL_CACHE_SIZE = 10000;
        private final ReentrantLock lock = new ReentrantLock();
        private final Map<String, LocalCacheItem> localMap = new LinkedHashMap<>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, LocalCacheItem> eldest) {
                return size() > MAX_LOCAL_CACHE_SIZE;
            }
        };

        @Override
        public String get(String key) {
            if (key == null) {
                return null;
            }
            lock.lock();
            try {
                LocalCacheItem item = localMap.get(key);
                if (item != null) {
                    if (!item.isExpired()) {
                        return item.getValue();
                    }
                    localMap.remove(key);
                }
                return null;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void set(String key, String value, long timeoutSeconds) {
            if (key == null) {
                return;
            }
            long expireAt = timeoutSeconds > 0 ? System.currentTimeMillis() + (timeoutSeconds * 1000) : -1;
            lock.lock();
            try {
                localMap.put(key, new LocalCacheItem(value, expireAt));
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void delete(String key) {
            if (key == null) {
                return;
            }
            lock.lock();
            try {
                localMap.remove(key);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void clearAll() {
            lock.lock();
            try {
                localMap.clear();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void evictLocal(String key) {
            delete(key);
        }

        @Override
        public void clearLocal() {
            clearAll();
        }

        @Override
        public int getLocalCacheSize() {
            lock.lock();
            try {
                return localMap.size();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 本地内存缓存项封装
     */
    public static class LocalCacheItem {
        private final String value;
        private final long expireAt;

        public LocalCacheItem(String value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }

        public String getValue() {
            return value;
        }

        public boolean isExpired() {
            return expireAt > 0 && System.currentTimeMillis() > expireAt;
        }
    }
}
