package io.github.atengk.boot.sample.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codecentric.boot.admin.client.config.InstanceProperties;
import de.codecentric.boot.admin.client.registration.ApplicationFactory;
import de.codecentric.boot.admin.client.registration.DefaultApplicationRegistrator;
import de.codecentric.boot.admin.client.registration.RegistrationClient;
import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import io.github.atengk.boot.admin.notifier.AtengAdminNotificationNotifier;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.silence.AdminSilenceManager;
import io.github.atengk.boot.admin.silence.SilenceRule;
import io.github.atengk.boot.sample.admin.config.RecordingNotificationTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Spring Boot Admin Client 与 Server 全链路端到端闭环验证测试
 * <p>
 * 在真实内嵌 Servlet 容器随机端口上验证：
 * 1. SBA Client 经 {@code X-Admin-Client-Secret} 自动完成向 Server 的 HTTP 注册与元数据注入；
 * 2. 客户端 {@code /actuator/env} 启用 {@code ActuatorEndpointSecurityFilter} 保护，未授权直连拦截；
 * 3. Server 端经 {@code AdminEndpointGuardFilter} + {@code AdminOutboundSsrfFilterFunction} + {@code AtengActuatorHttpHeadersProvider}
 *    自动携带 {@code X-Actuator-Token} 代理穿透访问客户端 {@code /actuator/env}，同时硬阻断 {@code heapdump}；
 * 4. 多角色控制台 RBAC（VIEWER 只读/禁写，OPERATOR/ADMIN 管理静默与审计查询）；
 * 5. Actuator {@code /actuator/health} 与 {@code /actuator/metrics/ateng.admin.*} 低基数自观测闭环。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.boot.admin.client.enabled=true",
                "spring.boot.admin.client.url=http://127.0.0.1:8080/admin",
                "spring.boot.admin.client.auto-registration=false",
                "management.endpoints.web.exposure.include=health,info,env,metrics,heapdump",
                "management.endpoint.health.show-details=always",
                "ateng.boot.admin.server.users[0].username=e2e-admin",
                "ateng.boot.admin.server.users[0].password=AdminPass!2026",
                "ateng.boot.admin.server.users[0].roles[0]=ADMIN",
                "ateng.boot.admin.server.users[1].username=e2e-viewer",
                "ateng.boot.admin.server.users[1].password=ViewerPass!2026",
                "ateng.boot.admin.server.users[1].roles[0]=VIEWER"
        }
)
@DisplayName("真实 SBA Client 与 Server 全链路端到端闭环测试")
class AdminRealClientEndToEndTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CLIENT_ACTUATOR_TOKEN = "CLIENT_ACTUATOR_TOKEN_DEMO";

    @LocalServerPort
    private int serverPort;

    @Autowired
    private ApplicationFactory applicationFactory;

    @Autowired
    private InstanceProperties instanceProperties;

    @Autowired
    private RegistrationClient registrationClient;

    @Autowired
    private InstanceRepository instanceRepository;

    @Autowired
    private AtengAdminNotificationNotifier notifier;

    @Autowired
    private AtengAdminServerProperties properties;

    @Autowired(required = false)
    private RecordingNotificationTemplate recordingTemplate;

    @Autowired(required = false)
    private AdminSilenceManager silenceManager;

    private DefaultApplicationRegistrator activeRegistrator;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @AfterEach
    void tearDown() {
        if (activeRegistrator != null) {
            try {
                activeRegistrator.deregister();
            } catch (Exception ignored) {
                // 忽略清理阶段的注销网络异常
            }
            activeRegistrator = null;
        }
        if (silenceManager != null) {
            for (SilenceRule rule : silenceManager.listActiveSilences()) {
                silenceManager.revokeSilence(rule.getId());
            }
        }
        SampleAdminTestSupport.resetSandboxState(instanceRepository, notifier, properties, recordingTemplate);
    }

    @Test
    @DisplayName("真实 Client 注册、双向令牌穿透、端点守卫、静默 RBAC 审计与自观测探针全链路通过")
    void shouldCompleteRealClientToServerEndToEndFlow() throws Exception {
        String baseUrl = "http://127.0.0.1:" + serverPort;
        instanceProperties.setServiceUrl(baseUrl + "/");
        instanceProperties.setManagementUrl(baseUrl + "/actuator");
        instanceProperties.setHealthUrl(baseUrl + "/actuator/health");
        activeRegistrator = new DefaultApplicationRegistrator(
                applicationFactory,
                registrationClient,
                new String[]{baseUrl + "/admin/instances"},
                true
        );

        // 1. 触发真实 SBA Client 注册，验证共享密钥握手与 actuator-token 元数据自动注入
        boolean registered = activeRegistrator.register();
        assertThat(registered).isTrue();
        assertThat(activeRegistrator.getRegisteredId()).isNotBlank();
        String registeredId = activeRegistrator.getRegisteredId();

        Instance registeredInstance = instanceRepository.find(
                de.codecentric.boot.admin.server.domain.values.InstanceId.of(registeredId)
        ).block(Duration.ofSeconds(3));
        assertThat(registeredInstance).isNotNull();
        assertThat(registeredInstance.isRegistered()).isTrue();
        assertThat(registeredInstance.getRegistration().getMetadata())
                .containsEntry("actuator-token", CLIENT_ACTUATOR_TOKEN);

        // 2. 验证客户端 /actuator/env 未携带 X-Actuator-Token 时被 ActuatorEndpointSecurityFilter 拦截
        HttpResponse<String> directUnauthEnv = sendGet(baseUrl + "/actuator/env", null, null);
        assertThat(directUnauthEnv.statusCode()).isIn(401, 403);

        // 3. 登录 ADMIN 与 VIEWER 控制台会话
        String adminCookie = loginAndExtractCookie(baseUrl, "e2e-admin", "AdminPass!2026");
        String viewerCookie = loginAndExtractCookie(baseUrl, "e2e-viewer", "ViewerPass!2026");

        // 4. ADMIN 经 SBA 反向代理访问客户端 /actuator/env 成功，访问高危 heapdump 被物理硬阻断 403
        HttpResponse<String> proxiedEnv = sendGet(baseUrl + "/admin/instances/" + registeredId + "/actuator/env", adminCookie, null);
        assertThat(proxiedEnv.statusCode()).isEqualTo(200);
        assertThat(proxiedEnv.body()).contains("activeProfiles");

        HttpResponse<String> proxiedHeapdump = sendGet(baseUrl + "/admin/instances/" + registeredId + "/actuator/heapdump", adminCookie, null);
        assertThat(proxiedHeapdump.statusCode()).isEqualTo(403);

        // 5. 多角色 RBAC 校验：VIEWER 可只读查看静默，禁止创建静默或向 Actuator 代理发起写操作
        HttpResponse<String> viewerReadSilences = sendGet(baseUrl + "/admin/console/silences", viewerCookie, null);
        assertThat(viewerReadSilences.statusCode()).isEqualTo(200);

        HttpResponse<String> viewerWriteSilence = sendPostJson(
                baseUrl + "/admin/console/silences",
                "{\"servicePattern\":\"order-*\",\"duration\":\"PT2M\",\"reason\":\"越权测试\"}",
                viewerCookie
        );
        assertThat(viewerWriteSilence.statusCode()).isEqualTo(403);

        HttpResponse<String> viewerMutateActuator = sendPostJson(
                baseUrl + "/admin/instances/" + registeredId + "/actuator/loggers/ROOT",
                "{\"configuredLevel\":\"WARN\"}",
                viewerCookie
        );
        assertThat(viewerMutateActuator.statusCode()).isEqualTo(403);

        // 6. ADMIN 通过 /admin/console/silences 创建并撤销静默规则，随后检索审计日志闭环
        HttpResponse<String> createSilenceResp = sendPostJson(
                baseUrl + "/admin/console/silences",
                "{\"servicePattern\":\"ateng-boot-sample-admin\",\"duration\":\"PT3M\",\"reason\":\"端到端维护演练\"}",
                adminCookie
        );
        assertThat(createSilenceResp.statusCode()).isEqualTo(200);
        JsonNode createdRule = OBJECT_MAPPER.readTree(createSilenceResp.body());
        String ruleId = createdRule.path("data").path("id").asText();
        assertThat(ruleId).isNotBlank();

        HttpResponse<String> revokeResp = sendDelete(baseUrl + "/admin/console/silences/" + ruleId, adminCookie);
        assertThat(revokeResp.statusCode()).isEqualTo(200);

        HttpResponse<String> auditsResp = sendGet(baseUrl + "/admin/console/audits?limit=20", adminCookie, null);
        assertThat(auditsResp.statusCode()).isEqualTo(200);
        assertThat(auditsResp.body()).contains("SILENCE_CREATED", "SILENCE_REVOKED", "ENDPOINT_BLOCKED", "LOGIN_SUCCESS");

        // 7. 验证 /actuator/health（adminServer）与 /actuator/metrics/ateng.admin.* 自观测指标闭环
        HttpResponse<String> healthResp = sendGet(baseUrl + "/actuator/health", null, null);
        assertThat(healthResp.statusCode()).isEqualTo(200);
        JsonNode healthJson = OBJECT_MAPPER.readTree(healthResp.body());
        JsonNode adminHealth = healthJson.path("components").path("adminServer");
        assertThat(adminHealth.path("status").asText()).isEqualTo("UP");
        assertThat(adminHealth.path("details").path("registeredInstances").asInt()).isGreaterThanOrEqualTo(1);

        HttpResponse<String> regMetricResp = sendGet(
                baseUrl + "/actuator/metrics/ateng.admin.instances.registered", null, CLIENT_ACTUATOR_TOKEN);
        assertThat(regMetricResp.statusCode()).isEqualTo(200);
        assertThat(OBJECT_MAPPER.readTree(regMetricResp.body())
                .path("measurements").get(0).path("value").asDouble()).isGreaterThanOrEqualTo(1.0);

        HttpResponse<String> blockedMetricResp = sendGet(
                baseUrl + "/actuator/metrics/ateng.admin.endpoint.blocked.total", null, CLIENT_ACTUATOR_TOKEN);
        assertThat(blockedMetricResp.statusCode()).isEqualTo(200);
        assertThat(OBJECT_MAPPER.readTree(blockedMetricResp.body())
                .path("measurements").get(0).path("value").asDouble()).isGreaterThanOrEqualTo(1.0);

        HttpResponse<String> admissionMetricResp = sendGet(
                baseUrl + "/actuator/metrics/ateng.admin.admission.requests", null, CLIENT_ACTUATOR_TOKEN);
        assertThat(admissionMetricResp.statusCode()).isEqualTo(200);

        HttpResponse<String> loginMetricResp = sendGet(
                baseUrl + "/actuator/metrics/ateng.admin.login.attempts", null, CLIENT_ACTUATOR_TOKEN);
        assertThat(loginMetricResp.statusCode()).isEqualTo(200);
    }

    private String loginAndExtractCookie(String baseUrl, String username, String password) throws Exception {
        String form = "username=" + username + "&password=" + password;
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/admin/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(302);
        List<String> setCookies = response.headers().allValues("Set-Cookie");
        assertThat(setCookies).isNotEmpty();
        return setCookies.get(0).split(";", 2)[0];
    }

    private HttpResponse<String> sendGet(String url, String cookie, String actuatorToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        if (actuatorToken != null) {
            builder.header("X-Actuator-Token", actuatorToken);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> sendPostJson(String url, String body, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> sendDelete(String url, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).DELETE();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
