package io.github.atengk.boot.satoken.interceptor;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.util.LoginHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
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
}
