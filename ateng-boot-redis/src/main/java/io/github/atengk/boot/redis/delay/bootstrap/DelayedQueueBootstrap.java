package io.github.atengk.boot.redis.delay.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.ContextCopyingTaskDecorator;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.redis.delay.RedisDelayedQueue;
import io.github.atengk.boot.redis.delay.annotation.DelayedQueueListener;
import io.github.atengk.boot.redis.delay.model.DeadLetterMessage;
import io.github.atengk.boot.redis.delay.spi.DelayedQueueFailureHandler;
import io.github.atengk.boot.redis.delay.spi.LoggingDelayedQueueFailureHandler;
import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RedissonClient;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis 延时队列生命周期容器调度器
 * 负责在 Spring 单例加载完毕后自动扫描 @DelayedQueueListener 注解方法，
 * 并拉起受管守护线程池循环监听与安全异常隔离调度
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Slf4j
public class DelayedQueueBootstrap implements SmartInitializingSingleton, DisposableBean,
        EmbeddedValueResolverAware, ApplicationContextAware {

    public static final String MDC_TRACE_ID_KEY = "traceId";

    private final RedisDelayedQueue redisDelayedQueue;
    private final ObjectMapper objectMapper;
    private final io.github.atengk.boot.redis.properties.RedisProperties redisProperties;
    private final DelayedQueueFailureHandler failureHandler;

    private ApplicationContext applicationContext;
    private StringValueResolver embeddedValueResolver;
    private volatile boolean running = true;
    private final List<ThreadPoolTaskExecutor> executors = new CopyOnWriteArrayList<>();

    public DelayedQueueBootstrap(RedisDelayedQueue redisDelayedQueue,
                                 ObjectMapper objectMapper) {
        this(redisDelayedQueue, objectMapper, null, null);
    }

    public DelayedQueueBootstrap(RedisDelayedQueue redisDelayedQueue,
                                 ObjectMapper objectMapper,
                                 io.github.atengk.boot.redis.properties.RedisProperties redisProperties) {
        this(redisDelayedQueue, objectMapper, redisProperties, null);
    }

    public DelayedQueueBootstrap(RedisDelayedQueue redisDelayedQueue,
                                 ObjectMapper objectMapper,
                                 io.github.atengk.boot.redis.properties.RedisProperties redisProperties,
                                 DelayedQueueFailureHandler failureHandler) {
        this.redisDelayedQueue = Objects.requireNonNull(redisDelayedQueue, "redisDelayedQueue must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : RedisSerializerFactory.createSafeObjectMapper();
        this.redisProperties = redisProperties;
        this.failureHandler = failureHandler != null ? failureHandler : new LoggingDelayedQueueFailureHandler();
    }

    @Deprecated
    public DelayedQueueBootstrap(RedissonClient redissonClient,
                                 RedisDelayedQueue redisDelayedQueue,
                                 ObjectMapper objectMapper,
                                 io.github.atengk.boot.redis.properties.RedisProperties redisProperties) {
        this(redisDelayedQueue, objectMapper, redisProperties);
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

            Map<Method, DelayedQueueListener> annotatedMethods = MethodIntrospector.selectMethods(
                    beanType,
                    (MethodIntrospector.MetadataLookup<DelayedQueueListener>) method ->
                            AnnotatedElementUtils.findMergedAnnotation(method, DelayedQueueListener.class)
            );

            if (CollectionUtils.isEmpty(annotatedMethods)) {
                continue;
            }

            Object bean = applicationContext.getBean(beanName);
            annotatedMethods.forEach((method, listener) -> registerListener(bean, method, listener));
        }
    }

    /**
     * 注册并启动单个延时队列监听器
     */
    protected void registerListener(Object bean, Method method, DelayedQueueListener listener) {
        String queueName = StringUtils.hasText(listener.queue()) ? listener.queue() : listener.value();
        if (embeddedValueResolver != null && StringUtils.hasText(queueName)) {
            queueName = embeddedValueResolver.resolveStringValue(queueName);
        }

        Assert.hasText(queueName, "@DelayedQueueListener 标注的方法必须指定有效的 queue/value 属性");
        int concurrency = Math.max(1, listener.concurrency());

        int parameterCount = method.getParameterCount();
        if (parameterCount > 2) {
            throw new IllegalArgumentException("@DelayedQueueListener 标注的方法最多支持 2 个参数 (payload, queueName)，异常方法: " + method);
        }

        ReflectionUtils.makeAccessible(method);

        // 预激活 Redisson 内部延迟轮询任务
        redisDelayedQueue.getDelayedQueue(queueName);
        RBlockingQueue<Object> blockingQueue = redisDelayedQueue.getBlockingQueue(queueName);

        final String finalQueueName = queueName;
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("redis-delay-queue-" + finalQueueName + "-");
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(0);
        executor.setDaemon(true);
        executor.setTaskDecorator(new ContextCopyingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        executors.add(executor);

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> consumeLoop(bean, method, blockingQueue, finalQueueName));
        }

        log.info("已成功注册 Redis 延时队列消费者 [queue={}, concurrency={}, method={}#{}]",
                finalQueueName, concurrency, bean.getClass().getSimpleName(), method.getName());
    }

    /**
     * 消费者主循环逻辑
     */
    private void consumeLoop(Object bean, Method method, RBlockingQueue<Object> blockingQueue, String queueName) {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // 采用 1 秒超时轮询，保障优雅停机时迅速退出
                Object rawMessage = blockingQueue.poll(1, TimeUnit.SECONDS);
                if (rawMessage != null) {
                    String traceId = IdUtil.fastSimpleUUID();
                    MDC.put(MDC_TRACE_ID_KEY, traceId);
                    try {
                        processMessageWithRetry(bean, method, rawMessage, queueName);
                    } finally {
                        MDC.remove(MDC_TRACE_ID_KEY);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable e) {
                log.error("Redis 延时队列 [{}] 消费发生未捕获异常: {}", queueName, e.getMessage(), e);
                try {
                    // 消费异常防抖休眠 500ms，避免持续异常造成 CPU 空转
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.debug("Redis 延时队列消费者正常退出 [queue={}, thread={}]", queueName, Thread.currentThread().getName());
    }

    /**
     * 带有重试补偿的消息消费执行逻辑
     */
    private void processMessageWithRetry(Object bean, Method method, Object rawMessage, String queueName) {
        int maxRetries = (redisProperties != null && redisProperties.getDelayQueue() != null)
                ? redisProperties.getDelayQueue().getRetryTimes() : 3;
        Duration retryDelay = (redisProperties != null && redisProperties.getDelayQueue() != null)
                ? redisProperties.getDelayQueue().getRetryDelay() : Duration.ofSeconds(3);

        int attempt = 0;
        Throwable lastError = null;
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                invokeListener(bean, method, rawMessage, queueName);
                return;
            } catch (Throwable e) {
                lastError = e instanceof InvocationTargetException ite ? ite.getTargetException() : e;
                attempt++;
                if (attempt <= maxRetries) {
                    log.warn("Redis 延时队列 [{}] 消费异常，准备执行第 {}/{} 次重试: {}", queueName, attempt, maxRetries, lastError.getMessage());
                    try {
                        TimeUnit.MILLISECONDS.sleep(retryDelay.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    handleDeadLetter(rawMessage, queueName, attempt - 1, lastError);
                    return;
                }
            }
        }
    }

    /**
     * 重试耗尽处理死信流转与 SPI 回调
     */
    private void handleDeadLetter(Object rawMessage, String queueName, int retryCount, Throwable cause) {
        DeadLetterMessage<Object> deadLetter = new DeadLetterMessage<>(
                queueName,
                rawMessage,
                retryCount,
                cause != null ? cause.getMessage() : "Unknown error",
                cause != null ? cause.getClass().getName() : "UnknownException",
                LocalDateTime.now()
        );

        boolean dlqEnabled = (redisProperties == null || redisProperties.getDelayQueue() == null)
                || redisProperties.getDelayQueue().isDeadLetterEnabled();
        if (dlqEnabled) {
            try {
                redisDelayedQueue.sendToDeadLetterQueue(queueName, deadLetter);
                log.info("Redis 延时队列 [{}] 消费失败且重试耗尽，已转入死信队列 [{}]",
                        queueName, redisDelayedQueue.resolveDeadLetterQueueName(queueName));
            } catch (Exception e) {
                log.error("Redis 延时队列 [{}] 消息转入死信队列异常: {}", queueName, e.getMessage(), e);
            }
        }

        if (failureHandler != null) {
            try {
                failureHandler.onFailed(deadLetter, cause);
            } catch (Exception e) {
                log.error("Redis 延时队列 [{}] 执行 DelayedQueueFailureHandler 异常: {}", queueName, e.getMessage(), e);
            }
        }
    }

    /**
     * 反射调用监听器方法并完成参数智能映射
     */
    private void invokeListener(Object bean, Method method, Object rawMessage, String queueName) throws Exception {
        int parameterCount = method.getParameterCount();
        if (parameterCount == 0) {
            method.invoke(bean);
            return;
        }

        Object[] args = new Object[parameterCount];
        if (parameterCount == 1) {
            args[0] = convertArgument(rawMessage, method.getParameters()[0]);
        } else {
            Parameter p0 = method.getParameters()[0];
            Parameter p1 = method.getParameters()[1];
            if (p0.getType().equals(String.class) && !p1.getType().equals(String.class)) {
                args[0] = queueName;
                args[1] = convertArgument(rawMessage, p1);
            } else {
                args[0] = convertArgument(rawMessage, p0);
                args[1] = queueName;
            }
        }

        method.invoke(bean, args);
    }

    /**
     * 将原始接收的消息体自适应转换为监听器期望的入参类型
     */
    private Object convertArgument(Object rawMessage, Parameter parameter) {
        if (rawMessage == null) {
            return null;
        }
        Class<?> targetType = parameter.getType();
        if (targetType.isInstance(rawMessage)) {
            return rawMessage;
        }

        if (rawMessage instanceof String jsonStr && targetType != String.class) {
            try {
                return objectMapper.readValue(jsonStr, objectMapper.constructType(parameter.getParameterizedType()));
            } catch (Exception ignored) {
                // 忽略异常，降级通过 convertValue 尝试
            }
        }

        return objectMapper.convertValue(rawMessage, objectMapper.constructType(parameter.getParameterizedType()));
    }

    @Override
    public void destroy() {
        running = false;
        // 平滑销毁受管线程执行器
        for (ThreadPoolTaskExecutor executor : executors) {
            try {
                executor.shutdown();
            } catch (Exception e) {
                log.warn("销毁 Redis 延时队列线程池异常: {}", e.getMessage());
            }
        }
        executors.clear();
    }
}
