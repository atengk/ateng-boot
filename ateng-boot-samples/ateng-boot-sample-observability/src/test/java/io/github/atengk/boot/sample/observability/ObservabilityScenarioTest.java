package io.github.atengk.boot.sample.observability;

import io.github.atengk.boot.observability.security.ActuatorEndpointSecurityFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 云原生可观测性端到端集成场景自验测试
 * 覆盖五大核心端到端生产场景：
 * 1. Prometheus 性能度量与受管线程池动态指标抓取
 * 2. Kubernetes 黄金探针（Health / Liveness / Readiness）聚合就绪检查
 * 3. 业务正常 API 访问不被 Actuator 安全过滤器干扰
 * 4. 黄金探针与度量白名单端点免密放行
 * 5. 敏感高危端点（env / beans）生产锁定与凭据鉴权防卫
 *
 * @author Ateng
 * @since 2026-10-22
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("云原生可观测性场景样例端到端自验测试")
class ObservabilityScenarioTest {

    private static final String VALID_TOKEN = "obs-admin-secret-token";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("场景一：/actuator/prometheus 端点抓取验证受管线程池动态打标指标")
    void scenario1_prometheusThreadPoolMetricsScrape() throws Exception {
        // 先触发业务接口调度，产生线程池负载
        mockMvc.perform(get("/api/demo/execute-task"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 抓取 Prometheus 文本指标报文
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ateng_threadpool_core_size{pool=\"sampleTaskExecutor\"")))
                .andExpect(content().string(containsString("ateng_threadpool_max_size{pool=\"sampleTaskExecutor\"")))
                .andExpect(content().string(containsString("ateng_threadpool_active_count{pool=\"sampleTaskExecutor\"")))
                .andExpect(content().string(containsString("ateng_threadpool_queue_size{pool=\"sampleTaskExecutor\"")))
                .andExpect(content().string(containsString("ateng_threadpool_rejected_tasks_total{pool=\"sampleTaskExecutor\"")));
    }

    @Test
    @DisplayName("场景二：/actuator/health 聚合探针与 K8s 存活/就绪探针健康返回 200")
    void scenario2_healthAndKubernetesProbes() throws Exception {
        // 1. 全局健康检查
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.dynamicDatasource.status").value("UP"))
                .andExpect(jsonPath("$.components.storage.status").value("UP"));

        // 2. K8s 存活探针（Liveness）
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // 3. K8s 就绪探针（Readiness）
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("场景三：业务正常 API 访问不被 Actuator 安全过滤器干扰")
    void scenario3_businessApiPassThrough() throws Exception {
        mockMvc.perform(get("/api/demo/execute-task"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("任务已提交至受管线程池 sampleTaskExecutor"));
    }

    @Test
    @DisplayName("场景四：白名单端点（health / info / prometheus）免密无 Token 畅行无阻")
    void scenario4_whitelistedEndpointsNoTokenRequired() throws Exception {
        // /actuator/health 免密 200
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // /actuator/info 免密 200
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());

        // /actuator/prometheus 免密 200
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("场景五：高危敏感端点（/actuator/env 与 /actuator/beans）生产锁定与 Token 凭据防卫")
    void scenario5_sensitiveEndpointsLockdownAndTokenGuard() throws Exception {
        // 1. /actuator/env 无 Token 访问 -> 403 Forbidden
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value(containsString("Actuator 端点访问拒绝")));

        // 2. /actuator/env 带错误 Token -> 403 Forbidden
        mockMvc.perform(get("/actuator/env")
                        .header(ActuatorEndpointSecurityFilter.ACTUATOR_TOKEN_HEADER, "wrong-token-abc"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        // 3. /actuator/env 带正确 Token -> 200 OK 正常访问且返回 activeProfiles 属性
        mockMvc.perform(get("/actuator/env")
                        .header(ActuatorEndpointSecurityFilter.ACTUATOR_TOKEN_HEADER, VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProfiles").exists());

        // 4. /actuator/beans 无 Token 访问 -> 403 Forbidden
        mockMvc.perform(get("/actuator/beans"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        // 5. /actuator/beans 带正确 Token -> 200 OK 正常访问且返回 contexts 属性
        mockMvc.perform(get("/actuator/beans")
                        .header(ActuatorEndpointSecurityFilter.ACTUATOR_TOKEN_HEADER, VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contexts").exists());
    }
}
