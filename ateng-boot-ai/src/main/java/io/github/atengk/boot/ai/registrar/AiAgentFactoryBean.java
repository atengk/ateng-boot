package io.github.atengk.boot.ai.registrar;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecutor;
import io.github.atengk.boot.ai.cache.AiSemanticCache;
import io.github.atengk.boot.ai.context.AgentCallContext;
import io.github.atengk.boot.ai.context.EpisodicMemoryContext;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.guardrail.AiGuardrail;
import io.github.atengk.boot.ai.guardrail.GuardrailAction;
import io.github.atengk.boot.ai.guardrail.GuardrailResult;
import io.github.atengk.boot.ai.memory.episodic.EpisodicMemoryTemplate;
import io.github.atengk.boot.ai.memory.episodic.MemoryFact;
import io.github.atengk.boot.ai.prompt.PromptDefinition;
import io.github.atengk.boot.ai.prompt.PromptRegistry;
import io.github.atengk.boot.ai.properties.AiModelConfig;
import io.github.atengk.boot.ai.properties.AiResponseFormat;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.quota.AiQuotaChecker;
import io.github.atengk.boot.ai.ratelimit.AiRateLimiter;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.routing.DynamicRoutingChatLanguageModel;
import io.github.atengk.boot.ai.routing.DynamicRoutingStreamingChatLanguageModel;
import io.github.atengk.boot.ai.sanitizer.JsonSchemaGenerator;
import io.github.atengk.boot.ai.sanitizer.NativeJsonSchemaContext;
import io.github.atengk.boot.ai.streaming.ActiveStreamRegistry;
import io.github.atengk.boot.ai.streaming.GuardrailedTokenStream;
import io.github.atengk.boot.ai.streaming.StreamContextHolder;
import io.github.atengk.boot.ai.tenant.AiTenantModelResolver;
import io.github.atengk.boot.ai.tool.ToolExecutionSandbox;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.util.ValidatorUtil;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.StringUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.temporal.Temporal;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 声明式大模型智能体（@AiAgent）接口代理工厂 Bean
 * 负责解析接口元数据，装配多模型动态路由、受管工具沙箱、RAG 知识库检索与全自动入参 JSON 序列化和输出 JSR-303 防卫。
 *
 * @param <T> 智能体接口类型
 * @author Ateng
 * @since 2026-11-03
 */
