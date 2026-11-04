package io.github.atengk.boot.ai.autoconfigure;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.factory.OpenAiModelFactory;
import io.github.atengk.boot.ai.memory.ChatMemoryManager;
import io.github.atengk.boot.ai.memory.store.ChatMemoryStore;
import io.github.atengk.boot.ai.memory.store.InMemoryChatMemoryStore;
import io.github.atengk.boot.ai.memory.store.RedisChatMemoryStore;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.properties.ChatMemoryProperties;
import io.github.atengk.boot.ai.properties.ChatMemoryStoreType;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.template.AiTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import io.github.atengk.boot.ai.registrar.AiAgentRegistrar;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import io.github.atengk.boot.ai.observability.AiAuditLogHandler;
import io.github.atengk.boot.ai.observability.AtengAiObservabilityListener;
import io.github.atengk.boot.ai.quota.AiQuotaChecker;
import io.github.atengk.boot.ai.quota.DefaultAiQuotaChecker;
import io.github.atengk.boot.ai.quota.RedisAiQuotaChecker;
import io.github.atengk.boot.ai.tenant.AiTenantModelResolver;
import io.github.atengk.boot.ai.tenant.DefaultAiTenantModelResolver;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;
import java.util.List;

/**
 * AI 智能体与认知图底座核心自动装配入口
 *
 * @author Ateng
 * @since 2026-11-03
 */
