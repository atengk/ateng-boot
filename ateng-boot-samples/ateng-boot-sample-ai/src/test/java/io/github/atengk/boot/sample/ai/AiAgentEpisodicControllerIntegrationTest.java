package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.ai.memory.episodic.EpisodicMemoryTemplate;
import io.github.atengk.boot.ai.memory.episodic.MemoryFact;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.sample.ai.dto.AgentEpisodicChatRequest;
import io.github.atengk.boot.sample.ai.dto.MemoryFactRememberRequest;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 声明式智能体长效情景记忆控制器端到端集成测试 (100% 离线自洽 MockWebServer 驱动)
 *
 * @author Ateng
 * @since 2026-11-06
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AI 声明式长效情景记忆智能体控制器集成测试")
class AiAgentEpisodicControllerIntegrationTest {

    private static MockWebServer server;
    private static final CopyOnWriteArrayList<String> recordedRequestBodies = new CopyOnWriteArrayList<>();

    private static boolean isLocalProfile() {
        String active = System.getProperty("spring.profiles.active");
        if (active == null) {
            active = System.getenv("SPRING_PROFILES_ACTIVE");
        }
        return active != null && active.contains("local");
    }

    @BeforeAll
    static void startServer() throws IOException {
        if (isLocalProfile()) {
            return;
        }
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() != null ? request.getPath() : "";
                String body = request.getBody().readUtf8();
                recordedRequestBodies.add(body);

                // 1. 向量化 Embedding 接口模拟 (/embeddings)
                if (path.contains("/embeddings")) {
                    String embeddingJson = """
                            {
                              "object": "list",
                              "data": [
                                {
                                  "object": "embedding",
                                  "embedding": [0.1, 0.2, 0.3, 0.4, 0.5],
                                  "index": 0
                                }
                              ],
                              "model": "text-embedding-3-small",
                              "usage": {
                                "prompt_tokens": 5,
                                "total_tokens": 5
                              }
                            }
                            """;
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json; charset=utf-8")
                            .setBody(embeddingJson);
                }

                // 2. Chat Completions 智能体与事实抽取模拟 (/chat/completions)
                // 若包含抽取关键词则返回 JSON 事实，否则返回正常智能体回复
                String replyContent;
                if (body.contains("长效情景记忆事实抽取专家")) {
                    replyContent = "[{\"subject\":\"user\",\"predicate\":\"drink_preference\",\"object\":\"Latte\",\"statement\":\"用户喜欢喝拿铁咖啡\",\"confidence\":0.99}]";
                } else {
                    replyContent = "已为您根据您的专属偏好准备好咖啡方案！";
                }

                String chatJson = """
                        {
                          "id": "chatcmpl-agent-episodic-1",
                          "object": "chat.completion",
                          "created": 1700000000,
                          "model": "gpt-4o",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "%s"
                              },
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 40,
                            "completion_tokens": 20,
                            "total_tokens": 60
                          }
                        }
                        """.formatted(replyContent.replace("\"", "\\\""));

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
    static void registerAiProperties(DynamicPropertyRegistry registry) {
        if (!isLocalProfile()) {
            registry.add("ateng.boot.ai.model.base-url", () -> server.url("/v1").toString());
            registry.add("ateng.boot.ai.model.api-key", () -> "mock-test-key-ci");
            registry.add("ateng.boot.ai.model.model-name", () -> "gpt-4o");
            registry.add("ateng.boot.ai.episodic-memory.enabled", () -> "true");
            registry.add("ateng.boot.ai.episodic-memory.min-score", () -> "0.0");
            registry.add("ateng.boot.ai.embedding.model-name", () -> "text-embedding-3-small");
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EpisodicMemoryTemplate episodicMemoryTemplate;

    @BeforeEach
    void setUp() {
        recordedRequestBodies.clear();
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("智能体对话：自动感知预置用户事实画像，注入系统提示词并执行对话与自动沉淀")
    void testAgentEpisodicChatWithUserAndPromptInjection() throws Exception {
        String testUserId = "user-coffee-fan";

        // 1. 预置用户事实画像
        episodicMemoryTemplate.remember(MemoryFact.builder()
                .subject("user")
                .predicate("coffee_preference")
                .object("OatMilkLatte")
                .statement("用户日常只喝燕麦奶拿铁咖啡，不加糖")
                .userId(testUserId)
                .confidence(0.99)
                .build());

        // 2. 发起智能体对话
        AgentEpisodicChatRequest request = new AgentEpisodicChatRequest();
        request.setUserId(testUserId);
        request.setMessage("请帮我点一杯今天的下午茶饮品");

        mockMvc.perform(post("/sample/ai/agent/episodic/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.userId").value(testUserId))
                .andExpect(jsonPath("$.data.reply").isNotEmpty())
                .andExpect(jsonPath("$.data.recalledFacts").isArray());

        // 3. 校验 MockWebServer 发出的 LLM 请求包含画像提示词
        if (!isLocalProfile()) {
            boolean promptInjected = recordedRequestBodies.stream()
                    .anyMatch(body -> body.contains("用户历史长效画像与情景记忆")
                            || body.contains("燕麦奶拿铁"));
            assertThat(promptInjected)
                    .as("发送给大模型的请求正文必须包含注入的情景记忆画像提示词")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("会话回退感知：未提供 userId 时依据 sessionId 正常执行对话")
    void testAgentEpisodicChatWithSessionFallback() throws Exception {
        AgentEpisodicChatRequest request = new AgentEpisodicChatRequest();
        request.setSessionId("session-agent-999");
        request.setMessage("你好，请问你是谁？");

        mockMvc.perform(post("/sample/ai/agent/episodic/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.userId").value("session-agent-999"))
                .andExpect(jsonPath("$.data.reply").isNotEmpty());
    }

    @Test
    @DisplayName("只读顾问模式：正常感知用户偏好，执行咨询回复")
    void testAgentConsultReadOnlyMode() throws Exception {
        String testUserId = "user-investor-01";

        episodicMemoryTemplate.remember(MemoryFact.builder()
                .subject("user")
                .predicate("risk_tolerance")
                .object("Conservative")
                .statement("用户风险承受能力为保守型，拒绝高波动产品")
                .userId(testUserId)
                .build());

        AgentEpisodicChatRequest request = new AgentEpisodicChatRequest();
        request.setUserId(testUserId);
        request.setMessage("请为我推荐配置策略");

        mockMvc.perform(post("/sample/ai/agent/episodic/consult-readonly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.userId").value(testUserId))
                .andExpect(jsonPath("$.data.reply").isNotEmpty());
    }

    @Test
    @DisplayName("事实清单与生命周期运维：创建事实、查询事实、删除事实")
    void testFactsCrudEndpoints() throws Exception {
        String testUserId = "user-crud-lifecycle";

        // 1. 创建事实
        MemoryFactRememberRequest rememberReq = new MemoryFactRememberRequest();
        rememberReq.setUserId(testUserId);
        rememberReq.setSubject("user");
        rememberReq.setPredicate("native_language");
        rememberReq.setObject("Chinese");
        rememberReq.setStatement("用户的母语是中文普通话");
        rememberReq.setConfidence(1.0);

        MvcResult createResult = mockMvc.perform(post("/sample/ai/agent/episodic/facts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rememberReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.userId").value(testUserId))
                .andExpect(jsonPath("$.data.slotKey").value("user:native_language"))
                .andReturn();

        String responseJson = createResult.getResponse().getContentAsString();
        String factId = objectMapper.readTree(responseJson).path("data").path("id").asText();
        assertThat(factId).isNotBlank();

        // 2. 查询该用户事实清单
        mockMvc.perform(get("/sample/ai/agent/episodic/facts/" + testUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].statement").value("用户的母语是中文普通话"));

        // 3. 删除事实
        mockMvc.perform(delete("/sample/ai/agent/episodic/facts/" + factId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data").value(true));

        // 4. 清空遗忘该用户所有事实
        episodicMemoryTemplate.forgetAll(testUserId);
    }
}
