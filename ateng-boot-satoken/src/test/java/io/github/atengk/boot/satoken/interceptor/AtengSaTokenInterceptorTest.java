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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder.clear();
        io.github.atengk.boot.satoken.context.SaTokenRouteContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        UserContextHolder.setUserProvider(null);
        TenantContextHolder.setTenantProvider(null);
        io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder.clear();
        io.github.atengk.boot.satoken.context.SaTokenRouteContextHolder.clear();
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

    @Test
    @DisplayName("测试多线程并发追加白名单：基于 ReentrantLock 写锁保护不丢失规则且视图不可变")
    void testConcurrentAddWhitelistSafety() throws Exception {
        int threads = 8;
        int pathsPerThread = 25;
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(threads);
        java.util.List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(threads);
        executor.setThreadNamePrefix("test-whitelist-");
        executor.initialize();

        try {
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                executor.execute(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < pathsPerThread; i++) {
                            interceptor.addWhitelist(List.of("/api/concurrent/" + tid + "/" + i));
                        }
                    } catch (Throwable ex) {
                        errors.add(ex);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            startLatch.countDown();
            assertThat(doneLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
        }
        assertThat(errors).isEmpty();
        assertThat(interceptor.getWhitelist()).hasSize(threads * pathsPerThread);
        assertThat(interceptor.isWhitelisted("/api/concurrent/7/24")).isTrue();
    }

    @Test
    @DisplayName("测试动态路径权限绑定 loginType：按规则指定账号体系路由校验登录态与权限")
    void testDynamicPathPermissionRoutesToRuleLoginType() throws Exception {
        DynamicPathPermissionProvider provider = DynamicPathPermissionProvider.of(
                PathPermissionRule.of("/api/admin/audit/**", "POST", "admin", SaMode.OR, "audit:write"),
                PathPermissionRule.of("/api/admin/login-only/**", "GET", "admin", SaMode.OR, List.of())
        );
        interceptor.setDynamicPathPermissionProvider(provider);

        try (MockedStatic<LoginHelper> loginHelperMock = mockStatic(LoginHelper.class)) {
            StpLogic adminLogic = mock(StpLogic.class);
            StpLogic memberLogic = mock(StpLogic.class);
            loginHelperMock.when(() -> LoginHelper.getActiveLoginType(any())).thenReturn("member");
            loginHelperMock.when(() -> LoginHelper.getStpLogic("admin")).thenReturn(adminLogic);
            loginHelperMock.when(() -> LoginHelper.getStpLogic("member")).thenReturn(memberLogic);
            loginHelperMock.when(() -> LoginHelper.isSuperAdmin("admin")).thenReturn(false);

            // 1. 命中绑定 admin 权限的动态规则
            when(request.getRequestURI()).thenReturn("/api/admin/audit/submit");
            when(request.getMethod()).thenReturn("POST");
            assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
            verify(adminLogic).checkLogin();
            verify(adminLogic).checkPermissionOr("audit:write");
            verify(memberLogic, never()).checkPermissionOr(any());

            // 2. 命中仅绑定 admin 登录态（空权限集）的规则时仍强制校验 admin 登录态
            when(request.getRequestURI()).thenReturn("/api/admin/login-only/status");
            when(request.getMethod()).thenReturn("GET");
            doThrow(new cn.dev33.satoken.exception.NotLoginException("未登录", "admin", "-1"))
                    .when(adminLogic).checkLogin();
            assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                    .isInstanceOf(cn.dev33.satoken.exception.NotLoginException.class);
        }
    }

    @Test
    @DisplayName("测试 check-login=true 未配置路径路由时按 primary-login-type 校验并同步上下文")
    void testCheckLoginFallbackToPrimaryLoginType() throws Exception {
        SaTokenProperties props = new SaTokenProperties();
        props.setPrimaryLoginType("admin");
        props.getInterceptor().setCheckLogin(true);
        AtengSaTokenInterceptor checkInterceptor = new AtengSaTokenInterceptor(props);
        checkInterceptor.isAnnotation(false);

        when(request.getRequestURI()).thenReturn("/api/dashboard/stats");

        try (MockedStatic<LoginHelper> loginHelperMock = mockStatic(LoginHelper.class)) {
            loginHelperMock.when(() -> LoginHelper.checkLogin("admin")).thenAnswer(inv -> null);
            loginHelperMock.when(() -> LoginHelper.getActiveLoginType("admin")).thenReturn("admin");
            loginHelperMock.when(() -> LoginHelper.getTenantId("admin")).thenReturn("tenant-admin");
            loginHelperMock.when(() -> LoginHelper.getUserId("admin")).thenReturn("admin-100");

            assertThat(checkInterceptor.preHandle(request, response, new Object())).isTrue();
            loginHelperMock.verify(() -> LoginHelper.checkLogin("admin"));
            loginHelperMock.verify(() -> LoginHelper.checkLogin("login"), never());
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-admin");
            assertThat(UserContextHolder.get()).isEqualTo("admin-100");

            checkInterceptor.afterCompletion(request, response, new Object(), null);
            assertThat(TenantContextHolder.getTenantId()).isNull();
            assertThat(UserContextHolder.get()).isNull();
        }
    }

    @Test
    @DisplayName("测试 check-login=true 按 login-type-routes 路由校验多账号体系（含重叠路径最精确优先与未命中兜底）")
    void testCheckLoginWithLoginTypeRoutesAndSpecificity() throws Exception {
        SaTokenProperties props = new SaTokenProperties();
        props.setPrimaryLoginType("login");
        props.getInterceptor().setCheckLogin(true);
        Map<String, List<String>> routes = new LinkedHashMap<>();
        routes.put("admin", List.of("/admin/**", "sys/**"));
        routes.put("member", List.of("/admin/open/**", "/app/**"));
        props.getInterceptor().setLoginTypeRoutes(routes);

        AtengSaTokenInterceptor routeInterceptor = new AtengSaTokenInterceptor(props);
        routeInterceptor.isAnnotation(false);

        assertThat(routeInterceptor.resolveRouteLoginType("/admin/users")).isEqualTo("admin");
        assertThat(routeInterceptor.resolveRouteLoginType("/sys/dict")).isEqualTo("admin");
        assertThat(routeInterceptor.resolveRouteLoginType("/admin/open/profile")).isEqualTo("member");
        assertThat(routeInterceptor.resolveRouteLoginType("/app/orders")).isEqualTo("member");
        assertThat(routeInterceptor.resolveRouteLoginType("/other/ping")).isNull();

        try (MockedStatic<LoginHelper> loginHelperMock = mockStatic(LoginHelper.class)) {
            loginHelperMock.when(() -> LoginHelper.checkLogin(anyString())).thenAnswer(inv -> null);
            loginHelperMock.when(() -> LoginHelper.isLogin("admin")).thenReturn(true);
            loginHelperMock.when(() -> LoginHelper.getUserId("admin")).thenReturn("admin-01");
            loginHelperMock.when(() -> LoginHelper.isLogin("member")).thenReturn(true);
            loginHelperMock.when(() -> LoginHelper.getUserId("member")).thenReturn("member-01");
            loginHelperMock.when(() -> LoginHelper.getActiveLoginType("login")).thenReturn("login");
            loginHelperMock.when(() -> LoginHelper.getUserId("login")).thenReturn("login-01");

            when(request.getRequestURI()).thenReturn("/admin/users");
            assertThat(routeInterceptor.preHandle(request, response, new Object())).isTrue();
            loginHelperMock.verify(() -> LoginHelper.checkLogin("admin"));
            assertThat(UserContextHolder.get()).isEqualTo("admin-01");
            routeInterceptor.afterCompletion(request, response, new Object(), null);

            when(request.getRequestURI()).thenReturn("/admin/open/profile");
            assertThat(routeInterceptor.preHandle(request, response, new Object())).isTrue();
            loginHelperMock.verify(() -> LoginHelper.checkLogin("member"));
            assertThat(UserContextHolder.get()).isEqualTo("member-01");
            routeInterceptor.afterCompletion(request, response, new Object(), null);

            when(request.getRequestURI()).thenReturn("/other/ping");
            assertThat(routeInterceptor.preHandle(request, response, new Object())).isTrue();
            loginHelperMock.verify(() -> LoginHelper.checkLogin("login"));
            assertThat(UserContextHolder.get()).isEqualTo("login-01");
        }
    }

    @Test
    @DisplayName("测试 check-login=true 与 DynamicPathPermissionProvider 协同优先级及去重校验")
    void testCheckLoginPriorityWithDynamicPathPermissionProvider() throws Exception {
        SaTokenProperties props = new SaTokenProperties();
        props.setPrimaryLoginType("login");
        props.getInterceptor().setCheckLogin(true);
        Map<String, List<String>> routes = new LinkedHashMap<>();
        routes.put("member", List.of("/api/orders/**", "/api/ops/**"));
        props.getInterceptor().setLoginTypeRoutes(routes);

        DynamicPathPermissionProvider provider = DynamicPathPermissionProvider.of(
                // 显式指定 ops 体系，优先级高于 YAML 的 member 路由
                PathPermissionRule.of("/api/ops/**", "POST", "ops", SaMode.OR, "ops:deploy"),
                // 未指定 loginType（仅配置权限码），应继承 YAML 路由的 member 体系
                PathPermissionRule.of("/api/orders/**", "GET", "order:view")
        );

        AtengSaTokenInterceptor combinedInterceptor = new AtengSaTokenInterceptor(props);
        combinedInterceptor.isAnnotation(false);
        combinedInterceptor.setDynamicPathPermissionProvider(provider);

        try (MockedStatic<LoginHelper> loginHelperMock = mockStatic(LoginHelper.class)) {
            StpLogic opsLogic = mock(StpLogic.class);
            StpLogic memberLogic = mock(StpLogic.class);
            loginHelperMock.when(() -> LoginHelper.checkLogin(anyString())).thenAnswer(inv -> null);
            loginHelperMock.when(() -> LoginHelper.getStpLogic("ops")).thenReturn(opsLogic);
            loginHelperMock.when(() -> LoginHelper.getStpLogic("member")).thenReturn(memberLogic);
            loginHelperMock.when(() -> LoginHelper.isSuperAdmin(anyString())).thenReturn(false);
            loginHelperMock.when(() -> LoginHelper.isLogin(anyString())).thenReturn(true);

            // 1. 动态规则显式指定 ops 覆盖 YAML 路由 member
            when(request.getRequestURI()).thenReturn("/api/ops/release");
            when(request.getMethod()).thenReturn("POST");
            assertThat(combinedInterceptor.preHandle(request, response, new Object())).isTrue();
            loginHelperMock.verify(() -> LoginHelper.checkLogin("ops"));
            verify(opsLogic, never()).checkLogin();
            verify(opsLogic).checkPermissionOr("ops:deploy");

            // 2. 动态规则未指定 loginType，继承 YAML 路由 member
            when(request.getRequestURI()).thenReturn("/api/orders/list");
            when(request.getMethod()).thenReturn("GET");
            assertThat(combinedInterceptor.preHandle(request, response, new Object())).isTrue();
            loginHelperMock.verify(() -> LoginHelper.checkLogin("member"));
            verify(memberLogic, never()).checkLogin();
            verify(memberLogic).checkPermissionOr("order:view");
        }
    }

    @Test
    @DisplayName("测试外部自定义 auth 优先接管 check-login，且 setAuth(null) 可恢复内置校验")
    void testCustomAuthOverridesCheckLoginAndResetRestores() throws Exception {
        SaTokenProperties props = new SaTokenProperties();
        props.setPrimaryLoginType("admin");
        props.getInterceptor().setCheckLogin(true);

        AtengSaTokenInterceptor customInterceptor = new AtengSaTokenInterceptor(handler -> {
            throw new IllegalStateException("custom-auth-invoked");
        }, props);
        customInterceptor.isAnnotation(false);
        assertThat(customInterceptor.isCustomAuthConfigured()).isTrue();

        when(request.getRequestURI()).thenReturn("/api/custom/test");
        assertThatThrownBy(() -> customInterceptor.preHandle(request, response, new Object()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("custom-auth-invoked");

        // 重置为 null 后恢复内置 check-login 路由校验
        customInterceptor.setAuth(null);
        assertThat(customInterceptor.isCustomAuthConfigured()).isFalse();
        try (MockedStatic<LoginHelper> loginHelperMock = mockStatic(LoginHelper.class)) {
            loginHelperMock.when(() -> LoginHelper.checkLogin("admin")).thenAnswer(inv -> null);
            assertThat(customInterceptor.preHandle(request, response, new Object())).isTrue();
            loginHelperMock.verify(() -> LoginHelper.checkLogin("admin"));
        }
    }

    @Test
    @DisplayName("测试 check-login=false 时 login-type-routes 不强制拦截，但精准对齐上下文防止跨账号串号")
    void testCheckLoginDisabledWithLoginTypeRoutesAlignsContextWithoutBlocking() throws Exception {
        SaTokenProperties props = new SaTokenProperties();
        props.setPrimaryLoginType("login");
        props.getInterceptor().setCheckLogin(false);
        props.getInterceptor().setLoginTypeRoutes(Map.of("member", List.of("/app/**")));

        AtengSaTokenInterceptor passiveInterceptor = new AtengSaTokenInterceptor(props);
        passiveInterceptor.isAnnotation(false);

        when(request.getRequestURI()).thenReturn("/app/orders/1");
        try (MockedStatic<LoginHelper> loginHelperMock = mockStatic(LoginHelper.class)) {
            // 仅 login 体系登录、member 未登录时：不抛 401，也不将 login 身份错误注入 member 路由上下文
            loginHelperMock.when(() -> LoginHelper.isLogin("member")).thenReturn(false);
            loginHelperMock.when(() -> LoginHelper.isLogin("login")).thenReturn(true);
            loginHelperMock.when(() -> LoginHelper.getUserId("login")).thenReturn("login-user");

            assertThat(passiveInterceptor.preHandle(request, response, new Object())).isTrue();
            loginHelperMock.verify(() -> LoginHelper.checkLogin(anyString()), never());
            assertThat(UserContextHolder.get()).isNull();

            // member 已登录时精准同步 member 上下文
            loginHelperMock.when(() -> LoginHelper.isLogin("member")).thenReturn(true);
            loginHelperMock.when(() -> LoginHelper.getUserId("member")).thenReturn("member-user");
            assertThat(passiveInterceptor.preHandle(request, response, new Object())).isTrue();
            assertThat(UserContextHolder.get()).isEqualTo("member-user");
        }
    }

    @Test
    @DisplayName("测试弱依赖自动桥接 DataPermissionContextHolder：超管注入与普通用户前置重置，afterCompletion 与异常时清理")
    void testDataPermissionSuperAdminBridgeAndResetCleanup() throws Exception {
        SaTokenProperties props = new SaTokenProperties();
        props.setPrimaryLoginType("admin");
        AtengSaTokenInterceptor bridgeInterceptor = new AtengSaTokenInterceptor(props);
        bridgeInterceptor.isAnnotation(false);

        when(request.getRequestURI()).thenReturn("/api/orders/list");

        try (MockedStatic<LoginHelper> loginHelperMock = mockStatic(LoginHelper.class)) {
            // 1. 模拟线程残留上一请求的脏 superAdmin 状态
            io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder.setSuperAdmin(true);

            // 普通用户进入 preHandle，应先重置为 false 再写入 false
            loginHelperMock.when(() -> LoginHelper.getActiveLoginType("admin")).thenReturn("admin");
            loginHelperMock.when(() -> LoginHelper.getUserId("admin")).thenReturn("user-1");
            loginHelperMock.when(() -> LoginHelper.isSuperAdmin("admin")).thenReturn(false);

            assertThat(bridgeInterceptor.preHandle(request, response, new Object())).isTrue();
            assertThat(io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder.isSuperAdmin()).isFalse();

            bridgeInterceptor.afterCompletion(request, response, new Object(), null);
            assertThat(io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder.isSuperAdmin()).isFalse();

            // 2. 超级管理员进入 preHandle，自动注入 true，afterCompletion 清理为 false
            loginHelperMock.when(() -> LoginHelper.isSuperAdmin("admin")).thenReturn(true);
            assertThat(bridgeInterceptor.preHandle(request, response, new Object())).isTrue();
            assertThat(io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder.isSuperAdmin()).isTrue();

            bridgeInterceptor.afterCompletion(request, response, new Object(), null);
            assertThat(io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder.isSuperAdmin()).isFalse();

            // 3. preHandle 中途抛出鉴权异常时，残留 superAdmin 状态亦应被重置清理
            io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder.setSuperAdmin(true);
            props.getInterceptor().setCheckLogin(true);
            loginHelperMock.when(() -> LoginHelper.checkLogin("admin"))
                    .thenThrow(new cn.dev33.satoken.exception.NotLoginException("未登录", "admin", "-1"));
            assertThatThrownBy(() -> bridgeInterceptor.preHandle(request, response, new Object()))
                    .isInstanceOf(cn.dev33.satoken.exception.NotLoginException.class);
            assertThat(io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder.isSuperAdmin()).isFalse();
        }
    }

    @Test
    @DisplayName("测试 preHandle（含白名单分支）写入路由 loginType 上下文，afterCompletion 与异常分支确定性清理")
    void testRouteLoginTypeContextLifecycleIncludingWhitelist() throws Exception {
        SaTokenProperties props = new SaTokenProperties();
        props.getInterceptor().setLoginTypeRoutes(Map.of("admin", List.of("/admin/**")));
        AtengSaTokenInterceptor routeInterceptor = new AtengSaTokenInterceptor(props);
        routeInterceptor.isAnnotation(false);
        routeInterceptor.setWhitelist(List.of("/admin/public/**"));

        try (MockedStatic<LoginHelper> loginHelperMock = mockStatic(LoginHelper.class)) {
            // 1. 白名单分支命中 /admin/public/**：应写入路由 loginType="admin"，afterCompletion 清理
            when(request.getRequestURI()).thenReturn("/admin/public/ping");
            assertThat(routeInterceptor.preHandle(request, response, new Object())).isTrue();
            assertThat(io.github.atengk.boot.satoken.context.SaTokenRouteContextHolder.get()).isEqualTo("admin");

            routeInterceptor.afterCompletion(request, response, new Object(), null);
            assertThat(io.github.atengk.boot.satoken.context.SaTokenRouteContextHolder.get()).isNull();

            // 2. 非白名单分支命中 /admin/users：写入 "admin"，afterCompletion 清理
            when(request.getRequestURI()).thenReturn("/admin/users");
            assertThat(routeInterceptor.preHandle(request, response, new Object())).isTrue();
            assertThat(io.github.atengk.boot.satoken.context.SaTokenRouteContextHolder.get()).isEqualTo("admin");

            routeInterceptor.afterCompletion(request, response, new Object(), null);
            assertThat(io.github.atengk.boot.satoken.context.SaTokenRouteContextHolder.get()).isNull();

            // 3. 非白名单分支中途抛出异常：preHandle 清理路由上下文，不泄漏
            props.getInterceptor().setCheckLogin(true);
            loginHelperMock.when(() -> LoginHelper.checkLogin("admin"))
                    .thenThrow(new cn.dev33.satoken.exception.NotLoginException("未登录", "admin", "-1"));
            assertThatThrownBy(() -> routeInterceptor.preHandle(request, response, new Object()))
                    .isInstanceOf(cn.dev33.satoken.exception.NotLoginException.class);
            assertThat(io.github.atengk.boot.satoken.context.SaTokenRouteContextHolder.get()).isNull();
        }
    }
}
