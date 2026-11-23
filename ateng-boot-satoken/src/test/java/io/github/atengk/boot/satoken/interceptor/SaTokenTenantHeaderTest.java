package io.github.atengk.boot.satoken.interceptor;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.config.SaTokenWebMvcConfigurer;
import io.github.atengk.boot.satoken.context.SaTokenRouteContextHolder;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.github.atengk.boot.satoken.spi.TenantHeaderSwitchValidator;
import io.github.atengk.boot.satoken.util.LoginHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * X-Tenant-Id 匿名租户解析、平台超管受控切换租户与白名单闭环单元测试
 *
 * @author Ateng
 * @since 2026-11-19
 */
@DisplayName("SaToken X-Tenant-Id 匿名解析与超管受控切租户单元测试")
class SaTokenTenantHeaderTest {

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
        UserContextHolder.setUserProvider(null);
        TenantContextHolder.setTenantProvider(null);
        DataPermissionContextHolder.clear();
        SaTokenRouteContextHolder.clear();
    }

    @Test
    @DisplayName("1. 默认配置下，普通已登录用户与匿名请求携带 X-Tenant-Id 均被严格忽略")
    void shouldIgnoreTenantHeaderByDefaultForNormalUserAndAnonymous() throws Exception {
        SaTokenProperties props = new SaTokenProperties();
        AtengSaTokenInterceptor interceptor = new AtengSaTokenInterceptor(props);
        interceptor.isAnnotation(false);
        interceptor.setWhitelist(List.of("/api/public/**"));

        // 1.1 普通已登录用户携带伪造 X-Tenant-Id -> 强制锁定会话租户
        when(request.getRequestURI()).thenReturn("/api/orders/list");
        when(request.getHeader("X-Tenant-Id")).thenReturn("tenant-evil");
        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class)) {
            helperMock.when(() -> LoginHelper.getActiveLoginType("login")).thenReturn("login");
            helperMock.when(() -> LoginHelper.getTenantId("login")).thenReturn("tenant-own");
            helperMock.when(() -> LoginHelper.getUserId("login")).thenReturn("user-1");
            helperMock.when(() -> LoginHelper.isSuperAdmin("login")).thenReturn(false);

            assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-own");

            interceptor.afterCompletion(request, response, new Object(), null);
            assertThat(TenantContextHolder.getTenantId()).isNull();
        }

        // 1.2 白名单匿名请求默认 allowAnonymousHeader=false -> 忽略 X-Tenant-Id
        when(request.getRequestURI()).thenReturn("/api/public/login");
        when(request.getHeader("X-Tenant-Id")).thenReturn("tenant-a");
        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class)) {
            helperMock.when(LoginHelper::isLogin).thenReturn(false);

            assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
            assertThat(TenantContextHolder.getTenantId()).isNull();
            interceptor.afterCompletion(request, response, new Object(), null);
        }
    }

    @Test
    @DisplayName("2. 开启 allowAnonymousHeader=true：匿名白名单解析合法头并清理；白名单带有效 Token 按已登录规则防绕过")
    void shouldParseAnonymousHeaderAndPreventLoggedInBypassOnWhitelist() throws Exception {
        SaTokenProperties props = new SaTokenProperties();
        props.getTenant().setAllowAnonymousHeader(true);
        AtengSaTokenInterceptor interceptor = new AtengSaTokenInterceptor(props);
        interceptor.isAnnotation(false);
        interceptor.setWhitelist(List.of("/api/public/**"));

        when(request.getRequestURI()).thenReturn("/api/public/config");
        when(request.getHeader("X-Tenant-Id")).thenReturn("tenant-001");

        // 2.1 真正匿名请求 -> 写入目标租户，并在 afterCompletion 清理
        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class)) {
            helperMock.when(LoginHelper::isLogin).thenReturn(false);

            assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-001");

            interceptor.afterCompletion(request, response, new Object(), null);
            assertThat(TenantContextHolder.getTenantId()).isNull();
        }

        // 2.2 白名单路径携带普通已登录用户 Token -> 按已登录规则锁定会话租户，防绕过
        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class)) {
            helperMock.when(() -> LoginHelper.getActiveLoginType("login")).thenReturn("login");
            helperMock.when(() -> LoginHelper.getTenantId("login")).thenReturn("tenant-real");
            helperMock.when(() -> LoginHelper.getUserId("login")).thenReturn("user-normal");
            helperMock.when(() -> LoginHelper.isSuperAdmin("login")).thenReturn(false);

            assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-real");

            interceptor.afterCompletion(request, response, new Object(), null);
            assertThat(TenantContextHolder.getTenantId()).isNull();
        }
    }

    @Test
    @DisplayName("3. 开启 allowSuperAdminSwitch=true：platformTenantIds 空不限制、非空须命中；超管可切租户且普通用户仍锁定")
    void shouldSupportSuperAdminSwitchWithOptionalPlatformTenantIdsConstraint() throws Exception {
        SaTokenProperties props = new SaTokenProperties();
        props.getTenant().setAllowSuperAdminSwitch(true);
        AtengSaTokenInterceptor interceptor = new AtengSaTokenInterceptor(props);
        interceptor.isAnnotation(false);

        when(request.getRequestURI()).thenReturn("/api/orders/list");
        when(request.getHeader("X-Tenant-Id")).thenReturn("tenant-target");

        // 3.1 platformTenantIds 为空时，任意超管均可切换
        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class)) {
            helperMock.when(() -> LoginHelper.getActiveLoginType("login")).thenReturn("login");
            helperMock.when(() -> LoginHelper.getTenantId("login")).thenReturn("tenant-any");
            helperMock.when(() -> LoginHelper.getUserId("login")).thenReturn("sa-1");
            helperMock.when(() -> LoginHelper.isSuperAdmin("login")).thenReturn(true);

            assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-target");

            interceptor.afterCompletion(request, response, new Object(), null);
            assertThat(TenantContextHolder.getTenantId()).isNull();
        }

        // 3.2 platformTenantIds 非空时，非平台租户超管忽略请求头锁定原租户，平台租户超管放行切换
        props.getTenant().setPlatformTenantIds(List.of("0", "platform"));
        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class)) {
            helperMock.when(() -> LoginHelper.getActiveLoginType("login")).thenReturn("login");
            helperMock.when(() -> LoginHelper.getUserId("login")).thenReturn("sa-2");
            helperMock.when(() -> LoginHelper.isSuperAdmin("login")).thenReturn(true);

            // 非平台租户超管 -> 锁定自身 tenant-biz
            helperMock.when(() -> LoginHelper.getTenantId("login")).thenReturn("tenant-biz");
            assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-biz");
            interceptor.afterCompletion(request, response, new Object(), null);

            // 平台租户超管 -> 允许切换为 tenant-target
            helperMock.when(() -> LoginHelper.getTenantId("login")).thenReturn("0");
            assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-target");
            interceptor.afterCompletion(request, response, new Object(), null);
        }
    }

    @Test
    @DisplayName("4. 非法格式或未通过 TenantHeaderSwitchValidator 校验时 Fail-Fast 抛出 BusinessException 并清理上下文")
    void shouldFailFastOnInvalidFormatOrValidatorRejection() {
        SaTokenProperties props = new SaTokenProperties();
        props.getTenant().setAllowAnonymousHeader(true);
        props.getTenant().setAllowSuperAdminSwitch(true);

        Set<String> allowedTenants = Set.of("tenant-active");
        TenantHeaderSwitchValidator validator = (targetTenantId, currentTenantId, loginType, anonymous) ->
                allowedTenants.contains(targetTenantId);

        AtengSaTokenInterceptor interceptor = new AtengSaTokenInterceptor(props);
        interceptor.isAnnotation(false);
        interceptor.setTenantHeaderSwitchValidator(validator);
        interceptor.setWhitelist(List.of("/api/public/**"));

        // 4.1 匿名请求非法格式（路径穿越 / 注入 / 超长）-> BAD_REQUEST
        when(request.getRequestURI()).thenReturn("/api/public/login");
        for (String badHeader : List.of("../etc", "tenant;drop", "a".repeat(65), "tenant id")) {
            when(request.getHeader("X-Tenant-Id")).thenReturn(badHeader);
            try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class)) {
                helperMock.when(LoginHelper::isLogin).thenReturn(false);
                assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                                .isEqualTo(CommonResultCode.BAD_REQUEST.getCode()));
                assertThat(TenantContextHolder.getTenantId()).isNull();
                assertThat(UserContextHolder.get()).isNull();
            }
        }

        // 4.2 匿名请求未通过 SPI 校验 -> FORBIDDEN
        when(request.getHeader("X-Tenant-Id")).thenReturn("tenant-disabled");
        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class)) {
            helperMock.when(LoginHelper::isLogin).thenReturn(false);
            assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(CommonResultCode.FORBIDDEN.getCode()));
            assertThat(TenantContextHolder.getTenantId()).isNull();
        }

        // 4.3 超管切换未通过 SPI 校验 -> FORBIDDEN 且所有上下文（含 User / DataPermission）被清理
        when(request.getRequestURI()).thenReturn("/api/orders/list");
        when(request.getHeader("X-Tenant-Id")).thenReturn("tenant-disabled");
        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class)) {
            helperMock.when(() -> LoginHelper.getActiveLoginType("login")).thenReturn("login");
            helperMock.when(() -> LoginHelper.getTenantId("login")).thenReturn("0");
            helperMock.when(() -> LoginHelper.getUserId("login")).thenReturn("sa-1");
            helperMock.when(() -> LoginHelper.isSuperAdmin("login")).thenReturn(true);

            assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(CommonResultCode.FORBIDDEN.getCode()));
            assertThat(TenantContextHolder.getTenantId()).isNull();
            assertThat(UserContextHolder.get()).isNull();
            assertThat(DataPermissionContextHolder.isSuperAdmin()).isFalse();
        }
    }

    @Test
    @DisplayName("5. SaTokenWebMvcConfigurer 不再向 Spring MVC 注册 excludePathPatterns，统一交由拦截器白名单处理")
    void shouldNotRegisterExcludePathPatternsInWebMvcConfigurer() {
        SaTokenProperties props = new SaTokenProperties();
        AtengSaTokenInterceptor interceptor = new AtengSaTokenInterceptor(props);
        SaTokenWebMvcConfigurer configurer = new SaTokenWebMvcConfigurer(props, interceptor);

        InterceptorRegistry registry = mock(InterceptorRegistry.class);
        InterceptorRegistration registration = mock(InterceptorRegistration.class);
        when(registry.addInterceptor(interceptor)).thenReturn(registration);
        when(registration.addPathPatterns(anyList())).thenReturn(registration);

        configurer.addInterceptors(registry);

        verify(registration).addPathPatterns(anyList());
        verify(registration, never()).excludePathPatterns(anyList());
        verify(registration, never()).excludePathPatterns(any(String[].class));
        assertThat(interceptor.getWhitelist()).contains("/doc.html", "/error", "/actuator/**");
    }

    @Test
    @DisplayName("6. 多账号路由白名单防跨端绕过：/admin/public/** 未登录 admin 但携带有效 member Token 时拒绝采信匿名 X-Tenant-Id")
    void shouldRejectAnonymousHeaderWhenOtherLoginTypeSessionIsActiveOnExplicitRouteWhitelist() throws Exception {
        SaTokenProperties props = new SaTokenProperties();
        props.getTenant().setAllowAnonymousHeader(true);
        props.getInterceptor().setLoginTypeRoutes(java.util.Map.of(
                "admin", List.of("/admin/**"),
                "member", List.of("/app/**")
        ));

        AtengSaTokenInterceptor interceptor = new AtengSaTokenInterceptor(props);
        interceptor.isAnnotation(false);
        interceptor.setWhitelist(List.of("/admin/public/**"));

        when(request.getRequestURI()).thenReturn("/admin/public/config");
        when(request.getHeader("X-Tenant-Id")).thenReturn("tenant-spoofed");

        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class)) {
            helperMock.when(() -> LoginHelper.isLogin("admin")).thenReturn(false);
            helperMock.when(() -> LoginHelper.isLogin("member")).thenReturn(true);
            helperMock.when(() -> LoginHelper.getActiveLoginType(any())).thenReturn("member");

            assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
            // 既不串染 member 身份，也绝不按匿名请求采信伪造的 X-Tenant-Id
            assertThat(UserContextHolder.get()).isNull();
            assertThat(TenantContextHolder.getTenantId()).isNull();
            interceptor.afterCompletion(request, response, new Object(), null);
        }
    }
}