public class AiAgentFactoryBean<T> implements FactoryBean<T>, ApplicationContextAware, BeanNameAware, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AiAgentFactoryBean.class);

    @Getter
    @Setter
    private String beanName;

    @Getter
    @Setter
    private Class<T> agentInterface;

    @Getter
    @Setter
    private String model;

    @Getter
    @Setter
    private Class<?>[] tools = new Class<?>[0];

    @Getter
    @Setter
    private String contentRetriever;

    @Getter
    @Setter
    private String chatMemory;

    @Getter
    @Setter
    private boolean episodicMemory;

    @Getter
    @Setter
    private boolean extractMemory = true;

    @Getter
    @Setter
    private String promptCode;

    @Setter
    private EpisodicMemoryTemplate episodicMemoryTemplate;

    private ApplicationContext applicationContext;
    private final ReentrantLock initLock = new ReentrantLock();
    private volatile T proxyInstance;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {
        if (agentInterface == null || !agentInterface.isInterface()) {
            throw new AiException(AiResultCode.AI_AGENT_CONFIG_ERROR, "agentInterface 必须声明为合法的 Java 接口类型");
        }
    }

    private void initProxy() {
        if (this.proxyInstance != null) {
            return;
        }
        initLock.lock();
        try {
            if (this.proxyInstance != null) {
                return;
            }

            // 1. 获取模型注册表
            AiModelRegistry registry;
            try {
                registry = applicationContext.getBean(AiModelRegistry.class);
            } catch (NoSuchBeanDefinitionException e) {
                throw new AiException(AiResultCode.AI_AGENT_CONFIG_ERROR,
                        "Spring 容器中未找到 AiModelRegistry Bean，无法初始化智能体代理: " + agentInterface.getName(), e);
            }

            org.springframework.beans.factory.ObjectProvider<AiTenantModelResolver> resolverProvider = applicationContext.getBeanProvider(AiTenantModelResolver.class);
            AiTenantModelResolver tenantModelResolver = resolverProvider != null ? resolverProvider.getIfAvailable() : null;

            org.springframework.beans.factory.ObjectProvider<AiQuotaChecker> quotaCheckerProvider = applicationContext.getBeanProvider(AiQuotaChecker.class);
            AiQuotaChecker quotaChecker = quotaCheckerProvider != null ? quotaCheckerProvider.getIfAvailable() : null;

            org.springframework.beans.factory.ObjectProvider<AiRateLimiter> rateLimiterProvider = applicationContext.getBeanProvider(AiRateLimiter.class);
            AiRateLimiter rateLimiter = rateLimiterProvider != null ? rateLimiterProvider.getIfAvailable() : null;

            org.springframework.beans.factory.ObjectProvider<AiSemanticCache> semanticCacheProvider = applicationContext.getBeanProvider(AiSemanticCache.class);
            AiSemanticCache semanticCache = semanticCacheProvider != null ? semanticCacheProvider.getIfAvailable() : null;

            org.springframework.beans.factory.ObjectProvider<AtengAiProperties> aiPropertiesProvider = applicationContext.getBeanProvider(AtengAiProperties.class);
            AtengAiProperties aiProperties = aiPropertiesProvider != null ? aiPropertiesProvider.getIfAvailable() : null;

            // 2. 构建多模型动态路由代理 (前置护栏已由增强代理对入参精确执行，此处挂载后置护栏确保回写缓存前 100% 过检)
            DynamicRoutingChatLanguageModel routingModel = new DynamicRoutingChatLanguageModel(
                    registry, model, tenantModelResolver, quotaChecker, rateLimiter, semanticCache, aiProperties, null, resolveGuardrail());

            // 3. 构建 LangChain4j 原始 AiServices 代理
            AiServices<T> builder = AiServices.builder(agentInterface)
                    .chatLanguageModel(routingModel);

            // 3.1 若接口声明了 TokenStream 流式响应方法或注册表中包含可用流式模型，构建并挂载流式动态路由代理
            boolean hasStreaming = hasStreamingMethods(agentInterface);
            boolean hasRegisteredStreamingModel = registry.hasStreamingChatModel(AiModelRegistry.DEFAULT_MODEL_NAME)
                    || (StringUtils.hasText(model) && registry.hasStreamingChatModel(model));

            if (hasStreaming || hasRegisteredStreamingModel) {
                DynamicRoutingStreamingChatLanguageModel streamingRoutingModel =
                        new DynamicRoutingStreamingChatLanguageModel(registry, model, tenantModelResolver, quotaChecker, rateLimiter);
                builder.streamingChatLanguageModel(streamingRoutingModel);
            }

            // 3.2 解析接口级 @SystemMessage / @AiAgent(promptCode) 动态提示词与长效情景记忆 Persona 提示词注入
            dev.langchain4j.service.SystemMessage interfaceSystemMessage = agentInterface.getAnnotation(dev.langchain4j.service.SystemMessage.class);
            String systemText = null;
            if (interfaceSystemMessage != null) {
                if (StringUtils.hasText(interfaceSystemMessage.fromResource())) {
                    systemText = loadResourceText(agentInterface, interfaceSystemMessage.fromResource().trim());
                } else {
                    systemText = String.join(interfaceSystemMessage.delimiter(), interfaceSystemMessage.value());
                }
            }
            io.github.atengk.boot.ai.annotation.AiAgent agentAnnotation =
                    agentInterface.getAnnotation(io.github.atengk.boot.ai.annotation.AiAgent.class);
            String resolvedPromptCode = StringUtils.hasText(this.promptCode)
                    ? this.promptCode.trim()
                    : (agentAnnotation != null && StringUtils.hasText(agentAnnotation.promptCode())
                            ? agentAnnotation.promptCode().trim()
                            : null);

            final String baseSystemText = systemText;
            if (StringUtils.hasText(resolvedPromptCode) || StringUtils.hasText(baseSystemText) || episodicMemory) {
                builder.systemMessageProvider(memoryId -> {
                    String dynamicSystemPrompt = baseSystemText;
                    if (StringUtils.hasText(resolvedPromptCode)) {
                        org.springframework.beans.factory.ObjectProvider<PromptRegistry> promptProvider =
                                applicationContext.getBeanProvider(PromptRegistry.class);
                        PromptRegistry promptRegistry = promptProvider != null ? promptProvider.getIfAvailable() : null;
                        if (promptRegistry == null) {
                            throw new AiException(AiResultCode.AI_AGENT_CONFIG_ERROR,
                                    "智能体 [" + agentInterface.getSimpleName() + "] 声明了 promptCode="
                                            + resolvedPromptCode + "，但 Spring 容器中未找到 PromptRegistry Bean");
                        }
                        PromptDefinition def = promptRegistry.get(resolvedPromptCode);
                        dynamicSystemPrompt = def.getContent();
                    }

                    String episodicPersona = episodicMemory ? EpisodicMemoryContext.getInjectedPrompt() : null;
                    if (StringUtils.hasText(episodicPersona)) {
                        return StringUtils.hasText(dynamicSystemPrompt)
                                ? episodicPersona + "\n\n" + dynamicSystemPrompt
                                : episodicPersona;
                    }
                    return dynamicSystemPrompt != null ? dynamicSystemPrompt : "";
                });
            }

            // 4. 解析受管工具类列表并接入 ToolExecutionSandbox 局部沙箱
            if (tools != null && tools.length > 0) {
                String effectiveBeanName = StringUtils.hasText(beanName) ? beanName : agentInterface.getSimpleName();
                Map<ToolSpecification, ToolExecutor> sandboxedTools = ToolExecutionSandbox.buildSandboxedTools(
                        applicationContext, tools, effectiveBeanName, agentInterface.getName());
                if (!sandboxedTools.isEmpty()) {
                    builder.tools(sandboxedTools);
                }
            }

            // 5. 挂载 ChatMemoryProvider / ChatMemory 会话记忆
            if (StringUtils.hasText(chatMemory)) {
                try {
                    Object memoryBean = applicationContext.getBean(chatMemory.trim());
                    if (memoryBean instanceof ChatMemoryProvider provider) {
                        builder.chatMemoryProvider(provider);
                    } else if (memoryBean instanceof dev.langchain4j.memory.ChatMemory memory) {
                        builder.chatMemory(memory);
                    } else {
                        throw new AiException(AiResultCode.AI_AGENT_CONFIG_ERROR,
                                "智能体 [" + agentInterface.getSimpleName() + "] 显式指定的 chatMemory Bean ["
                                        + chatMemory + "] 类型不是 ChatMemoryProvider 或 ChatMemory: " + memoryBean.getClass().getName());
                    }
                } catch (NoSuchBeanDefinitionException e) {
                    throw new AiException(AiResultCode.AI_AGENT_CONFIG_ERROR,
                            "智能体 [" + agentInterface.getSimpleName() + "] 显式指定的 chatMemory Bean ["
                                    + chatMemory + "] 在 Spring 容器中未找到", e);
                }
            } else {
                // 缺省自动装配单 Bean
                Map<String, ChatMemoryProvider> memoryProviders = applicationContext.getBeansOfType(ChatMemoryProvider.class);
                if (memoryProviders.size() == 1) {
                    builder.chatMemoryProvider(memoryProviders.values().iterator().next());
                } else if (memoryProviders.size() > 1) {
                    ChatMemoryProvider primaryProvider = applicationContext.getBeanProvider(ChatMemoryProvider.class).getIfUnique();
                    if (primaryProvider != null) {
                        builder.chatMemoryProvider(primaryProvider);
                    } else {
                        log.warn("Spring 容器中存在多个 ChatMemoryProvider Bean，智能体 [{}] 未显式指定 chatMemory 名称且无 @Primary 标注，跳过自动装配",
                                agentInterface.getSimpleName());
                    }
                }
            }

            // 6. 挂载 ContentRetriever 知识库检索点 (仅在显式声明 @AiAgent(contentRetriever = "...") 时装配，遵循显式契约原则，杜绝无差别自动绑架无关业务智能体)
            if (StringUtils.hasText(contentRetriever)) {
                String beanName = contentRetriever.trim();
                try {
                    ContentRetriever retriever;
                    if ("auto".equalsIgnoreCase(beanName) || "default".equalsIgnoreCase(beanName)) {
                        retriever = applicationContext.getBean(ContentRetriever.class);
                    } else {
                        retriever = applicationContext.getBean(beanName, ContentRetriever.class);
                    }
                    builder.contentRetriever(retriever);
                } catch (NoSuchBeanDefinitionException e) {
                    throw new AiException(AiResultCode.AI_AGENT_CONFIG_ERROR,
                            "智能体 [" + agentInterface.getSimpleName() + "] 显式指定的 ContentRetriever Bean ["
                                    + contentRetriever + "] 在 Spring 容器中未找到", e);
                }
            }

            // 6. 生成 LangChain4j 原生服务实例
            T rawAgent = builder.build();

            // 7. 环绕增强：复合对象入参自动序列化与输出 JSR-303 防卫
            this.proxyInstance = createEnhancedProxy(rawAgent, routingModel);
            log.info("成功装配声明式智能体代理 Bean: [{}] (绑定模型: {})",
                    agentInterface.getName(), StringUtils.hasText(model) ? model : "默认模型");
        } finally {
            if (initLock.isHeldByCurrentThread()) {
                initLock.unlock();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private T createEnhancedProxy(T rawAgent, DynamicRoutingChatLanguageModel routingModel) {
        InvocationHandler handler = (proxy, method, args) -> {
            // Object 基类方法直接透传
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(rawAgent, args);
            }

            // 0. 前置内容安全护栏检查 (PRE_PROMPT 防注入/越狱/超长，覆盖 String、ChatMessage 与复合 POJO/DTO 入参)
            AiGuardrail guardrail = resolveGuardrail();
            if (guardrail != null && args != null) {
                String replacedOutput = inspectPrePromptGuardrail(guardrail, method, args);
                if (replacedOutput != null) {
                    return replacedOutput;
                }
            }

            // 1. 若开启长效情景记忆 (episodicMemory)，执行三阶梯用户识别并召回 Persona 画像注入
            String resolvedUserId = null;
            String userQuery = null;
            String personaPrompt = null;
            if (episodicMemory) {
                resolvedUserId = resolveUserId(method, args);
                userQuery = resolveUserQuery(method, args);
                EpisodicMemoryTemplate template = resolveEpisodicMemoryTemplate();
                if (template != null && StringUtils.hasText(resolvedUserId)) {
                    try {
                        List<MemoryFact> facts = null;
                        if (StringUtils.hasText(userQuery)) {
                            facts = template.recall(userQuery, resolvedUserId, 5, 0.6);
                        }
                        if (facts == null || facts.isEmpty()) {
                            facts = template.listFacts(resolvedUserId, 5);
                        }
                        personaPrompt = EpisodicMemoryTemplate.formatPersonaPrompt(facts);
                    } catch (Exception e) {
                        log.warn("智能体 [{}] 长效情景记忆召回受控跳过: {}", agentInterface.getSimpleName(), e.getMessage());
                    }
                }
            }

            // 2. 注入情景记忆并在严格 finally 块中保证确定性清理
            Object result;
            try {
                if (StringUtils.hasText(personaPrompt)) {
                    EpisodicMemoryContext.setInjectedPrompt(personaPrompt);
                }

                // 提取复杂对象参数并构建 JSON 替换映射
                Map<String, String> replacements = buildArgumentReplacements(args);

                // 按需打开原生 JSON Schema 作用域，并在 try-with-resources 中确定性清理 ThreadLocal
                try (NativeJsonSchemaContext.Scope nativeSchemaScope = openNativeSchemaScopeIfNeeded(method, routingModel);
                     AgentCallContext.Scope ignored = AgentCallContext.openScope(replacements)) {
                    method.setAccessible(true);
                    result = method.invoke(rawAgent, args);
                } catch (InvocationTargetException ite) {
                    Throwable target = ite.getTargetException();
                    if (target instanceof RuntimeException re) {
                        throw re;
                    }
                    throw ite;
                }
            } finally {
                // 确保 ThreadLocal 记忆提示词绝对清理，杜绝跨请求污染
                if (episodicMemory) {
                    EpisodicMemoryContext.clear();
                }
            }

            // 4. 后置内容安全护栏检查 (POST_RESPONSE 防违禁敏感词，覆盖同步 String 与流式 TokenStream)
            if (guardrail != null) {
                if (result instanceof String textResult) {
                    GuardrailResult postResult = guardrail.inspectResponse(textResult);
                    if (postResult.getAction() == GuardrailAction.BLOCK) {
                        throw new AiException(AiResultCode.AI_CONTENT_RISK_DETECTED, postResult.getReason());
                    } else if (postResult.getAction() == GuardrailAction.REPLACE) {
                        result = postResult.getSafeContent();
                    }
                } else if (result instanceof dev.langchain4j.service.TokenStream tokenStream) {
                    ActiveStreamRegistry activeStreamRegistry = resolveActiveStreamRegistry();
                    String activeStreamId = StreamContextHolder.getStreamId();
                    if (!StringUtils.hasText(activeStreamId)) {
                        activeStreamId = org.slf4j.MDC.get("traceId");
                    }
                    if (!StringUtils.hasText(activeStreamId) && activeStreamRegistry != null) {
                        activeStreamId = IdUtil.fastSimpleUUID();
                    }
                    result = new GuardrailedTokenStream(tokenStream, guardrail, activeStreamId, activeStreamRegistry);
                }
            }

            // 5. 对强类型结构化输出执行 JSR-303 数据防卫 (跳过 TokenStream 流式响应句柄)
            if (result != null && !(result instanceof dev.langchain4j.service.TokenStream) && isComplexObject(result)) {
                try {
                    ValidatorUtil.validate(result);
                } catch (BusinessException be) {
                    throw new AiException(AiResultCode.AI_RESPONSE_PARSE_ERROR,
                            "智能体响应结构化数据未通过 JSR-303 校验: " + be.getMessage(), be);
                }
            }

            // 6. 若启用长效事实自动提炼 (episodicMemory && extractMemory)，异步提取新事实并写入向量库
            if (episodicMemory && extractMemory && StringUtils.hasText(resolvedUserId) && result != null && !(result instanceof dev.langchain4j.service.TokenStream)) {
                dispatchPostConversationExtraction(userQuery, result, resolvedUserId);
            }

            return result;
        };

        return (T) Proxy.newProxyInstance(
                agentInterface.getClassLoader(),
                new Class<?>[]{agentInterface},
                handler
        );
    }

    /**
     * 对代理方法入参（String / ChatMessage / 复合 POJO/DTO）执行前置内容安全护栏校验
     *
     * @return 若命中 REPLACE 且方法返回类型为 String 则直接返回安全替代文案；否则返回 null 继续正常执行
     */
    private String inspectPrePromptGuardrail(AiGuardrail guardrail, Method method, Object[] args) {
        java.lang.reflect.Parameter[] parameters = method.getParameters();
        for (int i = 0; i < args.length; i++) {
            if (i < parameters.length && (parameters[i].isAnnotationPresent(io.github.atengk.boot.ai.annotation.UserId.class)
                    || parameters[i].isAnnotationPresent(dev.langchain4j.service.MemoryId.class))) {
                continue;
            }
            Object arg = args[i];
            if (arg == null) {
                continue;
            }
            GuardrailResult preResult = null;
            if (arg instanceof String text) {
                preResult = guardrail.inspectPrompt(text);
            } else if (arg instanceof ChatMessage chatMsg) {
                preResult = guardrail.inspectMessages(Collections.singletonList(chatMsg));
            } else if (isComplexObject(arg)) {
                String jsonText = JsonUtil.toJsonString(arg);
                if (StringUtils.hasText(jsonText)) {
                    preResult = guardrail.inspectPrompt(jsonText);
                }
            }
            if (preResult == null) {
                continue;
            }
            if (preResult.getAction() == GuardrailAction.BLOCK) {
                throw new AiException(AiResultCode.AI_CONTENT_RISK_DETECTED, preResult.getReason());
            }
            if (preResult.getAction() == GuardrailAction.REPLACE) {
                if (method.getReturnType() == String.class) {
                    return preResult.getSafeContent();
                }
                if (arg instanceof String) {
                    args[i] = preResult.getSafeContent();
                } else {
                    throw new AiException(AiResultCode.AI_CONTENT_RISK_DETECTED, preResult.getReason());
                }
            }
        }
        return null;
    }

    private ActiveStreamRegistry resolveActiveStreamRegistry() {
        if (applicationContext != null) {
            try {
                return applicationContext.getBeanProvider(ActiveStreamRegistry.class).getIfAvailable();
            } catch (Exception ex) {
                log.debug("获取 ActiveStreamRegistry Bean 失败或未注册: {}", ex.getMessage());
            }
        }
        return null;
    }

    private AiGuardrail resolveGuardrail() {
        if (applicationContext != null) {
            try {
                return applicationContext.getBeanProvider(AiGuardrail.class).getIfAvailable();
            } catch (Exception ex) {
                log.debug("获取 AiGuardrail Bean 失败或未注册，跳过安全护栏装配: {}", ex.getMessage());
            }
        }
        return null;
    }

    /**
     * 将复合对象（JavaBean / Record）序列化为 JSON 字符串并建立 toString 映射
     */
    private Map<String, String> buildArgumentReplacements(Object[] args) {
        if (args == null || args.length == 0) {
            return Collections.emptyMap();
        }
        Map<String, String> replacements = new HashMap<>();
        for (Object arg : args) {
            if (arg != null && isComplexObject(arg)) {
                try {
                    String json = JsonUtil.toJsonString(arg);
                    replacements.put(arg.toString(), json);
                } catch (Exception e) {
                    log.debug("序列化入参对象为 JSON 失败，保留原生 toString: {}", arg.getClass().getName());
                }
            }
        }
        return replacements;
    }

    /**
     * 判断对象是否为复杂业务对象（非基础数据类型）
     */
    private NativeJsonSchemaContext.Scope openNativeSchemaScopeIfNeeded(Method method,
                                                                        DynamicRoutingChatLanguageModel routingModel) {
        if (applicationContext == null || method == null || routingModel == null) {
            return null;
        }
        Class<?> returnType = method.getReturnType();
        if (!isComplexSchemaTarget(returnType)) {
            return null;
        }
        org.springframework.beans.factory.ObjectProvider<AtengAiProperties> propsProvider =
                applicationContext.getBeanProvider(AtengAiProperties.class);
        AtengAiProperties aiProperties = propsProvider != null ? propsProvider.getIfAvailable() : null;
        if (aiProperties == null) {
            return null;
        }
        AiModelConfig modelConfig = aiProperties.resolveConfig(routingModel.resolveModelName());
        if (modelConfig != null && modelConfig.isStrictNativeJsonSchema()) {
            String strictSchema = JsonSchemaGenerator.generateStrictSchema(returnType);
            return NativeJsonSchemaContext.openScope(returnType.getSimpleName(), strictSchema);
        }
        return null;
    }

    private static boolean isComplexSchemaTarget(Class<?> returnType) {
        if (returnType == null || returnType == void.class || returnType == Void.class || returnType == Object.class) {
            return false;
        }
        if (returnType.isPrimitive() || returnType.isArray() || returnType.isEnum()) {
            return false;
        }
        if (dev.langchain4j.service.TokenStream.class.isAssignableFrom(returnType)
                || dev.langchain4j.model.output.Response.class.isAssignableFrom(returnType)) {
            return false;
        }
        if (CharSequence.class.isAssignableFrom(returnType)
                || Number.class.isAssignableFrom(returnType)
                || Boolean.class.isAssignableFrom(returnType)
                || Character.class.isAssignableFrom(returnType)
                || Temporal.class.isAssignableFrom(returnType)
                || Date.class.isAssignableFrom(returnType)
                || Collection.class.isAssignableFrom(returnType)
                || Map.class.isAssignableFrom(returnType)) {
            return false;
        }
        return true;
    }

    private static boolean isComplexObject(Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj instanceof CharSequence || obj instanceof Number || obj instanceof Boolean
                || obj instanceof Character || obj instanceof Enum<?> || obj instanceof Temporal
                || obj instanceof Date) {
            return false;
        }
        return true;
    }

    private static String loadResourceText(Class<?> clazz, String resourcePath) {
        try (var in = clazz.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new AiException(AiResultCode.AI_AGENT_CONFIG_ERROR,
                        "智能体接口 [" + clazz.getSimpleName() + "] 声明的 @SystemMessage 资源未找到: " + resourcePath);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            if (e instanceof AiException aiException) {
                throw aiException;
            }
            throw new AiException(AiResultCode.AI_AGENT_CONFIG_ERROR,
                    "读取智能体接口 [" + clazz.getSimpleName() + "] @SystemMessage 资源失败: " + resourcePath, e);
        }
    }

    private static boolean hasStreamingMethods(Class<?> intf) {
        if (intf == null) {
            return false;
        }
        for (Method m : intf.getMethods()) {
            if (dev.langchain4j.service.TokenStream.class.isAssignableFrom(m.getReturnType())) {
                return true;
            }
        }
        return false;
    }

    private EpisodicMemoryTemplate resolveEpisodicMemoryTemplate() {
        if (this.episodicMemoryTemplate != null) {
            return this.episodicMemoryTemplate;
        }
        if (this.applicationContext != null) {
            try {
                this.episodicMemoryTemplate = this.applicationContext.getBeanProvider(EpisodicMemoryTemplate.class).getIfAvailable();
                return this.episodicMemoryTemplate;
            } catch (Exception ex) {
                log.debug("获取 EpisodicMemoryTemplate 失败或未注册: {}", ex.getMessage());
            }
        }
        return null;
    }

    private String resolveUserId(Method method, Object[] args) {
        if (method != null && args != null && args.length > 0) {
            java.lang.reflect.Parameter[] parameters = method.getParameters();
            // 阶梯一：方法参数标注 @UserId
            for (int i = 0; i < parameters.length && i < args.length; i++) {
                if (parameters[i].isAnnotationPresent(io.github.atengk.boot.ai.annotation.UserId.class)) {
                    Object val = args[i];
                    if (val != null && StringUtils.hasText(val.toString())) {
                        return val.toString().trim();
                    }
                }
            }
            // 阶梯二：方法参数标注 @MemoryId
            for (int i = 0; i < parameters.length && i < args.length; i++) {
                if (parameters[i].isAnnotationPresent(dev.langchain4j.service.MemoryId.class)) {
                    Object val = args[i];
                    if (val != null && StringUtils.hasText(val.toString())) {
                        return val.toString().trim();
                    }
                }
            }
        }
        // 阶梯三：当前调用上下文 UserContextHolder.getUserId()
        String contextUserId = UserContextHolder.getUserId();
        if (StringUtils.hasText(contextUserId)) {
            return contextUserId.trim();
        }
        return null;
    }

    private String resolveUserQuery(Method method, Object[] args) {
        if (method == null || args == null || args.length == 0) {
            return null;
        }
        java.lang.reflect.Parameter[] parameters = method.getParameters();
        // 优先匹配 @UserMessage
        for (int i = 0; i < parameters.length && i < args.length; i++) {
            if (parameters[i].isAnnotationPresent(dev.langchain4j.service.UserMessage.class)) {
                Object val = args[i];
                if (val != null) {
                    return val instanceof String s ? s : JsonUtil.toJsonString(val);
                }
            }
        }
        // 次选：第一个非控制参数的 String
        for (int i = 0; i < parameters.length && i < args.length; i++) {
            if (parameters[i].isAnnotationPresent(io.github.atengk.boot.ai.annotation.UserId.class)
                    || parameters[i].isAnnotationPresent(dev.langchain4j.service.MemoryId.class)
                    || parameters[i].isAnnotationPresent(dev.langchain4j.service.SystemMessage.class)) {
                continue;
            }
            Object val = args[i];
            if (val instanceof String s && StringUtils.hasText(s)) {
                return s;
            }
        }
        // 保底：第一个非控制参数对象
        for (int i = 0; i < parameters.length && i < args.length; i++) {
            if (parameters[i].isAnnotationPresent(io.github.atengk.boot.ai.annotation.UserId.class)
                    || parameters[i].isAnnotationPresent(dev.langchain4j.service.MemoryId.class)) {
                continue;
            }
            Object val = args[i];
            if (val != null) {
                return val instanceof String s ? s : JsonUtil.toJsonString(val);
            }
        }
        return null;
    }

    private void dispatchPostConversationExtraction(String userQuery, Object result, String userId) {
        EpisodicMemoryTemplate template = resolveEpisodicMemoryTemplate();
        if (template == null || !template.isExtractorAvailable()) {
            return;
        }
        String outputText = (result instanceof String s) ? s : JsonUtil.toJsonString(result);
        if (!StringUtils.hasText(outputText)) {
            return;
        }
        String conversationText;
        if (StringUtils.hasText(userQuery)) {
            conversationText = "用户: " + userQuery + "\n助手: " + outputText;
        } else {
            conversationText = "助手: " + outputText;
        }
        String tenantId = (!TenantContextHolder.isIgnoreTenant() && StringUtils.hasText(TenantContextHolder.getTenantId()))
                ? TenantContextHolder.getTenantId().trim()
                : null;
        try {
            template.extractAndRememberAsync(conversationText, userId, tenantId)
                    .whenComplete((facts, ex) -> {
                        if (ex != null) {
                            log.warn("智能体 [{}] 异步抽取情景事实异常: {}", agentInterface.getSimpleName(), ex.getMessage());
                        } else if (facts != null && !facts.isEmpty()) {
                            log.debug("智能体 [{}] 异步抽取并持久化了 {} 条情景事实", agentInterface.getSimpleName(), facts.size());
                        }
                    });
        } catch (Exception ex) {
            log.warn("智能体 [{}] 派发异步事实抽取任务受控跳过: {}", agentInterface.getSimpleName(), ex.getMessage());
        }
    }

    @Override
    public T getObject() {
        if (proxyInstance == null) {
            initProxy();
        }
        return proxyInstance;
    }

    @Override
    public Class<?> getObjectType() {
        return agentInterface;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }
}
