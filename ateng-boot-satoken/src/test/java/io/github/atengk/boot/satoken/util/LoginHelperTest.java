package io.github.atengk.boot.satoken.util;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.satoken.constant.SaTokenConstant;
import io.github.atengk.boot.satoken.model.LoginUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Duration;
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

    @Test
    @DisplayName("9. openSafe: Duration 强类型重载与前置卫语句防卫")
    void testOpenSafeDuration() {
        assertThatThrownBy(() -> LoginHelper.openSafe((Duration) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("维持时长不能为空");

        assertThatThrownBy(() -> LoginHelper.openSafe("pay", (Duration) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("维持时长不能为空");

        try (MockedStatic<StpUtil> stpUtilMock = mockStatic(StpUtil.class)) {
            LoginHelper.openSafe(Duration.ofMinutes(5));
            stpUtilMock.verify(() -> StpUtil.openSafe(300));

            LoginHelper.openSafe("payment", Duration.ofSeconds(120));
            stpUtilMock.verify(() -> StpUtil.openSafe("payment", 120));
        }
    }

    @Test
    @DisplayName("10. replaced: 顶替下线全局与多设备端对称门面")
    void testReplaced() {
        try (MockedStatic<StpUtil> stpUtilMock = mockStatic(StpUtil.class)) {
            LoginHelper.replaced("user-1");
            stpUtilMock.verify(() -> StpUtil.replaced("user-1"));

            LoginHelper.replaced("user-2", "PC");
            stpUtilMock.verify(() -> StpUtil.replaced("user-2", "PC"));
        }
    }

    @Test
    @DisplayName("11. disable & untieDisable: 账号封禁与解封对称门面（含 Duration 强类型）")
    void testDisableAndUntieDisable() {
        assertThatThrownBy(() -> LoginHelper.disable("user-1", (Duration) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("封禁时长不能为空");

        assertThatThrownBy(() -> LoginHelper.disable("user-1", "chat", (Duration) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("封禁时长不能为空");

        try (MockedStatic<StpUtil> stpUtilMock = mockStatic(StpUtil.class)) {
            stpUtilMock.when(() -> StpUtil.isDisable("user-1")).thenReturn(true);
            stpUtilMock.when(() -> StpUtil.isDisable("user-1", "chat")).thenReturn(false);
            stpUtilMock.when(() -> StpUtil.getDisableTime("user-1")).thenReturn(3600L);
            stpUtilMock.when(() -> StpUtil.getDisableTime("user-1", "chat")).thenReturn(-2L);

            // Duration 封禁
            LoginHelper.disable("user-1", Duration.ofHours(1));
            stpUtilMock.verify(() -> StpUtil.disable("user-1", 3600L));

            // 秒级封禁
            LoginHelper.disable("user-1", 1800L);
            stpUtilMock.verify(() -> StpUtil.disable("user-1", 1800L));

            // 服务级封禁
            LoginHelper.disable("user-1", "chat", Duration.ofMinutes(10));
            stpUtilMock.verify(() -> StpUtil.disable("user-1", "chat", 600L));

            LoginHelper.disable("user-1", "chat", 300L);
            stpUtilMock.verify(() -> StpUtil.disable("user-1", "chat", 300L));

            // 状态判断与校验
            assertThat(LoginHelper.isDisable("user-1")).isTrue();
            assertThat(LoginHelper.isDisable("user-1", "chat")).isFalse();
            assertThat(LoginHelper.getDisableTime("user-1")).isEqualTo(3600L);
            assertThat(LoginHelper.getDisableTime("user-1", "chat")).isEqualTo(-2L);

            LoginHelper.checkDisable("user-1");
            stpUtilMock.verify(() -> StpUtil.checkDisable("user-1"));

            LoginHelper.checkDisable("user-1", "chat");
            stpUtilMock.verify(() -> StpUtil.checkDisable("user-1", "chat"));

            // 解封
            LoginHelper.untieDisable("user-1");
            stpUtilMock.verify(() -> StpUtil.untieDisable("user-1"));

            LoginHelper.untieDisable("user-1", "chat");
            stpUtilMock.verify(() -> StpUtil.untieDisable("user-1", "chat"));
        }
    }

    @Test
    @DisplayName("12. tokenQuery: 获取指定账号/设备 Token 及有效期")
    void testTokenQueryMethods() {
        try (MockedStatic<StpUtil> stpUtilMock = mockStatic(StpUtil.class)) {
            stpUtilMock.when(() -> StpUtil.getTokenValueByLoginId("user-1")).thenReturn("tk-global");
            stpUtilMock.when(() -> StpUtil.getTokenValueByLoginId("user-1", "APP")).thenReturn("tk-app");
            stpUtilMock.when(StpUtil::getTokenTimeout).thenReturn(7200L);
            stpUtilMock.when(StpUtil::getSessionTimeout).thenReturn(14400L);

            assertThat(LoginHelper.getTokenValueByLoginId("user-1")).isEqualTo("tk-global");
            assertThat(LoginHelper.getTokenValueByLoginId("user-1", "APP")).isEqualTo("tk-app");
            assertThat(LoginHelper.getTokenTimeout()).isEqualTo(7200L);
            assertThat(LoginHelper.getSessionTimeout()).isEqualTo(14400L);
        }
    }

    @Test
    @DisplayName("13. checkSelf 与 isSelf: 数据所有权断言与管理员豁免")
    void testCheckSelfAndIsSelf() {
        try (MockedStatic<StpUtil> stpUtilMock = mockStatic(StpUtil.class)) {
            cn.dev33.satoken.session.SaSession mockSession = mock(cn.dev33.satoken.session.SaSession.class);
            stpUtilMock.when(StpUtil::isLogin).thenReturn(true);
            stpUtilMock.when(() -> StpUtil.getSession(false)).thenReturn(mockSession);
            stpUtilMock.when(StpUtil::getLoginIdDefaultNull).thenReturn("1001");

            // 1. 本人访问：匹配放行
            LoginHelper.checkSelf("1001");
            LoginHelper.checkSelf(1001L);
            assertThat(LoginHelper.isSelf("1001")).isTrue();
            assertThat(LoginHelper.isSelf(1001L)).isTrue();

            // 2. 他人访问（非管理员）：抛出 403 异常
            assertThatThrownBy(() -> LoginHelper.checkSelf("2002"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("无权访问该资源");
            assertThatThrownBy(() -> LoginHelper.checkSelf(2002L, "自定义越权错误"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("自定义越权错误");
            assertThat(LoginHelper.isSelf("2002")).isFalse();
            assertThat(LoginHelper.isSelf(2002L)).isFalse();

            // 3. 空值防卫
            assertThatThrownBy(() -> LoginHelper.checkSelf((String) null))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> LoginHelper.checkSelf((Long) null))
                    .isInstanceOf(BusinessException.class);
            assertThat(LoginHelper.isSelf((String) null)).isFalse();
            assertThat(LoginHelper.isSelf((Long) null)).isFalse();

            // 4. 管理员特权：豁免放行
            LoginUser adminUser = LoginUser.builder()
                    .userId("1001")
                    .roles(Set.of("admin"))
                    .build();
            when(mockSession.get(SaTokenConstant.LOGIN_USER_KEY)).thenReturn(io.github.atengk.boot.core.util.JsonUtil.toJsonString(adminUser));

            LoginHelper.checkSelf("2002");
            LoginHelper.checkSelf(2002L);
            assertThat(LoginHelper.isSelf("2002")).isTrue();
            assertThat(LoginHelper.isSelf(2002L)).isTrue();
        }
    }

    @Test
    @DisplayName("14. checkBelongsToTenant: 多租户隔离断言")
    void testCheckBelongsToTenant() {
        try (MockedStatic<StpUtil> stpUtilMock = mockStatic(StpUtil.class)) {
            stpUtilMock.when(StpUtil::isLogin).thenReturn(true);
            stpUtilMock.when(() -> StpUtil.getSession(false)).thenReturn(null);
            stpUtilMock.when(StpUtil::getLoginIdDefaultNull).thenReturn("tenant-01_1001");

            // 1. 本租户匹配：放行
            LoginHelper.checkBelongsToTenant("tenant-01");

            // 2. 跨租户篡改：抛出 403 异常
            assertThatThrownBy(() -> LoginHelper.checkBelongsToTenant("tenant-02"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("非法跨租户数据访问");

            assertThatThrownBy(() -> LoginHelper.checkBelongsToTenant("tenant-02", "跨租户阻止"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("跨租户阻止");

            // 3. 空值防卫
            assertThatThrownBy(() -> LoginHelper.checkBelongsToTenant(null))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> LoginHelper.checkBelongsToTenant("  "))
                    .isInstanceOf(BusinessException.class);
        }
    }
}

