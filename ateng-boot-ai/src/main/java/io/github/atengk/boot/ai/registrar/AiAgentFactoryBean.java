package io.github.atengk.boot.ai.registrar;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecutor;
import io.github.atengk.boot.ai.context.AgentCallContext;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.quota.AiQuotaChecker;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.routing.DynamicRoutingChatLanguageModel;
import io.github.atengk.boot.ai.tenant.AiTenantModelResolver;
import io.github.atengk.boot.ai.tool.ToolExecutionSandbox;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.util.ValidatorUtil;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
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

/**
 * 声明式大模型智能体（@AiAgent）接口代理工厂 Bean
 * 负责解析接口元数据，装配多模型动态路由、受管工具沙箱、RAG 知识库检索与全自动入参 JSON 序列化和输出 JSR-303 防卫。
 *
 * @param <T> 智能体接口类型
 * @author Ateng
 * @since 2026-11-03
 */
public class AiAgentFactoryBean<T> implements FactoryBean<T>, ApplicationContextAware, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AiAgentFactoryBean.class);

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

    private ApplicationContext applicationContext;
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

    private synchronized void initProxy() {
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

        // 2. 构建多模型动态路由代理 (注入多租户切源与配额风控守卫)
        DynamicRoutingChatLanguageModel routingModel = new DynamicRoutingChatLanguageModel(registry, model, tenantModelResolver, quotaChecker);

        // 3. 构建 LangChain4j 原始 AiServices 代理
        AiServices<T> builder = AiServices.builder(agentInterface)
                .chatLanguageModel(routingModel);

        // 3.1 解析接口级 @SystemMessage 系统提示词
        dev.langchain4j.service.SystemMessage interfaceSystemMessage = agentInterface.getAnnotation(dev.langchain4j.service.SystemMessage.class);
        if (interfaceSystemMessage != null) {
            String systemText;
            if (StringUtils.hasText(interfaceSystemMessage.fromResource())) {
                systemText = loadResourceText(agentInterface, interfaceSystemMessage.fromResource().trim());
            } else {
                systemText = String.join(interfaceSystemMessage.delimiter(), interfaceSystemMessage.value());
            }
            if (StringUtils.hasText(systemText)) {
                builder.systemMessageProvider(memoryId -> systemText);
            }
        }

        // 4. 解析受管工具类列表并接入 ToolExecutionSandbox 局部沙箱
        if (tools != null && tools.length > 0) {
            Map<ToolSpecification, ToolExecutor> sandboxedTools = ToolExecutionSandbox.buildSandboxedTools(
                    applicationContext, tools, agentInterface.getSimpleName());
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

        // 6. 挂载 ContentRetriever 知识库检索点
        if (StringUtils.hasText(contentRetriever)) {
            try {
                ContentRetriever retriever = applicationContext.getBean(contentRetriever.trim(), ContentRetriever.class);
                builder.contentRetriever(retriever);
            } catch (NoSuchBeanDefinitionException e) {
                throw new AiException(AiResultCode.AI_AGENT_CONFIG_ERROR,
                        "智能体 [" + agentInterface.getSimpleName() + "] 显式指定的 ContentRetriever Bean ["
                                + contentRetriever + "] 在 Spring 容器中未找到", e);
            }
        } else {
            // 缺省单 Bean 自动装配
            Map<String, ContentRetriever> retrievers = applicationContext.getBeansOfType(ContentRetriever.class);
            if (retrievers.size() == 1) {
                builder.contentRetriever(retrievers.values().iterator().next());
            } else if (retrievers.size() > 1) {
                log.warn("Spring 容器中存在多个 ContentRetriever Bean，智能体 [{}] 未显式指定名称，跳过自动装配",
                        agentInterface.getSimpleName());
            }
        }

        // 6. 生成 LangChain4j 原生服务实例
        T rawAgent = builder.build();

        // 7. 环绕增强：复合对象入参自动序列化与输出 JSR-303 防卫
        this.proxyInstance = createEnhancedProxy(rawAgent);
        log.info("成功装配声明式智能体代理 Bean: [{}] (绑定模型: {})",
                agentInterface.getName(), StringUtils.hasText(model) ? model : "默认模型");
    }

    @SuppressWarnings("unchecked")
    private T createEnhancedProxy(T rawAgent) {
        InvocationHandler handler = (proxy, method, args) -> {
            // Object 基类方法直接透传
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(rawAgent, args);
            }

            // 1. 提取复杂对象参数并构建 JSON 替换映射
            Map<String, String> replacements = buildArgumentReplacements(args);

            // 2. 打开调用上下文作用域并执行底层调用
            Object result;
            try (AgentCallContext.Scope ignored = AgentCallContext.openScope(replacements)) {
                method.setAccessible(true);
                result = method.invoke(rawAgent, args);
            } catch (InvocationTargetException ite) {
                Throwable target = ite.getTargetException();
                if (target instanceof RuntimeException re) {
                    throw re;
                }
                throw ite;
            }

            // 3. 对强类型结构化输出执行 JSR-303 数据防卫
            if (result != null && isComplexObject(result)) {
                try {
                    ValidatorUtil.validate(result);
                } catch (BusinessException be) {
                    throw new AiException(AiResultCode.AI_RESPONSE_PARSE_ERROR,
                            "智能体响应结构化数据未通过 JSR-303 校验: " + be.getMessage(), be);
                }
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
