package io.github.atengk.boot.sample.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 云原生生命周期治理、启动就绪总览卡片与生产安全防呆端到端集成测试
 * 验证 /pre-stop 探针主动排空、ASCII 就绪总览卡片渲染及生产环境防呆自检
 *
 * @author Ateng
 * @since 2026-11-08
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Web 云原生生命周期与安全防呆端到端测试")
class WebCloudLifecycleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("1. 复合报告接口：GET /sample/web/lifecycle/ready-and-sanity 获取就绪卡片与防呆报告")
    void shouldReturnReadyCardAndSanityReport() throws Exception {
        mockMvc.perform(get("/sample/web/lifecycle/ready-and-sanity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.readyCard").isNotEmpty())
                .andExpect(jsonPath("$.data.isProductionProfile").isBoolean())
                .andExpect(jsonPath("$.data.sanityWarnings").isArray());
    }

    @Test
    @DisplayName("2. 独立卡片端点：GET /sample/web/lifecycle/ready-card 获取 ASCII 启动卡片纯文本")
    void shouldReturnReadyCard() throws Exception {
        mockMvc.perform(get("/sample/web/lifecycle/ready-card"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNotEmpty());
    }

    @Test
    @DisplayName("3. 独立防呆端点：GET /sample/web/lifecycle/sanity-check 获取安全防呆告警列表")
    void shouldReturnSanityWarnings() throws Exception {
        mockMvc.perform(get("/sample/web/lifecycle/sanity-check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("4. PreStopController 降级端点：GET /pre-stop 翻转就绪状态并幂等排空，返回在途请求水位")
    void shouldSupportPreStopGet() throws Exception {
        MvcResult result = mockMvc.perform(get("/pre-stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").isNotEmpty())
                .andReturn();
        assertNonNegativeInFlightMetrics(result);
    }

    @Test
    @DisplayName("5. PreStopController 降级端点：POST /pre-stop 幂等排空成功，返回在途请求水位")
    void shouldSupportPreStopPost() throws Exception {
        MvcResult result = mockMvc.perform(post("/pre-stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").isNotEmpty())
                .andReturn();
        assertNonNegativeInFlightMetrics(result);
    }

    private void assertNonNegativeInFlightMetrics(MvcResult result) throws Exception {
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.hasNonNull("inFlightRequestsAtStart")).isTrue();
        assertThat(data.hasNonNull("inFlightRequestsRemaining")).isTrue();
        assertThat(Long.parseLong(data.get("inFlightRequestsAtStart").asText())).isGreaterThanOrEqualTo(0L);
        assertThat(Long.parseLong(data.get("inFlightRequestsRemaining").asText())).isGreaterThanOrEqualTo(0L);
    }
}
