package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.ai.dto.MultiAgentTaskRequest;
import io.github.atengk.boot.sample.ai.dto.MultiAgentWatchdogRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多智能体分层协同 (Supervisor-Worker) 控制器端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-06
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AI 主管-专工认知状态图与死循环硬看门狗控制器集成测试")
class MultiAgentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("端到端多智能体全流程协同：主管依次调度 researcher -> coder -> reviewer 并收敛完成")
    void testStartMultiAgentTask() throws Exception {
        MultiAgentTaskRequest request = MultiAgentTaskRequest.builder()
                .taskObjective("实现电商高并发订单扣减与库存延时双删一致性系统")
                .requirementDetails("采用 Spring Boot 3 与 Redis 方案，需具备高可用和严密审计")
                .build();

        MvcResult result = mockMvc.perform(post("/sample/ai/multi-agent/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.isFinished").value(true))
                .andExpect(jsonPath("$.data.turnCount").value(4))
                .andExpect(jsonPath("$.data.workerResults.researcher").exists())
                .andExpect(jsonPath("$.data.workerResults.coder").exists())
                .andExpect(jsonPath("$.data.workerResults.reviewer").exists())
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String threadId = responseNode.path("data").path("threadId").asText();
        String finalOutput = responseNode.path("data").path("finalOutput").asText();
        assertThat(threadId).isNotBlank();
        assertThat(finalOutput).contains("软件研发协同完成");

        // 验证通过会话 threadId 查询快照端点
        mockMvc.perform(get("/sample/ai/multi-agent/snapshot/" + threadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.threadId").value(threadId))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.isFinished").value(true));
    }

    @Test
    @DisplayName("死循环硬看门狗熔断测试：达到 maxTurns 上限时强制熔断并返回终态")
    void testWatchdogBreaker() throws Exception {
        MultiAgentWatchdogRequest request = MultiAgentWatchdogRequest.builder()
                .taskObjective("故意制造失控死循环压力测试")
                .build();

        MvcResult result = mockMvc.perform(post("/sample/ai/multi-agent/watchdog-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.isFinished").value(true))
                .andExpect(jsonPath("$.data.turnCount").value(4))
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String finalOutput = responseNode.path("data").path("finalOutput").asText();
        assertThat(finalOutput).contains("触发安全熔断终止");
    }

    @Test
    @DisplayName("参数校验防卫：空任务目标时触发 Fail-Fast 参数校验失败")
    void testStartValidationFailure() throws Exception {
        MultiAgentTaskRequest invalidRequest = MultiAgentTaskRequest.builder()
                .taskObjective("")
                .build();

        mockMvc.perform(post("/sample/ai/multi-agent/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
