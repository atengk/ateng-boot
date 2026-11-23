package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.ai.memory.episodic.EpisodicMemoryTemplate;
import io.github.atengk.boot.sample.ai.dto.MemoryExtractRequest;
import io.github.atengk.boot.sample.ai.dto.MemoryFactRememberRequest;
import io.github.atengk.boot.sample.ai.dto.MemoryRecallRequest;
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
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 跨会话原子化长效情景语义记忆控制器端到端集成测试 (100% 离线自洽 MockWebServer 驱动)
 *
 * @author Ateng
 * @since 2026-11-06
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AI 长效情景语义记忆控制器端到端集成测试")
class AiEpisodicMemoryControllerIntegrationTest {

    private static MockWebServer server;

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

                // 2. Chat Completions 事实抽取大模型模拟 (/chat/completions)
                String chatJson = """
                        {
                          "id": "chatcmpl-episodic-test-1",
                          "object": "chat.completion",
                          "created": 1700000000,
                          "model": "gpt-4o",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "[{\\"subject\\":\\"user\\",\\"predicate\\":\\"favorite_food\\",\\"object\\":\\"Hotpot\\",\\"statement\\":\\"用户最喜欢四川火锅\\",\\"confidence\\":0.98}]"
                              },
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 35,
                            "completion_tokens": 25,
                            "total_tokens": 60
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
    static void registerAiProperties(DynamicPropertyRegistry registry) {
        if (!isLocalProfile()) {
            registry.add("ateng.boot.ai.model.base-url", () -> server.url("/v1").toString());
            registry.add("ateng.boot.ai.model.api-key", () -> "mock-test-key-episodic-ci");
            registry.add("ateng.boot.ai.model.model-name", () -> "gpt-4o");
            registry.add("ateng.boot.ai.episodic-memory.enabled", () -> "true");
            registry.add("ateng.boot.ai.episodic-memory.min-score", () -> "0.0");
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
        episodicMemoryTemplate.forgetAll("user-test-01");
        episodicMemoryTemplate.forgetAll("user-test-02");
    }

    @AfterEach
    void tearDown() {
        episodicMemoryTemplate.forgetAll("user-test-01");
        episodicMemoryTemplate.forgetAll("user-test-02");
    }

    @Test
    @DisplayName("情景事实持久化与语义槽位覆写 (Upsert by Semantic Key) 接口测试")
    void testRememberAndSlotOverwriting() throws Exception {
        // 1. 记录第一条偏好事实：暗色主题
        MemoryFactRememberRequest req1 = MemoryFactRememberRequest.builder()
                .userId("user-test-01")
                .subject("user")
                .predicate("theme")
                .object("dark")
                .statement("用户偏好暗色极客主题")
                .confidence(0.95)
                .build();

        mockMvc.perform(post("/sample/ai/episodic-memory/remember")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.slotKey").value("user:theme"))
                .andExpect(jsonPath("$.data.object").value("dark"));

        // 2. 记录同一槽位的更新事实：明亮主题
        MemoryFactRememberRequest req2 = MemoryFactRememberRequest.builder()
                .userId("user-test-01")
                .subject("user")
                .predicate("theme")
                .object("light")
                .statement("用户切换偏好为亮色主题")
                .confidence(0.99)
                .build();

        mockMvc.perform(post("/sample/ai/episodic-memory/remember")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.slotKey").value("user:theme"))
                .andExpect(jsonPath("$.data.object").value("light"));

        // 3. 查询事实清单，强断言仅存在最新的覆写事实，无旧冲突数据
        MvcResult listResult = mockMvc.perform(get("/sample/ai/episodic-memory/facts?userId=user-test-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].object").value("light"))
                .andExpect(jsonPath("$.data[0].statement").value("用户切换偏好为亮色主题"))
                .andReturn();

        String content = listResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(content).contains("亮色主题");
    }

    @Test
    @DisplayName("长效情景记忆物理隔离与语义召回接口测试")
    void testRecallWithPhysicalIsolation() throws Exception {
        // 1. 分别为两个用户注入不同领域的偏好事实
        MemoryFactRememberRequest u1Req = MemoryFactRememberRequest.builder()
                .userId("user-test-01")
                .subject("user")
                .predicate("language")
                .object("Java")
                .statement("用户1的核心研发语言是 Java")
                .build();

        MemoryFactRememberRequest u2Req = MemoryFactRememberRequest.builder()
                .userId("user-test-02")
                .subject("user")
                .predicate("language")
                .object("Rust")
                .statement("用户2的核心研发语言是 Rust")
                .build();

        mockMvc.perform(post("/sample/ai/episodic-memory/remember")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(u1Req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        mockMvc.perform(post("/sample/ai/episodic-memory/remember")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(u2Req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        // 2. 召回用户 1 的记忆
        MemoryRecallRequest recallReq1 = MemoryRecallRequest.builder()
                .userId("user-test-01")
                .query("用户的核心开发语言是什么？")
                .maxResults(5)
                .minScore(0.0)
                .build();

        MvcResult recallResult1 = mockMvc.perform(post("/sample/ai/episodic-memory/recall")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(recallReq1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        String content1 = recallResult1.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(content1).contains("Java");
        assertThat(content1).doesNotContain("Rust");
    }

    @Test
    @DisplayName("多轮对话历史事实抽取并自动入库接口测试")
    void testExtractEndpoint() throws Exception {
        MemoryExtractRequest req = MemoryExtractRequest.builder()
                .userId("user-test-01")
                .conversationText("User: 我最喜欢四川火锅！\nAssistant: 好的，已为您记住！")
                .build();

        MvcResult extractResult = mockMvc.perform(post("/sample/ai/episodic-memory/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].object").value("Hotpot"))
                .andReturn();

        String content = extractResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(content).contains("Hotpot");

        // 验证已落库
        mockMvc.perform(get("/sample/ai/episodic-memory/facts?userId=user-test-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.object == 'Hotpot')]").exists());
    }

    @Test
    @DisplayName("对称遗忘接口测试 (按 factId 与按 slotKey 遗忘)")
    void testForgetEndpoints() throws Exception {
        MemoryFactRememberRequest req = MemoryFactRememberRequest.builder()
                .userId("user-test-01")
                .subject("user")
                .predicate("ide")
                .object("VSCode")
                .statement("用户首选 IDE 为 VSCode")
                .build();

        MvcResult addResult = mockMvc.perform(post("/sample/ai/episodic-memory/remember")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andReturn();

        String factId = objectMapper.readTree(addResult.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").asText();
        assertThat(factId).isNotBlank();

        // 1. 依据 factId 遗忘
        mockMvc.perform(delete("/sample/ai/episodic-memory/facts/" + factId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(get("/sample/ai/episodic-memory/facts?userId=user-test-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // 2. 再次记录并按 slotKey 遗忘
        mockMvc.perform(post("/sample/ai/episodic-memory/remember")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        mockMvc.perform(delete("/sample/ai/episodic-memory/slots?slotKey=user:ide&userId=user-test-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(get("/sample/ai/episodic-memory/facts?userId=user-test-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("参数校验防卫测试 (JSR-303 Fail-Fast 返回 HTTP 200 且业务错误码非 200)")
    void testValidationFailure() throws Exception {
        // subject 为空违反 @NotBlank
        MemoryFactRememberRequest invalidReq = MemoryFactRememberRequest.builder()
                .predicate("test")
                .object("test")
                .build();

        mockMvc.perform(post("/sample/ai/episodic-memory/remember")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("400"));
    }
}
