package io.github.atengk.boot.admin.security;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import de.codecentric.boot.admin.server.config.AdminServerProperties;
import io.github.atengk.boot.admin.audit.AdminAuditAction;
import io.github.atengk.boot.admin.audit.AdminAuditEvent;
import io.github.atengk.boot.admin.audit.AdminAuditLogRepository;
import io.github.atengk.boot.admin.audit.AdminAuditService;
import io.github.atengk.boot.admin.audit.DefaultAdminAuditLogHandler;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.provider.AdminConsolePrincipal;
import io.github.atengk.boot.admin.provider.AdminRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminEndpointGuardFilter 高危端点硬阻断、RBAC 写权限与操作审计单元测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("AdminEndpointGuardFilter 高危端点与 RBAC 守卫单元测试")
class AdminEndpointGuardFilterTest {

    private AtengAdminServerProperties properties;
    private AdminServerProperties adminServerProperties;
    private AdminAuditLogRepository auditRepository;
    private List<AdminAuditEvent> publishedEvents;
    private AdminEndpointGuardFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        properties = new AtengAdminServerProperties();
        adminServerProperties = new AdminServerProperties();
        adminServerProperties.setContextPath("/admin");
        auditRepository = new AdminAuditLogRepository(50);
        publishedEvents = new ArrayList<>();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof AdminAuditEvent auditEvent) {
                publishedEvents.add(auditEvent);
            }
        };
        AdminAuditService auditService = new AdminAuditService(
                properties,
                publisher,
                List.of(new DefaultAdminAuditLogHandler(auditRepository))
        );
        filter = new AdminEndpointGuardFilter(properties, adminServerProperties, auditService);
        filterChain = mock(FilterChain.class);
    }

    @Test
    @DisplayName("默认物理硬阻断 GET/POST heapdump、shutdown 与 restart，同时放行 env 与 metrics 只读访问")
    void shouldHardBlockDangerousEndpointsAndAllowReadOnlyEndpoints() throws ServletException, IOException {
        AdminConsolePrincipal admin = AdminConsolePrincipal.of("super-admin", Set.of(AdminRole.ADMIN));
        SaSession mockSession = mock(SaSession.class);
        when(mockSession.get(AdminSessionHelper.SESSION_PRINCIPAL_KEY)).thenReturn(admin);

        try (MockedStatic<StpUtil> stpMock = Mockito.mockStatic(StpUtil.class)) {
            stpMock.when(StpUtil::isLogin).thenReturn(true);
            stpMock.when(() -> StpUtil.getSession(false)).thenReturn(mockSession);

            // 1. GET /admin/instances/inst-01/actuator/heapdump -> 403
            MockHttpServletRequest heapdumpReq = new MockHttpServletRequest("GET", "/admin/instances/inst-01/actuator/heapdump");
            MockHttpServletResponse heapdumpRes = new MockHttpServletResponse();
            filter.doFilter(heapdumpReq, heapdumpRes, filterChain);
            assertThat(heapdumpRes.getStatus()).isEqualTo(403);
            verify(filterChain, never()).doFilter(heapdumpReq, heapdumpRes);

            // 2. POST /admin/applications/order-svc/actuator/shutdown -> 403 (覆盖 /applications/** 聚合代理)
            MockHttpServletRequest shutdownReq = new MockHttpServletRequest("POST", "/admin/applications/order-svc/actuator/shutdown");
            MockHttpServletResponse shutdownRes = new MockHttpServletResponse();
            filter.doFilter(shutdownReq, shutdownRes, filterChain);
            assertThat(shutdownRes.getStatus()).isEqualTo(403);
            verify(filterChain, never()).doFilter(shutdownReq, shutdownRes);

            // 3. POST /admin/instances/inst-01/actuator/restart -> 403
            MockHttpServletRequest restartReq = new MockHttpServletRequest("POST", "/admin/instances/inst-01/actuator/restart");
            MockHttpServletResponse restartRes = new MockHttpServletResponse();
            filter.doFilter(restartReq, restartRes, filterChain);
            assertThat(restartRes.getStatus()).isEqualTo(403);

            // 4. GET /admin/instances/inst-01/actuator/env & /metrics -> 放行
            MockHttpServletRequest envReq = new MockHttpServletRequest("GET", "/admin/instances/inst-01/actuator/env");
            MockHttpServletResponse envRes = new MockHttpServletResponse();
            filter.doFilter(envReq, envRes, filterChain);
            verify(filterChain).doFilter(envReq, envRes);

            MockHttpServletRequest metricsReq = new MockHttpServletRequest("GET", "/admin/instances/inst-01/actuator/metrics");
            MockHttpServletResponse metricsRes = new MockHttpServletResponse();
            filter.doFilter(metricsReq, metricsRes, filterChain);
            verify(filterChain).doFilter(metricsReq, metricsRes);
        }

        assertThat(publishedEvents).hasSize(3);
        assertThat(publishedEvents).allSatisfy(event ->
                assertThat(event.getRecord().getAction()).isEqualTo(AdminAuditAction.ENDPOINT_BLOCKED));
    }

    @Test
    @DisplayName("RBAC 分级：VIEWER 发起写操作返回 403 并审计，OPERATOR/ADMIN 放行并记录 ACTUATOR_PROXY_MUTATION 审计")
    void shouldEnforceRoleBasedWritePermissionsAndAuditMutations() throws ServletException, IOException {
        AdminConsolePrincipal viewer = AdminConsolePrincipal.of("viewer-bob", Set.of(AdminRole.VIEWER));
        AdminConsolePrincipal operator = AdminConsolePrincipal.of("sre-alice", Set.of(AdminRole.OPERATOR));
        SaSession viewerSession = mock(SaSession.class);
        when(viewerSession.get(AdminSessionHelper.SESSION_PRINCIPAL_KEY)).thenReturn(viewer);
        SaSession operatorSession = mock(SaSession.class);
        when(operatorSession.get(AdminSessionHelper.SESSION_PRINCIPAL_KEY)).thenReturn(operator);

        try (MockedStatic<StpUtil> stpMock = Mockito.mockStatic(StpUtil.class)) {
            stpMock.when(StpUtil::isLogin).thenReturn(true);

            // 1. VIEWER 发起 POST .../loggers/root -> 403 拦截
            stpMock.when(() -> StpUtil.getSession(false)).thenReturn(viewerSession);
            MockHttpServletRequest viewerPost = new MockHttpServletRequest("POST", "/admin/instances/inst-01/actuator/loggers/root");
            MockHttpServletResponse viewerRes = new MockHttpServletResponse();
            filter.doFilter(viewerPost, viewerRes, filterChain);
            assertThat(viewerRes.getStatus()).isEqualTo(403);
            verify(filterChain, never()).doFilter(viewerPost, viewerRes);

            // 2. OPERATOR 发起 POST .../loggers/root -> 放行并审计
            stpMock.when(() -> StpUtil.getSession(false)).thenReturn(operatorSession);
            MockHttpServletRequest opPost = new MockHttpServletRequest("POST", "/admin/instances/inst-01/actuator/loggers/root");
            MockHttpServletResponse opRes = new MockHttpServletResponse();
            filter.doFilter(opPost, opRes, filterChain);
            verify(filterChain).doFilter(opPost, opRes);
        }

        assertThat(auditRepository.query(AdminAuditAction.ENDPOINT_BLOCKED, "viewer-bob", 10)).hasSize(1);
        assertThat(auditRepository.query(AdminAuditAction.ACTUATOR_PROXY_MUTATION, "sre-alice", 10)).hasSize(1);
    }

    @Test
    @DisplayName("向下兼容 AdminWriteGuardFilter 类型与公开方法契约")
    void shouldRemainCompatibleWithAdminWriteGuardFilterContract() {
        AdminWriteGuardFilter legacyRef = filter;
        properties.setEnableWriteOperations(false);
        assertThat(legacyRef.isActuatorProxyEndpoint("/admin/instances/inst-01/actuator/health")).isTrue();
        assertThat(legacyRef.isActuatorProxyEndpoint("/admin/applications/order-svc/actuator/env")).isTrue();
        assertThat(legacyRef.shouldBlock("POST", "/admin/instances/inst-01/actuator/loggers/root")).isTrue();
        assertThat(legacyRef.shouldBlock("GET", "/admin/instances/inst-01/actuator/heapdump")).isTrue();
        assertThat(legacyRef.shouldBlock("GET", "/admin/instances/inst-01/actuator/health")).isFalse();
    }
}
