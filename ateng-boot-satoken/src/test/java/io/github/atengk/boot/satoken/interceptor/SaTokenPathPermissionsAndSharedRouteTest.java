package io.github.atengk.boot.satoken.interceptor;

import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpLogic;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.context.SaTokenRouteContextHolder;
import io.github.atengk.boot.satoken.model.PathPermissionRule;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.github.atengk.boot.satoken.spi.DynamicPathPermissionProvider;
import io.github.atengk.boot.satoken.util.LoginHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * YAML path-permissions 静态路径权限规则与多账号共享路径活跃体系探测单元测试
 *
 * @author Ateng
 * @since 2026-11-19
 */
@DisplayName("SaToken YAML path-permissions 与共享路径活跃体系探测单元测试")
class SaTokenPathPermissionsAndSharedRouteTest {

    private HttpServletRequest request;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        clearAll();
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    private void clearAll() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        DataPermissionContextHolder.clear();
        SaTokenRouteContextHolder.clear();
    }

    @Test
    @DisplayName("1. YAML path-permissions 无需手写 DynamicPathPermissionProvider Bean 即可拦截 /api/system/enums/**")
    void shouldEnforceYamlPathPermissionsWithoutSpiProvider() throws Exception {
        SaTokenProperties props = new SaTokenProperties();
        props.getInterceptor().setPathPermissions(List.of(
                PathPermissionRule.of("/api/system/enums/**", "GET", "system:enum:list")
        ));

        AtengSaTokenInterceptor interceptor = new AtengSaTokenInterceptor(props);
        interceptor.isAnnotation(false);

        when(request.getRequestURI()).thenReturn("/api/system/enums/OrderStatus");
        when(request.getMethod()).thenReturn("GET");

        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class)) {
            StpLogic loginLogic = mock(StpLogic.class);
            helperMock.when(() -> LoginHelper.getActiveLoginType(any())).thenReturn("login");
            helperMock.when(() -> LoginHelper.getStpLogic("login")).thenReturn(loginLogic);
            helperMock.when(() -> LoginHelper.isSuperAdmin("login")).thenReturn(false);

            // 1.1 有权限放行
            assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
            verify(loginLogic).checkPermissionOr("system:enum:list");

            // 1.2 缺权限抛 NotPermissionException
            doThrow(new NotPermissionException("system:enum:list"))
                    .when(loginLogic).checkPermissionOr("system:enum:list");
            assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                    .isInstanceOf(NotPermissionException.class);
        }
    }

    @Test
    @DisplayName("2. DynamicPathPermissionProvider 优先于 YAML path-permissions，未命中动态规则时回退 YAML 规则")
    void shouldPreferDynamicProviderAndFallbackToYamlPathPermissions() throws Exception {
        SaTokenProperties props = new SaTokenProperties();
        props.getInterceptor().setPathPermissions(List.of(
                PathPermissionRule.of("/api/system/enums/**", "GET", "yaml:enum:view"),
                PathPermissionRule.of("/api/orders/**", "GET", "yaml:order:view")
        ));

        AtengSaTokenInterceptor interceptor = new AtengSaTokenInterceptor(props);
        interceptor.isAnnotation(false);
        interceptor.setDynamicPathPermissionProvider(DynamicPathPermissionProvider.of(
                PathPermissionRule.of("/api/orders/**", "GET", "dynamic:order:view")
        ));

        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class)) {
            StpLogic loginLogic = mock(StpLogic.class);
            helperMock.when(() -> LoginHelper.getActiveLoginType(any())).thenReturn("login");
            helperMock.when(() -> LoginHelper.getStpLogic("login")).thenReturn(loginLogic);
            helperMock.when(() -> LoginHelper.isSuperAdmin("login")).thenReturn(false);

            // 命中动态规则优先
            when(request.getRequestURI()).thenReturn("/api/orders/list");
            when(request.getMethod()).thenReturn("GET");
            assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
            verify(loginLogic).checkPermissionOr("dynamic:order:view");
            verify(loginLogic, never()).checkPermissionOr("yaml:order:view");

            // 动态未命中回退 YAML 规则
            when(request.getRequestURI()).thenReturn("/api/system/enums/all");
            assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
            verify(loginLogic).checkPermissionOr("yaml:enum:view");
        }
    }

    @Test
    @DisplayName("3. check-login=true 多账号共享路径按请求实际活跃体系探测放行，不因 primary-login-type 误杀")
    void shouldAllowAnyActiveLoginTypeOnSharedPathsWhenCheckLoginEnabled() throws Exception {
        SaTokenProperties props = new SaTokenProperties();
        props.setPrimaryLoginType("admin");
        props.getInterceptor().setCheckLogin(true);
        Map<String, List<String>> routes = new LinkedHashMap<>();
        routes.put("admin", List.of("/admin/**"));
        routes.put("member", List.of("/app/**"));
        props.getInterceptor().setLoginTypeRoutes(routes);
        props.getInterceptor().setSharedLoginTypePaths(List.of("/api/system/enums/**", "/api/common/**"));

        AtengSaTokenInterceptor interceptor = new AtengSaTokenInterceptor(props);
        interceptor.isAnnotation(false);

        when(request.getRequestURI()).thenReturn("/api/system/enums/OrderStatus");
        when(request.getMethod()).thenReturn("GET");

        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class)) {
            // 3.1 仅 member 处于活跃登录态（即使 getActiveLoginType 返回 null 也应探测 loginTypeRoutes 声明的 member）
            helperMock.when(() -> LoginHelper.getActiveLoginType("admin")).thenReturn(null);
            helperMock.when(() -> LoginHelper.isLogin("admin")).thenReturn(false);
            helperMock.when(() -> LoginHelper.isLogin("member")).thenReturn(true);
            helperMock.when(() -> LoginHelper.checkLogin("member")).thenAnswer(inv -> null);
            helperMock.when(() -> LoginHelper.getUserId("member")).thenReturn("member-77");
            helperMock.when(() -> LoginHelper.getTenantId("member")).thenReturn("tenant-mall");

            assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
            helperMock.verify(() -> LoginHelper.checkLogin("member"));
            helperMock.verify(() -> LoginHelper.checkLogin("admin"), never());
            assertThat(SaTokenRouteContextHolder.get()).isEqualTo("member");
            assertThat(UserContextHolder.get()).isEqualTo("member-77");
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-mall");
            interceptor.afterCompletion(request, response, new Object(), null);

            // 3.2 全部体系均未登录时，回退 primary-login-type=admin 抛出 NotLoginException
            helperMock.when(() -> LoginHelper.isLogin("member")).thenReturn(false);
            helperMock.when(() -> LoginHelper.checkLogin("admin"))
                    .thenThrow(new NotLoginException("未登录", "admin", "-1"));
            assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                    .isInstanceOf(NotLoginException.class);
            assertThat(SaTokenRouteContextHolder.get()).isNull();
        }
    }

    @Test
    @DisplayName("4. check-login=true + YAML path-permissions 未绑定 loginType 时支持探测活跃体系并校验权限，显式路由仍严格隔离")
    void shouldProbeActiveLoginTypeForUnboundPathRuleWhileKeepingExplicitRouteStrict() throws Exception {
        SaTokenProperties props = new SaTokenProperties();
        props.setPrimaryLoginType("admin");
        props.getInterceptor().setCheckLogin(true);
        props.getInterceptor().setLoginTypeRoutes(Map.of("admin", List.of("/admin/**")));
        props.getInterceptor().setSharedLoginTypePaths(List.of("/admin/shared-attempt/**"));
        props.getInterceptor().setPathPermissions(List.of(
                PathPermissionRule.of("/api/system/enums/**", "GET", SaMode.OR, "system:enum:list")
        ));

        AtengSaTokenInterceptor interceptor = new AtengSaTokenInterceptor(props);
        interceptor.isAnnotation(false);

        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class)) {
            StpLogic memberLogic = mock(StpLogic.class);
            helperMock.when(() -> LoginHelper.getActiveLoginType("admin")).thenReturn("member");
            helperMock.when(() -> LoginHelper.checkLogin("member")).thenAnswer(inv -> null);
            helperMock.when(() -> LoginHelper.getStpLogic("member")).thenReturn(memberLogic);
            helperMock.when(() -> LoginHelper.isSuperAdmin("member")).thenReturn(false);
            helperMock.when(() -> LoginHelper.isLogin("member")).thenReturn(true);
            helperMock.when(() -> LoginHelper.getUserId("member")).thenReturn("member-88");

            // 4.1 命中未绑定 loginType 的 YAML path-permissions，由活跃的 member 体系放行并检查权限
            when(request.getRequestURI()).thenReturn("/api/system/enums/Gender");
            when(request.getMethod()).thenReturn("GET");
            assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
            helperMock.verify(() -> LoginHelper.checkLogin("member"));
            verify(memberLogic, never()).checkLogin();
            verify(memberLogic).checkPermissionOr("system:enum:list");
            assertThat(SaTokenRouteContextHolder.get()).isEqualTo("member");
            interceptor.afterCompletion(request, response, new Object(), null);

            // 4.2 命中显式 login-type-routes=/admin/** 时，即使也在 shared-login-type-paths 中，仍严格锁定 admin 拒绝 member
            when(request.getRequestURI()).thenReturn("/admin/shared-attempt/test");
            helperMock.when(() -> LoginHelper.checkLogin("admin"))
                    .thenThrow(new NotLoginException("未登录", "admin", "-1"));
            assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                    .isInstanceOf(NotLoginException.class);
        }
    }
}
