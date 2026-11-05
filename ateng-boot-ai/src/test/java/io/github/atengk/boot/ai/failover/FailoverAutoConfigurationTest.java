package io.github.atengk.boot.ai.failover;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.atengk.boot.ai.autoconfigure.AtengAiAutoConfiguration;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 故障自动转移配置与自动装配集成测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
class FailoverAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengAiAutoConfiguration.class));

    @Test
    @DisplayName("配置 models.<name>.fallbacks 时，自动装配并替换为 FailoverChatLanguageModel 与 FailoverStreamingChatLanguageModel")
    void testFailoverAutoConfigurationApplied() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.ai.enabled=true",
                        "ateng.boot.ai.model.api-key=test-primary-key",
                        "ateng.boot.ai.models.model-b.api-key=test-key-b",
                        "ateng.boot.ai.models.model-b.base-url=https://api.model-b.com/v1",
                        "ateng.boot.ai.models.model-c.api-key=test-key-c",
                        "ateng.boot.ai.models.model-c.base-url=https://api.model-c.com/v1",
                        "ateng.boot.ai.models.model-a.api-key=test-key-a",
                        "ateng.boot.ai.models.model-a.base-url=https://api.model-a.com/v1",
                        "ateng.boot.ai.models.model-a.fallbacks[0]=model-b",
                        "ateng.boot.ai.models.model-a.fallbacks[1]=model-c",
                        "ateng.boot.ai.models.model-a.total-timeout=30s"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(AiModelRegistry.class);
                    AiModelRegistry registry = context.getBean(AiModelRegistry.class);

                    // 验证 model-a 已被包装为 Failover 代理
                    ChatLanguageModel chatModelA = registry.getChatModel("model-a");
                    assertThat(chatModelA).isInstanceOf(FailoverChatLanguageModel.class);
                    FailoverChatLanguageModel failoverChatModel = (FailoverChatLanguageModel) chatModelA;
                    assertThat(failoverChatModel.getCandidateModels()).hasSize(3);
                    assertThat(failoverChatModel.getPrimaryModel().name()).isEqualTo("model-a");
                    assertThat(failoverChatModel.getFallbackModels()).extracting(NamedModel::name)
                            .containsExactly("model-b", "model-c");
                    assertThat(failoverChatModel.getTotalTimeout()).hasSeconds(30);

                    // 验证流式模型同样被包装为 FailoverStreamingChatLanguageModel
                    StreamingChatLanguageModel streamingModelA = registry.getStreamingChatModel("model-a");
                    assertThat(streamingModelA).isInstanceOf(FailoverStreamingChatLanguageModel.class);
                    FailoverStreamingChatLanguageModel failoverStreamingModel = (FailoverStreamingChatLanguageModel) streamingModelA;
                    assertThat(failoverStreamingModel.getCandidateModels()).hasSize(3);
                    assertThat(failoverStreamingModel.getPrimaryModel().name()).isEqualTo("model-a");
                    assertThat(failoverStreamingModel.getFallbackModels()).extracting(NamedModel::name)
                            .containsExactly("model-b", "model-c");

                    // 验证未配置 fallbacks 的 model-b 保持原生包装，不包装 Failover
                    ChatLanguageModel chatModelB = registry.getChatModel("model-b");
                    assertThat(chatModelB).isNotInstanceOf(FailoverChatLanguageModel.class);
                });
    }

    @Test
    @DisplayName("主默认模型 model.fallbacks 配置时，自动装配并替换默认模型为 Failover 代理")
    void testDefaultModelFailoverAutoConfiguration() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.ai.enabled=true",
                        "ateng.boot.ai.model.api-key=test-default-key",
                        "ateng.boot.ai.models.backup-model.api-key=test-backup-key",
                        "ateng.boot.ai.models.backup-model.base-url=https://api.backup.com/v1",
                        "ateng.boot.ai.model.fallbacks[0]=backup-model",
                        "ateng.boot.ai.model.total-timeout=20s"
                )
                .run(context -> {
                    AiModelRegistry registry = context.getBean(AiModelRegistry.class);
                    ChatLanguageModel defaultChatModel = registry.getChatModel();
                    assertThat(defaultChatModel).isInstanceOf(FailoverChatLanguageModel.class);
                    FailoverChatLanguageModel failoverChat = (FailoverChatLanguageModel) defaultChatModel;
                    assertThat(failoverChat.getCandidateModels()).hasSize(2);
                    assertThat(failoverChat.getPrimaryModel().name()).isEqualTo(AiModelRegistry.DEFAULT_MODEL_NAME);
                    assertThat(failoverChat.getFallbackModels().get(0).name()).isEqualTo("backup-model");
                });
    }
}
