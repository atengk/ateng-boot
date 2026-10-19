package io.github.atengk.boot.redis.pubsub.bootstrap;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.ContextCopyingTaskDecorator;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.boot.redis.pubsub.annotation.ChannelTopicListener;
import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 声明式 Redis Pub/Sub 广播主题生命周期调度器
 * 负责在 Spring 单例加载完毕后自动扫描 {@link ChannelTopicListener} 注解方法，
 * 并将其安全挂载到 {@link RedisMessageListenerContainer} 中，提供强类型安全反序列化与受管线程池隔离调度
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Slf4j
public class ChannelTopicBootstrap implements SmartInitializingSingleton, DisposableBean,
        EmbeddedValueResolverAware, ApplicationContextAware {

    public static final String MDC_TRACE_ID_KEY = "traceId";

    private final RedisMessageListenerContainer listenerContainer;
    private final org.springframework.beans.factory.ObjectProvider<RedisMessageListenerContainer> containerProvider;
    private final ObjectMapper objectMapper;
    private final RedisProperties redisProperties;

    private volatile RedisMessageListenerContainer resolvedContainer;
    private ApplicationContext applicationContext;
    private StringValueResolver embeddedValueResolver;

    private final Map<MessageListener, List<Topic>> registeredListeners = new ConcurrentHashMap<>();
    private final List<ThreadPoolTaskExecutor> executors = new CopyOnWriteArrayList<>();

    public ChannelTopicBootstrap(RedisMessageListenerContainer listenerContainer) {
        this(listenerContainer, null, null);
    }

    public ChannelTopicBootstrap(RedisMessageListenerContainer listenerContainer,
                                 ObjectMapper objectMapper) {
        this(listenerContainer, objectMapper, null);
    }

    public ChannelTopicBootstrap(RedisMessageListenerContainer listenerContainer,
                                 ObjectMapper objectMapper,
                                 RedisProperties redisProperties) {
        this.listenerContainer = listenerContainer;
        this.containerProvider = null;
        this.objectMapper = objectMapper != null ? objectMapper : RedisSerializerFactory.createSafeObjectMapper();
        this.redisProperties = redisProperties;
    }

    public ChannelTopicBootstrap(org.springframework.beans.factory.ObjectProvider<RedisMessageListenerContainer> containerProvider,
                                 ObjectMapper objectMapper,
                                 RedisProperties redisProperties) {
        this.listenerContainer = null;
        this.containerProvider = containerProvider;
        this.objectMapper = objectMapper != null ? objectMapper : RedisSerializerFactory.createSafeObjectMapper();
        this.redisProperties = redisProperties;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void setEmbeddedValueResolver(StringValueResolver resolver) {
        this.embeddedValueResolver = resolver;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (applicationContext == null) {
            return;
        }

        String[] beanNames = applicationContext.getBeanNamesForType(Object.class);
        ConfigurableListableBeanFactory beanFactory = (applicationContext instanceof org.springframework.context.ConfigurableApplicationContext cac)
                ? cac.getBeanFactory() : null;

        for (String beanName : beanNames) {
            if (ScopedProxyUtils.isScopedTarget(beanName)) {
                continue;
            }

            Class<?> beanType = null;
            try {
                if (beanFactory != null) {
                    beanType = AutoProxyUtils.determineTargetClass(beanFactory, beanName);
                } else {
                    beanType = applicationContext.getType(beanName);
                }
            } catch (Throwable ex) {
                beanType = applicationContext.getType(beanName);
            }

            if (beanType == null) {
                continue;
            }

            Map<Method, ChannelTopicListener> annotatedMethods = MethodIntrospector.selectMethods(
                    beanType,
                    (MethodIntrospector.MetadataLookup<ChannelTopicListener>) method ->
                            AnnotatedElementUtils.findMergedAnnotation(method, ChannelTopicListener.class)
            );

            if (CollectionUtils.isEmpty(annotatedMethods)) {
                continue;
            }

            Object bean = applicationContext.getBean(beanName);
            annotatedMethods.forEach((method, listener) -> registerListener(bean, method, listener));
        }
    }

    /**
     * 注册单个声明式广播监听方法
     *
     * @param bean 目标 Bean 实例
     * @param method 标注了 @ChannelTopicListener 的方法
     * @param listener 注解配置元数据
     */
    protected void registerListener(Object bean, Method method, ChannelTopicListener listener) {
        int parameterCount = method.getParameterCount();
        if (parameterCount > 3) {
            throw new IllegalArgumentException("@ChannelTopicListener 标注的方法最多支持 3 个参数 (payload, channel, pattern)，异常方法: " + method);
        }

        List<Topic> topics = resolveTopics(listener);
        Assert.notEmpty(topics, "@ChannelTopicListener 必须至少指定一个有效的 topic 或 pattern，异常方法: " + method);

        RedisMessageListenerContainer container = obtainListenerContainer();
        if (container == null) {
            log.warn("RedisMessageListenerContainer 未注入或不可用，忽略 @ChannelTopicListener 注册: method={}#{}",
                    bean.getClass().getSimpleName(), method.getName());
            return;
        }

        ReflectionUtils.makeAccessible(method);

        int concurrency = Math.max(1, listener.concurrency());
        String cleanTopicName = sanitizeTopicName(topics.get(0).getTopic());

        // 1. 初始化受管线程池
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("redis-pubsub-" + cleanTopicName + "-");
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(concurrency * 32);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setDaemon(true);
        executor.setTaskDecorator(new ContextCopyingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        executors.add(executor);

        // 2. 预解析参数类型
        Parameter[] parameters = method.getParameters();
        JavaType javaType = null;
        Class<?> param0Type = null;
        if (parameterCount > 0) {
            param0Type = parameters[0].getType();
            Type genericType = method.getGenericParameterTypes()[0];
            javaType = objectMapper.getTypeFactory().constructType(genericType);
        }

        final Class<?> finalParam0Type = param0Type;
        final JavaType finalJavaType = javaType;

        // 3. 构建 MessageListener
        MessageListener messageListener = (message, pattern) -> {
            executor.submit(() -> {
                String existingTraceId = MDC.get(MDC_TRACE_ID_KEY);
                boolean traceGenerated = false;
                if (!StringUtils.hasText(existingTraceId)) {
                    MDC.put(MDC_TRACE_ID_KEY, IdUtil.fastSimpleUUID());
                    traceGenerated = true;
                }
                try {
                    invokeListenerMethod(bean, method, parameters, parameterCount, finalParam0Type, finalJavaType, message, pattern);
                } catch (Throwable ex) {
                    Throwable cause = (ex instanceof InvocationTargetException ite) ? ite.getTargetException() : ex;
                    String ch = (message != null && message.getChannel() != null)
                            ? new String(message.getChannel(), StandardCharsets.UTF_8) : "unknown";
                    log.error("Redis Pub/Sub 广播消息消费异常: channel={}, method={}#{}, error={}",
                            ch, bean.getClass().getSimpleName(), method.getName(), cause.getMessage(), cause);
                } finally {
                    if (traceGenerated) {
                        MDC.remove(MDC_TRACE_ID_KEY);
                    }
                }
            });
        };

        // 4. 注册到 Redis 监听容器
        container.addMessageListener(messageListener, topics);
        registeredListeners.put(messageListener, topics);

        log.info("已成功注册 Redis Pub/Sub 广播监听器 [topics={}, concurrency={}, method={}#{}]",
                topics, concurrency, bean.getClass().getSimpleName(), method.getName());
    }

    private void invokeListenerMethod(Object bean,
                                      Method method,
                                      Parameter[] parameters,
                                      int parameterCount,
                                      Class<?> param0Type,
                                      JavaType javaType,
                                      Message message,
                                      byte[] pattern) throws Exception {
        if (parameterCount == 0) {
            method.invoke(bean);
            return;
        }

        Object[] args = new Object[parameterCount];

        // 解析参数 0 (Payload)
        byte[] body = (message != null) ? message.getBody() : null;
        if (body == null || body.length == 0) {
            args[0] = null;
        } else if (Message.class.isAssignableFrom(param0Type)) {
            args[0] = message;
        } else if (byte[].class.equals(param0Type)) {
            args[0] = body;
        } else if (String.class.equals(param0Type)) {
            args[0] = new String(body, StandardCharsets.UTF_8);
        } else {
            args[0] = objectMapper.readValue(body, javaType);
        }

        // 解析参数 1 (Channel)
        if (parameterCount >= 2) {
            args[1] = (message != null && message.getChannel() != null)
                    ? new String(message.getChannel(), StandardCharsets.UTF_8) : null;
        }

        // 解析参数 2 (Pattern)
        if (parameterCount >= 3) {
            args[2] = (pattern != null) ? new String(pattern, StandardCharsets.UTF_8) : null;
        }

        method.invoke(bean, args);
    }

    private List<Topic> resolveTopics(ChannelTopicListener listener) {
        List<Topic> topics = new ArrayList<>();

        // 解析精确主题 (topics / value)
        String[] directTopics = listener.topics().length > 0 ? listener.topics() : listener.value();
        if (directTopics != null) {
            for (String topic : directTopics) {
                if (StringUtils.hasText(topic)) {
                    String resolved = resolveString(topic);
                    if (StringUtils.hasText(resolved)) {
                        topics.add(new ChannelTopic(resolved.trim()));
                    }
                }
            }
        }

        // 解析单个主题别名 (topic)
        if (StringUtils.hasText(listener.topic())) {
            String resolved = resolveString(listener.topic());
            if (StringUtils.hasText(resolved)) {
                topics.add(new ChannelTopic(resolved.trim()));
            }
        }

        // 解析通配模式 (patterns)
        String[] patterns = listener.patterns();
        if (patterns != null) {
            for (String pattern : patterns) {
                if (StringUtils.hasText(pattern)) {
                    String resolved = resolveString(pattern);
                    if (StringUtils.hasText(resolved)) {
                        topics.add(new PatternTopic(resolved.trim()));
                    }
                }
            }
        }

        return topics;
    }

    private String resolveString(String value) {
        if (embeddedValueResolver != null && StringUtils.hasText(value)) {
            return embeddedValueResolver.resolveStringValue(value);
        }
        return value;
    }

    private String sanitizeTopicName(String topic) {
        if (!StringUtils.hasText(topic)) {
            return "topic";
        }
        return topic.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    private RedisMessageListenerContainer obtainListenerContainer() {
        if (listenerContainer != null) {
            return listenerContainer;
        }
        if (resolvedContainer != null) {
            return resolvedContainer;
        }
        if (containerProvider != null) {
            try {
                resolvedContainer = containerProvider.getIfAvailable();
                if (resolvedContainer == null) {
                    resolvedContainer = containerProvider.getObject();
                }
            } catch (Throwable ex) {
                log.warn("通过 ObjectProvider 获取 RedisMessageListenerContainer 失败: {}", ex.getMessage());
            }
        }
        return resolvedContainer;
    }

    @Override
    public void destroy() {
        RedisMessageListenerContainer container = obtainListenerContainer();
        if (container != null && !registeredListeners.isEmpty()) {
            registeredListeners.forEach((listener, topics) -> {
                try {
                    container.removeMessageListener(listener, topics);
                } catch (Throwable ex) {
                    log.warn("注销 Redis Pub/Sub 广播监听器异常: topics={}", topics, ex);
                }
            });
            registeredListeners.clear();
        }

        for (ThreadPoolTaskExecutor executor : executors) {
            try {
                executor.shutdown();
            } catch (Throwable ex) {
                log.warn("关闭 Pub/Sub 消费线程池异常: {}", ex.getMessage());
            }
        }
        executors.clear();
        log.info("ChannelTopicBootstrap 已成功注销所有广播监听器并安全释放线程池资源");
    }
}
