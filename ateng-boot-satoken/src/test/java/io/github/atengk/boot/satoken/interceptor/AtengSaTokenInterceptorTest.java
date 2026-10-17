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
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
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
}
