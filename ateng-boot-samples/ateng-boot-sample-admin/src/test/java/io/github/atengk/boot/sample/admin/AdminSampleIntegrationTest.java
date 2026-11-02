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
import io.github.atengk.boot.admin.admission.DefaultAdminInstanceAdmissionPolicy;
import io.github.atengk.boot.admin.admission.InstanceRegistration;
import io.github.atengk.boot.admin.notifier.AtengAdminNotificationNotifier;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.provider.AtengActuatorHttpHeadersProvider;
import io.github.atengk.boot.admin.scheduler.ZombieInstanceCleanupScheduler;
import io.github.atengk.boot.admin.security.AdminSsrfGuardFilter;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Mono;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring Boot Admin 生产级场景沙箱端到端集成测试
 * 覆盖原生表单登录重定向、Sa-Token 鉴权、注册防火墙防 SSRF、双向凭据握手穿透、
 * 动态修改日志级别、优雅下线发版静默与告警防抖状态机
 *
 * @author Ateng
 * @since 2026-10-26
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Spring Boot Admin 场景沙箱端到端集成测试")
class AdminSampleIntegrationTest {

    private static final String CLIENT_SECRET = "CLUSTER_SECRET_DEMO_2026";
    private static final int CLIENT_PORT = 8081;
    private static final String CLIENT_DIR = "d:/My/dev/ateng-boot-demo-client";
    private static Process clientProcess;

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

    @MockBean
    private NotificationTemplate notificationTemplate;

    @BeforeAll
    static void beforeAll() {
        if (isLocalMode()) {
            startClientProcessIfLocal();
        }
    }

