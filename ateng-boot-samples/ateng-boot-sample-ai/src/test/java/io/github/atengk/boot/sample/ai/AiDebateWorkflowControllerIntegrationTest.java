package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.ai.dto.ArchitectureDebateStartRequest;
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
 * 对抗式多智能体辩论与共识裁决 (DebateConsensusWorkflow) 控制器端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-06
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("对抗式多智能体辩论与共识裁决控制器集成测试 (100% 离线自洽驱动)")
class AiDebateWorkflowControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("端到端验证正常对抗式架构评审：第 2 轮方案补强后达成共识并获得 APPROVED 裁决")
    void testNormalAdversarialReviewReachesConsensus() throws Exception {
        ArchitectureDebateStartRequest request = ArchitectureDebateStartRequest.builder()
                .proposalTitle("电商高并发库存延时双删架构设计")
                .proposalDesc("采用多级缓存与延时双删保障高并发下 DB 与缓存最终一致性")
                .targetQps(50000)
                .forceDeadlock(false)
                .build();

        MvcResult result = mockMvc.perform(post("/sample/ai/workflow/debate/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.state.consensusReached").value(true))
                .andExpect(jsonPath("$.data.state.watchdogTripped").value(false))
                .andExpect(jsonPath("$.data.state.finalVerdict").value("APPROVED"))
                .andExpect(jsonPath("$.data.state.roundCount").value(2))
                .andExpect(jsonPath("$.data.state.criticApproved").value(true))
                .andReturn();

        JsonNode jsonNode = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String threadId = jsonNode.path("data").path("threadId").asText();
        assertThat(threadId).isNotBlank();

        // 验证快照探查接口
        mockMvc.perform(get("/sample/ai/workflow/debate/snapshot/{threadId}", threadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.threadId").value(threadId))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.state.consensusReached").value(true));
    }

    @Test
    @DisplayName("端到端验证死锁对抗辩论触发 maxDebateTurns=3 看门狗熔断并强制仲裁")
    void testDeadlockDebateTripsWatchdogFallback() throws Exception {
        ArchitectureDebateStartRequest request = ArchitectureDebateStartRequest.builder()
                .proposalTitle("拒绝妥协的极端单机架构")
                .proposalDesc("坚持单机轻量不作集群冗余")
                .targetQps(100000)
                .forceDeadlock(true)
                .build();

        MvcResult result = mockMvc.perform(post("/sample/ai/workflow/debate/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.state.consensusReached").value(false))
                .andExpect(jsonPath("$.data.state.watchdogTripped").value(true))
                .andExpect(jsonPath("$.data.state.finalVerdict").value("FORCED_ARBITRATION"))
                .andExpect(jsonPath("$.data.state.roundCount").value(3))
                .andReturn();

        JsonNode jsonNode = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String summary = jsonNode.path("data").path("state").path("consensusSummary").asText();
        assertThat(summary).contains("达到最大轮次上限 (3 轮)");
    }
}
