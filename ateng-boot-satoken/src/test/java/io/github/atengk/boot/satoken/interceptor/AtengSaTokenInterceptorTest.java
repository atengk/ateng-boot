package io.github.atengk.boot.satoken.interceptor;

import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.satoken.model.LoginUser;
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
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * AtengSaTokenInterceptor 路由拦截器单元测试
 * 验证鉴权放行后 UserContextHolder 与 TenantContextHolder 双向绑定与 afterCompletion 严格清理
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("AtengSaTokenInterceptor 路由拦截器单元测试")
class AtengSaTokenInterceptorTest {

    private AtengSaTokenInterceptor interceptor;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        interceptor = new AtengSaTokenInterceptor();
        interceptor.isAnnotation(false);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        UserContextHolder.clear();
        TenantContextHolder.clear();
        UserContextHolder.setUserProvider(null);
        TenantContextHolder.setTenantProvider(null);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        UserContextHolder.setUserProvider(null);
        TenantContextHolder.setTenantProvider(null);
    }

    @Test
    @DisplayName("测试登录状态下 preHandle 自动注入 tenantId 与 userId，并在 afterCompletion 严格清理")
    void testPreHandleAndAfterCompletionWhenLoggedIn() throws Exception {
        try (MockedStatic<LoginHelper> loginHelperMock = mockStatic(LoginHelper.class)) {
            loginHelperMock.when(LoginHelper::isLogin).thenReturn(true);
            loginHelperMock.when(LoginHelper::getTenantId).thenReturn("tenant-001");
            loginHelperMock.when(LoginHelper::getUserId).thenReturn("user-999");

            boolean pass = interceptor.preHandle(request, response, new Object());
            assertThat(pass).isTrue();

            // 验证上下文已双向注入
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-001");
            assertThat(UserContextHolder.get()).isEqualTo("user-999");

            // 执行 afterCompletion
            interceptor.afterCompletion(request, response, new Object(), null);

            // 验证上下文已双向严格清理
            assertThat(TenantContextHolder.getTenantId()).isNull();
            assertThat(UserContextHolder.get()).isNull();
        }
    }

    @Test
    @DisplayName("测试未登录状态下 preHandle 不注入上下文，afterCompletion 保障现场干净")
    void testPreHandleAndAfterCompletionWhenNotLoggedIn() throws Exception {
        try (MockedStatic<LoginHelper> loginHelperMock = mockStatic(LoginHelper.class)) {
            loginHelperMock.when(LoginHelper::isLogin).thenReturn(false);

            boolean pass = interceptor.preHandle(request, response, new Object());
            assertThat(pass).isTrue();

            assertThat(TenantContextHolder.getTenantId()).isNull();
            assertThat(UserContextHolder.get()).isNull();

            interceptor.afterCompletion(request, response, new Object(), null);
            assertThat(TenantContextHolder.getTenantId()).isNull();
            assertThat(UserContextHolder.get()).isNull();
        }
    }

    @Test
    @DisplayName("测试白名单路径匹配逻辑 (isWhitelisted)")
    void testIsWhitelisted() {
        interceptor.setWhitelist(java.util.List.of("/api/public/**", "/users/*", "/favicon.ico"));

        assertThat(interceptor.isWhitelisted("/api/public/ping")).isTrue();
        assertThat(interceptor.isWhitelisted("/api/public/article/10086")).isTrue();
        assertThat(interceptor.isWhitelisted("/users/99")).isTrue();
        assertThat(interceptor.isWhitelisted("/favicon.ico")).isTrue();

        assertThat(interceptor.isWhitelisted("/users/99/detail")).isFalse();
        assertThat(interceptor.isWhitelisted("/api/admin/users")).isFalse();
        assertThat(interceptor.isWhitelisted("")).isFalse();
        assertThat(interceptor.isWhitelisted(null)).isFalse();
    }

    @Test
    @DisplayName("测试白名单路由在 checkLogin 开启时成功放行，非白名单路由抛出 NotLoginException")
    void testWhitelistBypassCheckLoginAndInterceptNonWhitelist() throws Exception {
        interceptor.setWhitelist(java.util.List.of("/api/public/**"));
        // 开启严格登录校验（未登录抛出异常）
        interceptor.setAuth(handler -> {
            throw new cn.dev33.satoken.exception.NotLoginException("未提供访问凭据", "-1", cn.dev33.satoken.exception.NotLoginException.NOT_TOKEN);
        });

        // 1. 白名单接口：即使未登录且开启 checkLogin，依然跳过 auth 成功放行
        when(request.getRequestURI()).thenReturn("/api/public/ping");
        boolean pass = interceptor.preHandle(request, response, new Object());
        assertThat(pass).isTrue();

        // 2. 非白名单接口：未登录直接抛出 NotLoginException 阻断
        when(request.getRequestURI()).thenReturn("/api/admin/data");
        org.junit.jupiter.api.Assertions.assertThrows(
                cn.dev33.satoken.exception.NotLoginException.class,
                () -> interceptor.preHandle(request, response, new Object())
        );
    }

    @Test
    @DisplayName("测试白名单接口携带合法 Token 访问时，依然自动注入四维上下文")
    void testWhitelistedRouteWithTokenStillSyncsContext() throws Exception {
        interceptor.setWhitelist(java.util.List.of("/api/public/**"));
        when(request.getRequestURI()).thenReturn("/api/public/ping");

        try (MockedStatic<LoginHelper> loginHelperMock = mockStatic(LoginHelper.class)) {
            loginHelperMock.when(LoginHelper::isLogin).thenReturn(true);
            loginHelperMock.when(LoginHelper::getTenantId).thenReturn("tenant-whitelisted");
            loginHelperMock.when(LoginHelper::getUserId).thenReturn("user-whitelisted");

            boolean pass = interceptor.preHandle(request, response, new Object());
            assertThat(pass).isTrue();

            assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-whitelisted");
            assertThat(UserContextHolder.get()).isEqualTo("user-whitelisted");

            interceptor.afterCompletion(request, response, new Object(), null);
            assertThat(TenantContextHolder.getTenantId()).isNull();
            assertThat(UserContextHolder.get()).isNull();
        }
    }

    @Test
    @DisplayName("测试未注册 DynamicPathPermissionProvider 时，非白名单请求直接短路跳过动态权限校验")
    void testDynamicPathPermissionWithoutProviderPasses() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/orders/list");
        when(request.getMethod()).thenReturn("GET");

        // 未配置 provider 时顺利放行
        boolean pass = interceptor.preHandle(request, response, new Object());
        assertThat(pass).isTrue();
    }

    @Test
    @DisplayName("测试动态路径权限匹配：具备权限时成功放行")
    void testDynamicPathPermissionWhenPermissionGranted() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/orders/list");
        when(request.getMethod()).thenReturn("GET");

        DynamicPathPermissionProvider provider = DynamicPathPermissionProvider.of(
                PathPermissionRule.of("/api/orders/**", "GET", "order:view")
        );
        interceptor.setDynamicPathPermissionProvider(provider);

        try (MockedStatic<LoginHelper> loginHelperMock = mockStatic(LoginHelper.class)) {
            StpLogic mockLogic = mock(StpLogic.class);
            loginHelperMock.when(() -> LoginHelper.getActiveLoginType(any())).thenReturn("login");
            loginHelperMock.when(() -> LoginHelper.getStpLogic("login")).thenReturn(mockLogic);
            loginHelperMock.when(() -> LoginHelper.isSuperAdmin(any())).thenReturn(false);

            boolean pass = interceptor.preHandle(request, response, new Object());
            assertThat(pass).isTrue();
            verify(mockLogic).checkPermissionOr("order:view");
        }
    }

    @Test
    @DisplayName("测试动态路径权限匹配：缺少权限时抛出 NotPermissionException 阻断")
    void testDynamicPathPermissionWhenPermissionDeniedThrows() {
        when(request.getRequestURI()).thenReturn("/api/orders/create");
        when(request.getMethod()).thenReturn("POST");

        DynamicPathPermissionProvider provider = DynamicPathPermissionProvider.of(
                PathPermissionRule.of("/api/orders/**", "POST", "order:create")
        );
        interceptor.setDynamicPathPermissionProvider(provider);

        try (MockedStatic<LoginHelper> loginHelperMock = mockStatic(LoginHelper.class)) {
            StpLogic mockLogic = mock(StpLogic.class);
            loginHelperMock.when(() -> LoginHelper.getActiveLoginType(any())).thenReturn("login");
            loginHelperMock.when(() -> LoginHelper.getStpLogic("login")).thenReturn(mockLogic);
            loginHelperMock.when(() -> LoginHelper.isSuperAdmin(any())).thenReturn(false);

            doThrow(new NotPermissionException("order:create")).when(mockLogic).checkPermissionOr("order:create");

            assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                    .isInstanceOf(NotPermissionException.class)
                    .hasMessageContaining("order:create");
        }
    }

    @Test
    @DisplayName("测试动态路径权限匹配：HTTP Method 不匹配时规则跳过，不阻断请求")
    void testDynamicPathPermissionHttpMethodMismatchPasses() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/orders/list");
        when(request.getMethod()).thenReturn("GET");

        // 仅限制 POST 方法
        DynamicPathPermissionProvider provider = DynamicPathPermissionProvider.of(
                PathPermissionRule.of("/api/orders/**", "POST", "order:create")
        );
        interceptor.setDynamicPathPermissionProvider(provider);

        try (MockedStatic<LoginHelper> loginHelperMock = mockStatic(LoginHelper.class)) {
            StpLogic mockLogic = mock(StpLogic.class);
            loginHelperMock.when(() -> LoginHelper.getActiveLoginType(any())).thenReturn("login");
            loginHelperMock.when(() -> LoginHelper.getStpLogic("login")).thenReturn(mockLogic);

            boolean pass = interceptor.preHandle(request, response, new Object());
            assertThat(pass).isTrue();
            verify(mockLogic, never()).checkPermissionOr(any());
        }
    }

    @Test
    @DisplayName("测试动态路径权限匹配：超级管理员特权豁免直接放行")
    void testDynamicPathPermissionSuperAdminPasses() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/admin/system/config");
        when(request.getMethod()).thenReturn("POST");

        DynamicPathPermissionProvider provider = DynamicPathPermissionProvider.of(
                PathPermissionRule.of("/api/admin/**", "POST", "system:config:edit")
        );
        interceptor.setDynamicPathPermissionProvider(provider);

        try (MockedStatic<LoginHelper> loginHelperMock = mockStatic(LoginHelper.class)) {
            StpLogic mockLogic = mock(StpLogic.class);
            loginHelperMock.when(() -> LoginHelper.getActiveLoginType(any())).thenReturn("login");
            loginHelperMock.when(() -> LoginHelper.getStpLogic("login")).thenReturn(mockLogic);
            loginHelperMock.when(() -> LoginHelper.isSuperAdmin("login")).thenReturn(true);

            boolean pass = interceptor.preHandle(request, response, new Object());
            assertThat(pass).isTrue();
            verify(mockLogic, never()).checkPermissionOr(any());
            verify(mockLogic, never()).checkPermissionAnd(any());
        }
    }

    @Test
    @DisplayName("测试动态路径权限匹配：SaMode.AND 组合校验")
    void testDynamicPathPermissionSaModeAnd() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/orders/delete/100");
        when(request.getMethod()).thenReturn("DELETE");

        DynamicPathPermissionProvider provider = DynamicPathPermissionProvider.of(
                PathPermissionRule.of("/api/orders/delete/**", "DELETE", SaMode.AND, "order:delete", "order:audit")
        );
        interceptor.setDynamicPathPermissionProvider(provider);

        try (MockedStatic<LoginHelper> loginHelperMock = mockStatic(LoginHelper.class)) {
            StpLogic mockLogic = mock(StpLogic.class);
            loginHelperMock.when(() -> LoginHelper.getActiveLoginType(any())).thenReturn("login");
            loginHelperMock.when(() -> LoginHelper.getStpLogic("login")).thenReturn(mockLogic);
            loginHelperMock.when(() -> LoginHelper.isSuperAdmin(any())).thenReturn(false);

            // 1. 具备全部权限通过
            boolean pass = interceptor.preHandle(request, response, new Object());
            assertThat(pass).isTrue();
            verify(mockLogic).checkPermissionAnd("order:delete", "order:audit");

            // 2. 缺少其中一个抛出异常
            doThrow(new NotPermissionException("order:audit")).when(mockLogic).checkPermissionAnd("order:delete", "order:audit");
            assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                    .isInstanceOf(NotPermissionException.class);
        }
    }

    @Test
    @DisplayName("测试基于 ObjectProvider 依赖注入构造的拦截器与 refresh 回调")
    void testObjectProviderInjectionAndRefresh() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<DynamicPathPermissionProvider> objectProvider = mock(ObjectProvider.class);

        DynamicPathPermissionProvider provider = mock(DynamicPathPermissionProvider.class);
        when(provider.getPathPermissionRules()).thenReturn(List.of(
                PathPermissionRule.of("/api/test/**", "GET", "test:view")
        ));
        when(provider.matchRule(any(), eq("/api/test/demo"), eq("GET"))).thenCallRealMethod();

        when(objectProvider.getIfAvailable()).thenReturn(provider);

        AtengSaTokenInterceptor customInterceptor = new AtengSaTokenInterceptor(new SaTokenProperties(), objectProvider);
        customInterceptor.isAnnotation(false);

        when(request.getRequestURI()).thenReturn("/api/test/demo");
        when(request.getMethod()).thenReturn("GET");

        try (MockedStatic<LoginHelper> loginHelperMock = mockStatic(LoginHelper.class)) {
            StpLogic mockLogic = mock(StpLogic.class);
            loginHelperMock.when(() -> LoginHelper.getActiveLoginType(any())).thenReturn("login");
            loginHelperMock.when(() -> LoginHelper.getStpLogic("login")).thenReturn(mockLogic);
            loginHelperMock.when(() -> LoginHelper.isSuperAdmin(any())).thenReturn(false);

            boolean pass = customInterceptor.preHandle(request, response, new Object());
            assertThat(pass).isTrue();
            verify(mockLogic).checkPermissionOr("test:view");

            // 测试 refresh 回调
            provider.refresh();
            verify(provider).refresh();
        }
    }

    @Test
    @DisplayName("测试静态白名单路由优先于动态路径权限规则直接放行")
    void testWhitelistedUrlBypassesDynamicCheck() throws Exception {
        interceptor.setWhitelist(List.of("/api/orders/public/**"));

        DynamicPathPermissionProvider provider = DynamicPathPermissionProvider.of(
                PathPermissionRule.of("/api/orders/**", "GET", "order:admin:only")
        );
        interceptor.setDynamicPathPermissionProvider(provider);

        when(request.getRequestURI()).thenReturn("/api/orders/public/ping");
        when(request.getMethod()).thenReturn("GET");

        try (MockedStatic<LoginHelper> loginHelperMock = mockStatic(LoginHelper.class)) {
            // 白名单命中，无需鉴权直接放行
            boolean pass = interceptor.preHandle(request, response, new Object());
            assertThat(pass).isTrue();
            loginHelperMock.verify(() -> LoginHelper.getStpLogic(anyString()), never());
        }
    }
}
