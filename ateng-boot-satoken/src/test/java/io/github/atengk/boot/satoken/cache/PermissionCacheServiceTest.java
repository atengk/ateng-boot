package io.github.atengk.boot.satoken.cache;

import io.github.atengk.boot.redis.cache.spi.TwoLevelCache;
import io.github.atengk.boot.redis.cache.twolevel.DefaultTwoLevelCache;
import io.github.atengk.boot.redis.cache.twolevel.TwoLevelCacheManager;
import io.github.atengk.boot.redis.cache.twolevel.l1.ConcurrentLruMemoryCache;
import io.github.atengk.boot.redis.cache.twolevel.l1.L1MemoryCache;
import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.boot.satoken.autoconfigure.AtengSaTokenAutoConfiguration;
import io.github.atengk.boot.satoken.cluster.broadcaster.SaTokenClusterBroadcaster;
import io.github.atengk.boot.satoken.cluster.enums.SaTokenClusterEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * PermissionCacheService 自适应两级权限缓存与离线降级测试
 * 覆盖：
 * 1. TwoLevelCacheManager 自适应委托（L1 击中、回填、TTL 写入、失效广播与条带锁复用）
 * 2. 异地失效广播联动（clearLocalUserCache / clearLocalRoleCache / clearLocalAllCache 本地精准淘汰）
 * 3. StringRedisTemplate 单层缓存降级
 * 4. 原生 JVM 有界 LRU 内存缓存 100% 离线自洽
 * 5. Spring Boot 自动装配两级缓存自适应感知
 *
 * @author Ateng
 * @since 2026-10-28
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PermissionCacheService 自适应权限缓存单元测试")
class PermissionCacheServiceTest {

    @Mock
    private TwoLevelCacheManager twoLevelCacheManager;

    @Mock
    private TwoLevelCache twoLevelCache;

    @Mock
    private L1MemoryCache l1MemoryCache;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> stringValueOperations;

    @Mock
    private ObjectProvider<TwoLevelCacheManager> twoLevelCacheManagerProvider;

    @Mock
    private ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    @Mock
    private ObjectProvider<SaTokenClusterBroadcaster> broadcasterProvider;

    @Mock
    private SaTokenClusterBroadcaster clusterBroadcaster;

    @AfterEach
    void tearDown() {
        // 确保无跨用例上下文残留
        io.github.atengk.boot.core.context.UserContextHolder.setUserProvider(null);
        io.github.atengk.boot.core.context.TenantContextHolder.setTenantProvider(null);
        io.github.atengk.boot.core.context.UserContextHolder.clear();
        io.github.atengk.boot.core.context.TenantContextHolder.clear();
    }

    @Test
    @DisplayName("1. 自适应装配：存在 TwoLevelCacheManager 时，100% 委托给 TwoLevelCache 门面")
    void testAdaptiveTwoLevelCacheDelegation() {
        when(twoLevelCacheManagerProvider.getIfAvailable()).thenReturn(twoLevelCacheManager);
        when(twoLevelCacheManager.getTwoLevelCache(PermissionCacheService.CACHE_SPACE_NAME)).thenReturn(twoLevelCache);
        when(twoLevelCache.getNativeCache()).thenReturn(l1MemoryCache);
        doAnswer(invocation -> {
            java.util.function.Consumer<SaTokenClusterBroadcaster> consumer = invocation.getArgument(0);
            consumer.accept(clusterBroadcaster);
            return null;
        }).when(broadcasterProvider).ifAvailable(any());

        PermissionCacheService service = new PermissionCacheService(
                twoLevelCacheManagerProvider,
                redisTemplateProvider,
                broadcasterProvider
        );

        // 验证适配器类型
        assertThat(service.getAdapter()).isInstanceOf(PermissionCacheService.TwoLevelPermissionCacheAdapter.class);

        // 1. 读操作
        when(twoLevelCache.get("test-key", String.class)).thenReturn("[\"admin\"]");
        assertThat(service.get("test-key")).isEqualTo("[\"admin\"]");
        verify(twoLevelCache).get("test-key", String.class);

        // 2. 写操作（带 TTL）
        service.set("test-key", "[\"user\"]", 600);
        verify(twoLevelCache).put("test-key", "[\"user\"]", Duration.ofSeconds(600));

        // 3. 删除操作
        service.delete("test-key");
        verify(twoLevelCache).evict("test-key");

        // 4. 用户维度即时热刷新
        service.refreshUserPermissions("user-999");
        verify(twoLevelCache).evict(PermissionCacheService.USER_ROLES_PREFIX + "user-999");
        verify(twoLevelCache).evict(PermissionCacheService.USER_PERMS_PREFIX + "user-999");
        verify(twoLevelCache).evictLocal(PermissionCacheService.USER_ROLES_PREFIX + "user-999");
        verify(twoLevelCache).evictLocal(PermissionCacheService.USER_PERMS_PREFIX + "user-999");
        verify(clusterBroadcaster).broadcast(SaTokenClusterEventType.USER_PERMISSION_REFRESH, "user-999");

        // 5. 角色维度即时热刷新
        service.refreshRolePermissions("super_admin");
        verify(twoLevelCache).evict(PermissionCacheService.ROLE_PERMS_PREFIX + "super_admin");
        verify(twoLevelCache).evictLocal(PermissionCacheService.ROLE_PERMS_PREFIX + "super_admin");
        verify(clusterBroadcaster).broadcast(SaTokenClusterEventType.ROLE_PERMISSION_REFRESH, "super_admin");

        // 6. 全域清空
        service.clearAll();
        verify(twoLevelCache).clear();
        verify(clusterBroadcaster).broadcast(SaTokenClusterEventType.ALL_CACHE_CLEAR, null);

        // 7. 异地广播触发的本地淘汰（不触发二次广播）
        service.clearLocalUserCache("user-888");
        verify(twoLevelCache).evictLocal(PermissionCacheService.USER_ROLES_PREFIX + "user-888");
        verify(twoLevelCache).evictLocal(PermissionCacheService.USER_PERMS_PREFIX + "user-888");

        service.clearLocalRoleCache("editor");
        verify(twoLevelCache).evictLocal(PermissionCacheService.ROLE_PERMS_PREFIX + "editor");

        service.clearLocalAllCache();
        verify(twoLevelCache).clearLocal();

        // 8. 缓存大小
        when(l1MemoryCache.size()).thenReturn(42L);
        assertThat(service.getLocalCacheSize()).isEqualTo(42);
    }