@AutoConfiguration
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")
@ConditionalOnClass(OpenAiChatModel.class)
@ConditionalOnProperty(prefix = "ateng.boot.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AtengAiProperties.class)
@Import(AiAgentRegistrar.class)
public class AtengAiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengAiAutoConfiguration.class);

    /**
     * 自动装配 AI 大模型全维链路可观测审计监听器
     *
     * @param properties               AI 根配置属性
     * @param auditLogHandlerProvider 可选的审计记录持久化扩展点
     * @param taskExecutorProvider     可选的受管异步线程池
     * @return AtengAiObservabilityListener 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.ai.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AtengAiObservabilityListener atengAiObservabilityListener(
            AtengAiProperties properties,
            ObjectProvider<AiAuditLogHandler> auditLogHandlerProvider,
            ObjectProvider<TaskExecutor> taskExecutorProvider) {
        return new AtengAiObservabilityListener(properties, auditLogHandlerProvider, taskExecutorProvider);
    }

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
                                             ObjectProvider<ChatModelListener> listenersProvider) {
        List<ChatModelListener> listeners = listenersProvider.orderedStream().toList();
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
                                                               ObjectProvider<ChatModelListener> listenersProvider) {
        List<ChatModelListener> listeners = listenersProvider.orderedStream().toList();
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
                                           ObjectProvider<ChatModelListener> listenersProvider) {
        AiModelRegistry registry = new AiModelRegistry();
        List<ChatModelListener> listeners = listenersProvider.orderedStream().toList();

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
     * 自动装配 SaaS 多租户动态大模型切源解析器
     *
     * @param properties AI 根配置属性
     * @return AiTenantModelResolver 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public AiTenantModelResolver aiTenantModelResolver(AtengAiProperties properties) {
        return new DefaultAiTenantModelResolver(properties);
    }

    /**
     * Redis 分布式配额风控自动装配配置类（当 StringRedisTemplate 在类路径存在时生效）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
    static class RedisQuotaConfiguration {

        @Bean
        @ConditionalOnMissingBean(AiQuotaChecker.class)
        public AiQuotaChecker redisAiQuotaChecker(AtengAiProperties properties,
                                                  ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate != null) {
                return new RedisAiQuotaChecker(properties.getQuota(), redisTemplate);
            }
            return new DefaultAiQuotaChecker(properties.getQuota());
        }
    }

    /**
     * 单机内存配额风控兜底装配配置类（当未引入 Redis 或无 StringRedisTemplate 时保底装配）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(AiQuotaChecker.class)
    static class InMemoryQuotaFallbackConfiguration {

        @Bean
        @ConditionalOnMissingBean(AiQuotaChecker.class)
        public AiQuotaChecker inMemoryAiQuotaChecker(AtengAiProperties properties) {
            return new DefaultAiQuotaChecker(properties.getQuota());
        }
    }

    /**
     * 自动装配 AI 核心命令式操作门面
     *
     * @param registry                   模型注册中心
     * @param properties                 AI 根配置属性
     * @param tenantModelResolverProvider 可选的多租户切源解析器
     * @param quotaCheckerProvider       可选的配额风控检查器
     * @return AiTemplate 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public AiTemplate aiTemplate(AiModelRegistry registry,
                                 AtengAiProperties properties,
                                 ObjectProvider<AiTenantModelResolver> tenantModelResolverProvider,
                                 ObjectProvider<AiQuotaChecker> quotaCheckerProvider) {
        return new AiTemplate(
                registry,
                properties,
                tenantModelResolverProvider.getIfAvailable(),
                quotaCheckerProvider.getIfAvailable()
        );
    }

    /**
     * 自动装配默认基于滑动窗口的会话记忆提供者 ChatMemoryProvider
     *
     * @param chatMemoryStore 底层会话存储
     * @param properties      AI 根配置属性
     * @return ChatMemoryProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean(ChatMemoryProvider.class)
    @ConditionalOnProperty(prefix = "ateng.boot.ai.chat-memory", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ChatMemoryProvider chatMemoryProvider(ChatMemoryStore chatMemoryStore, AtengAiProperties properties) {
        int maxMessages = properties.getChatMemory().getMaxMessages();
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(maxMessages)
                .chatMemoryStore(chatMemoryStore)
                .build();
    }

    /**
     * 自动装配 AI 会话记忆对称管理门面 ChatMemoryManager
     *
     * @param chatMemoryStore 底层会话存储
     * @param properties      AI 根配置属性
     * @return ChatMemoryManager 实例
     */
    @Bean
    @ConditionalOnMissingBean(ChatMemoryManager.class)
    @ConditionalOnProperty(prefix = "ateng.boot.ai.chat-memory", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ChatMemoryManager chatMemoryManager(ChatMemoryStore chatMemoryStore, AtengAiProperties properties) {
        return new ChatMemoryManager(chatMemoryStore, properties.getChatMemory().getMaxMessages());
    }

    /**
     * Redis 分布式会话存储自动装配配置类
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnProperty(prefix = "ateng.boot.ai.chat-memory", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class RedisChatMemoryConfiguration {

        @Bean
        @ConditionalOnMissingBean(ChatMemoryStore.class)
        public ChatMemoryStore redisChatMemoryStore(AtengAiProperties properties,
                                                    ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
            ChatMemoryProperties config = properties.getChatMemory();
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();

            if (config.getType() == ChatMemoryStoreType.REDIS) {
                if (redisTemplate == null) {
                    throw new AiException(AiResultCode.AI_CONFIG_ERROR,
                            "已配置 ateng.boot.ai.chat-memory.type=REDIS，但 Spring 容器中未找到 StringRedisTemplate Bean，请检查 Redis 依赖与连接配置");
                }
                return createRedisStore(redisTemplate, config, "");
            }

            if (config.getType() == ChatMemoryStoreType.AUTO && redisTemplate != null) {
                return createRedisStore(redisTemplate, config, "自动探测到 Redis，");
            }

            return new InMemoryChatMemoryStore();
        }

        private ChatMemoryStore createRedisStore(StringRedisTemplate redisTemplate, ChatMemoryProperties config, String modeDesc) {
            log.info("AI 会话记忆{}启用分布式 Redis 存储 [keyPrefix={}, ttl={}]", modeDesc, config.getKeyPrefix(), config.getTtl());
            return new RedisChatMemoryStore(redisTemplate, config.getKeyPrefix(), config.getTtl());
        }
    }

    /**
     * 单机内存会话存储兜底装配配置类（当未引入 Redis 或上述配置未生效时保底装配）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(ChatMemoryStore.class)
    @ConditionalOnProperty(prefix = "ateng.boot.ai.chat-memory", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class InMemoryChatMemoryFallbackConfiguration {

        @Bean
        @ConditionalOnMissingBean(ChatMemoryStore.class)
        public ChatMemoryStore inMemoryChatMemoryStore() {
            log.info("AI 会话记忆启用单机内存存储 (InMemoryChatMemoryStore)");
            return new InMemoryChatMemoryStore();
        }
    }

    /**
     * Micrometer 云原生度量监控扩展装配
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "ateng.boot.ai.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class AiMetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "aiMetricsBinder")
        public SmartInitializingSingleton aiMetricsBinder(
                ObjectProvider<AtengAiObservabilityListener> listenerProvider,
                ObjectProvider<MeterRegistry> meterRegistryProvider) {
            return () -> {
                AtengAiObservabilityListener listener = listenerProvider.getIfAvailable();
                MeterRegistry registry = meterRegistryProvider.getIfAvailable();
                if (listener != null && registry != null) {
                    listener.setMeterRegistry(registry);
                }
            };
        }
    }
}
