package io.github.atengk.boot.satoken.util;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import io.github.atengk.boot.satoken.constant.SaTokenConstant;
import io.github.atengk.boot.satoken.model.LoginUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Collections;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * LoginHelper 登录门面工具类单元测试
 * 验证入参防御、多租户复合 LoginId 编解码、空安全保底返回与权限判定逻辑
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("LoginHelper 强类型门面单元测试")
class LoginHelperTest {

    @Test
    @DisplayName("1. buildCompositeLoginId: 智能合成多租户复合主键")
    void testBuildCompositeLoginId() {
        assertThat(LoginHelper.buildCompositeLoginId("tenant-1", "1001"))
                .isEqualTo("tenant-1" + SaTokenConstant.TENANT_DELIMITER + "1001");
        assertThat(LoginHelper.buildCompositeLoginId(null, "1001"))
                .isEqualTo("1001");
        assertThat(LoginHelper.buildCompositeLoginId("  ", "1001"))
                .isEqualTo("1001");
    }

    @Test
    @DisplayName("2. login 参数校验防卫：主体或 userId 为空时快速失败")
    void testLoginValidation() {
        assertThatThrownBy(() -> LoginHelper.login(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("登录主体或用户唯一标识 (userId) 不能为空");

        LoginUser userWithNullId = new LoginUser().setUserId(null);
        assertThatThrownBy(() -> LoginHelper.login(userWithNullId))
                .isInstanceOf(IllegalArgumentException.class);

        LoginUser userWithBlankId = new LoginUser().setUserId("   ");
        assertThatThrownBy(() -> LoginHelper.login(userWithBlankId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("3. login: 当 loginParameter 为 null 时防御性补齐并正常调用底层")
    void testLoginWithNullLoginParameter() {
        try (MockedStatic<StpUtil> stpUtilMock = mockStatic(StpUtil.class)) {
            cn.dev33.satoken.session.SaSession mockSession = mock(cn.dev33.satoken.session.SaSession.class);
            stpUtilMock.when(StpUtil::getSession).thenReturn(mockSession);

            LoginUser loginUser = LoginUser.builder()
                    .userId("1001")
                    .tenantId("tenant-a")
                    .build();

            // 传入 null loginParameter 不应抛出 NPE
            LoginHelper.login(loginUser, (SaLoginParameter) null);

            // 验证底层 StpUtil.login 被正确调用
            stpUtilMock.verify(() -> StpUtil.login(eq("tenant-a_1001"), any(SaLoginParameter.class)));
            verify(mockSession).set(eq(SaTokenConstant.LOGIN_USER_KEY), anyString());
        }
    }

    @Test
    @DisplayName("4. setLoginUser: 传入 null 时安全移除 Session 对应键")
    void testSetLoginUserNull() {
        try (MockedStatic<StpUtil> stpUtilMock = mockStatic(StpUtil.class)) {
            cn.dev33.satoken.session.SaSession mockSession = mock(cn.dev33.satoken.session.SaSession.class);
            stpUtilMock.when(() -> StpUtil.getSession(false)).thenReturn(mockSession);

            LoginHelper.setLoginUser(null);
            verify(mockSession).delete(SaTokenConstant.LOGIN_USER_KEY);
        }
    }

    @Test
    @DisplayName("5. getUserId 与 getTenantId: 从复合 LoginId 双向对称剥离还原")
    void testGetUserIdAndTenantIdFromCompositeLoginId() {
        try (MockedStatic<StpUtil> stpUtilMock = mockStatic(StpUtil.class)) {
            stpUtilMock.when(StpUtil::isLogin).thenReturn(true);
            stpUtilMock.when(() -> StpUtil.getSession(false)).thenReturn(null);
            stpUtilMock.when(StpUtil::getLoginIdDefaultNull).thenReturn("tenant-99_user-88");

            assertThat(LoginHelper.getTenantId()).isEqualTo("tenant-99");
            assertThat(LoginHelper.getUserId()).isEqualTo("user-88");
            assertThat(LoginHelper.getUserIdAsLong()).isNull(); // 非数字格式优雅返回 null
        }
    }

    @Test
    @DisplayName("6. getUserIdAsLong: 数字 ID 正常转换，非数字或空保底返回 null")
    void testGetUserIdAsLong() {
        try (MockedStatic<StpUtil> stpUtilMock = mockStatic(StpUtil.class)) {
            stpUtilMock.when(StpUtil::isLogin).thenReturn(true);
            stpUtilMock.when(() -> StpUtil.getSession(false)).thenReturn(null);
            stpUtilMock.when(StpUtil::getLoginIdDefaultNull).thenReturn("10086");

            assertThat(LoginHelper.getUserIdAsLong()).isEqualTo(10086L);
        }
    }

    @Test
    @DisplayName("7. 未登录状态下所有 getter 保底空安全返回")
    void testDefensiveReturnWhenNotLogin() {
        try (MockedStatic<StpUtil> stpUtilMock = mockStatic(StpUtil.class)) {
            stpUtilMock.when(StpUtil::isLogin).thenReturn(false);

            assertThat(LoginHelper.getUserId()).isNull();
            assertThat(LoginHelper.getUserIdAsLong()).isNull();
            assertThat(LoginHelper.getUserAccount()).isNull();
            assertThat(LoginHelper.getUserName()).isNull();
            assertThat(LoginHelper.getTenantId()).isNull();
            assertThat(LoginHelper.getDeptId()).isNull();
            assertThat(LoginHelper.getRoles()).isEmpty();
            assertThat(LoginHelper.getPermissions()).isEmpty();
            assertThat(LoginHelper.hasRole("admin")).isFalse();
            assertThat(LoginHelper.hasPermission("order:export")).isFalse();
            assertThat(LoginHelper.isSuperAdmin()).isFalse();
        }
    }

    @Test
    @DisplayName("8. isSuperAdmin: 判定超级管理员角色与通配权限")
    void testIsSuperAdmin() {
        LoginUser adminUser = LoginUser.builder()
                .userId("1")
                .roles(Set.of("admin"))
                .build();
        try (MockedStatic<StpUtil> stpUtilMock = mockStatic(StpUtil.class)) {
            stpUtilMock.when(StpUtil::isLogin).thenReturn(true);
            cn.dev33.satoken.session.SaSession mockSession = mock(cn.dev33.satoken.session.SaSession.class);
            stpUtilMock.when(() -> StpUtil.getSession(false)).thenReturn(mockSession);
            when(mockSession.get(SaTokenConstant.LOGIN_USER_KEY)).thenReturn(adminUser);

            assertThat(LoginHelper.isSuperAdmin()).isTrue();
        }
    }
}
