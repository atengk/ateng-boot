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
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import io.github.atengk.boot.admin.notifier.AtengAdminNotificationNotifier;
import io.github.atengk.boot.admin.provider.AtengActuatorHttpHeadersProvider;
import io.github.atengk.boot.admin.security.AdminSsrfGuardFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

        // 在线动态调整为 DEBUG
        logger.setLevel(Level.DEBUG);
        assertThat(logger.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(logger.isDebugEnabled()).isTrue();

        // 恢复为 INFO
        logger.setLevel(Level.INFO);
        assertThat(logger.getLevel()).isEqualTo(Level.INFO);
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
}
