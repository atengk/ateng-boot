package io.github.atengk.boot.admin.security;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.secure.BCrypt;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.stp.StpUtil;
import de.codecentric.boot.admin.server.config.AdminServerAutoConfiguration;
import de.codecentric.boot.admin.server.config.AdminServerMarkerConfiguration;
import de.codecentric.boot.admin.server.ui.config.AdminServerUiAutoConfiguration;
import de.codecentric.boot.admin.server.ui.web.UiController;
import io.github.atengk.boot.admin.audit.AdminAuditAction;
import io.github.atengk.boot.admin.audit.AdminAuditEvent;
import io.github.atengk.boot.admin.audit.AdminAuditLogRepository;
import io.github.atengk.boot.admin.autoconfigure.AtengAdminServerAutoConfiguration;
import io.github.atengk.boot.admin.autoconfigure.AtengAdminServerDefenseAutoConfiguration;
import io.github.atengk.boot.admin.provider.AdminRole;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #1141 控制台多角色 RBAC、防爆破、SBA UI Principal 桥接、高危端点守卫与结构化审计全容器端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("Admin RBAC / LoginDefense / EndpointGuard / Audit 全容器集成测试")
class AdminRbacEndToEndTest {

    private static final class MutableClock extends Clock {
        private Instant current = Instant.parse("2026-11-21T05:00:00Z");

        void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.current;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AuditCaptureConfiguration {
        final List<AdminAuditEvent> events = new ArrayList<>();

        @Bean
        public MutableClock testClock() {
            return new MutableClock();
        }

        @EventListener
        public void onAudit(AdminAuditEvent event) {
            events.add(event);
        }

        @org.springframework.web.bind.annotation.RestControllerAdvice
        static class MockAsyncContextFallbackAdvice {
            @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
            public org.springframework.http.ResponseEntity<String> handleMockMvcAsyncLimit(IllegalArgumentException ex) {
                if (ex.getMessage() != null && ex.getMessage().contains("HttpServletResponse must not be null")) {
                    return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                            .body("mockmvc-async-proxy-reached");
                }
                throw ex;
            }
        }
    }

    @AfterEach
    void cleanSaTokenContext() {
        SaManager.setSaTokenContext(null);
    }

