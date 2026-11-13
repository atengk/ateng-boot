package io.github.atengk.boot.redis.stream.bootstrap;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.ContextCopyingTaskDecorator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import io.github.atengk.boot.redis.stream.RedisStreamTemplate;
import io.github.atengk.boot.redis.stream.annotation.RedisStreamListener;
import io.github.atengk.boot.redis.stream.model.StreamDeadLetterMessage;
import io.github.atengk.boot.redis.stream.spi.LoggingStreamFailureHandler;
import io.github.atengk.boot.redis.stream.spi.StreamFailureHandler;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.Record;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Redis 5.0+ Stream 容器生命周期调度器
 * 负责扫描 @RedisStreamListener 注解方法、幂等自愈创建 Stream 与消费组、
 * 接入受管多线程并发驱动与 ContextCopyingTaskDecorator 上下文装饰器，
 * 支持自动 ACK、异常重试、死信转储 ({stream}:dlq) 及 StreamFailureHandler 回调
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Slf4j
public class StreamBootstrap implements SmartLifecycle, SmartInitializingSingleton, DisposableBean,
        EmbeddedValueResolverAware, ApplicationContextAware {

    public static final String MDC_TRACE_ID_KEY = "traceId";
    private static final long DEFAULT_CLAIM_CHECK_INTERVAL_MILLIS = 5000L;

    private final RedisStreamTemplate redisStreamTemplate;
    private final ObjectMapper objectMapper;
    private final RedisProperties redisProperties;
    private final StreamFailureHandler failureHandler;
    private final String instanceId;

    private ApplicationContext applicationContext;
    private StringValueResolver embeddedValueResolver;
    private volatile boolean running = true;
    private final List<ThreadPoolTaskExecutor> executors = new CopyOnWriteArrayList<>();

    public StreamBootstrap(RedisStreamTemplate redisStreamTemplate, ObjectMapper objectMapper) {
        this(redisStreamTemplate, objectMapper, null, null);
    }

    public StreamBootstrap(RedisStreamTemplate redisStreamTemplate,
                           ObjectMapper objectMapper,
                           RedisProperties redisProperties) {
        this(redisStreamTemplate, objectMapper, redisProperties, null);
    }

    public StreamBootstrap(RedisStreamTemplate redisStreamTemplate,
                           ObjectMapper objectMapper,
                           RedisProperties redisProperties,
                           StreamFailureHandler failureHandler) {
        this.redisStreamTemplate = Objects.requireNonNull(redisStreamTemplate, "redisStreamTemplate must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : RedisSerializerFactory.createSafeObjectMapper();
        this.redisProperties = redisProperties;
        this.failureHandler = failureHandler != null ? failureHandler : new LoggingStreamFailureHandler();

        String configuredInstanceId = (redisProperties != null && redisProperties.getStream() != null)
                ? redisProperties.getStream().getInstanceId()
                : null;
        this.instanceId = StringUtils.hasText(configuredInstanceId) ? configuredInstanceId.trim() : IdUtil.fastSimpleUUID();
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

        if (redisProperties != null && redisProperties.getStream() != null && !redisProperties.getStream().isEnabled()) {
            log.info("[RedisStream] StreamBootstrap 已通过配置显式禁用，跳过监听器注册");
            return;
        }

        ConfigurableListableBeanFactory beanFactory = (ConfigurableListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
        String[] beanNames = beanFactory.getBeanDefinitionNames();

        for (String beanName : beanNames) {
            if (ScopedProxyUtils.isScopedTarget(beanName)) {
                continue;
            }
            Class<?> targetClass = null;
            try {
                targetClass = AutoProxyUtils.determineTargetClass(beanFactory, beanName);
            } catch (Throwable ignored) {
            }
            if (targetClass == null) {
                targetClass = applicationContext.getType(beanName);
            }
            if (targetClass == null && applicationContext.containsBean(beanName)) {
                targetClass = AopUtils.getTargetClass(applicationContext.getBean(beanName));
            }
            if (targetClass == null) {
                continue;
            }

            Map<Method, RedisStreamListener> annotatedMethods = MethodIntrospector.selectMethods(
                    targetClass,
                    (MethodIntrospector.MetadataLookup<RedisStreamListener>) method ->
                            AnnotatedElementUtils.findMergedAnnotation(method, RedisStreamListener.class)
            );

            if (CollectionUtils.isEmpty(annotatedMethods)) {
                continue;
            }

            Object bean = applicationContext.getBean(beanName);
            for (Map.Entry<Method, RedisStreamListener> entry : annotatedMethods.entrySet()) {
                Method method = entry.getKey();
                RedisStreamListener listener = entry.getValue();
                Method invocableMethod = AopUtils.selectInvocableMethod(method, bean.getClass());
                registerStreamListener(bean, invocableMethod, listener);
            }
        }
    }

    /**
     * 注册并启动单个 @RedisStreamListener 监听器
     */
    private void registerStreamListener(Object bean, Method method, RedisStreamListener listener) {
        String stream = resolveString(listener.stream());
        String group = resolveString(listener.group());

        Assert.hasText(stream, "@RedisStreamListener stream must not be blank");
        Assert.hasText(group, "@RedisStreamListener group must not be blank");

        int concurrency = Math.max(1, listener.concurrency());
        boolean autoAck = listener.autoAck();
        int batchSize = listener.batchSize();
        int retryTimes = listener.retryTimes();

        // 1. 幂等自愈创建 Stream 与消费组 (MKSTREAM)
        redisStreamTemplate.createGroup(stream, group, ReadOffset.from("0"), true);

        // 2. 创建受管线程池，挂载上下文装饰器
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("redis-stream-" + sanitize(stream) + "-" + sanitize(group) + "-");
        executor.setTaskDecorator(new ContextCopyingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        executors.add(executor);

        // 3. 按照 concurrency 并发度拉起工作消费线程
        for (int i = 1; i <= concurrency; i++) {
            final int workerIndex = i;
            executor.execute(() -> runConsumerLoop(bean, method, stream, group, workerIndex, autoAck, batchSize, retryTimes));
        }

        log.info("[RedisStream] 成功装配 Stream 消费组监听器 [stream={}, group={}, concurrency={}, method={}#{}]",
                stream, group, concurrency, bean.getClass().getSimpleName(), method.getName());
    }

    /**
     * 单个工作线程的循环消费主流程
     */
    private void runConsumerLoop(Object bean, Method method, String stream, String group,
                                int workerIndex, boolean autoAck, int batchSize, int maxRetries) {
        String consumerName = instanceId + ":" + workerIndex;
        Consumer consumer = Consumer.from(group, consumerName);

        Duration pollTimeout = (redisProperties != null && redisProperties.getStream() != null)
                ? redisProperties.getStream().getPollTimeout()
                : Duration.ofSeconds(2);
        int effectiveBatchSize = batchSize > 0
                ? batchSize
                : (redisProperties != null && redisProperties.getStream() != null ? redisProperties.getStream().getBatchSize() : 10);
        int effectiveMaxRetries = maxRetries >= 0
                ? maxRetries
                : (redisProperties != null && redisProperties.getStream() != null ? redisProperties.getStream().getRetryTimes() : 3);

        String fullStreamKey = redisStreamTemplate.formatStreamKey(stream);

        Duration claimIdleTime = (redisProperties != null && redisProperties.getStream() != null)
                ? redisProperties.getStream().getClaimIdleTime()
                : Duration.ofMinutes(1);
        int claimBatchSize = (redisProperties != null && redisProperties.getStream() != null)
                ? redisProperties.getStream().getClaimBatchSize()
                : 20;
        long lastClaimTime = 0L;

        // 清理启动主线程可能通过 TaskDecorator 复制到长轮询线程的初始上下文
        clearConsumptionContext();
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    // 自动认领自愈 PEL 超时孤儿消息 (XPENDING + XCLAIM)
                    long now = System.currentTimeMillis();
                    if (claimIdleTime != null && !claimIdleTime.isNegative() && !claimIdleTime.isZero()
                            && (now - lastClaimTime > DEFAULT_CLAIM_CHECK_INTERVAL_MILLIS)) {
                        lastClaimTime = now;
                        try {
                            List<MapRecord<String, Object, Object>> claimed = redisStreamTemplate.claimPendingMessages(
                                    stream, group, consumerName, claimIdleTime, claimBatchSize);
                            if (!CollectionUtils.isEmpty(claimed)) {
                                log.info("[RedisStream] 成功自动认领 PEL 孤儿消息: stream={}, group={}, consumer={}, count={}",
                                        stream, group, consumerName, claimed.size());
                                for (MapRecord<String, Object, Object> claimedRecord : claimed) {
                                    processRecord(bean, method, stream, group, consumerName, claimedRecord, autoAck, effectiveMaxRetries);
                                }
                            }
                        } catch (Exception ex) {
                            log.debug("[RedisStream] 自动认领 PEL 孤儿消息跳过: stream={}, group={}, msg={}", stream, group, ex.getMessage());
                        }
                    }

                    StreamReadOptions readOptions = StreamReadOptions.empty()
                            .count(effectiveBatchSize)
                            .block(pollTimeout);

                    StreamOffset<String> streamOffset = StreamOffset.create(fullStreamKey, ReadOffset.lastConsumed());
                    List<MapRecord<String, Object, Object>> records = redisStreamTemplate.read(consumer, readOptions, streamOffset);

                    if (!CollectionUtils.isEmpty(records)) {
                        for (MapRecord<String, Object, Object> record : records) {
                            processRecord(bean, method, stream, group, consumerName, record, autoAck, effectiveMaxRetries);
                        }
                    }
                } catch (Exception ex) {
                    if (!running) {
                        break;
                    }
                    log.warn("[RedisStream] 消费拉取轮询异常: stream={}, group={}, consumer={}, error={}",
                            stream, group, consumerName, ex.getMessage());
                    // 若由于运维清理或 Stream 键被删除导致消费组不存在 (NOGROUP)，执行幂等自愈重新建组
                    if (ex.getMessage() != null && ex.getMessage().contains("NOGROUP")) {
                        try {
                            redisStreamTemplate.createGroup(stream, group, ReadOffset.from("0"), true);
                            log.info("[RedisStream] 检测到 NOGROUP，已触发消费组幂等自愈重建: stream={}, group={}", stream, group);
                        } catch (Exception healEx) {
                            log.debug("[RedisStream] 消费组自愈建组跳过: {}", healEx.getMessage());
                        }
                    }
                    try {
                        TimeUnit.MILLISECONDS.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            clearConsumptionContext();
        }
    }

    /**
     * 单条记录安全处理、自动重试与死信转移
     */
    private void processRecord(Object bean, Method method, String stream, String group,
                              String consumerName, MapRecord<String, Object, Object> record,
                              boolean autoAck, int maxRetries) {
        clearConsumptionContext();
        String recordTraceId = extractTraceId(record);
        MDC.put(MDC_TRACE_ID_KEY, StringUtils.hasText(recordTraceId) ? recordTraceId : IdUtil.fastSimpleUUID());

        // 提取并恢复四维上下文：多租户与操作人身份
        Object rawTenantId = getRecordField(record, RedisStreamTemplate.META_TENANT_ID);
        if (rawTenantId != null && StringUtils.hasText(String.valueOf(rawTenantId))) {
            TenantContextHolder.setTenantId(String.valueOf(rawTenantId));
        }
        Object rawIgnoreTenant = getRecordField(record, RedisStreamTemplate.META_IGNORE_TENANT);
        if (rawIgnoreTenant != null && "true".equalsIgnoreCase(String.valueOf(rawIgnoreTenant))) {
            TenantContextHolder.setIgnoreTenant(true);
        }
        Object rawUser = getRecordField(record, RedisStreamTemplate.META_USER_CTX);
        if (rawUser != null && StringUtils.hasText(String.valueOf(rawUser))) {
            UserContextHolder.set(String.valueOf(rawUser));
        }

        int attempt = 0;
        boolean success = false;
        Throwable lastError = null;

        try {
            while (attempt <= maxRetries && running) {
                try {
                    invokeListenerMethod(bean, method, record);
                    success = true;
                    break;
                } catch (Throwable t) {
                    attempt++;
                    lastError = (t instanceof InvocationTargetException ite) ? ite.getTargetException() : t;
                    log.warn("[RedisStream] 消费消息异常 (重试 {}/{}): stream={}, group={}, id={}, error={}",
                            attempt, maxRetries, stream, group, record.getId(), lastError.getMessage());
                    if (attempt <= maxRetries && running) {
                        try {
                            TimeUnit.MILLISECONDS.sleep(100L * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            if (success) {
                if (autoAck) {
                    redisStreamTemplate.acknowledge(stream, group, record.getId());
                }
            } else if (attempt > maxRetries) {
                // 重试耗尽，转移至死信流并触发 SPI 回调；仅当死信成功入队后才安全 ACK 确认原消息
                try {
                    RecordId dlqRecordId = redisStreamTemplate.transferToDeadLetter(
                            stream, group, consumerName, record, attempt, lastError);
                    log.error("[RedisStream] 消息重试耗尽已转入死信: stream={}, group={}, id={}, dlqId={}",
                            stream, group, record.getId(), dlqRecordId);

                    if (failureHandler != null) {
                        StreamDeadLetterMessage dlm = StreamDeadLetterMessage.builder()
                                .stream(stream)
                                .group(group)
                                .consumer(consumerName)
                                .recordId(record.getId().getValue())
                                .payload(record.getValue())
                                .retryCount(attempt)
                                .failedTime(LocalDateTime.now())
                                .exceptionClass(lastError != null ? lastError.getClass().getName() : "Unknown")
                                .exceptionMessage(lastError != null ? lastError.getMessage() : "Unknown")
                                .build();
                        failureHandler.onFailed(dlm, lastError);
                    }
                } catch (Exception ex) {
                    log.error("[RedisStream] 转移死信队列异常，保留原消息在 PEL 中不执行 ACK: stream={}, id={}", stream, record.getId(), ex);
                }
            }
        } finally {
            clearConsumptionContext();
        }
    }

    /**
     * 完整清理当前消费线程的四维上下文（全量 MDC、租户上下文与操作人上下文）
     */
    private static void clearConsumptionContext() {
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
    }

    /**
     * 反射调用监听器方法并适配入参
     */
    private void invokeListenerMethod(Object bean, Method method, MapRecord<String, Object, Object> record) throws Exception {
        ReflectionUtils.makeAccessible(method);
        Parameter[] parameters = method.getParameters();
        if (parameters == null || parameters.length == 0) {
            method.invoke(bean);
            return;
        }

        Object[] args = new Object[parameters.length];
        Class<?> param0Type = parameters[0].getType();

        // 过滤内部元数据键（__ateng_*），保留 traceId 及业务载荷键
        Map<Object, Object> rawMap = record.getValue();
        Map<Object, Object> cleanMap = new HashMap<>();
        if (rawMap != null) {
            rawMap.forEach((k, v) -> {
                if (k != null) {
                    String keyStr = String.valueOf(k);
                    if (!keyStr.startsWith("__ateng_")) {
                        cleanMap.put(k, v);
                    }
                }
            });
        }

        // 1. 解析参数 0（消息体 / 原生 Record）
        if (Record.class.isAssignableFrom(param0Type)) {
            args[0] = MapRecord.create(record.getStream(), cleanMap).withId(record.getId());
        } else {
            Object rawPayload = cleanMap.containsKey("payload") ? cleanMap.get("payload") : cleanMap;
            if (rawPayload == null) {
                args[0] = null;
            } else if (String.class.equals(param0Type)) {
                args[0] = (rawPayload instanceof String s) ? s : objectMapper.writeValueAsString(rawPayload);
            } else {
                try {
                    JavaType javaType = objectMapper.getTypeFactory().constructType(parameters[0].getParameterizedType());
                    args[0] = objectMapper.convertValue(rawPayload, javaType);
                } catch (Exception ex) {
                    if (param0Type.isInstance(rawPayload)) {
                        args[0] = rawPayload;
                    } else {
                        String json = (rawPayload instanceof String s) ? s : objectMapper.writeValueAsString(rawPayload);
                        args[0] = objectMapper.readValue(json, objectMapper.constructType(parameters[0].getParameterizedType()));
                    }
                }
            }
        }

        // 2. 解析可选参数 1（RecordId / 字符串 ID）
        if (parameters.length >= 2) {
            Class<?> param1Type = parameters[1].getType();
            if (RecordId.class.isAssignableFrom(param1Type)) {
                args[1] = record.getId();
            } else if (String.class.equals(param1Type)) {
                args[1] = record.getId().getValue();
            } else {
                args[1] = cleanMap;
            }
        }

        method.invoke(bean, args);
    }

    private Object getRecordField(MapRecord<String, Object, Object> record, String fieldName) {
        if (record != null && record.getValue() != null) {
            return record.getValue().get(fieldName);
        }
        return null;
    }

    private String extractTraceId(MapRecord<String, Object, Object> record) {
        if (record != null && record.getValue() != null) {
            Object trace = record.getValue().get("traceId");
            if (trace != null) {
                return String.valueOf(trace);
            }
        }
        return null;
    }

    private String resolveString(String value) {
        if (embeddedValueResolver != null && StringUtils.hasText(value)) {
            return embeddedValueResolver.resolveStringValue(value);
        }
        return value;
    }

    private String sanitize(String text) {
        if (!StringUtils.hasText(text)) {
            return "stream";
        }
        return text.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void start() {
        this.running = true;
    }

    @Override
    public void stop() {
        this.running = false;
        destroy();
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        if (callback != null) {
            callback.run();
        }
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }

    @Override
    public void destroy() {
        this.running = false;
        for (ThreadPoolTaskExecutor executor : executors) {
            try {
                executor.shutdown();
            } catch (Exception ex) {
                log.warn("[RedisStream] 关闭 Stream 线程池异常: {}", ex.getMessage());
            }
        }
        executors.clear();
        log.info("[RedisStream] StreamBootstrap 已安全停止所有监听工作线程");
    }

    public List<ThreadPoolTaskExecutor> getExecutors() {
        return executors;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