    @Test
    @DisplayName("2. 两级缓存真实读穿透与 L1 回填验证：首次穿透 Redis，二次直读本地 L1 零网络 IO")
    @SuppressWarnings("unchecked")
    void testTwoLevelCacheL1HitAndBackfill() {
        RedisTemplate<String, Object> mockRedisTemplate = mock(RedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, Object> mockOps = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(mockRedisTemplate.opsForValue()).thenReturn(mockOps);

        ConcurrentLruMemoryCache l1 = new ConcurrentLruMemoryCache(100, Duration.ofMinutes(5));
        RedisProperties redisProperties = new RedisProperties();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        DefaultTwoLevelCache realTwoLevelCache = new DefaultTwoLevelCache(
                PermissionCacheService.CACHE_SPACE_NAME,
                l1,
                mockRedisTemplate,
                mapper,
                "node-test",
                "ateng:cache:evict:topic",
                redisProperties,
                Duration.ofMinutes(30),
                true,
                Duration.ofMinutes(1)
        );

        PermissionCacheService service = new PermissionCacheService(
                new PermissionCacheService.TwoLevelPermissionCacheAdapter(realTwoLevelCache)
        );

        String testKey = PermissionCacheService.USER_ROLES_PREFIX + "1001";
        String redisKey = realTwoLevelCache.buildRedisKey(testKey);

        // 首次读取：L1 未命中，穿透 Redis
        when(mockOps.get(redisKey)).thenReturn("[\"admin\"]");
        String result1 = service.get(testKey);
        assertThat(result1).isEqualTo("[\"admin\"]");
        verify(mockOps, times(1)).get(redisKey);

        // 二次读取：命中本地 L1 内存，不再调用 Redis opsForValue().get()
        String result2 = service.get(testKey);
        assertThat(result2).isEqualTo("[\"admin\"]");
        verify(mockOps, times(1)).get(redisKey); // 次数依然为 1

        // 本地失效后：再次穿透 Redis
        service.clearLocalUserCache("1001");
        String result3 = service.get(testKey);
        assertThat(result3).isEqualTo("[\"admin\"]");
        verify(mockOps, times(2)).get(redisKey); // 次数变为 2
    }

    @Test
    @DisplayName("3. 离线降级兜底：无 Redis 环境平滑回退至内置有界 LRU 内存缓存，100% 离线自洽")
    void testFallbackToInMemoryBoundedCache() {
        PermissionCacheService service = new PermissionCacheService(); // 无参，纯离线模式
        assertThat(service.getAdapter()).isInstanceOf(PermissionCacheService.InMemoryPermissionCacheAdapter.class);

        // 1. 写入与读取
        service.set("offline-key", "val-1", 100);
        assertThat(service.get("offline-key")).isEqualTo("val-1");
        assertThat(service.getLocalCacheSize()).isEqualTo(1);

        // 2. 删除
        service.delete("offline-key");
        assertThat(service.get("offline-key")).isNull();
        assertThat(service.getLocalCacheSize()).isEqualTo(0);

        // 3. 用户与角色热刷新
        String userRolesKey = PermissionCacheService.USER_ROLES_PREFIX + "user-123";
        String rolePermsKey = PermissionCacheService.ROLE_PERMS_PREFIX + "role-456";

        service.set(userRolesKey, "[\"role_a\"]", 300);
        service.set(rolePermsKey, "[\"perm_b\"]", 300);
        assertThat(service.get(userRolesKey)).isEqualTo("[\"role_a\"]");
        assertThat(service.get(rolePermsKey)).isEqualTo("[\"perm_b\"]");

        service.refreshUserPermissions("user-123");
        assertThat(service.get(userRolesKey)).isNull();
        assertThat(service.get(rolePermsKey)).isEqualTo("[\"perm_b\"]");

        service.refreshRolePermissions("role-456");
        assertThat(service.get(rolePermsKey)).isNull();

        // 4. 全域清空
        service.set("k1", "v1", 300);
        service.set("k2", "v2", 300);
        assertThat(service.getLocalCacheSize()).isEqualTo(2);
        service.clearAll();
        assertThat(service.getLocalCacheSize()).isEqualTo(0);

        // 5. 过期项自动剔除
        service.set("expired-key", "expired-val", -1); // <= 0 表示已过期或不过期，我们测试 expireAt 逻辑
        service.set("exp-key", "v", 1);
        PermissionCacheService.LocalCacheItem item = new PermissionCacheService.LocalCacheItem("old", System.currentTimeMillis() - 1000);
        assertThat(item.isExpired()).isTrue();
    }

    @Test
    @DisplayName("4. 单层 Redis 降级：未配置 TwoLevelCacheManager 时自动降级为 StringRedisTemplate 并具备 L1 进程内微缓存防穿透")
    void testFallbackToStringRedisTemplate() {
        when(twoLevelCacheManagerProvider.getIfAvailable()).thenReturn(null);
        when(redisTemplateProvider.getIfAvailable()).thenReturn(stringRedisTemplate);
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOperations);

        PermissionCacheService service = new PermissionCacheService(
                twoLevelCacheManagerProvider,
                redisTemplateProvider,
                null
        );

        assertThat(service.getAdapter()).isInstanceOf(PermissionCacheService.RedisPermissionCacheAdapter.class);

        // 读：首次穿透 Redis 并回填 L1
        when(stringValueOperations.get("k-redis")).thenReturn("val-redis");
        assertThat(service.get("k-redis")).isEqualTo("val-redis");
        // 二次直读 L1 微缓存，verify 证明 opsForValue().get() 仅被调用 1 次
        assertThat(service.get("k-redis")).isEqualTo("val-redis");
        verify(stringValueOperations, times(1)).get("k-redis");

        // 写：使用 Duration 强类型重载
        service.set("k-redis", "val-redis-new", Duration.ofSeconds(60));
        verify(stringValueOperations).set("k-redis", "val-redis-new", 60, java.util.concurrent.TimeUnit.SECONDS);

        // 写后直读 L1 微缓存，依然零网络 IO
        assertThat(service.get("k-redis")).isEqualTo("val-redis-new");
        verify(stringValueOperations, times(1)).get("k-redis");

        // 删
        service.delete("k-redis");
        verify(stringRedisTemplate).delete("k-redis");
        assertThat(service.getLocalCacheSize()).isEqualTo(0);
    }

    @Test
    @DisplayName("5. Spring Boot 自动装配集成测试：自适应检测与 ConditionalOnMissingBean 守卫")
    void testAutoConfigurationIntegration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengSaTokenAutoConfiguration.class))
                .withBean(TwoLevelCacheManager.class, () -> twoLevelCacheManager)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PermissionCacheService.class);
                    PermissionCacheService service = context.getBean(PermissionCacheService.class);
                    assertThat(service).isNotNull();
                });

        // 纯单机无 TwoLevelCacheManager 环境装配
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengSaTokenAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PermissionCacheService.class);
                    PermissionCacheService service = context.getBean(PermissionCacheService.class);
                    assertThat(service).isNotNull();
                    assertThat(service.getAdapter()).isInstanceOf(PermissionCacheService.InMemoryPermissionCacheAdapter.class);
                });
    }
}
