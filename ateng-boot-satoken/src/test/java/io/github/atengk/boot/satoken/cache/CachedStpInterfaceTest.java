package io.github.atengk.boot.satoken.cache;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.satoken.constant.SaTokenConstant;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
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
        properties.getPermissionCache().setTimeout(java.time.Duration.ofSeconds(300));
        properties.getPermissionCache().setEmptyTtl(java.time.Duration.ofSeconds(60));

        cachedStpInterface = new CachedStpInterface(loader, cacheService, properties);
    }

    @Test
    @DisplayName("1. 角色缓存命中：首次查询回源 loader 并写入缓存，二次查询直接从缓存命中")
    void testGetRoleListCached() {
        when(loader.loadRolesByUserId("1001", "login")).thenReturn(List.of("admin", "manager"));

        // 第一次调用：回源 loader
        List<String> roles1 = cachedStpInterface.getRoleList("1001", "login");
        assertThat(roles1).containsExactly("admin", "manager");
        verify(loader, times(1)).loadRolesByUserId("1001", "login");

        // 第二次调用：直接从缓存命中，loader 调用次数仍为 1
        List<String> roles2 = cachedStpInterface.getRoleList("1001", "login");
        assertThat(roles2).containsExactly("admin", "manager");
        verify(loader, times(1)).loadRolesByUserId("1001", "login");
    }

    @Test
    @DisplayName("2. 空哨兵防穿透：用户无任何角色时，写入 __EMPTY__ 哨兵，后续查询直接返回空列表不再击穿 loader")
    void testAntiPenetrationWithEmptySentinel() {
        when(loader.loadRolesByUserId("none-user", "login")).thenReturn(List.of());

        // 第一次调用：发现为空，写入哨兵
        List<String> roles1 = cachedStpInterface.getRoleList("none-user", "login");
        assertThat(roles1).isEmpty();
        verify(loader, times(1)).loadRolesByUserId("none-user", "login");

        // 验证底层缓存已写入哨兵
        String cachedVal = cacheService.get(PermissionCacheService.USER_ROLES_PREFIX + "none-user");
        assertThat(cachedVal).isEqualTo(CachedStpInterface.EMPTY_SENTINEL);

        // 第二次调用：命中哨兵直接返回空列表，loader 未被二次击穿
        List<String> roles2 = cachedStpInterface.getRoleList("none-user", "login");
        assertThat(roles2).isEmpty();
        verify(loader, times(1)).loadRolesByUserId("none-user", "login");
    }

    @Test
    @DisplayName("3. 两级权限汇总与去重：聚合所有角色的权限与直属权限")
    void testTwoLevelPermissionAggregationAndDeduplication() {
        when(loader.loadRolesByUserId("2001", "login")).thenReturn(List.of("role_a", "role_b"));
        when(loader.loadPermissionsByRole("role_a", "login")).thenReturn(List.of("user:query", "user:export"));
        when(loader.loadPermissionsByRole("role_b", "login")).thenReturn(List.of("user:export", "order:view"));
        when(loader.loadDirectPermissionsByUserId("2001", "login")).thenReturn(List.of("report:view"));

        List<String> permissions = cachedStpInterface.getPermissionList("2001", "login");
        // 去重后应包含 user:query, user:export, order:view, report:view
        assertThat(permissions).containsExactlyInAnyOrder("user:query", "user:export", "order:view", "report:view");

        // 二次查询，全部从两级缓存获取，loader 不再被额外调用
        List<String> permissions2 = cachedStpInterface.getPermissionList("2001", "login");
        assertThat(permissions2).containsExactlyInAnyOrder("user:query", "user:export", "order:view", "report:view");

        verify(loader, times(1)).loadRolesByUserId("2001", "login");
        verify(loader, times(1)).loadPermissionsByRole("role_a", "login");
        verify(loader, times(1)).loadPermissionsByRole("role_b", "login");
        verify(loader, times(1)).loadDirectPermissionsByUserId("2001", "login");
    }

    @Test
    @DisplayName("4. 权限即时热刷新：修改角色权限后调用 refreshRolePermissions，缓存即刻失效并重新加载最新权限")
    void testHotRefreshRolePermissions() {
        when(loader.loadRolesByUserId("3001", "login")).thenReturn(List.of("admin"));
        when(loader.loadPermissionsByRole("admin", "login")).thenReturn(List.of("user:query"));
        when(loader.loadDirectPermissionsByUserId("3001", "login")).thenReturn(List.of());

        // 首次加载
        List<String> perms1 = cachedStpInterface.getPermissionList("3001", "login");
        assertThat(perms1).containsExactly("user:query");

        // 管理员在后台给 admin 角色增加了 "user:delete" 权限，并触发即时刷新
        when(loader.loadPermissionsByRole("admin", "login")).thenReturn(List.of("user:query", "user:delete"));
        cacheService.refreshRolePermissions("admin");

        // 再次获取，权限秒级更新
        List<String> perms2 = cachedStpInterface.getPermissionList("3001", "login");
        assertThat(perms2).containsExactlyInAnyOrder("user:query", "user:delete");
        verify(loader, times(2)).loadPermissionsByRole("admin", "login");
    }

    @Test
    @DisplayName("5. 用户角色即时热刷新：分配新角色后调用 refreshUserPermissions，用户角色缓存即刻清理")
    void testHotRefreshUserRoles() {
        when(loader.loadRolesByUserId("4001", "login")).thenReturn(List.of("common_user"));

        List<String> roles1 = cachedStpInterface.getRoleList("4001", "login");
        assertThat(roles1).containsExactly("common_user");

        // 分配新角色并热刷新
        when(loader.loadRolesByUserId("4001", "login")).thenReturn(List.of("common_user", "vip_user"));
        cacheService.refreshUserPermissions("4001");

        List<String> roles2 = cachedStpInterface.getRoleList("4001", "login");
        assertThat(roles2).containsExactly("common_user", "vip_user");
        verify(loader, times(2)).loadRolesByUserId("4001", "login");
    }

    @Test
    @DisplayName("6. 全域清空：clearAll 清理所有两级缓存")
    void testClearAll() {
        when(loader.loadRolesByUserId("5001", "login")).thenReturn(List.of("role_x"));
        cachedStpInterface.getRoleList("5001", "login");

        assertThat(cacheService.getLocalCacheSize()).isGreaterThan(0);

        cacheService.clearAll();
        assertThat(cacheService.getLocalCacheSize()).isEqualTo(0);
    }

    @Test
    @DisplayName("7. 向后兼容验证：当自定义 loader 仅实现单参数方法时，默认方法正确回退调用单参数方法")
    void testFallbackToSingleArgMethodWhenDefaultInterfaceMethodUsed() {
        UserRolePermissionLoader fallbackLoader = new UserRolePermissionLoader() {
            @Override
            public List<String> loadRolesByUserId(String userId) {
                return List.of("legacy_role");
            }

            @Override
            public List<String> loadPermissionsByRole(String roleCode) {
                return List.of("legacy:perm");
            }

            @Override
            public List<String> loadDirectPermissionsByUserId(String userId) {
                return List.of("direct:perm");
            }
        };

        CachedStpInterface fallbackStp = new CachedStpInterface(fallbackLoader, cacheService, properties);
        List<String> roles = fallbackStp.getRoleList("9001", "admin");
        assertThat(roles).containsExactly("legacy_role");

        List<String> permissions = fallbackStp.getPermissionList("9001", "admin");
        assertThat(permissions).containsExactlyInAnyOrder("legacy:perm", "direct:perm");
    }

    @Test
    @DisplayName("8. 多账号体系物理隔离：相同 userId 与同名 roleCode 在 admin / member / login 下互不串号，按体系精准刷新")
    void testMultiAccountCacheKeyIsolation() {
        when(loader.loadRolesByUserId("1001", "admin")).thenReturn(List.of("manager"));
        when(loader.loadRolesByUserId("1001", "member")).thenReturn(List.of("vip"));
        when(loader.loadRolesByUserId("1001", "login")).thenReturn(List.of("default_role"));

        when(loader.loadPermissionsByRole("manager", "admin")).thenReturn(List.of("sys:user:write"));
        when(loader.loadPermissionsByRole("manager", "member")).thenReturn(List.of("shop:order:read"));
        when(loader.loadPermissionsByRole("vip", "member")).thenReturn(List.of("mall:coupon:use"));
        when(loader.loadPermissionsByRole("default_role", "login")).thenReturn(List.of("base:view"));

        when(loader.loadDirectPermissionsByUserId("1001", "admin")).thenReturn(List.of("admin:audit"));
        when(loader.loadDirectPermissionsByUserId("1001", "member")).thenReturn(List.of("member:sign"));
        when(loader.loadDirectPermissionsByUserId("1001", "login")).thenReturn(List.of("login:basic"));

        // 1. 同 userId=1001 在三个账号体系下的角色与权限完全隔离
        assertThat(cachedStpInterface.getRoleList("1001", "admin")).containsExactly("manager");
        assertThat(cachedStpInterface.getRoleList("1001", "member")).containsExactly("vip");
        assertThat(cachedStpInterface.getRoleList("1001", "login")).containsExactly("default_role");

        assertThat(cachedStpInterface.getPermissionList("1001", "admin"))
                .containsExactlyInAnyOrder("sys:user:write", "admin:audit");
        assertThat(cachedStpInterface.getPermissionList("1001", "member"))
                .containsExactlyInAnyOrder("mall:coupon:use", "member:sign");
        assertThat(cachedStpInterface.getPermissionList("1001", "login"))
                .containsExactlyInAnyOrder("base:view", "login:basic");

        // 2. 同名角色 "manager" 在 admin 与 member 体系下的缓存 Key 物理隔离
        assertThat(cachedStpInterface.getPermissionsByRole("manager", "admin")).containsExactly("sys:user:write");
        assertThat(cachedStpInterface.getPermissionsByRole("manager", "member")).containsExactly("shop:order:read");

        // 3. 验证底层缓存 Key 格式（默认 login 保持原有前缀，非默认体系携带 loginType 命名空间）
        assertThat(cacheService.get(PermissionCacheService.USER_ROLES_PREFIX + "1001")).isEqualTo("[\"default_role\"]");
        assertThat(cacheService.get("ateng:satoken:cache:admin:user-roles:1001")).isEqualTo("[\"manager\"]");
        assertThat(cacheService.get("ateng:satoken:cache:member:user-roles:1001")).isEqualTo("[\"vip\"]");
        assertThat(cacheService.get("ateng:satoken:cache:admin:role-perms:manager")).isEqualTo("[\"sys:user:write\"]");
        assertThat(cacheService.get("ateng:satoken:cache:member:role-perms:manager")).isEqualTo("[\"shop:order:read\"]");

        // 4. 按 admin 体系定向刷新用户缓存，不影响 member 与 login 体系缓存
        when(loader.loadRolesByUserId("1001", "admin")).thenReturn(List.of("super_manager"));
        cacheService.refreshUserPermissions("admin", "1001");
        assertThat(cachedStpInterface.getRoleList("1001", "admin")).containsExactly("super_manager");
        assertThat(cachedStpInterface.getRoleList("1001", "member")).containsExactly("vip");
        assertThat(cachedStpInterface.getRoleList("1001", "login")).containsExactly("default_role");
        verify(loader, times(1)).loadRolesByUserId("1001", "member");
        verify(loader, times(1)).loadRolesByUserId("1001", "login");

        // 5. 按 admin 体系定向刷新角色缓存，不影响 member 体系同名角色缓存
        when(loader.loadPermissionsByRole("manager", "admin")).thenReturn(List.of("sys:user:all"));
        cacheService.refreshRolePermissions("admin", "manager");
        assertThat(cachedStpInterface.getPermissionsByRole("manager", "admin")).containsExactly("sys:user:all");
        assertThat(cachedStpInterface.getPermissionsByRole("manager", "member")).containsExactly("shop:order:read");
        verify(loader, times(1)).loadPermissionsByRole("manager", "member");
    }

    @AfterEach
    void tearDownTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("9. 复合 loginId 自动拆解租户前缀，并在切租户回源时锁定主体归属租户与异常安全还原")
    void testCompositeLoginIdStrippingAndTenantContextIsolationDuringFallback() {
        try (MockedStatic<StpUtil> stpUtilMock = mockStatic(StpUtil.class)) {
            SaSession session = mock(SaSession.class);
            LoginUser operatorUser = LoginUser.builder()
                    .tenantId("tenant_B")
                    .userId("operator_88001")
                    .build();
            when(session.get(SaTokenConstant.LOGIN_USER_KEY)).thenReturn(operatorUser);
            stpUtilMock.when(() -> StpUtil.getSessionByLoginId("tenant_B_operator_88001", false))
                    .thenReturn(session);

            when(loader.loadRolesByUserId("operator_88001", "login", "tenant_B"))
                    .thenAnswer(inv -> {
                        assertThat(TenantContextHolder.getExplicit()).isEqualTo("tenant_B");
                        assertThat(TenantContextHolder.isIgnoreTenant()).isTrue();
                        return List.of("role_ops");
                    });
            when(loader.loadPermissionsByRole("role_ops", "login"))
                    .thenAnswer(inv -> {
                        assertThat(TenantContextHolder.getExplicit()).isEqualTo("tenant_B");
                        return List.of("ops:deploy");
                    });
            when(loader.loadDirectPermissionsByUserId("operator_88001", "login", "tenant_B"))
                    .thenAnswer(inv -> {
                        assertThat(TenantContextHolder.getExplicit()).isEqualTo("tenant_B");
                        return List.of("ops:direct");
                    });

            // 外层切到 tenant_working 且开启 ignoreTenant
            TenantContextHolder.setTenantId("tenant_working");
            TenantContextHolder.runWithoutTenant(() -> {
                List<String> perms = cachedStpInterface.getPermissionList("tenant_B_operator_88001", "login");
                assertThat(perms).containsExactlyInAnyOrder("ops:deploy", "ops:direct");

                // 返回外层后立即恢复 tenant_working 且不破坏 ignoreDepth
                assertThat(TenantContextHolder.getExplicit()).isEqualTo("tenant_working");
                assertThat(TenantContextHolder.isIgnoreTenant()).isTrue();
            });
            assertThat(TenantContextHolder.isIgnoreTenant()).isFalse();

            // 验证缓存键按主体租户隔离存储，且剥离前缀
            assertThat(cacheService.get(PermissionCacheService.buildUserRolesKey("operator_88001", "login", "tenant_B")))
                    .isEqualTo("[\"role_ops\"]");
            assertThat(cacheService.get(PermissionCacheService.buildUserPermsKey("operator_88001", "login", "tenant_B")))
                    .isEqualTo("[\"ops:direct\"]");

            // 异常场景下亦必须严格恢复外层工作租户
            SaSession errSession = mock(SaSession.class);
            LoginUser errUser = LoginUser.builder()
                    .tenantId("tenant_err")
                    .userId("u99")
                    .build();
            when(errSession.get(SaTokenConstant.LOGIN_USER_KEY)).thenReturn(errUser);
            stpUtilMock.when(() -> StpUtil.getSessionByLoginId("tenant_err_u99", false)).thenReturn(errSession);
            when(loader.loadRolesByUserId("u99", "login", "tenant_err"))
                    .thenThrow(new IllegalStateException("db down"));

            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> cachedStpInterface.getRoleList("tenant_err_u99", "login"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(TenantContextHolder.getExplicit()).isEqualTo("tenant_working");
        }
    }
}
