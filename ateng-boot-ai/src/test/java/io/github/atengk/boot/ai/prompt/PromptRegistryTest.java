package io.github.atengk.boot.ai.prompt;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.annotation.AiAgent;
import io.github.atengk.boot.ai.context.AiContextHolder;
import io.github.atengk.boot.ai.context.EpisodicMemoryContext;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.properties.AtengAiPromptProperties;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.registrar.AiAgentFactoryBean;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.template.AiTemplate;
import io.github.atengk.boot.core.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * 动态提示词资产托管注册表与版本路由单元测试
 *
 * @author Ateng
 * @since 2026-11-22
 */
@DisplayName("PromptRegistry 动态提示词托管单元测试")
class PromptRegistryTest {

    @AiAgent(promptCode = "customer-advisor")
    interface DynamicPromptAgent {
        String reply(String question);
    }

    @AiAgent(promptCode = "customer-advisor", episodicMemory = true, extractMemory = false)
    interface DynamicPromptWithMemoryAgent {
        String reply(String question);
    }

    @AfterEach
    void tearDown() {
        AiContextHolder.clearAll();
        TenantContextHolder.clear();
        EpisodicMemoryContext.clear();
    }

    @Test
    @DisplayName("多版本与多标签（prod/canary）精准路由、严格变量校验与列出快照")
    void shouldRouteByVersionAndLabelAndEnforceStrictRender() {
        PromptRegistry registry = new PromptRegistry();

        PromptDefinition v1 = PromptDefinition.builder()
                .code("order-reply")
                .version("1.0.0")
                .label("prod")
                .requiredVariables("orderId")
                .content("【v1生产版】订单 {{orderId}} 状态：{{status}}")
                .description("生产稳态版本")
                .build();
        PromptDefinition v2 = PromptDefinition.builder()
                .code("order-reply")
                .version("2.0.0")
                .label("canary")
                .requiredVariables("orderId", "eta")
                .content("【v2灰度版】订单 {{orderId}} 预计到达 {{eta}}")
                .description("金丝雀实验版本")
                .build();

        registry.register(v1);
        registry.register(v2);

        // 1. 缺省 get(code) 优先命中 label=prod 版本
        assertThat(registry.get("order-reply").getVersion()).isEqualTo("1.0.0");
        assertThat(registry.get("order-reply", "canary").getVersion()).isEqualTo("2.0.0");
        assertThat(registry.get("order-reply", "1.0.0").getLabel()).isEqualTo("prod");
        assertThat(registry.listDefinitions()).hasSize(2);

        // 2. 宽松 render vs 严格 renderStrict
        String lenient = registry.render("order-reply", Map.of("orderId", "ORD-01"));
        assertThat(lenient).isEqualTo("【v1生产版】订单 ORD-01 状态：{{status}}");

        assertThatThrownBy(() -> registry.renderStrict("order-reply", Map.of("orderId", "ORD-01")))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_INVALID_PAYLOAD));

        assertThat(registry.renderStrict("order-reply", "canary", Map.of("orderId", "ORD-01", "eta", "明天")))
                .isEqualTo("【v2灰度版】订单 ORD-01 预计到达 明天");

        // 3. 发布新 prod 版本时，prod 标签原子迁移至新版本
        PromptDefinition v3Prod = PromptDefinition.builder()
                .code("order-reply")
                .version("3.0.0")
                .label("prod")
                .content("【v3生产版】订单 {{orderId}} 已确认")
                .build();
        registry.register(v3Prod);
        assertThat(registry.get("order-reply").getVersion()).isEqualTo("3.0.0");
    }

    @Test
    @DisplayName("DefaultPromptProvider 支持 YAML/Markdown（含 Frontmatter）加载、运行期热刷新与路径穿越防御")
    void shouldLoadFromFilesRefreshHotAndBlockPathTraversal(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path yamlFile = tempDir.resolve("catalog.yml");
        String yamlV1 = """
                prompts:
                  - code: billing-summary
                    version: "1.0"
                    label: prod
                    description: 账单汇总 v1
                    requiredVariables:
                      - month
                    content: "请生成 {{month}} 月度账单总结"
                """;
        Files.writeString(yamlFile, yamlV1, StandardCharsets.UTF_8);

        Path mdFile = tempDir.resolve("markdown-guide.md");
        String mdContent = """
                ---
                code: markdown-guide
                version: "1.0.0"
                label: canary
                description: 带前置元数据的 Markdown 提示词
                ---
                请使用结构化格式分析主题：{{topic}}
                """;
        Files.writeString(mdFile, mdContent, StandardCharsets.UTF_8);

        AtengAiPromptProperties promptProps = new AtengAiPromptProperties();
        promptProps.setLocations(List.of(tempDir.toAbsolutePath().toString()));
        DefaultPromptProvider provider = new DefaultPromptProvider(promptProps);
        PromptRegistry registry = new PromptRegistry(promptProps, List.of(provider), true);

        assertThat(registry.get("billing-summary").getContent()).contains("月度账单总结");
        assertThat(registry.get("markdown-guide", "canary").getContent()).contains("分析主题：{{topic}}");

        // 外部文件更新后调用 refresh(code) 即刻热生效
        String yamlV2 = """
                prompts:
                  - code: billing-summary
                    version: "2.0"
                    label: prod
                    content: "【新版】请输出 {{month}} 账单明细与节约建议"
                """;
        Files.writeString(yamlFile, yamlV2, StandardCharsets.UTF_8);
        registry.refresh("billing-summary");
        assertThat(registry.get("billing-summary").getContent()).contains("【新版】");

        // cacheTtl > 0 时运行期外部新增编码可被懒加载拉取发现
        AtengAiPromptProperties ttlProps = new AtengAiPromptProperties();
        ttlProps.setLocations(List.of(tempDir.toAbsolutePath().toString()));
        ttlProps.setCacheTtl(java.time.Duration.ofMillis(1));
        PromptRegistry ttlRegistry = new PromptRegistry(ttlProps, List.of(new DefaultPromptProvider(ttlProps)), true);
        assertThat(ttlRegistry.find("late-added")).isEmpty();
        Files.writeString(tempDir.resolve("late-added.md"), "新增懒加载模板 {{param}}", StandardCharsets.UTF_8);
        Thread.sleep(10L);
        assertThat(ttlRegistry.get("late-added").getContent()).isEqualTo("新增懒加载模板 {{param}}");

        // 路径穿越攻击 Fail-Fast 拦截
        AtengAiPromptProperties evilProps = new AtengAiPromptProperties();
        evilProps.setLocations(List.of("classpath:/prompts/../../etc/passwd"));
        DefaultPromptProvider evilProvider = new DefaultPromptProvider(evilProps);
        assertThatThrownBy(evilProvider::loadAll)
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_CONFIG_ERROR));
    }

    @Test
    @DisplayName("AiTemplate.chatByPromptCode 与 @AiAgent(promptCode) 每次调用实时应用最新托管系统提示词")
    @SuppressWarnings("unchecked")
    void shouldApplyLatestPromptInAiTemplateAndDeclarativeAiAgent() {
        PromptRegistry registry = new PromptRegistry();
        registry.register(PromptDefinition.of("greeting-tpl", "1.0", "prod", "你好 {{user}}，请回答 {{question}}"));
        registry.register(PromptDefinition.of("customer-advisor", "1.0", "prod", "你是一位严谨的金牌售前顾问（V1）"));

        ChatLanguageModel chatModel = mock(ChatLanguageModel.class);
        when(chatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("智能体已按最新系统设定应答")));
        StreamingChatLanguageModel streamingModel = mock(StreamingChatLanguageModel.class);
        doAnswer(inv -> {
            StreamingResponseHandler<AiMessage> handler = inv.getArgument(1);
            handler.onNext("流式");
            handler.onNext("OK");
            handler.onComplete(Response.from(AiMessage.from("流式OK")));
            return null;
        }).when(streamingModel).generate(anyList(), any());
        AiModelRegistry modelRegistry = new AiModelRegistry();
        modelRegistry.registerDefault(chatModel, streamingModel, "gpt-4o");

        AtengAiProperties aiProps = new AtengAiProperties();
        AiTemplate aiTemplate = new AiTemplate(modelRegistry, aiProps, null, null, null, null, null, null, registry);

        // 1. AiTemplate.chatByPromptCode 与 streamByPromptCode 正常渲染与缺失变量 strict 阻断
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                aiTemplate.streamByPromptCode("greeting-tpl", Map.of("user", "Ateng", "question", "SSE 流式"));
        assertThat(emitter).isNotNull();
        verify(streamingModel, times(1)).generate(anyList(), any());

        org.springframework.web.servlet.mvc.method.annotation.SseEmitter failEmitter =
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter();
        aiTemplate.streamByPromptCode("greeting-tpl", Map.of("user", "Ateng"), failEmitter);
        verify(streamingModel, times(1)).generate(anyList(), any());
        String reply = aiTemplate.chatByPromptCode("greeting-tpl", Map.of("user", "Ateng", "question", "Java 21 特性"));
        assertThat(reply).isEqualTo("智能体已按最新系统设定应答");

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel, times(1)).generate(captor.capture());
        assertThat(captor.getValue().get(0).toString()).contains("你好 Ateng，请回答 Java 21 特性");

        assertThatThrownBy(() -> aiTemplate.chatByPromptCode("greeting-tpl", Map.of("user", "Ateng")))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_INVALID_PAYLOAD));

        // 2. @AiAgent(promptCode = "customer-advisor") 动态拉取 PromptRegistry 最新提示词
        ApplicationContext appCtx = mock(ApplicationContext.class);
        when(appCtx.getBean(AiModelRegistry.class)).thenReturn(modelRegistry);
        ObjectProvider<PromptRegistry> promptProvider = mock(ObjectProvider.class);
        when(promptProvider.getIfAvailable()).thenReturn(registry);
        when(appCtx.getBeanProvider(PromptRegistry.class)).thenReturn(promptProvider);

        AiAgentFactoryBean<DynamicPromptAgent> factoryBean = new AiAgentFactoryBean<>();
        factoryBean.setAgentInterface(DynamicPromptAgent.class);
        factoryBean.setPromptCode("customer-advisor");
        factoryBean.setApplicationContext(appCtx);
        factoryBean.afterPropertiesSet();

        DynamicPromptAgent agent = factoryBean.getObject();
        assertThat(agent).isNotNull();

        agent.reply("产品价格是多少？");
        verify(chatModel, times(2)).generate(captor.capture());
        List<ChatMessage> firstAgentCall = captor.getValue();
        assertThat(firstAgentCall).anyMatch(msg -> msg instanceof SystemMessage sm && sm.text().contains("金牌售前顾问（V1）"));

        // 热更新 PromptRegistry 后无需重建 Agent 代理即可生效 V2 系统提示词
        AtomicReference<String> externalVersion = new AtomicReference<>("你是极速响应技术架构师（V2热更新）");
        PromptRegistry hotRegistry = new PromptRegistry(new AtengAiPromptProperties(), List.of(() -> List.of(
                PromptDefinition.of("customer-advisor", "2.0", "prod", externalVersion.get())
        )), true);
        when(promptProvider.getIfAvailable()).thenReturn(hotRegistry);

        agent.reply("如何保证高并发？");
        verify(chatModel, times(3)).generate(captor.capture());
        List<ChatMessage> secondAgentCall = captor.getValue();
        assertThat(secondAgentCall).anyMatch(msg -> msg instanceof SystemMessage sm && sm.text().contains("极速响应技术架构师（V2热更新）"));

        // 3. 验证与 EpisodicMemoryContext 画像拼接兼容
        AiAgentFactoryBean<DynamicPromptWithMemoryAgent> memFactory = new AiAgentFactoryBean<>();
        memFactory.setAgentInterface(DynamicPromptWithMemoryAgent.class);
        memFactory.setPromptCode("customer-advisor");
        memFactory.setEpisodicMemory(true);
        memFactory.setExtractMemory(false);
        memFactory.setApplicationContext(appCtx);
        memFactory.afterPropertiesSet();

        EpisodicMemoryContext.setInjectedPrompt("[用户画像]: 偏好架构设计方案");
        memFactory.getObject().reply("给我推荐缓存架构");
        verify(chatModel, times(4)).generate(captor.capture());
        List<ChatMessage> memoryCall = captor.getValue();
        assertThat(memoryCall).anyMatch(msg -> msg instanceof SystemMessage sm
                && sm.text().contains("[用户画像]: 偏好架构设计方案")
                && sm.text().contains("极速响应技术架构师（V2热更新）"));
    }
}
