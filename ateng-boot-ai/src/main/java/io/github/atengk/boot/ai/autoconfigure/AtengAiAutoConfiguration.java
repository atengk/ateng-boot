package io.github.atengk.boot.ai.autoconfigure;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.github.atengk.boot.ai.factory.OpenAiModelFactory;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.template.AiTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Collections;
import java.util.List;

/**
 * AI 智能体与认知图底座核心自动装配入口
 *
 * @author Ateng
 * @since 2026-11-03
 */
@AutoConfiguration
@ConditionalOnClass(OpenAiChatModel.class)
@ConditionalOnProperty(prefix = "ateng.boot.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AtengAiProperties.class)
public class AtengAiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengAiAutoConfiguration.class);

    /**
     * 自动装配默认同步大模型 (仅当配置了默认 apiKey 时初始化，实现平滑探测防御)
     *
     * @param properties        AI 根配置属性
     * @param listenersProvider 链路监听器提供者
     * @return ChatLanguageModel 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.ai.model", name = "api-key")
    public ChatLanguageModel openAiChatModel(AtengAiProperties properties,
                                             ObjectProvider<List<ChatModelListener>> listenersProvider) {
        List<ChatModelListener> listeners = listenersProvider.getIfAvailable(Collections::emptyList);
        return OpenAiModelFactory.createChatModel(properties.getModel(), listeners);
    }

    /**
     * 自动装配默认异步流式大模型 (仅当配置了默认 apiKey 时初始化)
     *
     * @param properties        AI 根配置属性
     * @param listenersProvider 链路监听器提供者
     * @return StreamingChatLanguageModel 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.ai.model", name = "api-key")
    public StreamingChatLanguageModel openAiStreamingChatModel(AtengAiProperties properties,
                                                               ObjectProvider<List<ChatModelListener>> listenersProvider) {
        List<ChatModelListener> listeners = listenersProvider.getIfAvailable(Collections::emptyList);
        return OpenAiModelFactory.createStreamingChatModel(properties.getModel(), listeners);
    }

    /**
     * 自动装配多模型统一命名注册中心
     *
     * @param properties                       AI 根配置属性
     * @param defaultChatModelProvider          默认同步模型提供者
     * @param defaultStreamingChatModelProvider 默认流式模型提供者
     * @param listenersProvider                链路监听器提供者
     * @return AiModelRegistry 注册表实例
     */
    @Bean
    @ConditionalOnMissingBean
    public AiModelRegistry aiModelRegistry(AtengAiProperties properties,
                                           ObjectProvider<ChatLanguageModel> defaultChatModelProvider,
                                           ObjectProvider<StreamingChatLanguageModel> defaultStreamingChatModelProvider,
                                           ObjectProvider<List<ChatModelListener>> listenersProvider) {
        AiModelRegistry registry = new AiModelRegistry();
        List<ChatModelListener> listeners = listenersProvider.getIfAvailable(Collections::emptyList);

        // 1. 注册默认模型 (若已由 Spring 容器装配或独立配置，优先使用唯一/Primary Bean，多 Bean 时平滑退避首个)
        ChatLanguageModel defaultChatModel = defaultChatModelProvider.getIfUnique();
        if (defaultChatModel == null) {
            defaultChatModel = defaultChatModelProvider.orderedStream().findFirst().orElse(null);
        }
        StreamingChatLanguageModel defaultStreamingChatModel = defaultStreamingChatModelProvider.getIfUnique();
        if (defaultStreamingChatModel == null) {
            defaultStreamingChatModel = defaultStreamingChatModelProvider.orderedStream().findFirst().orElse(null);
        }

        if (defaultChatModel != null || defaultStreamingChatModel != null) {
            String defaultAlias = properties.getModel() != null ? properties.getModel().getModelName() : null;
            registry.registerDefault(defaultChatModel, defaultStreamingChatModel, defaultAlias);
        } else if (properties.getModel() != null && properties.getModel().isConfigured()) {
            OpenAiChatModel chatModel = OpenAiModelFactory.createChatModel(properties.getModel(), listeners);
            OpenAiStreamingChatModel streamingChatModel = OpenAiModelFactory.createStreamingChatModel(properties.getModel(), listeners);
            registry.registerDefault(chatModel, streamingChatModel, properties.getModel().getModelName());
        }

        // 2. 注册命名多模型拓扑
        if (properties.getModels() != null && !properties.getModels().isEmpty()) {
            properties.getModels().forEach((name, config) -> {
                if (config != null && config.isConfigured()) {
                    OpenAiChatModel chatModel = OpenAiModelFactory.createChatModel(config, listeners);
                    OpenAiStreamingChatModel streamingChatModel = OpenAiModelFactory.createStreamingChatModel(config, listeners);
                    registry.register(name, chatModel, streamingChatModel);
                } else {
                    log.warn("跳过未配置有效 apiKey 的命名大模型: {}", name);
                }
            });
        }

        if (!registry.hasModels()) {
            log.info("ateng-boot-ai 未检测到任何有效的大模型 API Key 配置，模型注册表处于待命状态。");
        }
        return registry;
    }

    /**
     * 自动装配 AI 核心命令式操作门面
     *
     * @param registry   模型注册中心
     * @param properties AI 根配置属性
     * @return AiTemplate 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public AiTemplate aiTemplate(AiModelRegistry registry, AtengAiProperties properties) {
        return new AiTemplate(registry, properties);
    }
}
