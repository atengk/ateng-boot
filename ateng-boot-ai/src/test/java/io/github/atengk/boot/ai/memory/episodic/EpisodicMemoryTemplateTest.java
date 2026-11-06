package io.github.atengk.boot.ai.memory.episodic;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.properties.EpisodicMemoryProperties;
import io.github.atengk.boot.ai.rag.filter.DynamicFilterContextHolder;
import io.github.atengk.boot.ai.template.EmbeddingTemplate;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * EpisodicMemoryTemplate 跨会话原子化长效情景语义记忆门面全维度单测
 *
 * @author Ateng
 * @since 2026-11-06
 */
@DisplayName("EpisodicMemoryTemplate 跨会话长效情景记忆门面单测")
class EpisodicMemoryTemplateTest {

    private EmbeddingModel mockEmbeddingModel;
    private ChatLanguageModel mockChatModel;
    private InMemoryEmbeddingStore<TextSegment> store;
    private EmbeddingTemplate embeddingTemplate;
    private MemoryFactExtractor factExtractor;
    private EpisodicMemoryTemplate episodicMemoryTemplate;
    private EpisodicMemoryProperties properties;

    @BeforeEach
    void setUp() {
        mockEmbeddingModel = mock(EmbeddingModel.class);
        mockChatModel = mock(ChatLanguageModel.class);
        store = new InMemoryEmbeddingStore<>();

        // 统一返回固定维度特征向量
        float[] vector = new float[]{0.1f, 0.2f, 0.3f};
        Embedding embedding = new Embedding(vector);
        when(mockEmbeddingModel.embed(any(TextSegment.class))).thenReturn(Response.from(embedding));
        when(mockEmbeddingModel.embed(any(String.class))).thenReturn(Response.from(embedding));

        AtengAiProperties aiProps = new AtengAiProperties();
        embeddingTemplate = new EmbeddingTemplate(mockEmbeddingModel, null, store, aiProps);

        properties = new EpisodicMemoryProperties();
        properties.setMaxRecallResults(5);
        properties.setMinScore(0.5);

        factExtractor = new MemoryFactExtractor(mockChatModel, null, properties, aiProps, null);
        episodicMemoryTemplate = new EpisodicMemoryTemplate(embeddingTemplate, null, store, factExtractor, properties, null);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        DynamicFilterContextHolder.clear();
    }

    @Test
    @DisplayName("语义槽位覆写 (Upsert by Semantic Key)：相同槽位新知识自动覆盖陈旧事实，消除知识冲突")
    void testRememberAndSlotOverwriting() {
        UserContextHolder.setUserId("user-001");
        TenantContextHolder.setTenantId("tenant-001");

        // 1. 记住第一条事实：用户首选语言是中文
        MemoryFact fact1 = MemoryFact.builder()
                .subject("user")
                .predicate("preferred_language")
                .object("zh-CN")
                .statement("用户首选交流语言为简体中文")
                .build();
        String id1 = episodicMemoryTemplate.remember(fact1);
        assertThat(id1).isNotBlank();

        List<MemoryFact> list1 = episodicMemoryTemplate.listFacts("user-001");
        assertThat(list1).hasSize(1);
        assertThat(list1.get(0).getObject()).isEqualTo("zh-CN");

        // 2. 记住第二条事实（同一槽位 user:preferred_language）：更新为英文
        MemoryFact fact2 = MemoryFact.builder()
                .subject("user")
                .predicate("preferred_language")
                .object("en-US")
                .statement("用户更改了偏好，首选语言为美式英语")
                .build();
        String id2 = episodicMemoryTemplate.remember(fact2);
        assertThat(id2).isNotBlank();

        // 3. 验证同槽位旧知识已自动被覆写去重，无冲突旧数据
        List<MemoryFact> list2 = episodicMemoryTemplate.listFacts("user-001");
        assertThat(list2).hasSize(1);
        assertThat(list2.get(0).getObject()).isEqualTo("en-US");
        assertThat(list2.get(0).getStatement()).contains("美式英语");
    }

