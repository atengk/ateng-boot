package io.github.atengk.boot.admin.controller;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.filter.SaServletFilter;
import cn.dev33.satoken.fun.strategy.SaRouteMatchFunction;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.strategy.SaStrategy;
import cn.dev33.satoken.stp.StpUtil;
import de.codecentric.boot.admin.server.config.AdminServerProperties;
import io.github.atengk.boot.admin.autoconfigure.AtengAdminServerAutoConfiguration;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.provider.AdminAuthProvider;
import io.github.atengk.boot.admin.provider.DefaultPropertiesAdminAuthProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.AntPathMatcher;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AdminLoginController 登录控制器单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("AdminLoginController 登录控制器单元测试")
class AdminLoginControllerTest {

    private MockMvc mockMvc;
    private AdminAuthProvider authProvider;
    private AdminServerProperties adminServerProperties;
    private MockedStatic<StpUtil> stpUtilMock;

    @BeforeEach
    void setUp() {
        stpUtilMock = mockStatic(StpUtil.class);
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        properties.setUsername("admin");
        properties.setPassword("admin");
        authProvider = new DefaultPropertiesAdminAuthProvider(properties);
        adminServerProperties = new AdminServerProperties();

        AdminLoginController controller = new AdminLoginController(authProvider, adminServerProperties);
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .defaultResponseCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (stpUtilMock != null) {
            stpUtilMock.close();
        }
    }

    @Test
    @DisplayName("GET /login 成功渲染内置原生 HTML 登录页面")
    void shouldRenderLoginPage() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ateng-boot Admin")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("action='/login'")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("type=\"password\"")));
    }

    @Test
    @DisplayName("GET /login?error=true 渲染错误提示信息")
    void shouldRenderErrorMessageOnLoginFailure() throws Exception {
        mockMvc.perform(get("/login").param("error", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("账号或密码错误")));
    }

    @Test
    @DisplayName("GET /login?logout=true 渲染安全退出提示信息")
    void shouldRenderLogoutMessage() throws Exception {
        mockMvc.perform(get("/login").param("logout", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("您已安全退出控制台")));
    }

    @Test
    @DisplayName("POST /login 认证成功时 302 重定向至控制台首页并植入会话")
    void shouldRedirectToHomeOnSuccessfulLogin() throws Exception {
        mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "admin")
                        .param("password", "admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        stpUtilMock.verify(() -> StpUtil.login("admin"));
    }

    @Test
    @DisplayName("POST /login 密码错误时 302 重定向至 /login?error=true 且不植入会话")
    void shouldRedirectToErrorOnBadCredentials() throws Exception {
        mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "admin")
                        .param("password", "wrong"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error=true"));

        stpUtilMock.verify(() -> StpUtil.login(anyString()), never());
    }

    @Test
    @DisplayName("POST /logout 安全注销会话并 302 重定向至 /login?logout=true")
    void shouldLogoutSuccessfully() throws Exception {
        mockMvc.perform(post("/logout"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout=true"));

        stpUtilMock.verify(StpUtil::logout);
    }

    @Test
    @DisplayName("支持自定义 contextPath 时动态调整表单 action 与重定向目标")
    void shouldRespectCustomContextPath() throws Exception {
        adminServerProperties.setContextPath("/admin");
        AdminLoginController controller = new AdminLoginController(authProvider, adminServerProperties);
        MockMvc customMvc = MockMvcBuilders.standaloneSetup(controller)
                .defaultResponseCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8)
                .build();

        // 验证表单 action
        customMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("action='/admin/login'")));

        // 验证登录成功重定向至 /admin
        customMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "admin")
                        .param("password", "admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"));

        // 验证失败重定向至 /admin/login?error=true
        customMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "admin")
                        .param("password", "wrong"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login?error=true"));

        // 验证退出重定向至 /admin/login?logout=true
        customMvc.perform(post("/logout"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login?logout=true"));
    }

    @Test
    @DisplayName("挂载 SaServletFilter 时，未登录访问受保护路径触发 302 重定向至登录页，Ajax/JSON 请求返回 401")
    void shouldInterceptUnauthenticatedAccessViaSaFilter() throws Exception {
        adminServerProperties.setContextPath("/admin");
        AdminLoginController controller = new AdminLoginController(authProvider, adminServerProperties);

        @SuppressWarnings("unchecked")
        ObjectProvider<AdminServerProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(adminServerProperties);

        AtengAdminServerAutoConfiguration autoConfig = new AtengAdminServerAutoConfiguration();
        SaServletFilter filter = autoConfig.adminSaServletFilter(provider);

        MockMvc filterMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(filter)
                .defaultResponseCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8)
                .build();

        stpUtilMock.when(StpUtil::checkLogin).thenThrow(new NotLoginException("未登录", "-1", "-1"));

        SaRouteMatchFunction originalRouteMatcher = SaStrategy.instance.routeMatcher;
        AntPathMatcher antPathMatcher = new AntPathMatcher();
        SaStrategy.instance.routeMatcher = antPathMatcher::match;
        SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
        try {
            // 1. 未登录 HTML 访问受保护看板页面 /admin，自动 302 重定向至 /admin/login
            filterMvc.perform(get("/admin").accept(MediaType.TEXT_HTML))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/admin/login"));

            // 2. 未登录 Ajax / JSON 请求受保护资源，返回 401 Unauthorized
            filterMvc.perform(get("/admin/instances").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401))
                    .andExpect(jsonPath("$.message").value("Unauthorized"));

            // 3. 访问登录页面处于放行白名单，不受登录拦截
            filterMvc.perform(get("/login"))
                    .andExpect(status().isOk());
        } finally {
            SaStrategy.instance.routeMatcher = originalRouteMatcher;
            SaManager.setSaTokenContext(null);
        }
    }
}
