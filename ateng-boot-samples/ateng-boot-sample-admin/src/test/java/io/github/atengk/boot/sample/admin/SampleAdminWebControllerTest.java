package io.github.atengk.boot.sample.admin;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.events.InstanceDeregisteredEvent;
import io.github.atengk.boot.admin.notifier.AtengAdminNotificationNotifier;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.sample.admin.config.RecordingNotificationTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 4 大领域 Controller 黑盒契约回归测试
 * 全面验证 /sample/admin/** 路由映射、JSR-303 Fail-Fast、Zero-Entity-Leakage VO、凭据脱敏与 Result<T> 契约
 *
 * @author Ateng
 * @since 2026-11-11
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Spring Boot Admin 样例 4 领域 Controller 黑盒测试")
class SampleAdminWebControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AtengAdminServerProperties properties;

    @Autowired
    private InstanceRepository instanceRepository;

    @Autowired
    private AtengAdminNotificationNotifier notificationNotifier;

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
    @DisplayName("准入领域控制器：评估合规/违规/SSRF 实例、认证 SPI 与路径规范化，且拦截空入参")
    void shouldVerifyAdmissionControllerContracts() throws Exception {
        String validReq = """
                {
                    "serviceName": "order-core-service",
                    "serviceUrl": "http://127.0.0.1:8080/",
                    "healthUrl": "http://127.0.0.1:8080/actuator/health",
                    "clientSecret": "CLUSTER_SECRET_DEMO_2026"
                }
                """;
        mockMvc.perform(post("/sample/admin/admission/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validReq))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.allowed").value(true))
                .andExpect(jsonPath("$.data.httpStatus").value(200))
                .andExpect(jsonPath("$.data.policyClass").value("SampleInstanceAdmissionPolicy"));

        String forbiddenReq = """
                {
                    "serviceName": "forbidden-order-svc",
                    "serviceUrl": "http://127.0.0.1:8080/",
                    "clientSecret": "CLUSTER_SECRET_DEMO_2026"
                }
                """;
        mockMvc.perform(post("/sample/admin/admission/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forbiddenReq))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed").value(false))
                .andExpect(jsonPath("$.data.httpStatus").value(403))
                .andExpect(jsonPath("$.data.reason", containsString("forbidden-order-svc")));

        String ftpReq = """
                {
                    "serviceName": "legacy-ftp-svc",
                    "serviceUrl": "ftp://127.0.0.1:21/",
                    "clientSecret": "CLUSTER_SECRET_DEMO_2026"
                }
                """;
        mockMvc.perform(post("/sample/admin/admission/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ftpReq))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed").value(false))
                .andExpect(jsonPath("$.data.httpStatus").value(403));

        mockMvc.perform(post("/sample/admin/admission/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceName\":\"\",\"serviceUrl\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));

        // 控制台认证 SPI 验证：合法配置账号通过、锁定策略账号拦截
        mockMvc.perform(post("/sample/admin/admission/auth-verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authenticated").value(true))
                .andExpect(jsonPath("$.data.providerClass").value("SampleAdminAuthProvider"))
                .andExpect(jsonPath("$.data.maskedUsername").value("ad**n"));

        mockMvc.perform(post("/sample/admin/admission/auth-verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"locked-operator\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authenticated").value(false))
                .andExpect(jsonPath("$.data.providerClass").value("SampleAdminAuthProvider"));

        mockMvc.perform(get("/sample/admin/admission/normalize-path").param("path", "admin///"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.normalizedPath").value("/admin"));
    }

    @Test
    @DisplayName("治理领域控制器：只读写守卫切换、出站四阶标头脱敏推导与日志级别调整")
    void shouldVerifyGovernanceControllerContracts() throws Exception {
        mockMvc.perform(post("/sample/admin/governance/write-guard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enableWriteOperations\":false,\"probeMethod\":\"POST\",\"probeUri\":\"/admin/instances/order-core-01/actuator/loggers/root\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enableWriteOperations").value(false))
                .andExpect(jsonPath("$.data.actuatorProxyEndpoint").value(true))
                .andExpect(jsonPath("$.data.wouldBlock").value(true));

        mockMvc.perform(get("/sample/admin/governance/write-guard")
                        .param("method", "POST")
                        .param("uri", "/admin/instances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actuatorProxyEndpoint").value(false))
                .andExpect(jsonPath("$.data.wouldBlock").value(false));

        String basicPreviewReq = """
                {
                    "serviceName": "payment-gateway-service",
                    "metadata": {
                        "user.name": "ops-admin",
                        "user.password": "TOP_SECRET_PASS_999"
                    }
                }
                """;
        mockMvc.perform(post("/sample/admin/governance/headers/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(basicPreviewReq))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authMode").value("BASIC"))
                .andExpect(jsonPath("$.data.headerName").value("Authorization"))
                .andExpect(jsonPath("$.data.maskedHeaderValue").value("Basic ******"))
                .andExpect(jsonPath("$.data.maskedHeaderValue", not(containsString("TOP_SECRET_PASS_999"))));

        String bearerPreviewReq = """
                {
                    "serviceName": "inventory-stock-service",
                    "metadata": {
                        "bearer-token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.sample-jwt-token-8888"
                    }
                }
                """;
        mockMvc.perform(post("/sample/admin/governance/headers/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bearerPreviewReq))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authMode").value("BEARER"))
                .andExpect(jsonPath("$.data.maskedHeaderValue", containsString("Bearer eyJh")))
                .andExpect(jsonPath("$.data.maskedHeaderValue", not(containsString("sample-jwt-token-8888"))));

        mockMvc.perform(post("/sample/admin/governance/headers/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceName\":\"logistics-dispatch-service\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authMode").value("GLOBAL_FALLBACK"))
                .andExpect(jsonPath("$.data.headerName").value("X-Actuator-Token"));

        mockMvc.perform(post("/sample/admin/governance/loggers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loggerName\":\"io.github.atengk.boot.sample.admin\",\"level\":\"DEBUG\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveLevel").value("DEBUG"));

        mockMvc.perform(get("/sample/admin/governance/loggers")
                        .param("loggerName", "io.github.atengk.boot.sample.admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveLevel").value("DEBUG"));

        mockMvc.perform(post("/sample/admin/governance/loggers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loggerName\":\"io.github.atengk.boot.sample.admin\",\"level\":\"INVALID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("生命周期领域控制器：演练实例 VO 登记查询、僵尸实例触发驱逐及负阈值 JSR-303 拦截")
    void shouldVerifyLifecycleControllerContracts() throws Exception {
        String zombieMockReq = """
                {
                    "instanceId": "sample-zombie-pod-01",
                    "serviceName": "settlement-batch-service",
                    "serviceUrl": "http://127.0.0.1:8080/",
                    "version": "v2.4.0",
                    "zone": "cn-hangzhou-a",
                    "status": "OFFLINE"
                }
                """;
        mockMvc.perform(post("/sample/admin/lifecycle/instances/mock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(zombieMockReq))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instanceId").value("sample-zombie-pod-01"))
                .andExpect(jsonPath("$.data.serviceName").value("settlement-batch-service"))
                .andExpect(jsonPath("$.data.status").value("OFFLINE"))
                .andExpect(jsonPath("$.data.registered").value(true));

        mockMvc.perform(get("/sample/admin/lifecycle/instances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].instanceId", hasItem("sample-zombie-pod-01")));

        // 负阈值覆盖入参触发 JSR-303 400 Fail-Fast
        mockMvc.perform(post("/sample/admin/lifecycle/zombies/clean")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zombieThresholdOverride\":\"-5S\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));

        Thread.sleep(25);
        mockMvc.perform(post("/sample/admin/lifecycle/zombies/clean")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zombieThresholdOverride\":\"PT0.01S\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schedulerRunning").value(true))
                .andExpect(jsonPath("$.data.evictedCount", greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("告警领域控制器：即时触发宕机告警卡片、恢复闭环与发版静默状态查询")
    void shouldVerifyAlertControllerContracts() throws Exception {
        String registerReq = """
                {
                    "instanceId": "sample-order-alert-01",
                    "serviceName": "order-core-service",
                    "serviceUrl": "http://127.0.0.1:8080/",
                    "version": "v3.0.1",
                    "zone": "cn-shanghai-b",
                    "status": "UP",
                    "metadata": {
                        "user.password": "HIDDEN_PASS_FOR_MASK_TEST"
                    }
                }
                """;
        mockMvc.perform(post("/sample/admin/lifecycle/instances/mock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerReq))
                .andExpect(status().isOk());

        String downReq = """
                {
                    "instanceId": "sample-order-alert-01",
                    "status": "DOWN",
                    "probeComponent": "diskSpace",
                    "probeError": "Free disk space is below threshold",
                    "immediate": true
                }
                """;
        mockMvc.perform(post("/sample/admin/alert/status-transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(downReq))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingCount").value(0))
                .andExpect(jsonPath("$.data.alertedInstanceIds", hasItem("sample-order-alert-01")));

        if (recordingNotificationTemplate != null) {
            mockMvc.perform(get("/sample/admin/alert/cards"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].title", containsString("微服务实例异常宕机告警")))
                    .andExpect(jsonPath("$.data[0].content", containsString("order-core-service")))
                    .andExpect(jsonPath("$.data[0].content", containsString("diskSpace")))
                    .andExpect(jsonPath("$.data[0].content", containsString("Free disk space is below threshold")))
                    .andExpect(jsonPath("$.data[0].content", not(containsString("HIDDEN_PASS_FOR_MASK_TEST"))));
        }

        mockMvc.perform(post("/sample/admin/alert/status-transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"sample-order-alert-01\",\"status\":\"UP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alertedInstanceIds", not(hasItem("sample-order-alert-01"))));

        if (recordingNotificationTemplate != null) {
            mockMvc.perform(get("/sample/admin/alert/cards"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].title", containsString("微服务实例恢复正常通知")));
        }

        mockMvc.perform(post("/sample/admin/alert/graceful-deregister")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"sample-order-alert-01\",\"followUpFaultStatus\":\"OFFLINE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deregisteredSilentInstanceIds", hasItem("sample-order-alert-01")))
                .andExpect(jsonPath("$.data.pendingCount").value(0));

        mockMvc.perform(get("/sample/admin/alert/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.excludedServices", hasItem("silent-*")));

        mockMvc.perform(post("/sample/admin/alert/status-transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"\",\"status\":\"INVALID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