    @Test
    @DisplayName("多租户与多用户物理隔离：跨租户与跨用户检索互不可见，杜绝越权脏读")
    void testRecallWithMultiTenantAndUserPhysicalIsolation() {
        // 1. 租户 1 用户 A 写入事实
        TenantContextHolder.setTenantId("tenant-alpha");
        UserContextHolder.setUserId("user-alice");
        episodicMemoryTemplate.remember(MemoryFact.builder()
                .subject("alice")
                .predicate("role")
                .object("CTO")
                .statement("Alice 的公司职位是首席技术官")
                .build());

        // 2. 租户 1 用户 B 写入事实
        UserContextHolder.setUserId("user-bob");
        episodicMemoryTemplate.remember(MemoryFact.builder()
                .subject("bob")
                .predicate("role")
                .object("CFO")
                .statement("Bob 的公司职位是首席财务官")
                .build());

        // 3. 租户 2 用户 A (同名) 写入事实
        TenantContextHolder.setTenantId("tenant-beta");
        UserContextHolder.setUserId("user-alice");
        episodicMemoryTemplate.remember(MemoryFact.builder()
                .subject("alice")
                .predicate("role")
                .object("Architect")
                .statement("Beta 租户中 Alice 的职位是首席架构师")
                .build());

        // 4. 验证在 (tenant-alpha, user-alice) 上下文下召回，绝对仅包含 Alice 在 Alpha 租户的事实
        TenantContextHolder.setTenantId("tenant-alpha");
        UserContextHolder.setUserId("user-alice");
        List<MemoryFact> recalledAliceAlpha = episodicMemoryTemplate.recall("Alice 的职位是什么？");
        assertThat(recalledAliceAlpha).hasSize(1);
        assertThat(recalledAliceAlpha.get(0).getObject()).isEqualTo("CTO");
        assertThat(recalledAliceAlpha.get(0).getTenantId()).isEqualTo("tenant-alpha");
        assertThat(recalledAliceAlpha.get(0).getUserId()).isEqualTo("user-alice");

        // 5. 验证在 (tenant-alpha, user-bob) 上下文下召回，绝对仅包含 Bob 的事实
        UserContextHolder.setUserId("user-bob");
        List<MemoryFact> recalledBobAlpha = episodicMemoryTemplate.recall("Bob 的职位是什么？");
        assertThat(recalledBobAlpha).hasSize(1);
        assertThat(recalledBobAlpha.get(0).getObject()).isEqualTo("CFO");

        // 6. 验证在 (tenant-beta, user-alice) 上下文下召回，绝对仅包含 Beta 租户的事实
        TenantContextHolder.setTenantId("tenant-beta");
        UserContextHolder.setUserId("user-alice");
        List<MemoryFact> recalledAliceBeta = episodicMemoryTemplate.recall("Alice 的职位是什么？");
        assertThat(recalledAliceBeta).hasSize(1);
        assertThat(recalledAliceBeta.get(0).getObject()).isEqualTo("Architect");
    }

    @Test
    @DisplayName("对称遗忘与清理测试 (依据 factId, slotKey, 全量 userId)")
    void testForgetByIdAndSlotAndUser() {
        UserContextHolder.setUserId("user-009");
        TenantContextHolder.setTenantId("tenant-001");

        MemoryFact f1 = MemoryFact.builder().subject("user").predicate("city").object("Beijing").build();
        MemoryFact f2 = MemoryFact.builder().subject("user").predicate("hobby").object("Coding").build();
        MemoryFact f3 = MemoryFact.builder().subject("user").predicate("editor").object("IntelliJ").build();

        String id1 = episodicMemoryTemplate.remember(f1);
        episodicMemoryTemplate.remember(f2);
        episodicMemoryTemplate.remember(f3);

        assertThat(episodicMemoryTemplate.listFacts("user-009")).hasSize(3);

        // 1. 依据 factId 遗忘 f1
        episodicMemoryTemplate.forget(id1);
        assertThat(episodicMemoryTemplate.listFacts("user-009")).hasSize(2);

        // 2. 依据 slotKey 遗忘 f2 (user:hobby)
        episodicMemoryTemplate.forgetBySlot("user-009", "user:hobby");
        List<MemoryFact> remaining = episodicMemoryTemplate.listFacts("user-009");
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getPredicate()).isEqualTo("editor");

