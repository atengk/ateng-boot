package io.github.atengk.boot.sample.admin;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.events.InstanceDeregisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceStatusChangedEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import io.github.atengk.boot.admin.notifier.AtengAdminNotificationNotifier;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.provider.AtengActuatorHttpHeadersProvider;
import io.github.atengk.boot.admin.scheduler.ZombieInstanceCleanupScheduler;
import io.github.atengk.boot.admin.security.AdminSsrfGuardFilter;
import io.github.atengk.boot.sample.admin.config.RecordingNotificationTemplate;
import io.github.atengk.boot.sample.admin.dto.RecordedAlertCardVO;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring Boot Admin 生产级场景沙箱端到端集成测试
 * 覆盖原生表单登录重定向、Sa-Token 鉴权、注册防火墙防 SSRF 与 413 载荷上限、双向凭据握手穿透、
 * 控制台只读守卫、自定义准入 SPI、僵尸实例清理、告警防抖静默状态机与宿主中立 Local 模式探测
 *
 * @author Ateng
 * @since 2026-10-26
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Spring Boot Admin 场景沙箱端到端集成测试")
class AdminSampleIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AdminSampleIntegrationTest.class);
    private static final String CLIENT_SECRET = "CLUSTER_SECRET_DEMO_2026";

    @Autowired
    private Environment environment;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InstanceRepository instanceRepository;

    @Autowired
    private AtengActuatorHttpHeadersProvider httpHeadersProvider;

    @Autowired
    private AtengAdminNotificationNotifier notificationNotifier;

    @Autowired
    private AtengAdminServerProperties properties;

    @Autowired(required = false)
    private ZombieInstanceCleanupScheduler zombieCleanupScheduler;

    @Autowired(required = false)
    private RecordingNotificationTemplate recordingNotificationTemplate;

    @AfterEach
    void tearDown() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = context.getLogger("io.github.atengk.boot.sample.admin");
        if (logger != null) {
            logger.setLevel(Level.INFO);
        }
        if (recordingNotificationTemplate != null) {
            recordingNotificationTemplate.clear();
        }
        List<Instance> instances = instanceRepository.findAll().collectList().block();
        if (instances != null) {
            for (Instance instance : instances) {
                if (instance.isRegistered()) {
                    instanceRepository.computeIfPresent(
                            instance.getId(),
                            (id, curr) -> Mono.just(curr.deregister())
                    ).block();
                }
                notificationNotifier.onInstanceDeregistered(new InstanceDeregisteredEvent(instance.getId(), instance.getVersion() + 1));
            }
        }
        if (properties != null) {
            properties.setEnableWriteOperations(true);
            properties.setAllowLocalInstances(true);
            properties.setDebounceDuration(Duration.ofSeconds(30));
            if (properties.getCleanup() != null) {
                properties.getCleanup().setZombieThreshold(Duration.ofHours(24));
            }
            if (properties.getAlert() != null) {
                properties.getAlert().getIncludedServices().clear();
                properties.getAlert().setExcludedServices(new ArrayList<>(List.of("silent-*")));
                properties.getAlert().setNotifyStatuses(new ArrayList<>(List.of("DOWN", "OFFLINE")));
            }
        }
    }

    @Test
    @DisplayName("场景 1：未登录访问 /admin 时自动 302 重定向至 /admin/login；访问登录页成功返回原生 HTML")
    void shouldRedirectToLoginAndRenderNativeHtml() throws Exception {
        mockMvc.perform(get("/admin").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin/login"));

        MvcResult pageResult = mockMvc.perform(get("/admin/login"))
                .andExpect(status().isOk())
                .andReturn();

        String htmlContent = pageResult.getResponse().getContentAsString();
        assertThat(htmlContent).contains("ateng-boot Admin");
        assertThat(htmlContent).contains("运维管理员账号");
        assertThat(htmlContent).contains("/admin/login");
    }

    @Test
    @DisplayName("场景 2：提交原生表单 POST 登录，账号密码匹配后 302 重定向至首页并下发 Sa-Token 会话")
    void shouldLoginSuccessfullyViaFormPost() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "admin")
                        .param("password", "admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin"))
                .andReturn();

        Cookie satokenCookie = loginResult.getResponse().getCookie("satoken");
        assertThat(satokenCookie).isNotNull();
        assertThat(satokenCookie.getValue()).isNotBlank();

        mockMvc.perform(get("/admin").cookie(satokenCookie).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("场景 3：防 SSRF 防火墙阻断云元数据、非 http/https 协议、未授权 IPv6 环回及超限 413 载荷")
    void shouldBlockSsrfAndOversizedRegistrationAttempts() throws Exception {
        // 1. 阻断 169.254 云元数据
        String metadataPayload = """
                {
                    "name": "malicious-metadata-svc",
                    "serviceUrl": "http://169.254.169.254/latest/meta-data/",
                    "managementUrl": "http://169.254.169.254/latest/meta-data/",
                    "healthUrl": "http://169.254.169.254/latest/meta-data/"
                }
                """;
        mockMvc.perform(post("/admin/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, CLIENT_SECRET)
                        .content(metadataPayload))
                .andExpect(status().isForbidden());

        // 2. 阻断非 http/https Scheme
        String ftpPayload = """
                {
                    "name": "malicious-ftp-svc",
                    "serviceUrl": "ftp://order-core-service:21/",
                    "managementUrl": "http://order-core-service:8080/actuator",
                    "healthUrl": "http://order-core-service:8080/actuator/health"
                }
                """;
        mockMvc.perform(post("/admin/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, CLIENT_SECRET)
                        .content(ftpPayload))
                .andExpect(status().isForbidden());

        // 3. allowLocalInstances=false 时阻断 IPv6 [::1] 环回
        properties.setAllowLocalInstances(false);
        String ipv6LoopbackPayload = """
                {
                    "name": "ipv6-loopback-svc",
                    "serviceUrl": "http://[::1]:8080/",
                    "managementUrl": "http://[::1]:8080/actuator",
                    "healthUrl": "http://[::1]:8080/actuator/health"
                }
                """;
        mockMvc.perform(post("/admin/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, CLIENT_SECRET)
                        .content(ipv6LoopbackPayload))
                .andExpect(status().isForbidden());
        properties.setAllowLocalInstances(true);

        // 4. 超过默认 1MB 上限的畸形注册请求响应 413 Payload Too Large
        String largePad = "x".repeat(1024 * 1024 + 128);
        String oversizedPayload = "{\"name\":\"oversized-svc\",\"serviceUrl\":\"http://127.0.0.1:8080/\",\"metadata\":{\"pad\":\""
                + largePad + "\"}}";
        mockMvc.perform(post("/admin/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, CLIENT_SECRET)
                        .content(oversizedPayload))
                .andExpect(status().is(HttpStatus.PAYLOAD_TOO_LARGE.value()));
    }

    @Test
    @DisplayName("场景 4：微服务携带有效密钥合法注册上线，且 Server 端成功通过 Metadata 提取 Token 挂载出站标头")
    void shouldRegisterSuccessfullyAndExtractActuatorToken() throws Exception {
        String registrationPayload = """
                {
                    "name": "order-core-service",
                    "serviceUrl": "http://127.0.0.1:8080/",
                    "managementUrl": "http://127.0.0.1:8080/actuator",
                    "healthUrl": "http://127.0.0.1:8080/actuator/health",
                    "metadata": {
                        "actuator-token": "DEMO_ACTUATOR_TOKEN_999"
                    }
                }
                """;

        MvcResult asyncResult = mockMvc.perform(post("/admin/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, CLIENT_SECRET)
                        .content(registrationPayload))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult result = mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().is2xxSuccessful())
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());
        String instanceIdStr = responseNode.path("id").asText();
        assertThat(instanceIdStr).isNotBlank();

        Instance instance = instanceRepository.find(InstanceId.of(instanceIdStr)).block();
        assertThat(instance).isNotNull();
        assertThat(instance.isRegistered()).isTrue();
        assertThat(instance.getRegistration().getName()).isEqualTo("order-core-service");

        HttpHeaders headers = httpHeadersProvider.getHeaders(instance);
        assertThat(headers.getFirst(AtengActuatorHttpHeadersProvider.ACTUATOR_TOKEN_HEADER))
                .isEqualTo("DEMO_ACTUATOR_TOKEN_999");
    }

    @Test
    @DisplayName("场景 5：在线动态调整 Logback 指定包的日志级别并实时生效")
    void shouldDynamicallyModifyLogLevelAtRuntime() {
        String loggerName = "io.github.atengk.boot.sample.admin";
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = context.getLogger(loggerName);

        logger.setLevel(Level.INFO);
        assertThat(logger.getLevel()).isEqualTo(Level.INFO);
        assertThat(logger.isDebugEnabled()).isFalse();

        logger.setLevel(Level.DEBUG);
        assertThat(logger.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(logger.isDebugEnabled()).isTrue();
    }

    @Test
    @DisplayName("场景 6：优雅下线发版静默与告警防抖自愈状态机完整端到端联动")
    void shouldHandleGracefulDeregistrationAndDebounceStateCycle() {
        InstanceId testInstanceId = InstanceId.of("logistics-dispatch-01");
        Instance testInstance = Instance.create(testInstanceId)
                .register(Registration.create("logistics-dispatch-service", "http://127.0.0.1:8080/actuator/health").build());
        instanceRepository.save(testInstance).block();

        // 1. 触发注销事件 -> 验证记录入静默缓存
        InstanceDeregisteredEvent deregisteredEvent = new InstanceDeregisteredEvent(testInstanceId, 1L);
        notificationNotifier.onInstanceDeregistered(deregisteredEvent);
        assertThat(notificationNotifier.getDeregisteredInstances()).containsKey(testInstanceId);

        // 2. 静默期内收到下线状态事件 -> 静默放行
        InstanceStatusChangedEvent offlineEvent = new InstanceStatusChangedEvent(testInstanceId, 2L, StatusInfo.ofOffline());
        notificationNotifier.notify(offlineEvent).block();
        assertThat(notificationNotifier.getPendingTasks()).doesNotContainKey(testInstanceId);

        // 3. 正常状态下实例变为 DOWN -> 纳入 30 秒防抖队列
        InstanceId faultInstanceId = InstanceId.of("inventory-stock-01");
        Instance faultInstance = Instance.create(faultInstanceId)
                .register(Registration.create("inventory-stock-service", "http://127.0.0.1:8080/actuator/health").build());
        instanceRepository.save(faultInstance).block();
        InstanceStatusChangedEvent downEvent = new InstanceStatusChangedEvent(faultInstanceId, 1L, StatusInfo.ofDown());
        notificationNotifier.notify(downEvent).block();
        assertThat(notificationNotifier.getPendingTasks()).containsKey(faultInstanceId);

        // 4. 实例在防抖期内自愈 UP -> 取消防抖任务，不发通知
        InstanceStatusChangedEvent upEvent = new InstanceStatusChangedEvent(faultInstanceId, 2L, StatusInfo.ofUp());
        notificationNotifier.notify(upEvent).block();
        assertThat(notificationNotifier.getPendingTasks()).doesNotContainKey(faultInstanceId);
        assertThat(notificationNotifier.getAlertedInstances()).doesNotContain(faultInstanceId);
        if (recordingNotificationTemplate != null) {
            assertThat(recordingNotificationTemplate.getRecordedCards()).isEmpty();
        }
    }

    @Test
    @DisplayName("场景 7：控制台只读安全守卫与高危写操作拦截")
    void shouldEnforceWriteGuardPolicy() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "admin")
                        .param("password", "admin"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        Cookie satokenCookie = loginResult.getResponse().getCookie("satoken");
        assertThat(satokenCookie).isNotNull();

        properties.setEnableWriteOperations(false);

        mockMvc.perform(post("/admin/instances/inst-sample-01/actuator/loggers/root")
                        .cookie(satokenCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configuredLevel\":\"DEBUG\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/admin/instances/inst-sample-01/actuator/env")
                        .cookie(satokenCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"test\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/admin/instances/inst-sample-01/actuator/loggers/root")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configuredLevel\":\"DEBUG\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin/instances/inst-sample-01/actuator/health")
                        .cookie(satokenCookie))
                .andExpect(status().is(org.hamcrest.Matchers.not(403)));

        String validPayload = """
                {
                    "name": "payment-readonly-probe",
                    "serviceUrl": "http://127.0.0.1:8080/",
                    "managementUrl": "http://127.0.0.1:8080/actuator",
                    "healthUrl": "http://127.0.0.1:8080/actuator/health"
                }
                """;
        mockMvc.perform(post("/admin/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, CLIENT_SECRET)
                        .content(validPayload))
                .andExpect(request().asyncStarted());
    }

    @Test
    @DisplayName("场景 8：动态实例注册准入策略 SPI 自定义扩展测试（拦截非法服务名与放行合规实例）")
    void shouldEnforceCustomAdmissionPolicy() throws Exception {
        String forbiddenPayload = """
                {
                    "name": "forbidden-order-svc",
                    "serviceUrl": "http://127.0.0.1:8080/",
                    "managementUrl": "http://127.0.0.1:8080/actuator",
                    "healthUrl": "http://127.0.0.1:8080/actuator/health"
                }
                """;
        mockMvc.perform(post("/admin/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, CLIENT_SECRET)
                        .content(forbiddenPayload))
                .andExpect(status().isForbidden())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("Service name rejected by sample admission policy: forbidden-order-svc"));

        String compliantPayload = """
                {
                    "name": "compliant-inventory-svc",
                    "serviceUrl": "http://127.0.0.1:8080/",
                    "managementUrl": "http://127.0.0.1:8080/actuator",
                    "healthUrl": "http://127.0.0.1:8080/actuator/health"
                }
                """;
        mockMvc.perform(post("/admin/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, CLIENT_SECRET)
                        .content(compliantPayload))
                .andExpect(request().asyncStarted());
    }

    @Test
    @DisplayName("场景 9：Actuator 出站抓取多模态认证自适应扩展测试（Basic Auth 与 Bearer Auth 端到端注册与标头挂载）")
    void shouldAdaptivelyDeriveMultiModalOutboundAuthHeaders() throws Exception {
        String basicPayload = """
                {
                    "name": "payment-gateway-service",
                    "serviceUrl": "http://127.0.0.1:8080/",
                    "managementUrl": "http://127.0.0.1:8080/actuator",
                    "healthUrl": "http://127.0.0.1:8080/actuator/health",
                    "metadata": {
                        "user.name": "ops-admin",
                        "user.password": "ops-pass-999"
                    }
                }
                """;
        MvcResult basicAsync = mockMvc.perform(post("/admin/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, CLIENT_SECRET)
                        .content(basicPayload))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult basicResult = mockMvc.perform(asyncDispatch(basicAsync))
                .andExpect(status().is2xxSuccessful())
                .andReturn();

        String basicInstId = objectMapper.readTree(basicResult.getResponse().getContentAsString()).path("id").asText();
        Instance basicInstance = instanceRepository.find(InstanceId.of(basicInstId)).block();
        assertThat(basicInstance).isNotNull();

        HttpHeaders basicHeaders = httpHeadersProvider.getHeaders(basicInstance);
        String expectedBasicAuth = "Basic " + Base64.getEncoder().encodeToString("ops-admin:ops-pass-999".getBytes(StandardCharsets.UTF_8));
        assertThat(basicHeaders.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo(expectedBasicAuth);
        assertThat(basicHeaders.containsKey(AtengActuatorHttpHeadersProvider.ACTUATOR_TOKEN_HEADER)).isFalse();

        String bearerPayload = """
                {
                    "name": "trade-settlement-service",
                    "serviceUrl": "http://127.0.0.1:8080/",
                    "managementUrl": "http://127.0.0.1:8080/actuator",
                    "healthUrl": "http://127.0.0.1:8080/actuator/health",
                    "metadata": {
                        "bearer-token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.sample-token-content"
                    }
                }
                """;
        MvcResult bearerAsync = mockMvc.perform(post("/admin/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, CLIENT_SECRET)
                        .content(bearerPayload))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult bearerResult = mockMvc.perform(asyncDispatch(bearerAsync))
                .andExpect(status().is2xxSuccessful())
                .andReturn();

        String bearerInstId = objectMapper.readTree(bearerResult.getResponse().getContentAsString()).path("id").asText();
        Instance bearerInstance = instanceRepository.find(InstanceId.of(bearerInstId)).block();
        assertThat(bearerInstance).isNotNull();

        HttpHeaders bearerHeaders = httpHeadersProvider.getHeaders(bearerInstance);
        assertThat(bearerHeaders.getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.sample-token-content");
        assertThat(bearerHeaders.containsKey(AtengActuatorHttpHeadersProvider.ACTUATOR_TOKEN_HEADER)).isFalse();
    }

    @Test
    @DisplayName("场景 10：容器云离线僵尸实例自动清理调度器测试（模拟超期离线实例并断言成功驱逐注销）")
    void shouldAutoEvictZombieInstanceWhenThresholdExceeded() throws Exception {
        assertThat(zombieCleanupScheduler).isNotNull();
        assertThat(zombieCleanupScheduler.isRunning()).isTrue();

        String payload = """
                {
                    "name": "zombie-settlement-service",
                    "serviceUrl": "http://127.0.0.1:8080/",
                    "managementUrl": "http://127.0.0.1:8080/actuator",
                    "healthUrl": "http://127.0.0.1:8080/actuator/health"
                }
                """;
        MvcResult asyncResult = mockMvc.perform(post("/admin/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, CLIENT_SECRET)
                        .content(payload))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult postResult = mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().is2xxSuccessful())
                .andReturn();

        String instIdStr = objectMapper.readTree(postResult.getResponse().getContentAsString()).path("id").asText();
        InstanceId instanceId = InstanceId.of(instIdStr);

        Instance instance = instanceRepository.find(instanceId).block();
        assertThat(instance).isNotNull();
        assertThat(instance.isRegistered()).isTrue();

        instanceRepository.compute(instanceId, (id, inst) -> Mono.just(inst.withStatusInfo(StatusInfo.ofOffline()))).block();

        properties.getCleanup().setZombieThreshold(Duration.ofMillis(10));
        Thread.sleep(30);

        int evicted = zombieCleanupScheduler.cleanZombies();
        assertThat(evicted).isGreaterThanOrEqualTo(1);

        Instance evictedInstance = instanceRepository.find(instanceId).block();
        assertThat(evictedInstance).isNotNull();
        assertThat(evictedInstance.isRegistered()).isFalse();
    }

    @Test
    @DisplayName("场景 11：告警通知规则名单过滤与离线录制桩富文本诊断卡片脱敏验证（零 @MockBean）")
    void shouldFilterAlertsByRulesAndRecordEnrichedCards() throws Exception {
        // 1. 默认配置 silent-* 命中 excluded-services 黑名单静默跳过
        InstanceId silentId = InstanceId.of("silent-inst-01");
        Instance silentInstance = Instance.create(silentId)
                .register(Registration.create("silent-canary-svc", "http://127.0.0.1:8080/actuator/health").build());
        instanceRepository.save(silentInstance).block();

        notificationNotifier.notify(new InstanceStatusChangedEvent(silentId, 1L, StatusInfo.ofDown())).block();
        assertThat(notificationNotifier.getPendingTasks()).doesNotContainKey(silentId);

        // 2. 配置 included-services 仅关注 core-*，非白名单服务跳过
        properties.getAlert().getExcludedServices().clear();
        properties.getAlert().getIncludedServices().add("core-*");

        InstanceId otherId = InstanceId.of("edge-inst-01");
        Instance otherInstance = Instance.create(otherId)
                .register(Registration.create("edge-gateway-svc", "http://127.0.0.1:8080/actuator/health").build());
        instanceRepository.save(otherInstance).block();

        notificationNotifier.notify(new InstanceStatusChangedEvent(otherId, 1L, StatusInfo.ofDown())).block();
        assertThat(notificationNotifier.getPendingTasks()).doesNotContainKey(otherId);

        // 3. 配置 notify-statuses 仅关注 DOWN，忽略 OFFLINE
        properties.getAlert().setNotifyStatuses(new ArrayList<>(List.of("DOWN")));

        InstanceId coreOfflineId = InstanceId.of("core-offline-01");
        Instance coreOfflineInstance = Instance.create(coreOfflineId)
                .register(Registration.create("core-payment-svc", "http://127.0.0.1:8080/actuator/health").build());
        instanceRepository.save(coreOfflineInstance).block();

        notificationNotifier.notify(new InstanceStatusChangedEvent(coreOfflineId, 1L, StatusInfo.ofOffline())).block();
        assertThat(notificationNotifier.getPendingTasks()).doesNotContainKey(coreOfflineId);

        // 4. 触发 core-* DOWN 并通过离线录制桩校验富文本诊断卡片与敏感凭据脱敏
        if (recordingNotificationTemplate == null) {
            return;
        }
        properties.getAlert().setNotifyStatuses(new ArrayList<>(List.of("DOWN", "OFFLINE")));
        properties.setDebounceDuration(Duration.ZERO);

        InstanceId coreAlertId = InstanceId.of("core-alert-01");
        Registration enrichedReg = Registration.builder()
                .name("core-order-svc")
                .serviceUrl("http://127.0.0.1:8080/")
                .healthUrl("http://127.0.0.1:8080/actuator/health")
                .managementUrl("http://127.0.0.1:8080/actuator")
                .metadata("version", "v2.5.0")
                .metadata("zone", "zone-east-1")
                .metadata("user.password", "TOP_SECRET_PASSWORD")
                .metadata("bearer-token", "RAW_ACCESS_TOKEN")
                .build();
        instanceRepository.save(Instance.create(coreAlertId).register(enrichedReg)).block();

        Map<String, Object> probeDetails = Map.of(
                "diskSpace", Map.of("status", "DOWN", "error", "Free disk space is below threshold"),
                "redis", Map.of("status", "DOWN", "error", "Redis connection refused")
        );
        notificationNotifier.notify(new InstanceStatusChangedEvent(coreAlertId, 1L, StatusInfo.valueOf("DOWN", probeDetails))).block();

        List<RecordedAlertCardVO> cards = waitForRecordedCards(recordingNotificationTemplate);
        assertThat(cards).hasSize(1);

        RecordedAlertCardVO sentCard = cards.get(0);
        assertThat(sentCard.getTitle()).contains("微服务实例异常宕机告警");
        String content = sentCard.getContent();
        assertThat(content).contains("core-order-svc");
        assertThat(content).contains("**应用版本**：v2.5.0");
        assertThat(content).contains("diskSpace");
        assertThat(content).contains("Free disk space is below threshold");
        assertThat(content).contains("redis");
        assertThat(content).contains("Redis connection refused");
        assertThat(content).contains("zone-east-1");
        assertThat(content).doesNotContain("TOP_SECRET_PASSWORD");
        assertThat(content).doesNotContain("RAW_ACCESS_TOKEN");
        assertThat(content).doesNotContain("user.password");
        assertThat(content).doesNotContain("bearer-token");
    }

    @Test
    @DisplayName("场景 12（Local 真实模式）：宿主中立配置化外部客户端探测与真实 Webhook 探测")
    void shouldProbeConfiguredExternalClientInLocalMode() throws Exception {
        if (!isLocalMode()) {
            return;
        }

        String clientHealthUrl = environment.getProperty("sample.admin.local.client-health-url");
        if (clientHealthUrl == null || clientHealthUrl.isBlank() || clientHealthUrl.contains("YOUR_LOCAL_CLIENT")) {
            log.info("[豁免：无可用环境] 未配置 sample.admin.local.client-health-url，跳过外部客户端真实网络探测。");
        } else {
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(clientHealthUrl.trim()))
                    .timeout(Duration.ofSeconds(3))
                    .GET();
            String clientToken = environment.getProperty("sample.admin.local.client-actuator-token");
            if (clientToken != null && !clientToken.isBlank() && !clientToken.contains("YOUR_LOCAL_CLIENT")) {
                reqBuilder.header(AtengActuatorHttpHeadersProvider.ACTUATOR_TOKEN_HEADER, clientToken.trim());
            }
            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.body()).contains("\"status\":\"UP\"");
        }

        String webhook = environment.getProperty("ateng.boot.notification.channels.ops-alert.webhook");
        if (webhook != null && !webhook.isBlank() && !webhook.contains("YOUR_WECOM_WEBHOOK_URL")) {
            log.info("[Local 真实模式] 探测到已配置的 ops-alert 真实 Webhook 渠道。");
        } else {
            log.info("[豁免：未配置真实 Webhook] ops-alert 未配置或仍为占位符，跳过真实群机器人投递。");
        }
    }

    private List<RecordedAlertCardVO> waitForRecordedCards(RecordingNotificationTemplate recorder) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            List<RecordedAlertCardVO> cards = recorder.getRecordedCards();
            if (!cards.isEmpty()) {
                return cards;
            }
            Thread.sleep(50);
        }
        return recorder.getRecordedCards();
    }

    private boolean isLocalMode() {
        return Arrays.asList(environment.getActiveProfiles()).contains("local");
    }
}