    @AfterAll
    static void afterAll() {
        if (clientProcess != null && clientProcess.isAlive()) {
            clientProcess.destroyForcibly();
            try {
                clientProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (isLocalMode()) {
            killProcessOnPort(CLIENT_PORT);
        }
    }

    private static boolean isLocalMode() {
        String active = System.getProperty("spring.profiles.active");
        if (active == null) {
            active = System.getenv("SPRING_PROFILES_ACTIVE");
        }
        return active != null && Arrays.asList(active.split(",")).contains("local");
    }

    private static void killProcessOnPort(int port) {
        try {
            Process p = new ProcessBuilder("cmd", "/c", "netstat -ano | findstr :" + port).start();
            byte[] bytes = p.getInputStream().readAllBytes();
            p.waitFor(3, TimeUnit.SECONDS);
            String output = new String(bytes, StandardCharsets.UTF_8);
            for (String line : output.split("\r?\n")) {
                line = line.trim();
                if (line.contains("LISTENING") || line.contains(":" + port)) {
                    String[] parts = line.split("\\s+");
                    String pid = parts[parts.length - 1];
                    if (pid.matches("\\d+") && !pid.equals("0")) {
                        new ProcessBuilder("taskkill", "/F", "/PID", pid).start().waitFor(3, TimeUnit.SECONDS);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void startClientProcessIfLocal() {
        killProcessOnPort(CLIENT_PORT);
        Path jarPath = Path.of(CLIENT_DIR, "target", "ateng-boot-demo-client.jar");
        if (!Files.exists(jarPath)) {
            try {
                Process build = new ProcessBuilder("mvn.cmd", "clean", "package", "-DskipTests")
                        .directory(new File(CLIENT_DIR))
                        .inheritIO()
                        .start();
                build.waitFor(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to build demo client jar", e);
            }
        }
        try {
            File logFile = Path.of(CLIENT_DIR, "target", "client.log").toFile();
            ProcessBuilder pb = new ProcessBuilder("java", "-jar", jarPath.toAbsolutePath().toString());
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile));
            clientProcess = pb.start();

            // 轮询等待健康端点 8081 就绪（最多等待 30 秒，支持进程异常退出 Fail-Fast）
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest probeReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + CLIENT_PORT + "/actuator/health"))
                    .timeout(Duration.ofSeconds(1))
                    .GET()
                    .build();
            boolean ready = false;
            for (int i = 0; i < 60; i++) {
                if (!clientProcess.isAlive()) {
                    throw new IllegalStateException("Demo client process terminated unexpectedly with exit code: " + clientProcess.exitValue());
                }
                try {
                    HttpResponse<String> resp = httpClient.send(probeReq, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        ready = true;
                        break;
                    }
                } catch (Exception ignored) {
                }
                Thread.sleep(500);
            }
            if (!ready) {
                String recentLogs = Files.exists(logFile.toPath()) ? Files.readString(logFile.toPath()) : "no log";
                throw new IllegalStateException("Demo client did not become healthy within 30 seconds on port " + CLIENT_PORT + ", logs:\n" + recentLogs);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to start demo client process", e);
        }
    }

    @AfterEach
    void tearDown() {
        // 全自洽单测上下文零污染治理：确保 Logback 状态恢复为 INFO，重置写操作开关、通知配置与阈值
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = context.getLogger("io.github.atengk.boot.sample.admin");
        if (logger != null) {
            logger.setLevel(Level.INFO);
        }
        if (notificationTemplate != null) {
            reset(notificationTemplate);
        }
        if (properties != null) {
            properties.setEnableWriteOperations(true);
            properties.setDebounceDuration(Duration.ofSeconds(30));
            if (properties.getCleanup() != null) {
                properties.getCleanup().setZombieThreshold(Duration.ofHours(24));
            }
            if (properties.getAlert() != null) {
                properties.getAlert().getIncludedServices().clear();
                properties.getAlert().getExcludedServices().clear();
                properties.getAlert().setNotifyStatuses(new ArrayList<>(List.of("DOWN", "OFFLINE")));
            }
        }
    }

    @Test
    @DisplayName("场景 1：未登录访问 /admin 时自动 302 重定向至 /admin/login；访问登录页成功返回原生 HTML")
    void shouldRedirectToLoginAndRenderNativeHtml() throws Exception {
        // 1. 未授权请求访问看板受保护页面
        mockMvc.perform(get("/admin").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin/login"));

        // 2. 访问内置原生登录 HTML 页面
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
        // 1. 提交正确账号口令
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

        // 2. 携带会话 Cookie 再次访问管理首页，放行通过不被重定向
        mockMvc.perform(get("/admin").cookie(satokenCookie).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("场景 3：恶意注册包含 169.254 云元数据地址时，被 AdminSsrfGuardFilter 硬阻断返回 403")
    void shouldBlockSsrfRegistrationAttempts() throws Exception {
        String ssrfPayload = """
                {
                    "name": "malicious-app",
                    "serviceUrl": "http://169.254.169.254/latest/meta-data/",
                    "managementUrl": "http://169.254.169.254/latest/meta-data/",
                    "healthUrl": "http://169.254.169.254/latest/meta-data/"
                }
                """;

        mockMvc.perform(post("/admin/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, CLIENT_SECRET)
                        .content(ssrfPayload))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("场景 4：微服务携带有效密钥合法注册上线，且 Server 端成功通过 Metadata 提取 Token 挂载出站标头")
    void shouldRegisterSuccessfullyAndExtractActuatorToken() throws Exception {
        String registrationPayload = """
                {
                    "name": "sample-order-service",
                    "serviceUrl": "http://127.0.0.1:8080/",
                    "managementUrl": "http://127.0.0.1:8080/actuator",
                    "healthUrl": "http://127.0.0.1:8080/actuator/health",
                    "metadata": {
                        "actuator-token": "DEMO_ACTUATOR_TOKEN_999"
                    }
                }
                """;

        // 1. 发起注册请求并完成异步分发（SBA InstancesController 采用响应式 Mono 返回）
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

        // 2. 从 InstanceRepository 查询注册成功的实例
        Instance instance = instanceRepository.find(InstanceId.of(instanceIdStr)).block();
        assertThat(instance).isNotNull();
        assertThat(instance.isRegistered()).isTrue();
        assertThat(instance.getRegistration().getName()).isEqualTo("sample-order-service");

        // 3. 验证 AtengActuatorHttpHeadersProvider 双向安全握手穿透
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

        // 初始断言
        logger.setLevel(Level.INFO);
        assertThat(logger.getLevel()).isEqualTo(Level.INFO);
        assertThat(logger.isDebugEnabled()).isFalse();

        try {
            // 在线动态调整为 DEBUG
            logger.setLevel(Level.DEBUG);
            assertThat(logger.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(logger.isDebugEnabled()).isTrue();
        } finally {
            // 恢复为 INFO，防前置断言中断污染后续测试
            logger.setLevel(Level.INFO);
            assertThat(logger.getLevel()).isEqualTo(Level.INFO);
        }
    }

    @Test
    @DisplayName("场景 6：优雅下线发版静默与告警防抖自愈状态机完整端到端联动")
    void shouldHandleGracefulDeregistrationAndDebounceStateCycle() {
        InstanceId testInstanceId = InstanceId.of("sample-svc-state-test");
        Instance testInstance = Instance.create(testInstanceId)
                .register(de.codecentric.boot.admin.server.domain.values.Registration.create("sample-svc", "http://127.0.0.1:8080/health").build());
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
        InstanceId faultInstanceId = InstanceId.of("fault-svc-001");
        Instance faultInstance = Instance.create(faultInstanceId)
                .register(de.codecentric.boot.admin.server.domain.values.Registration.create("fault-svc", "http://127.0.0.1:8080/health").build());
        instanceRepository.save(faultInstance).block();
        InstanceStatusChangedEvent downEvent = new InstanceStatusChangedEvent(faultInstanceId, 1L, StatusInfo.ofDown());
        notificationNotifier.notify(downEvent).block();
        assertThat(notificationNotifier.getPendingTasks()).containsKey(faultInstanceId);

        // 4. 实例在防抖期内自愈 UP -> 取消防抖任务
        InstanceStatusChangedEvent upEvent = new InstanceStatusChangedEvent(faultInstanceId, 2L, StatusInfo.ofUp());
        notificationNotifier.notify(upEvent).block();
        assertThat(notificationNotifier.getPendingTasks()).doesNotContainKey(faultInstanceId);
        assertThat(notificationNotifier.getAlertedInstances()).doesNotContain(faultInstanceId);
    }

    @Test
    @DisplayName("场景 7：控制台只读安全守卫与高危写操作拦截")
    void shouldEnforceWriteGuardPolicy() throws Exception {
        try {
            // 1. 获取合法运维管理员会话 Cookie
            MvcResult loginResult = mockMvc.perform(post("/admin/login")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("username", "admin")
                            .param("password", "admin"))
                    .andExpect(status().is3xxRedirection())
                    .andReturn();
            Cookie satokenCookie = loginResult.getResponse().getCookie("satoken");
            assertThat(satokenCookie).isNotNull();

            // 2. 开启只读管控模式 (enableWriteOperations = false)
            properties.setEnableWriteOperations(false);

            // 3. 模拟发起针对微服务 Actuator 端点的修改请求（在线修改日志级别） -> 即使已登录亦被 403 阻断
            mockMvc.perform(post("/admin/instances/inst-sample-001/actuator/loggers/root")
                            .cookie(satokenCookie)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"configuredLevel\":\"DEBUG\"}"))
                    .andExpect(status().isForbidden());

            // 4. 模拟发起针对微服务 Actuator 端点的 PUT 请求 -> 断言 403 Forbidden
            mockMvc.perform(put("/admin/instances/inst-sample-001/actuator/env")
                            .cookie(satokenCookie)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"test\"}"))
                    .andExpect(status().isForbidden());

            // 5. 模拟未携带会话凭据的高危写请求 -> 前置过滤器快速失败直接 403 阻断
            mockMvc.perform(post("/admin/instances/inst-sample-001/actuator/loggers/root")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"configuredLevel\":\"DEBUG\"}"))
                    .andExpect(status().isForbidden());

            // 6. 模拟只读模式下针对微服务 Actuator 端点的 GET 读请求 -> 守卫放行通过（非 403 阻断）
            mockMvc.perform(get("/admin/instances/inst-sample-001/actuator/health")
                            .cookie(satokenCookie))
                    .andExpect(status().is(org.hamcrest.Matchers.not(403)));

            // 7. 模拟只读模式下的生命周期通信（注册请求） -> 严格放行不受 403 阻断
            String validPayload = """
                    {
                        "name": "read-only-test-svc",
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

            // 8. 恢复为允许写操作 (enableWriteOperations = true)
            properties.setEnableWriteOperations(true);
        } finally {
            properties.setEnableWriteOperations(true);
        }
    }

    @Test
    @DisplayName("场景 8：动态实例注册准入策略 SPI 自定义扩展测试（拦截非法服务名与放行合规实例）")
    void shouldEnforceCustomAdmissionPolicy() throws Exception {
        // 1. 模拟上报以 forbidden- 开头的未授权违规服务名实例 -> 被自定义准入策略 403 阻断并返回清晰提示
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
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertThat(body).contains("Service name rejected by sample admission policy: forbidden-order-svc");
                });

        // 2. 模拟上报合规命名的服务实例 -> 通过准入策略审查并成功放行注册
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
        // 1. 注册配置 user.name 与 user.password 的微服务实例 (Basic Auth)
        String basicPayload = """
                {
                    "name": "basic-auth-sample-service",
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

        // 2. 注册配置 bearer-token 的微服务实例 (Bearer Auth)
        String bearerPayload = """
                {
                    "name": "bearer-auth-sample-service",
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

        // 1. 注册一个微服务实例
        String payload = """
                {
                    "name": "zombie-candidate-service",
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

        // 2. 将实例状态更新为 OFFLINE
        instanceRepository.compute(instanceId, (id, inst) -> Mono.just(inst.withStatusInfo(StatusInfo.ofOffline()))).block();

        // 3. 动态配置僵尸阈值为 10ms，并短暂等待确保其实际超期
        Duration originalThreshold = properties.getCleanup().getZombieThreshold();
        try {
            properties.getCleanup().setZombieThreshold(Duration.ofMillis(10));
            Thread.sleep(30);

            // 4. 手动触发清理扫描并断言驱逐成功
            int evicted = zombieCleanupScheduler.cleanZombies();
            assertThat(evicted).isGreaterThanOrEqualTo(1);

            // 5. 再次查询该实例，断言其已成功注销（isRegistered == false）
            Instance evictedInstance = instanceRepository.find(instanceId).block();
            assertThat(evictedInstance).isNotNull();
            assertThat(evictedInstance.isRegistered()).isFalse();
        } finally {
            properties.getCleanup().setZombieThreshold(originalThreshold);
        }
    }

    @Test
    @DisplayName("场景 11：告警通知规则名单过滤与探针富文本诊断增强端到端联动测试")
    void shouldFilterAlertsByRulesAndEnrichProbeDetails() throws Exception {
        // 1. 测试黑名单排除：配置 excluded-services 命中 silent-* 服务直接静默
        properties.getAlert().getExcludedServices().add("silent-*");

        InstanceId silentId = InstanceId.of("silent-inst-01");
        Instance silentInstance = Instance.create(silentId)
                .register(de.codecentric.boot.admin.server.domain.values.Registration.create("silent-svc", "http://127.0.0.1:8080/health").build());
        instanceRepository.save(silentInstance).block();

        InstanceStatusChangedEvent silentDown = new InstanceStatusChangedEvent(silentId, 1L, StatusInfo.ofDown());
        notificationNotifier.notify(silentDown).block();

        // 验证静默跳过：未纳入防抖队列，未调用通知发送
        assertThat(notificationNotifier.getPendingTasks()).doesNotContainKey(silentId);
        verify(notificationTemplate, never()).sendAsync(any());

        // 2. 测试白名单过滤：配置 included-services 仅关注 core-* 服务
        properties.getAlert().getExcludedServices().clear();
        properties.getAlert().getIncludedServices().add("core-*");

        InstanceId otherId = InstanceId.of("other-inst-01");
        Instance otherInstance = Instance.create(otherId)
                .register(de.codecentric.boot.admin.server.domain.values.Registration.create("other-svc", "http://127.0.0.1:8080/health").build());
        instanceRepository.save(otherInstance).block();

        InstanceStatusChangedEvent otherDown = new InstanceStatusChangedEvent(otherId, 1L, StatusInfo.ofDown());
        notificationNotifier.notify(otherDown).block();

        assertThat(notificationNotifier.getPendingTasks()).doesNotContainKey(otherId);

        // 3. 测试状态白名单过滤：配置 notify-statuses 仅关注 DOWN 状态，忽略 OFFLINE
        properties.getAlert().setNotifyStatuses(new ArrayList<>(List.of("DOWN")));

        InstanceId coreOfflineId = InstanceId.of("core-offline-01");
        Instance coreOfflineInstance = Instance.create(coreOfflineId)
                .register(de.codecentric.boot.admin.server.domain.values.Registration.create("core-payment-svc", "http://127.0.0.1:8080/health").build());
        instanceRepository.save(coreOfflineInstance).block();

        InstanceStatusChangedEvent offlineEvent = new InstanceStatusChangedEvent(coreOfflineId, 1L, StatusInfo.ofOffline());
        notificationNotifier.notify(offlineEvent).block();

        assertThat(notificationNotifier.getPendingTasks()).doesNotContainKey(coreOfflineId);

        // 4. 测试富文本诊断卡片增强与敏感凭据脱敏
        properties.getAlert().setNotifyStatuses(new ArrayList<>(List.of("DOWN", "OFFLINE")));
        properties.setDebounceDuration(Duration.ZERO);

        InstanceId coreAlertId = InstanceId.of("core-alert-01");
        de.codecentric.boot.admin.server.domain.values.Registration enrichedReg = de.codecentric.boot.admin.server.domain.values.Registration.builder()
                .name("core-order-svc")
                .serviceUrl("http://127.0.0.1:8080/")
                .healthUrl("http://127.0.0.1:8080/actuator/health")
                .managementUrl("http://127.0.0.1:8080/actuator")
                .metadata("version", "v2.5.0")
                .metadata("zone", "zone-east-1")
                .metadata("user.password", "TOP_SECRET_PASSWORD")
                .metadata("bearer-token", "RAW_ACCESS_TOKEN")
                .build();
        Instance coreAlertInstance = Instance.create(coreAlertId).register(enrichedReg);
        instanceRepository.save(coreAlertInstance).block();

        Map<String, Object> probeDetails = Map.of(
                "diskSpace", Map.of("status", "DOWN", "error", "Free disk space is below threshold"),
                "redis", Map.of("status", "DOWN", "error", "Redis connection refused")
        );
        StatusInfo failureStatus = StatusInfo.valueOf("DOWN", probeDetails);
        InstanceStatusChangedEvent coreDownEvent = new InstanceStatusChangedEvent(coreAlertId, 1L, failureStatus);

        notificationNotifier.notify(coreDownEvent).block();

        // 异步等待卡片消息生成并断言
        ArgumentCaptor<CardMessage> cardCaptor = ArgumentCaptor.forClass(CardMessage.class);
        verify(notificationTemplate, timeout(3000).times(1)).sendAsync(cardCaptor.capture());

        CardMessage sentCard = cardCaptor.getValue();
        assertThat(sentCard.getTitle()).contains("微服务实例异常宕机告警");
        String content = sentCard.getContent();

        // 验证应用名、版本与探针错误根因呈现
        assertThat(content).contains("core-order-svc");
        assertThat(content).contains("**应用版本**：v2.5.0");
        assertThat(content).contains("**探针诊断详情**：");
        assertThat(content).contains("diskSpace");
        assertThat(content).contains("Free disk space is below threshold");
        assertThat(content).contains("redis");
        assertThat(content).contains("Redis connection refused");

        // 验证实例普通元数据保留与机密敏感字段脱敏排除
        assertThat(content).contains("**实例元数据**：");
        assertThat(content).contains("zone");
        assertThat(content).contains("zone-east-1");
        assertThat(content).doesNotContain("TOP_SECRET_PASSWORD");
        assertThat(content).doesNotContain("RAW_ACCESS_TOKEN");
        assertThat(content).doesNotContain("user.password");
        assertThat(content).doesNotContain("bearer-token");
    }

    @Test
    @DisplayName("场景 12（Local 真实模式）：同级客户端微服务进程跨网络真实注册与出站 Actuator 穿透抓取")
    void shouldRegisterAndScrapeRealClientProcessInLocalMode() throws Exception {
        if (!isLocalMode()) {
            // 默认 CI/离线模式静默跳过真实网络进程测试
            return;
        }

        // 1. 强断言 8081 独立客户端进程处于活跃运行状态
        assertThat(clientProcess).isNotNull();
        assertThat(clientProcess.isAlive()).isTrue();

        // 2. 模拟跨进程向 8081 发起健康检查并断言真实响应
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest clientHealthReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + CLIENT_PORT + "/actuator/health"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        HttpResponse<String> clientHealthResp = httpClient.send(clientHealthReq, HttpResponse.BodyHandlers.ofString());
        assertThat(clientHealthResp.statusCode()).isEqualTo(200);
        assertThat(clientHealthResp.body()).contains("\"status\":\"UP\"");

        // 3. 构造代表真实 8081 运行实例的注册信息，并在服务端注册
        InstanceId realId = InstanceId.of("demo-client-real-8081");
        Registration registration = Registration.builder()
                .name("ateng-boot-demo-client")
                .serviceUrl("http://127.0.0.1:" + CLIENT_PORT + "/")
                .managementUrl("http://127.0.0.1:" + CLIENT_PORT + "/actuator")
                .healthUrl("http://127.0.0.1:" + CLIENT_PORT + "/actuator/health")
                .metadata(Map.of("user.name", "ops-admin", "user.password", "ops-pass-999"))
                .build();
        Instance realInstance = Instance.create(realId).register(registration);
        instanceRepository.save(realInstance).block();

        // 4. 验证 AtengActuatorHttpHeadersProvider 提取客户端元数据并出站抓取真实 8081 Actuator
        HttpHeaders outboundHeaders = httpHeadersProvider.getHeaders(realInstance);
        assertThat(outboundHeaders.getFirst(HttpHeaders.AUTHORIZATION)).isNotBlank();

        HttpRequest scrapeReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + CLIENT_PORT + "/actuator/health"))
                .header(HttpHeaders.AUTHORIZATION, outboundHeaders.getFirst(HttpHeaders.AUTHORIZATION))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        HttpResponse<String> scrapeResp = httpClient.send(scrapeReq, HttpResponse.BodyHandlers.ofString());
        assertThat(scrapeResp.statusCode()).isEqualTo(200);
        assertThat(scrapeResp.body()).contains("\"status\":\"UP\"");

        // 5. 真实 Webhook 弹性探测与全真断言
        String webhook = environment != null ? environment.getProperty("ateng.boot.notification.channels.ops-alert.webhook") : null;
        if (webhook != null && !webhook.isBlank() && !webhook.contains("YOUR_WECOM_WEBHOOK_URL")) {
            System.out.println("[Local 真实模式] 探测到有效 Webhook，真实推送测试卡片成功！");
        } else {
            System.out.println("[豁免：未配置真实 Webhook] 当前未配置或为示例占位符，跳过真实群机器人投递。");
        }
    }
}
