package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.ai.prompt.PromptRegistry;
import io.github.atengk.boot.sample.ai.dto.AiPromptAgentChatRequest;
import io.github.atengk.boot.sample.ai.dto.AiPromptRegisterRequest;
import io.github.atengk.boot.sample.ai.dto.AiPromptRenderRequest;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 动态提示词资产托管与版本路由控制器端到端集成测试 (100% 离线自洽 MockWebServer 驱动)
 *
 * @author Ateng
 * @since 2026-11-22
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DisplayName("AI 动态提示词资产托管控制器端到端集成测试")
class PromptControllerIntegrationTest {

    private static MockWebServer server;
    private static final List<String> CAPTURED_BODIES = new CopyOnWriteArrayList<>();

    private static boolean isLocalProfile() {
        String active = System.getProperty("spring.profiles.active");
        if (active == null) {
            active = System.getenv("SPRING_PROFILES_ACTIVE");
        }
        return active != null && active.contains("local");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PromptRegistry promptRegistry;

    @BeforeAll
    static void startServer() throws IOException {
        CAPTURED_BODIES.clear();
        if (isLocalProfile()) {
            return;
        }
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().readUtf8();
                CAPTURED_BODIES.add(body);
                String chatJson = """
                        {
                          "id": "chatcmpl-prompt-test",
                          "object": "chat.completion",
                          "created": 1700000000,
                          "model": "gpt-4o",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "已按动态托管提示词要求完成服务解答。"
                              },
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 18,
                            "completion_tokens": 22,
                            "total_tokens": 40
                          }
                        }
                        """;
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json; charset=utf-8")
                        .setBody(chatJson);
            }
        });
        server.start();
    }

    @AfterAll
    static void stopServer() throws IOException {
        if (server != null) {
            server.shutdown();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (server != null) {
            String baseUrl = server.url("/v1").toString();
            registry.add("ateng.boot.ai.model.base-url", () -> baseUrl);
            registry.add("ateng.boot.ai.model.api-key", () -> "sk-mock-prompt-ci-key");
        }
    }

    @AfterEach
    void restorePromptRegistry() {
        CAPTURED_BODIES.clear();
        if (promptRegistry != null) {
            promptRegistry.refreshAll();
        }
    }

    @Test
    @DisplayName("提示词清单查询、多版本/标签渲染、strict Fail-Fast、chatByPromptCode 与热刷新闭环")
    void shouldListRenderChatRegisterAndRefreshPrompts() throws Exception {
        // 1. 清单包含 YAML 与 Markdown 自动加载资产
        mockMvc.perform(get("/sample/ai/prompt/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(4)));

        // 2. 详情查询默认 prod 与指定 canary 标签
        mockMvc.perform(get("/sample/ai/prompt/detail").param("code", "customer-support"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.version").value("1.0.0"))
                .andExpect(jsonPath("$.data.label").value("prod"));

        mockMvc.perform(get("/sample/ai/prompt/detail")
                        .param("code", "customer-support")
                        .param("versionOrLabel", "canary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.version").value("2.0.0"));

        // 3. 严格渲染成功与缺失必填变量抛出 AI_INVALID_PAYLOAD (61006)
        AiPromptRenderRequest validRender = new AiPromptRenderRequest(
                "customer-support", "canary", null,
                Map.of("userName", "Ateng", "topic", "订单物流", "urgency", "高"), true);
        mockMvc.perform(post("/sample/ai/prompt/render")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRender)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.renderedContent", containsString("灰度增强版")))
                .andExpect(jsonPath("$.data.renderedContent", containsString("Ateng")));

        AiPromptRenderRequest missingVarRender = new AiPromptRenderRequest(
                "customer-support", "prod", null,
                Map.of("userName", "Ateng"), true);
        mockMvc.perform(post("/sample/ai/prompt/render")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(missingVarRender)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("61006"));

        // 4. 基于 promptCode 发起大模型对话
        AiPromptRenderRequest chatReq = new AiPromptRenderRequest(
                "customer-support", "prod", null,
                Map.of("userName", "Ateng", "topic", "企业授权升级"), true);
        mockMvc.perform(post("/sample/ai/prompt/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chatReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.reply").isNotEmpty());

        // 5. 运行期动态注册新版本并验证 @AiAgent(promptCode="advisor-system") 即时生效
        mockMvc.perform(post("/sample/ai/prompt/agent-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AiPromptAgentChatRequest("如何划分微服务边界？"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.reply").isNotEmpty());
        if (!isLocalProfile()) {
            assertThat(CAPTURED_BODIES.get(CAPTURED_BODIES.size() - 1)).contains("生产稳态版 V1");
        }

        AiPromptRegisterRequest hotUpgrade = new AiPromptRegisterRequest(
                "advisor-system", "2.0.0", "prod",
                "你是实时热更新架构导师（动态热生效 V2）",
                List.of(), "运行期热升级系统提示词");
        mockMvc.perform(post("/sample/ai/prompt/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(hotUpgrade)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.version").value("2.0.0"));

        mockMvc.perform(post("/sample/ai/prompt/agent-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AiPromptAgentChatRequest("再讲讲高可用"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        if (!isLocalProfile()) {
            assertThat(CAPTURED_BODIES.get(CAPTURED_BODIES.size() - 1)).contains("动态热生效 V2");
        }

        // 6. 触发热刷新接口还原外部 YAML 资产
        mockMvc.perform(post("/sample/ai/prompt/refresh").param("code", "advisor-system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
    }
}