    @Test
    @DisplayName("多账号 RBAC、SBA Principal 桥接、防爆破锁定恢复、高危端点与 DELETE 注销权限、操作审计闭环")
    void shouldEnforceFullRbacDefenseEndpointGuardAndAuditLifecycle() {
        String bcryptOpPass = "{bcrypt}" + BCrypt.hashpw("OpPass#2026");

        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class,
                        WebMvcAutoConfiguration.class,
                        org.springframework.boot.autoconfigure.thymeleaf.ThymeleafAutoConfiguration.class,
                        cn.dev33.satoken.spring.SaBeanRegister.class,
                        cn.dev33.satoken.spring.SaBeanInject.class,
                        cn.dev33.satoken.spring.SaTokenContextRegister.class,
                        AtengAdminServerDefenseAutoConfiguration.class,
                        AdminServerAutoConfiguration.class,
                        AdminServerUiAutoConfiguration.class,
                        AtengAdminServerAutoConfiguration.class,
                        WebClientAutoConfiguration.class
                ))
                .withBean(AdminServerMarkerConfiguration.Marker.class, AdminServerMarkerConfiguration.Marker::new)
                .withUserConfiguration(AuditCaptureConfiguration.class)
                .withPropertyValues(
                        "spring.boot.admin.context-path=/admin",
                        "ateng.boot.admin.server.client-secret=SECRET_TEST_1141",
                        "ateng.boot.admin.server.users[0].username=viewer-user",
                        "ateng.boot.admin.server.users[0].password=viewer-pass",
                        "ateng.boot.admin.server.users[0].roles[0]=VIEWER",
                        "ateng.boot.admin.server.users[1].username=ops-user",
                        "ateng.boot.admin.server.users[1].password=" + bcryptOpPass,
                        "ateng.boot.admin.server.users[1].roles[0]=OPERATOR",
                        "ateng.boot.admin.server.login-defense.max-failures=2",
                        "ateng.boot.admin.server.login-defense.lock-duration=10m",
                        "ateng.boot.admin.server.login-defense.trusted-proxies[0]=10.0.0.0/8",
                        "ateng.boot.admin.server.login-defense.ip-whitelist[0]=127.0.0.1",
                        "ateng.boot.admin.server.audit.max-records=20"
                )
                .run(context -> {
                    MockMvc mockMvc = buildMockMvcWithOrderedAdminFilters(context);
                    MutableClock clock = context.getBean(MutableClock.class);
                    AdminLoginDefenseService defenseService = context.getBean(AdminLoginDefenseService.class);
                    AdminAuditLogRepository auditRepository = context.getBean(AdminAuditLogRepository.class);
                    AuditCaptureConfiguration auditCapture = context.getBean(AuditCaptureConfiguration.class);

                    // 1. 配置非空 users 后，默认 admin/admin 必须失效
                    mockMvc.perform(post("/admin/login")
                                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                    .param("username", "admin")
                                    .param("password", "admin"))
                            .andExpect(status().is3xxRedirection())
                            .andExpect(redirectedUrl("/admin/login?error=true"));

                    // 2. 防爆破测试：可信代理 10.1.2.3 透传真实源 IP 203.0.113.88，连续 2 次失败锁定，登录页与 POST 均拒绝
                    for (int i = 0; i < 2; i++) {
                        mockMvc.perform(post("/admin/login")
                                        .with(req -> {
                                            req.setRemoteAddr("10.1.2.3");
                                            return req;
                                        })
                                        .header("X-Forwarded-For", "203.0.113.88")
                                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                        .param("username", "ops-user")
                                        .param("password", "wrong-pass"))
                                .andExpect(status().is3xxRedirection());
                    }

                    // 锁定期间 GET /admin/login 回显锁定告警且禁用提交；POST /admin/login 即使输入正确口令仍被拒绝
                    mockMvc.perform(get("/admin/login")
                                    .with(req -> {
                                        req.setRemoteAddr("10.1.2.3");
                                        return req;
                                    })
                                    .header("X-Forwarded-For", "203.0.113.88"))
                            .andExpect(status().isOk())
                            .andExpect(content().string(containsString("已被临时锁定")))
                            .andExpect(content().string(containsString("disabled")));

                    mockMvc.perform(post("/admin/login")
                                    .with(req -> {
                                        req.setRemoteAddr("10.1.2.3");
                                        return req;
                                    })
                                    .header("X-Forwarded-For", "203.0.113.88")
                                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                    .param("username", "ops-user")
                                    .param("password", "OpPass#2026"))
                            .andExpect(status().is3xxRedirection())
                            .andExpect(redirectedUrl("/admin/login?locked=true"));

                    // 推进时间超过 10 分钟锁定期后恢复正常登录
                    clock.advance(Duration.ofMinutes(11));

                    // 3. VIEWER 登录：校验 /console/me、Principal 桥接、heapdump/写操作/DELETE 实例被拒，audits 可查
                    MvcResult viewerLogin = mockMvc.perform(post("/admin/login")
                                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                    .param("username", "viewer-user")
                                    .param("password", "viewer-pass"))
                            .andExpect(status().is3xxRedirection())
                            .andExpect(redirectedUrl("/admin"))
                            .andReturn();
                    Cookie viewerCookie = viewerLogin.getResponse().getCookie("satoken");
                    assertThat(viewerCookie).isNotNull();

                    // 验证 SBA UiController.getUser(Principal) 解析出当前用户名
                    mockMvc.perform(get("/admin/console/me").cookie(viewerCookie))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.data.username").value("viewer-user"))
                            .andExpect(jsonPath("$.data.roles", hasItem("VIEWER")))
                            .andExpect(jsonPath("$.data.writePermitted").value(false));

                    mockMvc.perform(get("/admin/sba-settings.js").cookie(viewerCookie))
                            .andExpect(status().isOk())
                            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model()
                                    .attribute("user", java.util.Map.of("name", "viewer-user")));

                    // VIEWER 发起写操作 POST .../loggers/root -> 403
                    mockMvc.perform(post("/admin/instances/inst-01/actuator/loggers/root")
                                    .cookie(viewerCookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"configuredLevel\":\"DEBUG\"}"))
                            .andExpect(status().isForbidden());

                    // VIEWER 发起 DELETE /admin/instances/inst-01 -> 403
                    mockMvc.perform(delete("/admin/instances/inst-01")
                                    .cookie(viewerCookie))
                            .andExpect(status().isForbidden());

                    // 4. OPERATOR 登录（{bcrypt} 口令）：heapdump 仍被硬阻断 403，env 只读放行，DELETE 注销与 loggers 写操作放行
                    MvcResult opLogin = mockMvc.perform(post("/admin/login")
                                    .with(req -> {
                                        req.setRemoteAddr("10.1.2.3");
                                        return req;
                                    })
                                    .header("X-Forwarded-For", "203.0.113.88")
                                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                    .param("username", "ops-user")
                                    .param("password", "OpPass#2026"))
                            .andExpect(status().is3xxRedirection())
                            .andExpect(redirectedUrl("/admin"))
                            .andReturn();
                    Cookie opCookie = opLogin.getResponse().getCookie("satoken");
                    assertThat(opCookie).isNotNull();

                    mockMvc.perform(get("/admin/instances/inst-01/actuator/heapdump").cookie(opCookie))
                            .andExpect(status().isForbidden());

                    mockMvc.perform(get("/admin/instances/inst-01/actuator/env").cookie(opCookie))
                            .andExpect(status().is(org.hamcrest.Matchers.not(403)));

                    mockMvc.perform(post("/admin/instances/inst-01/actuator/loggers/root")
                                    .cookie(opCookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"configuredLevel\":\"DEBUG\"}"))
                            .andExpect(status().is(org.hamcrest.Matchers.not(403)));

                    mockMvc.perform(delete("/admin/instances/non-existent-inst").cookie(opCookie))
                            .andExpect(status().is(org.hamcrest.Matchers.not(403)));

                    // 5. 查询 /admin/console/audits 验证审计闭环与领域事件发布
                    mockMvc.perform(get("/admin/console/audits").cookie(viewerCookie))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.code").value(200))
                            .andExpect(jsonPath("$.data[*].action", hasItem(AdminAuditAction.LOGIN_LOCKED.getCode())))
                            .andExpect(jsonPath("$.data[*].action", hasItem(AdminAuditAction.LOGIN_SUCCESS.getCode())))
                            .andExpect(jsonPath("$.data[*].action", hasItem(AdminAuditAction.ENDPOINT_BLOCKED.getCode())))
                            .andExpect(jsonPath("$.data[*].action", hasItem(AdminAuditAction.ACTUATOR_PROXY_MUTATION.getCode())))
                            .andExpect(jsonPath("$.data[*].action", hasItem(AdminAuditAction.INSTANCE_DEREGISTERED.getCode())));

                    assertThat(auditRepository.size()).isGreaterThanOrEqualTo(5);
                    assertThat(auditCapture.events).isNotEmpty();
                    assertThat(defenseService.isLocked("ops-user", "203.0.113.88")).isFalse();
                    mockMvc.perform(post("/admin/logout").cookie(opCookie))
                            .andExpect(status().is3xxRedirection())
                            .andExpect(redirectedUrl("/admin/login?logout=true"));
                });
    }

    private MockMvc buildMockMvcWithOrderedAdminFilters(WebApplicationContext context) {
        List<Filter> filters = new ArrayList<>();
        filters.add(context.getBean("saTokenContextFilterForServlet",
                org.springframework.boot.web.servlet.FilterRegistrationBean.class).getFilter());
        filters.add(context.getBean(AdminSsrfGuardFilter.class));
        filters.add(context.getBean("adminSaServletFilter", Filter.class));
        filters.add(context.getBean(AdminPrincipalBridgeFilter.class));
        filters.add(context.getBean(AdminEndpointGuardFilter.class));
        AnnotationAwareOrderComparator.sort(filters);

        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(filters.toArray(Filter[]::new))
                .build();
    }
}
