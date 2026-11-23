package io.github.atengk.boot.admin.controller;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.admin.audit.AdminAuditAction;
import io.github.atengk.boot.admin.audit.AdminAuditEvent;
import io.github.atengk.boot.admin.audit.AdminAuditLogRepository;
import io.github.atengk.boot.admin.audit.AdminAuditRecord;
import io.github.atengk.boot.admin.audit.AdminAuditService;
import io.github.atengk.boot.admin.audit.DefaultAdminAuditLogHandler;
import io.github.atengk.boot.admin.cluster.InMemoryAdminClusterCoordinator;
import io.github.atengk.boot.admin.notifier.AtengAdminNotificationNotifier;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.provider.AdminConsolePrincipal;
import io.github.atengk.boot.admin.provider.AdminRole;
import io.github.atengk.boot.admin.security.AdminPrincipalBridgeFilter;
import io.github.atengk.boot.admin.security.AdminSessionHelper;
import io.github.atengk.boot.admin.silence.AdminSilenceManager;
import io.github.atengk.boot.admin.silence.DefaultAdminSilenceManager;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import de.codecentric.boot.admin.server.config.AdminServerProperties;
import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.events.InstanceStatusChangedEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Test
    @DisplayName("维护静默端点 RBAC 控制：VIEWER 仅可只读查询而禁写，OPERATOR 可创建与撤销并同步产出审计事件")
    void shouldEnforceRbacAndAuditOnConsoleSilencesEndpoints() throws Exception {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-21T06:00:00Z"), ZoneOffset.UTC);
        AdminAuditLogRepository auditRepository = new AdminAuditLogRepository(50);
        List<AdminAuditEvent> publishedEvents = new ArrayList<>();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof AdminAuditEvent auditEvent) {
                publishedEvents.add(auditEvent);
            }
        };
        AdminAuditService auditService = new AdminAuditService(
                new AtengAdminServerProperties(),
                publisher,
                List.of(new DefaultAdminAuditLogHandler(auditRepository)),
                fixedClock
        );
        AdminSilenceManager silenceManager = new DefaultAdminSilenceManager(
                new InMemoryAdminClusterCoordinator(fixedClock),
                Duration.ofHours(2),
                fixedClock
        );
        AdminServerProperties sbaProperties = new AdminServerProperties();
        sbaProperties.setContextPath("/admin");

        AdminConsoleController controller = new AdminConsoleController(auditRepository, silenceManager, auditService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addPlaceholderValue("spring.boot.admin.context-path", "/admin")
                .addFilters(new AdminPrincipalBridgeFilter(sbaProperties))
                .defaultResponseCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8)
                .build();

        AdminConsolePrincipal viewer = AdminConsolePrincipal.of("viewer-bob", Set.of(AdminRole.VIEWER));
        AdminConsolePrincipal operator = AdminConsolePrincipal.of("sre-alice", Set.of(AdminRole.OPERATOR));
        SaSession viewerSession = mock(SaSession.class);
        when(viewerSession.get(AdminSessionHelper.SESSION_PRINCIPAL_KEY)).thenReturn(viewer);
        SaSession operatorSession = mock(SaSession.class);
        when(operatorSession.get(AdminSessionHelper.SESSION_PRINCIPAL_KEY)).thenReturn(operator);

        try (MockedStatic<StpUtil> stpMock = Mockito.mockStatic(StpUtil.class)) {
            stpMock.when(StpUtil::isLogin).thenReturn(true);

            // 1. OPERATOR 创建维护静默规则 -> 200 并触发 SILENCE_CREATED 审计
            stpMock.when(() -> StpUtil.getSession(false)).thenReturn(operatorSession);
            MvcResult createResult = mockMvc.perform(post("/admin/console/silences")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"servicePattern\":\"order-*\",\"duration\":\"PT30M\",\"reason\":\"订单库主从切换\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.servicePattern").value("order-*"))
                    .andExpect(jsonPath("$.data.reason").value("订单库主从切换"))
                    .andReturn();

            String ruleId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(createResult.getResponse().getContentAsString())
                    .path("data").path("id").asText();
            assertThat(ruleId).isNotBlank();
            assertThat(silenceManager.isSilenced("order-core-service")).isTrue();

            // 1.1 验证活动静默规则实际屏蔽针对匹配服务的故障告警投递
            InstanceId silencedInstanceId = InstanceId.of("order-silenced-01");
            Instance silencedInstance = Instance.create(silencedInstanceId)
                    .register(Registration.create("order-core-service", "http://10.0.0.10:8080/actuator/health").build())
                    .withStatusInfo(StatusInfo.ofDown());
            InstanceRepository mockRepo = mock(InstanceRepository.class);
            when(mockRepo.find(silencedInstanceId)).thenReturn(Mono.just(silencedInstance));
            NotificationTemplate mockTemplate = mock(NotificationTemplate.class);
            @SuppressWarnings("unchecked")
            ObjectProvider<NotificationTemplate> templateProvider = mock(ObjectProvider.class);
            when(templateProvider.getIfAvailable()).thenReturn(mockTemplate);
            AtengAdminServerProperties serverProps = new AtengAdminServerProperties();
            serverProps.setDebounceDuration(Duration.ZERO);
            AtengAdminNotificationNotifier notifier = new AtengAdminNotificationNotifier(
                    mockRepo, templateProvider, serverProps,
                    (java.util.concurrent.ScheduledExecutorService) null,
                    silenceManager, null, fixedClock);
            notifier.notify(new InstanceStatusChangedEvent(silencedInstanceId, 1L, StatusInfo.ofDown())).block();
            assertThat(notifier.getPendingTasks()).isEmpty();
            assertThat(notifier.getAlertedInstances()).isEmpty();
            Mockito.verifyNoInteractions(mockTemplate);

            // 2. VIEWER 只读查询放行，但新增与撤销均被 403 拒绝（并记入 ENDPOINT_BLOCKED 审计）
            stpMock.when(() -> StpUtil.getSession(false)).thenReturn(viewerSession);
            mockMvc.perform(get("/admin/console/silences").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.data[0].id").value(ruleId));

            mockMvc.perform(post("/admin/console/silences")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"servicePattern\":\"pay-*\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));

            mockMvc.perform(delete("/admin/console/silences/" + ruleId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));

            // 3. OPERATOR 撤销维护静默规则 -> 200 并触发 SILENCE_REVOKED 审计，服务恢复非静默
            stpMock.when(() -> StpUtil.getSession(false)).thenReturn(operatorSession);
            mockMvc.perform(delete("/admin/console/silences/" + ruleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(true));

            assertThat(silenceManager.isSilenced("order-core-service")).isFalse();
            mockMvc.perform(delete("/admin/console/silences/not-found-id"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404));
        }

        assertThat(publishedEvents).extracting(e -> e.getRecord().getAction())
                .containsExactly(
                        AdminAuditAction.SILENCE_CREATED,
                        AdminAuditAction.ENDPOINT_BLOCKED,
                        AdminAuditAction.ENDPOINT_BLOCKED,
                        AdminAuditAction.SILENCE_REVOKED
                );
    }
}
