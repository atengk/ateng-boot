package io.github.atengk.boot.satoken.cache;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.satoken.constant.SaTokenConstant;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.stp.DefaultStpInterface;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 默认权限加载器与 DefaultStpInterface 复合登录 ID / 租户会话回退单元测试
 *
 * @author Ateng
 * @since 2026-11-23
 */
@DisplayName("DefaultUserRolePermissionLoader & DefaultStpInterface 单元测试")
class DefaultUserRolePermissionLoaderTest {

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("1. UserRolePermissionLoader 三参默认方法正确回退两参及单参契约")
    void shouldFallbackToLegacyOverloadsInDefaultMethods() {
        UserRolePermissionLoader legacyLoader = new UserRolePermissionLoader() {
            @Override
            public List<String> loadRolesByUserId(String userId) {
                return List.of("role_" + userId);
            }

            @Override
            public List<String> loadPermissionsByRole(String roleCode) {
                return List.of("perm_" + roleCode);
            }

            @Override
            public List<String> loadDirectPermissionsByUserId(String userId) {
                return List.of("direct_" + userId);
            }
        };

        assertThat(legacyLoader.loadRolesByUserId("operator_1", "login", "tenant_B"))
                .containsExactly("role_operator_1");
        assertThat(legacyLoader.loadDirectPermissionsByUserId("operator_1", "login", "tenant_B"))
                .containsExactly("direct_operator_1");

        UserRolePermissionLoader typedLoader = new UserRolePermissionLoader() {
            @Override
            public List<String> loadRolesByUserId(String userId) {
                return List.of();
            }

            @Override
            public List<String> loadRolesByUserId(String userId, String loginType) {
                return List.of(loginType + ":" + userId);
            }

            @Override
            public List<String> loadPermissionsByRole(String roleCode) {
                return List.of();
            }

            @Override
            public List<String> loadDirectPermissionsByUserId(String userId, String loginType) {
                return List.of(loginType + ":direct:" + userId);
            }
        };

        assertThat(typedLoader.loadRolesByUserId("u1", "admin", "tenant_B"))
                .containsExactly("admin:u1");
        assertThat(typedLoader.loadDirectPermissionsByUserId("u1", "admin", "tenant_B"))
                .containsExactly("admin:direct:u1");
    }

    @Test
    @DisplayName("2. DefaultUserRolePermissionLoader 支持三参从复合 SaSession 解析并隔离不同当前登录人")
    void shouldResolveRolesAndPermsFromCompositeSessionWithoutLeakingCallerPermissions() {
        DefaultUserRolePermissionLoader loader = new DefaultUserRolePermissionLoader();
        try (MockedStatic<StpUtil> stpUtilMock = mockStatic(StpUtil.class)) {
            SaSession callerSession = mock(SaSession.class);
            LoginUser caller = LoginUser.builder()
                    .tenantId("tenant_A")
                    .userId("caller_admin")
                    .roles(Set.of("super_caller"))
                    .permissions(Set.of("caller:all"))
                    .build();
            when(callerSession.get(SaTokenConstant.LOGIN_USER_KEY)).thenReturn(caller);

            SaSession targetSession = mock(SaSession.class);
            LoginUser target = LoginUser.builder()
                    .tenantId("tenant_B")
                    .userId("operator_88001")
                    .roles(Set.of("ops_role"))
                    .permissions(Set.of("ops:read"))
                    .build();
            when(targetSession.get(SaTokenConstant.LOGIN_USER_KEY)).thenReturn(target);

            stpUtilMock.when(StpUtil::isLogin).thenReturn(true);
            stpUtilMock.when(() -> StpUtil.getSession(false)).thenReturn(callerSession);
            stpUtilMock.when(() -> StpUtil.getSessionByLoginId("operator_88001", false)).thenReturn(null);
            stpUtilMock.when(() -> StpUtil.getSessionByLoginId("tenant_B_operator_88001", false)).thenReturn(targetSession);

            assertThat(loader.loadRolesByUserId("operator_88001", "login", "tenant_B"))
                    .containsExactly("ops_role");
            assertThat(loader.loadDirectPermissionsByUserId("operator_88001", "login", "tenant_B"))
                    .containsExactly("ops:read");

            // 当仅传纯净 userId 且 TenantContextHolder 指向 tenant_B 时，亦能回退命中复合 SaSession
            TenantContextHolder.setTenantId("tenant_B");
            assertThat(loader.loadRolesByUserId("operator_88001", "login"))
                    .containsExactly("ops_role");
            assertThat(loader.loadDirectPermissionsByUserId("operator_88001", "login"))
                    .containsExactly("ops:read");

            // 当存在同名无租户裸用户 "1001" 时，指定租户 "tenant_B" 查询绝不能误读裸用户会话
            SaSession plainSameIdSession = mock(SaSession.class);
            LoginUser plainSameIdUser = LoginUser.builder()
                    .tenantId(null)
                    .userId("1001")
                    .roles(Set.of("plain_admin"))
                    .permissions(Set.of("plain:all"))
                    .build();
            when(plainSameIdSession.get(SaTokenConstant.LOGIN_USER_KEY)).thenReturn(plainSameIdUser);
            stpUtilMock.when(() -> StpUtil.getSessionByLoginId("1001", false)).thenReturn(plainSameIdSession);
            stpUtilMock.when(() -> StpUtil.getSessionByLoginId("tenant_B_1001", false)).thenReturn(null);
            assertThat(loader.loadRolesByUserId("1001", "login", "tenant_B")).isEmpty();
        }
    }

    @Test
    @DisplayName("3. DefaultStpInterface 在无当前请求或跨租户查询时正确从复合/纯净 SaSession 解析权限与角色")
    void shouldResolveDefaultStpInterfaceForCompositeAndPlainSession() {
        DefaultStpInterface stpInterface = new DefaultStpInterface();
        try (MockedStatic<StpUtil> stpUtilMock = mockStatic(StpUtil.class)) {
            SaSession targetSession = mock(SaSession.class);
            LoginUser target = LoginUser.builder()
                    .tenantId("tenant_B")
                    .userId("operator_88001")
                    .roles(Set.of("finance_mgr"))
                    .permissions(Set.of("finance:approve"))
                    .build();
            when(targetSession.get(SaTokenConstant.LOGIN_USER_KEY)).thenReturn(target);

            stpUtilMock.when(StpUtil::isLogin).thenReturn(false);
            stpUtilMock.when(() -> StpUtil.getSessionByLoginId("operator_88001", false)).thenReturn(null);
            stpUtilMock.when(() -> StpUtil.getSessionByLoginId("tenant_B_operator_88001", false)).thenReturn(targetSession);

            // 直接传入复合 loginId
            assertThat(stpInterface.getRoleList("tenant_B_operator_88001", "login"))
                    .containsExactly("finance_mgr");
            assertThat(stpInterface.getPermissionList("tenant_B_operator_88001", "login"))
                    .containsExactly("finance:approve");

            // 传入纯净 userId + TenantContextHolder 回退组合查 SaSession
            TenantContextHolder.setTenantId("tenant_B");
            assertThat(stpInterface.getRoleList("operator_88001", "login"))
                    .containsExactly("finance_mgr");
            assertThat(stpInterface.getPermissionList("operator_88001", "login"))
                    .containsExactly("finance:approve");
        }
    }
}
