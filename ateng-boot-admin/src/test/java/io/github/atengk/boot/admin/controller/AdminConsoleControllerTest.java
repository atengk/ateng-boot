package io.github.atengk.boot.admin.controller;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.admin.audit.AdminAuditAction;
import io.github.atengk.boot.admin.audit.AdminAuditLogRepository;
import io.github.atengk.boot.admin.audit.AdminAuditRecord;
import io.github.atengk.boot.admin.provider.AdminConsolePrincipal;
import io.github.atengk.boot.admin.provider.AdminRole;
import io.github.atengk.boot.admin.security.AdminPrincipalBridgeFilter;
import io.github.atengk.boot.admin.security.AdminSessionHelper;
import de.codecentric.boot.admin.server.config.AdminServerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Set;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminConsoleController（/console/me 与 /console/audits）单元测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("AdminConsoleController 控制台主体与审计查询端点测试")
class AdminConsoleControllerTest {

    @Test
    @DisplayName("已登录主体访问 /admin/console/me 返回脱敏主体及角色，访问 /admin/console/audits 支持条件过滤")
    void shouldReturnPrincipalInfoAndFilteredAudits() throws Exception {
        AdminAuditLogRepository auditRepository = new AdminAuditLogRepository(50);
        auditRepository.record(AdminAuditRecord.of(
                AdminAuditAction.LOGIN_SUCCESS, "sre-alice", "10.0.0.5", "console",
                "POST", "/admin/login", "SUCCESS", "ok", Instant.parse("2026-11-21T03:00:00Z")));
        auditRepository.record(AdminAuditRecord.of(
                AdminAuditAction.ENDPOINT_BLOCKED, "viewer-bob", "10.0.0.6", "heapdump",
                "GET", "/admin/instances/1/actuator/heapdump", "DENIED", "blocked", Instant.parse("2026-11-21T03:01:00Z")));

        AdminServerProperties sbaProperties = new AdminServerProperties();
        sbaProperties.setContextPath("/admin");

        AdminConsoleController controller = new AdminConsoleController(auditRepository);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addPlaceholderValue("spring.boot.admin.context-path", "/admin")
                .addFilters(new AdminPrincipalBridgeFilter(sbaProperties))
                .defaultResponseCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8)
                .build();

        // 1. 未登录访问返回 401
        mockMvc.perform(get("/admin/console/me").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        // 2. VIEWER 登录态可只读查询 /console/me 与 /console/audits
        AdminConsolePrincipal viewer = AdminConsolePrincipal.of("viewer-bob", Set.of(AdminRole.VIEWER));
        SaSession mockSession = mock(SaSession.class);
        when(mockSession.get(AdminSessionHelper.SESSION_PRINCIPAL_KEY)).thenReturn(viewer);

        try (MockedStatic<StpUtil> stpMock = Mockito.mockStatic(StpUtil.class)) {
            stpMock.when(StpUtil::isLogin).thenReturn(true);
            stpMock.when(() -> StpUtil.getSession(false)).thenReturn(mockSession);

            mockMvc.perform(get("/admin/console/me").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.username").value("viewer-bob"))
                    .andExpect(jsonPath("$.data.maskedUsername").value("vi*******b"))
                    .andExpect(jsonPath("$.data.roles", hasItem("VIEWER")))
                    .andExpect(jsonPath("$.data.writePermitted").value(false))
                    .andExpect(jsonPath("$.data.admin").value(false));

            mockMvc.perform(get("/admin/console/audits")
                            .param("action", "ENDPOINT_BLOCKED")
                            .param("actor", "viewer-bob")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.data[0].action").value("ENDPOINT_BLOCKED"))
                    .andExpect(jsonPath("$.data[0].maskedActor").value("vi*******b"))
                    .andExpect(jsonPath("$.data[0].actor").doesNotExist());

            // 3. 会话缺失控制台主体时不得降级或默认授予 ADMIN
            stpMock.when(() -> StpUtil.getSession(false)).thenReturn(null);
            mockMvc.perform(get("/admin/console/me").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }
}
