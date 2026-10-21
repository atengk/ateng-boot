package io.github.atengk.boot.realtime.auth;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.satoken.constant.SaTokenConstant;
import io.github.atengk.boot.satoken.model.LoginUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 基于 Sa-Token 框架的握手鉴权提供者单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("SaTokenRealtimeAuthProvider 握手鉴权测试")
class SaTokenRealtimeAuthProviderTest {

    private final SaTokenRealtimeAuthProvider provider = new SaTokenRealtimeAuthProvider();

    @Test
    @DisplayName("空值或空白 Token 快速失败防御")
    void testBlankToken() {
        assertThat(provider.authenticate(null)).isNull();
        assertThat(provider.authenticate("")).isNull();
        assertThat(provider.authenticate("   ")).isNull();
    }

    @Test
    @DisplayName("有效 Token 解析基础用户 ID 与设备端")
    void testValidTokenBasic() {
        try (MockedStatic<StpUtil> stpMock = mockStatic(StpUtil.class)) {
            stpMock.when(() -> StpUtil.getLoginIdByToken("token-001")).thenReturn("1001");
            stpMock.when(() -> StpUtil.getLoginDeviceByToken("token-001")).thenReturn("PC");
            stpMock.when(() -> StpUtil.getTokenSessionByToken("token-001")).thenReturn(null);

            RealtimeAuthPayload payload = provider.authenticate("token-001");

            assertThat(payload).isNotNull();
            assertThat(payload.getUserId()).isEqualTo("1001");
            assertThat(payload.getDeviceType()).isEqualTo("PC");
            assertThat(payload.getTenantId()).isNull();
            assertThat(payload.isAnonymous()).isFalse();
        }
    }

    @Test
    @DisplayName("从 TokenSession 中提取强类型 LoginUser 实体与租户信息")
    void testValidTokenWithLoginUser() {
        try (MockedStatic<StpUtil> stpMock = mockStatic(StpUtil.class)) {
            stpMock.when(() -> StpUtil.getLoginIdByToken("token-002")).thenReturn("1002");
            stpMock.when(() -> StpUtil.getLoginDeviceByToken("token-002")).thenReturn("APP");

            SaSession session = mock(SaSession.class);
            LoginUser loginUser = LoginUser.builder()
                    .userId("1002")
                    .userName("zhangsan")
                    .userAccount("zhangsan_acc")
                    .tenantId("666")
                    .build();
            when(session.get(SaTokenConstant.LOGIN_USER_KEY)).thenReturn(loginUser);

            stpMock.when(() -> StpUtil.getTokenSessionByToken("token-002")).thenReturn(session);

            RealtimeAuthPayload payload = provider.authenticate("token-002");

            assertThat(payload).isNotNull();
            assertThat(payload.getUserId()).isEqualTo("1002");
            assertThat(payload.getDeviceType()).isEqualTo("APP");
            assertThat(payload.getTenantId()).isEqualTo(666L);
            assertThat(payload.getExtraAttributes()).containsEntry("userName", "zhangsan");
        }
    }

    @Test
    @DisplayName("多租户复合 LoginId (tenantId_userId) 格式智能拆分提取")
    void testCompositeLoginId() {
        try (MockedStatic<StpUtil> stpMock = mockStatic(StpUtil.class)) {
            stpMock.when(() -> StpUtil.getLoginIdByToken("token-003")).thenReturn("999_user888");
            stpMock.when(() -> StpUtil.getLoginDeviceByToken("token-003")).thenReturn("H5");
            stpMock.when(() -> StpUtil.getTokenSessionByToken("token-003")).thenReturn(null);

            RealtimeAuthPayload payload = provider.authenticate("token-003");

            assertThat(payload).isNotNull();
            assertThat(payload.getUserId()).isEqualTo("user888");
            assertThat(payload.getTenantId()).isEqualTo(999L);
            assertThat(payload.getDeviceType()).isEqualTo("H5");
        }
    }

    @Test
    @DisplayName("无效或过期 Token 拒绝认证并返回 null")
    void testInvalidToken() {
        try (MockedStatic<StpUtil> stpMock = mockStatic(StpUtil.class)) {
            stpMock.when(() -> StpUtil.getLoginIdByToken("expired-token")).thenReturn(null);

            RealtimeAuthPayload payload = provider.authenticate("expired-token");

            assertThat(payload).isNull();
        }
    }
}
