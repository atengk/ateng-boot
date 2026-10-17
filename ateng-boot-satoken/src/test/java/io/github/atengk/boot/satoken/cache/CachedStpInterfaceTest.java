package io.github.atengk.boot.satoken.cache;

import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * CachedStpInterface 两级权限缓存引擎单元测试
 * 验证两级缓存切片命中、空哨兵防穿透、多角色与直属权限去重合并、以及即时热清理
 *
 * @author Ateng
 * @since 2026-10-17
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CachedStpInterface 两级权限缓存引擎单元测试")
class CachedStpInterfaceTest {

    @Mock
    private UserRolePermissionLoader loader;

    @Mock
    private ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    private PermissionCacheService cacheService;
    private SaTokenProperties properties;
    private CachedStpInterface cachedStpInterface;

    @BeforeEach
    void setUp() {
        // 单机本地内存模式运行 cacheService
        when(redisTemplateProvider.getIfAvailable()).thenReturn(null);
        cacheService = new PermissionCacheService(redisTemplateProvider);

        properties = new SaTokenProperties();
        properties.getPermissionCache().setTimeoutSeconds(300);
        properties.getPermissionCache().setEmptyTtlSeconds(60);

        cachedStpInterface = new CachedStpInterface(loader, cacheService, properties);
    }

    @Test
    @DisplayName("1. 角色缓存命中：首次查询回源 loader 并写入缓存，二次查询直接从缓存命中")
    void testGetRoleListCached() {
        when(loader.loadRolesByUserId("1001")).thenReturn(List.of("admin", "manager"));

        // 第一次调用：回源 loader
        List<String> roles1 = cachedStpInterface.getRoleList("1001", "login");
        assertThat(roles1).containsExactly("admin", "manager");
        verify(loader, times(1)).loadRolesByUserId("1001");

        // 第二次调用：直接从缓存命中，loader 调用次数仍为 1
        List<String> roles2 = cachedStpInterface.getRoleList("1001", "login");
        assertThat(roles2).containsExactly("admin", "manager");
        verify(loader, times(1)).loadRolesByUserId("1001");
    }

    @Test
    @DisplayName("2. 空哨兵防穿透：用户无任何角色时，写入 __EMPTY__ 哨兵，后续查询直接返回空列表不再击穿 loader")
    void testAntiPenetrationWithEmptySentinel() {
        when(loader.loadRolesByUserId("none-user")).thenReturn(List.of());

        // 第一次调用：发现为空，写入哨兵
        List<String> roles1 = cachedStpInterface.getRoleList("none-user", "login");
        assertThat(roles1).isEmpty();
        verify(loader, times(1)).loadRolesByUserId("none-user");

        // 验证底层缓存已写入哨兵
        String cachedVal = cacheService.get(PermissionCacheService.USER_ROLES_PREFIX + "none-user");
        assertThat(cachedVal).isEqualTo(CachedStpInterface.EMPTY_SENTINEL);

        // 第二次调用：命中哨兵直接返回空列表，loader 未被二次击穿
        List<String> roles2 = cachedStpInterface.getRoleList("none-user", "login");
        assertThat(roles2).isEmpty();
        verify(loader, times(1)).loadRolesByUserId("none-user");
    }

    @Test
    @DisplayName("3. 两级权限汇总与去重：聚合所有角色的权限与直属权限")
    void testTwoLevelPermissionAggregationAndDeduplication() {
        when(loader.loadRolesByUserId("2001")).thenReturn(List.of("role_a", "role_b"));
        when(loader.loadPermissionsByRole("role_a")).thenReturn(List.of("user:query", "user:export"));
        when(loader.loadPermissionsByRole("role_b")).thenReturn(List.of("user:export", "order:view"));
        when(loader.loadDirectPermissionsByUserId("2001")).thenReturn(List.of("report:view"));

        List<String> permissions = cachedStpInterface.getPermissionList("2001", "login");
        // 去重后应包含 user:query, user:export, order:view, report:view
        assertThat(permissions).containsExactlyInAnyOrder("user:query", "user:export", "order:view", "report:view");

        // 二次查询，全部从两级缓存获取，loader 不再被额外调用
        List<String> permissions2 = cachedStpInterface.getPermissionList("2001", "login");
        assertThat(permissions2).containsExactlyInAnyOrder("user:query", "user:export", "order:view", "report:view");

        verify(loader, times(1)).loadRolesByUserId("2001");
        verify(loader, times(1)).loadPermissionsByRole("role_a");
        verify(loader, times(1)).loadPermissionsByRole("role_b");
        verify(loader, times(1)).loadDirectPermissionsByUserId("2001");
    }

    @Test
    @DisplayName("4. 权限即时热刷新：修改角色权限后调用 refreshRolePermissions，缓存即刻失效并重新加载最新权限")
    void testHotRefreshRolePermissions() {
        when(loader.loadRolesByUserId("3001")).thenReturn(List.of("admin"));
        when(loader.loadPermissionsByRole("admin")).thenReturn(List.of("user:query"));
        when(loader.loadDirectPermissionsByUserId("3001")).thenReturn(List.of());

        // 首次加载
        List<String> perms1 = cachedStpInterface.getPermissionList("3001", "login");
        assertThat(perms1).containsExactly("user:query");

        // 管理员在后台给 admin 角色增加了 "user:delete" 权限，并触发即时刷新
        when(loader.loadPermissionsByRole("admin")).thenReturn(List.of("user:query", "user:delete"));
        cacheService.refreshRolePermissions("admin");

        // 再次获取，权限秒级更新
        List<String> perms2 = cachedStpInterface.getPermissionList("3001", "login");
        assertThat(perms2).containsExactlyInAnyOrder("user:query", "user:delete");
        verify(loader, times(2)).loadPermissionsByRole("admin");
    }

    @Test
    @DisplayName("5. 用户角色即时热刷新：分配新角色后调用 refreshUserPermissions，用户角色缓存即刻清理")
    void testHotRefreshUserRoles() {
        when(loader.loadRolesByUserId("4001")).thenReturn(List.of("common_user"));

        List<String> roles1 = cachedStpInterface.getRoleList("4001", "login");
        assertThat(roles1).containsExactly("common_user");

        // 分配新角色并热刷新
        when(loader.loadRolesByUserId("4001")).thenReturn(List.of("common_user", "vip_user"));
        cacheService.refreshUserPermissions("4001");

        List<String> roles2 = cachedStpInterface.getRoleList("4001", "login");
        assertThat(roles2).containsExactly("common_user", "vip_user");
        verify(loader, times(2)).loadRolesByUserId("4001");
    }

    @Test
    @DisplayName("6. 全域清空：clearAll 清理所有两级缓存")
    void testClearAll() {
        when(loader.loadRolesByUserId("5001")).thenReturn(List.of("role_x"));
        cachedStpInterface.getRoleList("5001", "login");

        assertThat(cacheService.getLocalCacheSize()).isGreaterThan(0);

        cacheService.clearAll();
        assertThat(cacheService.getLocalCacheSize()).isEqualTo(0);
    }
}
