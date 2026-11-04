package io.github.atengk.boot.ai;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.atengk.boot.ai.autoconfigure.AtengAiAutoConfiguration;
import io.github.atengk.boot.ai.observability.AtengAiObservabilityListener;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.template.AiTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 自动装配平滑探测与守卫单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class AtengAiAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengAiAutoConfiguration.class));

    @Test
    @DisplayName("缺配置平滑探测：未配置 API Key 时应用正常启动且不初始化大模型 Bean")
    void testSmoothDetectionWithoutApiKey() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AiModelRegistry.class);
            assertThat(context).hasSingleBean(AiTemplate.class);
            assertThat(context).doesNotHaveBean(ChatLanguageModel.class);
            assertThat(context).doesNotHaveBean(StreamingChatLanguageModel.class);

            AiModelRegistry registry = context.getBean(AiModelRegistry.class);
            assertThat(registry.hasModels()).isFalse();
        });
    }

    @Test
    @DisplayName("开关禁用测试：ateng.boot.ai.enabled=false 时不装配任何 AI Bean")
    void testDisabled() {
        contextRunner.withPropertyValues("ateng.boot.ai.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AiModelRegistry.class);
                    assertThat(context).doesNotHaveBean(AiTemplate.class);
                    assertThat(context).doesNotHaveBean(ChatLanguageModel.class);
                });
    }

    @Test
    @DisplayName("配置默认模型时正常初始化 ChatLanguageModel 与 StreamingChatLanguageModel")
    void testDefaultModelConfigured() {
        contextRunner.withPropertyValues(
                "ateng.boot.ai.model.base-url=https://api.openai.com/v1",
                "ateng.boot.ai.model.api-key=test-api-key",
                "ateng.boot.ai.model.model-name=gpt-4o"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AiModelRegistry.class);
            assertThat(context).hasSingleBean(AiTemplate.class);
            assertThat(context).hasSingleBean(ChatLanguageModel.class);
            assertThat(context).hasSingleBean(StreamingChatLanguageModel.class);

            AiModelRegistry registry = context.getBean(AiModelRegistry.class);
            assertThat(registry.hasModels()).isTrue();
            assertThat(registry.hasChatModel(AiModelRegistry.DEFAULT_MODEL_NAME)).isTrue();
            assertThat(registry.hasChatModel("gpt-4o")).isTrue();
        });
    }

    @Test
    @DisplayName("配置多命名模型时注册表正确纳入命名模型")
    void testNamedModelsConfigured() {
        contextRunner.withPropertyValues(
                "ateng.boot.ai.model.api-key=test-default-key",
                "ateng.boot.ai.models.deepseek.base-url=https://api.deepseek.com/v1",
                "ateng.boot.ai.models.deepseek.api-key=test-deepseek-key",
                "ateng.boot.ai.models.deepseek.model-name=deepseek-chat"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            AiModelRegistry registry = context.getBean(AiModelRegistry.class);
            assertThat(registry.hasChatModel(AiModelRegistry.DEFAULT_MODEL_NAME)).isTrue();
            assertThat(registry.hasChatModel("deepseek")).isTrue();
        });
    }

    @Test
    @DisplayName("用户自定义 ChatLanguageModel Bean 时触发 @ConditionalOnMissingBean 守卫接管")
    void testCustomBeanOverride() {
        contextRunner.withUserConfiguration(CustomModelConfig.class)
                .withPropertyValues("ateng.boot.ai.model.api-key=test-api-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ChatLanguageModel.class);
                    ChatLanguageModel chatModel = context.getBean(ChatLanguageModel.class);
                    assertThat(chatModel).isSameAs(CustomModelConfig.MOCK_MODEL);
                });
    }

    @Test
    @DisplayName("用户自定义 AiTemplate Bean 时触发 @ConditionalOnMissingBean 守卫接管")
    void testCustomAiTemplateOverride() {
        contextRunner.withUserConfiguration(CustomAiTemplateConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AiTemplate.class);
                    AiTemplate template = context.getBean(AiTemplate.class);
                    assertThat(template).isSameAs(CustomAiTemplateConfig.MOCK_TEMPLATE);
                });
    }

    @Test
    @DisplayName("防御性测试：当容器中存在多个自定义 ChatLanguageModel Bean 且未设 @Primary 时，平滑获取首个可用 Bean 而不崩溃")
    void testMultipleCustomChatModelsWithoutPrimaryDoesNotCrash() {
        contextRunner.withUserConfiguration(MultipleModelsConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AiModelRegistry.class);
                    AiModelRegistry registry = context.getBean(AiModelRegistry.class);
                    assertThat(registry.hasChatModel(AiModelRegistry.DEFAULT_MODEL_NAME)).isTrue();
                });
    }

    @Test
    @DisplayName("可观测审计监听器默认自动装配测试")
    void testObservabilityListenerAutoConfigured() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AtengAiObservabilityListener.class);
        });
    }

    @Test
    @DisplayName("审计开关禁用测试：ateng.boot.ai.audit.enabled=false 时不装配 AtengAiObservabilityListener")
    void testObservabilityListenerDisabled() {
        contextRunner.withPropertyValues("ateng.boot.ai.audit.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AtengAiObservabilityListener.class);
                });
    }

    @Test
    @DisplayName("用户自定义 AtengAiObservabilityListener 时触发 @ConditionalOnMissingBean 接管")
    void testCustomObservabilityListenerOverride() {
        contextRunner.withUserConfiguration(CustomListenerConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AtengAiObservabilityListener.class);
                    AtengAiObservabilityListener listener = context.getBean(AtengAiObservabilityListener.class);
                    assertThat(listener).isSameAs(CustomListenerConfig.MOCK_LISTENER);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomListenerConfig {
        static final AtengAiObservabilityListener MOCK_LISTENER = Mockito.mock(AtengAiObservabilityListener.class);

        @Bean
        public AtengAiObservabilityListener atengAiObservabilityListener() {
            return MOCK_LISTENER;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MultipleModelsConfig {
        @Bean
        public ChatLanguageModel model1() {
            return Mockito.mock(ChatLanguageModel.class);
        }

        @Bean
        public ChatLanguageModel model2() {
            return Mockito.mock(ChatLanguageModel.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomModelConfig {
        static final ChatLanguageModel MOCK_MODEL = Mockito.mock(ChatLanguageModel.class);

        @Bean
        public ChatLanguageModel openAiChatModel() {
            return MOCK_MODEL;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomAiTemplateConfig {
        static final AiTemplate MOCK_TEMPLATE = Mockito.mock(AiTemplate.class);

        @Bean
        public AiTemplate aiTemplate() {
            return MOCK_TEMPLATE;
        }
    }
}
