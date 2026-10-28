package io.github.atengk.boot.satoken.cache;

import io.github.atengk.boot.satoken.cluster.broadcaster.SaTokenClusterBroadcaster;
import io.github.atengk.boot.satoken.cluster.enums.SaTokenClusterEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 两级权限与角色缓存即时热刷新治理服务
 * 支持在用户分配新角色、或系统管理员动态修改角色菜单权限时，秒级清理对应粒度的缓存；
 * 自适应支持 Redis 分布式集群模式与单机内存降级模式，全链路杜绝阻塞。
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
public class PermissionCacheService {

    public static final String CACHE_PREFIX = "ateng:satoken:cache:";
    public static final String USER_ROLES_PREFIX = CACHE_PREFIX + "user-roles:";
    public static final String ROLE_PERMS_PREFIX = CACHE_PREFIX + "role-perms:";
    public static final String USER_PERMS_PREFIX = CACHE_PREFIX + "user-perms:";

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectProvider<SaTokenClusterBroadcaster> broadcasterProvider;

    // 单机内存降级缓存（当 Redis 不可用时生效，带简易过期时间戳防内存无限增长）
    private static final Map<String, LocalCacheItem> LOCAL_MEMORY_CACHE = new ConcurrentHashMap<>();

    public PermissionCacheService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this(redisTemplateProvider, null);
    }

    public PermissionCacheService(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                  ObjectProvider<SaTokenClusterBroadcaster> broadcasterProvider) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.broadcasterProvider = broadcasterProvider;
    }

    /**
     * 读取指定缓存 key 的字符串值
     *
     * @param key 缓存键
     * @return 缓存值，不存在或已过期返回 null
     */
    public String get(String key) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate != null) {
            return redisTemplate.opsForValue().get(key);
        }
        LocalCacheItem item = LOCAL_MEMORY_CACHE.get(key);
        if (item != null) {
            if (!item.isExpired()) {
                return item.getValue();
            }
            LOCAL_MEMORY_CACHE.remove(key);
        }
        return null;
    }

    /**
     * 写入指定缓存 key 与过期时间
     *
     * @param key            缓存键
     * @param value          缓存值
     * @param timeoutSeconds 过期秒数
     */
    public void set(String key, String value, long timeoutSeconds) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate != null) {
            if (timeoutSeconds > 0) {
                redisTemplate.opsForValue().set(key, value, timeoutSeconds, TimeUnit.SECONDS);
            } else {
                redisTemplate.opsForValue().set(key, value);
            }
        } else {
            long expireAt = timeoutSeconds > 0 ? System.currentTimeMillis() + (timeoutSeconds * 1000) : -1;
            LOCAL_MEMORY_CACHE.put(key, new LocalCacheItem(value, expireAt));
        }
    }

    /**
     * 删除指定缓存键
     *
     * @param key 缓存键
     */
    public void delete(String key) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate != null) {
            redisTemplate.delete(key);
        } else {
            LOCAL_MEMORY_CACHE.remove(key);
        }
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
     * 清理全域两级权限与角色缓存（采用 SCAN 增量游标扫描，杜绝 KEYS * 阻塞）
     */
    public void clearAll() {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate != null) {
            ScanOptions scanOptions = ScanOptions.scanOptions().match(CACHE_PREFIX + "*").count(1000).build();
            Set<String> keys = new HashSet<>();
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                try (Cursor<byte[]> cursor = connection.keyCommands().scan(scanOptions)) {
                    while (cursor.hasNext()) {
                        String key = redisTemplate.getStringSerializer().deserialize(cursor.next());
                        if (key != null && !key.isBlank()) {
                            keys.add(key);
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
        }
        clearLocalAllCache();
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
        LOCAL_MEMORY_CACHE.remove(USER_ROLES_PREFIX + userId);
        LOCAL_MEMORY_CACHE.remove(USER_PERMS_PREFIX + userId);
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
        LOCAL_MEMORY_CACHE.remove(ROLE_PERMS_PREFIX + roleCode);
    }

    /**
     * 清理本地所有内存权限缓存（异地节点协同）
     */
    public void clearLocalAllCache() {
        LOCAL_MEMORY_CACHE.clear();
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
     * 获取底层本地内存缓存容器大小（单机模式辅助调试）
     *
     * @return 本地缓存项数量
     */
    public int getLocalCacheSize() {
        return LOCAL_MEMORY_CACHE.size();
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
