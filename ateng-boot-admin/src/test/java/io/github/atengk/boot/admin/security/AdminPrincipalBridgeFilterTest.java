package io.github.atengk.boot.admin.security;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import de.codecentric.boot.admin.server.config.AdminServerProperties;
import io.github.atengk.boot.admin.provider.AdminConsolePrincipal;
import io.github.atengk.boot.admin.provider.AdminRole;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.security.Principal;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AdminPrincipalBridgeFilter 身份桥接过滤器单元测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("AdminPrincipalBridgeFilter SBA UI 身份桥接测试")
class AdminPrincipalBridgeFilterTest {

    @Test
    @DisplayName("仅在 SBA contextPath 作用域内将 Sa-Token 会话主体桥接至 HttpServletRequest.getUserPrincipal()")
    void shouldBridgePrincipalOnlyWithinAdminContextPath() throws ServletException, IOException {
        AdminServerProperties sbaProperties = new AdminServerProperties();
        sbaProperties.setContextPath("/admin");
        AdminPrincipalBridgeFilter filter = new AdminPrincipalBridgeFilter(sbaProperties);

        AdminConsolePrincipal principal = AdminConsolePrincipal.of("sre-alice", Set.of(AdminRole.OPERATOR));
        SaSession mockSession = mock(SaSession.class);
        when(mockSession.get(AdminSessionHelper.SESSION_PRINCIPAL_KEY)).thenReturn(principal);

        try (MockedStatic<StpUtil> stpMock = Mockito.mockStatic(StpUtil.class)) {
            stpMock.when(StpUtil::isLogin).thenReturn(true);
            stpMock.when(() -> StpUtil.getSession(false)).thenReturn(mockSession);

            // 1. 命中 /admin/** -> 注入 Principal
            MockHttpServletRequest adminReq = new MockHttpServletRequest("GET", "/admin/sba-settings.js");
            MockHttpServletResponse adminRes = new MockHttpServletResponse();
            AtomicReference<Principal> capturedAdminPrincipal = new AtomicReference<>();
            filter.doFilter(adminReq, adminRes, (req, res) ->
                    capturedAdminPrincipal.set(((HttpServletRequest) req).getUserPrincipal()));

            assertThat(capturedAdminPrincipal.get()).isInstanceOf(AdminConsolePrincipal.class);
            assertThat(capturedAdminPrincipal.get().getName()).isEqualTo("sre-alice");

            // 2. 宿主业务路径 /api/orders -> 不注入 SBA 控制台 Principal
            MockHttpServletRequest bizReq = new MockHttpServletRequest("GET", "/api/orders");
            MockHttpServletResponse bizRes = new MockHttpServletResponse();
            AtomicReference<Principal> capturedBizPrincipal = new AtomicReference<>();
            filter.doFilter(bizReq, bizRes, (req, res) ->
                    capturedBizPrincipal.set(((HttpServletRequest) req).getUserPrincipal()));

            assertThat(capturedBizPrincipal.get()).isNull();
        }
    }
}
