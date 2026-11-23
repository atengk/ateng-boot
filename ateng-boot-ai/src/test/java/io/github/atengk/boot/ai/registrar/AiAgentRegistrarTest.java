package io.github.atengk.boot.ai.registrar;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.UserMessage;
import io.github.atengk.boot.ai.annotation.AiAgent;
import io.github.atengk.boot.ai.annotation.AiAgentScan;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.springframework.context.annotation.Import;

/**
 * 智能体包扫描器 AiAgentRegistrar 单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class AiAgentRegistrarTest {

    @AiAgent
    public interface ScannedAgent {
        String echo(@UserMessage String msg);
    }

    @Configuration
    @AiAgentScan(basePackageClasses = ScannedAgent.class)
    static class ExplicitScanConfig {
        @Bean
        public AiModelRegistry aiModelRegistry() {
            AiModelRegistry registry = new AiModelRegistry();
            ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
            when(mockModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("echo response")));
            registry.registerDefault(mockModel, null, "default");
            return registry;
        }
    }

    @Configuration
    @Import(AiAgentRegistrar.class)
    static class AutoDetectScanConfig {
        @Bean
        public AiModelRegistry aiModelRegistry() {
            AiModelRegistry registry = new AiModelRegistry();
            ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
            when(mockModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("auto echo")));
            registry.registerDefault(mockModel, null, "default");
            return registry;
        }
    }

    @Test
    @DisplayName("通过 @AiAgentScan 显式指定包扫描成功注册智能体代理 Bean")
    void testExplicitScanRegistration() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(ExplicitScanConfig.class);
            context.refresh();

            assertThat(context.containsBean("scannedAgent")).isTrue();
            ScannedAgent agent = context.getBean(ScannedAgent.class);
            assertThat(agent).isNotNull();
            assertThat(agent.echo("hello")).isEqualTo("echo response");
        }
    }

    @Test
    @DisplayName("通过 AutoConfigurationPackages 自动探测主程序所在包并注册智能体代理 Bean")
    void testAutoDetectScanRegistration() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            AutoConfigurationPackages.register(context, ScannedAgent.class.getPackageName());
            context.register(AutoDetectScanConfig.class);
            context.refresh();

            assertThat(context.containsBean("scannedAgent")).isTrue();
            ScannedAgent agent = context.getBean(ScannedAgent.class);
            assertThat(agent).isNotNull();
            assertThat(agent.echo("hello auto")).isEqualTo("auto echo");
        }
    }

    @Configuration
    @AiAgentScan(basePackages = "io.github.atengk.boot.ai.nonexistent")
    @Import(AiAgentRegistrar.class)
    static class OverrideScanConfig {
        @Bean
        public AiModelRegistry aiModelRegistry() {
            AiModelRegistry registry = new AiModelRegistry();
            ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
            registry.registerDefault(mockModel, null, "default");
            return registry;
        }
    }

    @Test
    @DisplayName("显式声明 @AiAgentScan 时成功抑制并覆盖 AutoConfigurationPackages 默认主包探测")
    void testExplicitScanOverridesAutoDetect() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            AutoConfigurationPackages.register(context, ScannedAgent.class.getPackageName());
            context.register(OverrideScanConfig.class);
            context.refresh();

            // 因为显式指定了不存在的子包，默认主包扫描被抑制，因此 ScannedAgent 不应被注册
            assertThat(context.containsBean("scannedAgent")).isFalse();
        }
    }
}