        // 3. 遗忘该用户全部记忆
        episodicMemoryTemplate.forgetAll("user-009");
        assertThat(episodicMemoryTemplate.listFacts("user-009")).isEmpty();
    }

    @Test
    @DisplayName("从对话文本异步提炼原子事实并自动入库 (extractAndRememberAsync)")
    void testExtractAndRememberAsync() throws Exception {
        UserContextHolder.setUserId("user-extract-01");
        TenantContextHolder.setTenantId("tenant-extract-01");

        String mockLlmJson = """
                [
                  {
                    "subject": "user",
                    "predicate": "favorite_food",
                    "object": "Sichuan Hotpot",
                    "statement": "用户最喜欢的食物是四川火锅",
                    "confidence": 0.98
                  },
                  {
                    "subject": "user",
                    "predicate": "spicy_tolerance",
                    "object": "high",
                    "statement": "用户的吃辣耐受度很高",
                    "confidence": 0.95
                  }
                ]
                """;

        when(mockChatModel.generate(any(UserMessage.class)))
                .thenReturn(Response.from(AiMessage.from(mockLlmJson)));

        String conversation = "User: 我超级喜欢吃四川麻辣火锅，越辣越好！\nAssistant: 收到，已为您记录您的美食喜好！";

        CompletableFuture<List<MemoryFact>> future = episodicMemoryTemplate.extractAndRememberAsync(conversation);
        List<MemoryFact> extractedFacts = future.get();

        assertThat(extractedFacts).hasSize(2);
        assertThat(extractedFacts.get(0).getObject()).isEqualTo("Sichuan Hotpot");
        assertThat(extractedFacts.get(1).getObject()).isEqualTo("high");

        // 验证已自动落库入库
        List<MemoryFact> stored = episodicMemoryTemplate.listFacts("user-extract-01");
        assertThat(stored).hasSize(2);
    }

    @Test
    @DisplayName("对抗性与边界测试：空入参防卫与格式异常平滑兜底")
    void testAdversarialAndEdgeCases() {
        // 1. remember 空入参防卫
        assertThatThrownBy(() -> episodicMemoryTemplate.remember(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> episodicMemoryTemplate.remember(MemoryFact.builder().build()))
                .isInstanceOf(IllegalArgumentException.class);

        // 2. recall 空入参保底返回空列表
        assertThat(episodicMemoryTemplate.recall(null)).isEmpty();
        assertThat(episodicMemoryTemplate.recall("   ")).isEmpty();

        // 3. 抽取结果非法 JSON 格式平滑降级为空列表
        when(mockChatModel.generate(any(UserMessage.class)))
                .thenReturn(Response.from(AiMessage.from("抱歉，我无法识别任何事实")));
        List<MemoryFact> fallbackList = episodicMemoryTemplate.extractAndRemember("无关对话");
        assertThat(fallbackList).isEmpty();
    }

    @Test
    @DisplayName("显式指定 userId 的事实抽取与持久化重载方法测试")
    void testExtractAndRememberWithExplicitUserId() throws Exception {
        String mockLlmJson = """
                [
                  {
                    "subject": "user",
                    "predicate": "framework",
                    "object": "Spring Boot 3",
                    "statement": "用户主要技术栈是 Spring Boot 3",
                    "confidence": 0.99
                  }
                ]
                """;

        when(mockChatModel.generate(any(UserMessage.class)))
                .thenReturn(Response.from(AiMessage.from(mockLlmJson)));

        // 同步带 userId 抽取
        List<MemoryFact> factsSync = episodicMemoryTemplate.extractAndRemember("对话内容", "user-explicit-01");
        assertThat(factsSync).hasSize(1);
        assertThat(factsSync.get(0).getUserId()).isEqualTo("user-explicit-01");

        // 异步带 userId 抽取
        CompletableFuture<List<MemoryFact>> future = episodicMemoryTemplate.extractAndRememberAsync("对话内容", "user-explicit-02");
        List<MemoryFact> factsAsync = future.get();
        assertThat(factsAsync).hasSize(1);
        assertThat(factsAsync.get(0).getUserId()).isEqualTo("user-explicit-02");
    }

    @Test
    @DisplayName("禁用本地缓存时直接从底层向量存储检索与覆写 (验证单例无状态一致性)")
    void testListFactsDirectlyFromStoreWhenLocalCacheDisabled() {
        properties.setEnableLocalCache(false);

        UserContextHolder.setUserId("user-no-cache");
        TenantContextHolder.setTenantId("tenant-no-cache");

        MemoryFact fact = MemoryFact.builder()
                .subject("user")
                .predicate("os")
                .object("Linux")
                .statement("用户偏好 Linux 操作系统")
                .build();
        episodicMemoryTemplate.remember(fact);

        // 验证直接通过向量存储查询出事实
        List<MemoryFact> list = episodicMemoryTemplate.listFacts("user-no-cache");
        assertThat(list).isNotEmpty();
        assertThat(list.get(0).getObject()).isEqualTo("Linux");
    }
}
