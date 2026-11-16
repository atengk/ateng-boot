package io.github.atengk.boot.rocketmq.template;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.core.util.AsyncUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.rocketmq.constant.RocketmqConstants;
import io.github.atengk.boot.rocketmq.consumer.DefaultSimpleConsumerFactory;
import io.github.atengk.boot.rocketmq.consumer.SimpleConsumerFactory;
import io.github.atengk.boot.rocketmq.helper.RocketmqLockHelper;
import io.github.atengk.boot.rocketmq.helper.RocketmqMessageHelper;
import io.github.atengk.boot.rocketmq.properties.AtengRocketmqProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * RocketMQ 5.x 消费端流式拉取与租约控制高阶门面
 *
 * <p>基于官方 {@link SimpleConsumer} (Pop 机制) 构建，提供批量拉取、消息显式确认 (ACK)、
 * 隐身不可见租约动态续期 (changeInvisibleDuration) 以及高阶推断反序列化消费闭包 (receiveAndExecute)。
 * 按 ConsumerGroup 延迟懒加载并内部线程安全复用，实现 {@link SmartLifecycle} 与 {@link DisposableBean}
 * 在 Push 监听容器之后、Producer 关闭之前确定性有序排空销毁。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
public class RocketmqSimpleConsumerTemplate implements SmartLifecycle, DisposableBean {

    private final AtengRocketmqProperties properties;
    private final ObjectProvider<ClientConfiguration> clientConfigurationProvider;
    private final ClientConfiguration clientConfiguration;
    private final SimpleConsumerFactory simpleConsumerFactory;
    private final Executor taskExecutor;

    private static final int MAX_TRACKED_MESSAGES = 10000;
    private static final Duration MIN_LEASE_RENEW_INTERVAL = Duration.ofSeconds(1);

    private final Map<String, SimpleConsumer> consumerCache = new ConcurrentHashMap<>();
    private final Map<SubscriptionKey, String> subscriptionFilterCache = new ConcurrentHashMap<>();
    private final Set<LeaseRenewalGuard> activeLeaseWatchdogs = ConcurrentHashMap.newKeySet();
    private final Map<String, String> messageGroupMap = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_TRACKED_MESSAGES;
        }
    };
    private final ReentrantLock consumerLock = new ReentrantLock();
    private final ReentrantLock messageGroupLock = new ReentrantLock();
    private volatile boolean running = true;

    public RocketmqSimpleConsumerTemplate(
            AtengRocketmqProperties properties,
            ClientConfiguration clientConfiguration,
            SimpleConsumerFactory simpleConsumerFactory) {
        this(properties, null, clientConfiguration, simpleConsumerFactory, null);
    }

    public RocketmqSimpleConsumerTemplate(
            AtengRocketmqProperties properties,
            ObjectProvider<ClientConfiguration> clientConfigurationProvider,
            SimpleConsumerFactory simpleConsumerFactory) {
        this(properties, clientConfigurationProvider, null, simpleConsumerFactory, null);
    }

    public RocketmqSimpleConsumerTemplate(
            AtengRocketmqProperties properties,
            ObjectProvider<ClientConfiguration> clientConfigurationProvider,
            SimpleConsumerFactory simpleConsumerFactory,
            ObjectProvider<Executor> taskExecutorProvider) {
        this(
                properties,
                clientConfigurationProvider,
                null,
                simpleConsumerFactory,
                taskExecutorProvider != null ? taskExecutorProvider.getIfAvailable() : null);
    }

    public RocketmqSimpleConsumerTemplate(
            AtengRocketmqProperties properties,
            ClientConfiguration clientConfiguration,
            SimpleConsumerFactory simpleConsumerFactory,
            Executor taskExecutor) {
        this(properties, null, clientConfiguration, simpleConsumerFactory, taskExecutor);
    }

    private RocketmqSimpleConsumerTemplate(
            AtengRocketmqProperties properties,
            ObjectProvider<ClientConfiguration> clientConfigurationProvider,
            ClientConfiguration clientConfiguration,
            SimpleConsumerFactory simpleConsumerFactory,
            Executor taskExecutor) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.clientConfigurationProvider = clientConfigurationProvider;
        this.clientConfiguration = clientConfiguration;
        this.simpleConsumerFactory = simpleConsumerFactory != null ? simpleConsumerFactory : new DefaultSimpleConsumerFactory();
        this.taskExecutor = taskExecutor;
    }

    // =========================================================================
    // 1. 同步拉取消息 (receive)
    // =========================================================================

    public List<MessageView> receive(String topic, String consumerGroup) {
        return receive(
                topic,
                consumerGroup,
                "*",
                properties.getSimpleConsumer().getMaxMessageNum(),
                properties.getSimpleConsumer().getInvisibleDuration());
    }

    public List<MessageView> receive(String topic, String consumerGroup, int maxMessageNum, Duration invisibleDuration) {
        return receive(topic, consumerGroup, "*", maxMessageNum, invisibleDuration);
    }

    public List<MessageView> receive(
            String topic,
            String consumerGroup,
            String tag,
            int maxMessageNum,
            Duration invisibleDuration) {
        validateReceiveParams(maxMessageNum, invisibleDuration);

        SimpleConsumer consumer = getOrCreateConsumer(topic, consumerGroup, tag);
        long startTime = System.currentTimeMillis();
        try {
            List<MessageView> messages = consumer.receive(maxMessageNum, invisibleDuration);
            long cost = System.currentTimeMillis() - startTime;
            if (messages != null && !messages.isEmpty()) {
                for (MessageView mv : messages) {
                    if (mv.getMessageId() != null) {
                        trackMessageGroup(mv.getMessageId().toString(), consumerGroup);
                    }
                }
            }
            log.info("[MQ-SIMPLE-RECEIVE] mq=rocketmq topic={} consumerGroup={} tag={} count={} cost={}ms",
                    topic, consumerGroup, StringUtils.hasText(tag) ? tag : "*", messages != null ? messages.size() : 0, cost);
            return messages != null ? messages : Collections.emptyList();
        } catch (Exception ex) {
            if (ex instanceof MessageDeliveryException mde) {
                throw mde;
            }
            throw new MessageDeliveryException(
                    RocketmqConstants.MQ_TYPE,
                    topic,
                    null,
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "RocketMQ SimpleConsumer 拉取消息失败: " + ex.getMessage(),
                    ex);
        }
    }

    // =========================================================================
    // 2. 异步非阻塞拉取消息 (receiveAsync)
    // =========================================================================

    public CompletableFuture<List<MessageView>> receiveAsync(String topic, String consumerGroup) {
        return receiveAsync(
                topic,
                consumerGroup,
                "*",
                properties.getSimpleConsumer().getMaxMessageNum(),
                properties.getSimpleConsumer().getInvisibleDuration());
    }

    public CompletableFuture<List<MessageView>> receiveAsync(
            String topic,
            String consumerGroup,
            int maxMessageNum,
            Duration invisibleDuration) {
        return receiveAsync(topic, consumerGroup, "*", maxMessageNum, invisibleDuration);
    }

    public CompletableFuture<List<MessageView>> receiveAsync(
            String topic,
            String consumerGroup,
            String tag,
            int maxMessageNum,
            Duration invisibleDuration) {
        validateReceiveParams(maxMessageNum, invisibleDuration);

        long startTime = System.currentTimeMillis();
        SimpleConsumer consumer;
        try {
            consumer = getOrCreateConsumer(topic, consumerGroup, tag);
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(ex);
        }

        return consumer.receiveAsync(maxMessageNum, invisibleDuration)
                .thenApply(messages -> {
                    long cost = System.currentTimeMillis() - startTime;
                    if (messages != null && !messages.isEmpty()) {
                        for (MessageView mv : messages) {
                            if (mv.getMessageId() != null) {
                                trackMessageGroup(mv.getMessageId().toString(), consumerGroup);
                            }
                        }
                    }
                    log.info("[MQ-SIMPLE-RECEIVE] mq=rocketmq topic={} consumerGroup={} tag={} count={} cost={}ms",
                            topic, consumerGroup, StringUtils.hasText(tag) ? tag : "*", messages != null ? messages.size() : 0, cost);
                    return messages != null ? messages : Collections.emptyList();
                });
    }

    // =========================================================================
    // 3. 消息确认 (ack / ackAsync)
    // =========================================================================

    public void ack(MessageView messageView) {
        ack(resolveConsumerGroup(messageView), messageView);
    }

    public void ack(String consumerGroup, MessageView messageView) {
        Assert.hasText(consumerGroup, "consumerGroup 不能为空");
        Assert.notNull(messageView, "messageView 不能为空");

        SimpleConsumer consumer = consumerCache.get(consumerGroup.trim());
        if (consumer == null) {
            throw new IllegalStateException("未找到属于消费者组 [" + consumerGroup + "] 的 SimpleConsumer 实例，请先执行拉取操作或显式初始化");
        }
        try {
            consumer.ack(messageView);
            untrackMessageGroup(messageView);
            log.info("[MQ-SIMPLE-ACK] mq=rocketmq messageId={} topic={} group={}",
                    messageView.getMessageId(), messageView.getTopic(), consumerGroup);
        } catch (Exception ex) {
            throw new MessageDeliveryException(
                    RocketmqConstants.MQ_TYPE,
                    messageView.getTopic(),
                    RocketmqMessageHelper.extractBizKey(messageView),
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "RocketMQ SimpleConsumer 确认消息失败: " + ex.getMessage(),
                    ex);
        }
    }

    public CompletableFuture<Void> ackAsync(MessageView messageView) {
        return ackAsync(resolveConsumerGroup(messageView), messageView);
    }

    public CompletableFuture<Void> ackAsync(String consumerGroup, MessageView messageView) {
        Assert.hasText(consumerGroup, "consumerGroup 不能为空");
        Assert.notNull(messageView, "messageView 不能为空");

        SimpleConsumer consumer = consumerCache.get(consumerGroup.trim());
        if (consumer == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "未找到属于消费者组 [" + consumerGroup + "] 的 SimpleConsumer 实例，请先执行拉取操作或显式初始化"));
        }
        return consumer.ackAsync(messageView)
                .thenAccept(v -> {
                    untrackMessageGroup(messageView);
                    log.info("[MQ-SIMPLE-ACK] mq=rocketmq messageId={} topic={} group={}",
                            messageView.getMessageId(), messageView.getTopic(), consumerGroup);
                });
    }

    // =========================================================================
    // 4. 隐身不可见租约动态续期 (changeInvisibleDuration)
    // =========================================================================

    public void changeInvisibleDuration(MessageView messageView, Duration invisibleDuration) {
        changeInvisibleDuration(resolveConsumerGroup(messageView), messageView, invisibleDuration);
    }

    public void changeInvisibleDuration(String consumerGroup, MessageView messageView, Duration invisibleDuration) {
        validateLeaseParams(consumerGroup, messageView, invisibleDuration);

        SimpleConsumer consumer = consumerCache.get(consumerGroup.trim());
        if (consumer == null) {
            throw new IllegalStateException("未找到属于消费者组 [" + consumerGroup + "] 的 SimpleConsumer 实例，请先执行拉取操作或显式初始化");
        }
        try {
            consumer.changeInvisibleDuration(messageView, invisibleDuration);
            log.info("[MQ-SIMPLE-RENEW] mq=rocketmq messageId={} topic={} group={} duration={}",
                    messageView.getMessageId(), messageView.getTopic(), consumerGroup, invisibleDuration);
        } catch (Exception ex) {
            throw new MessageDeliveryException(
                    RocketmqConstants.MQ_TYPE,
                    messageView.getTopic(),
                    RocketmqMessageHelper.extractBizKey(messageView),
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "RocketMQ SimpleConsumer 续期消息失败: " + ex.getMessage(),
                    ex);
        }
    }

    public CompletableFuture<Void> changeInvisibleDurationAsync(MessageView messageView, Duration invisibleDuration) {
        return changeInvisibleDurationAsync(resolveConsumerGroup(messageView), messageView, invisibleDuration);
    }

    public CompletableFuture<Void> changeInvisibleDurationAsync(String consumerGroup, MessageView messageView, Duration invisibleDuration) {
        validateLeaseParams(consumerGroup, messageView, invisibleDuration);

        SimpleConsumer consumer = consumerCache.get(consumerGroup.trim());
        if (consumer == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "未找到属于消费者组 [" + consumerGroup + "] 的 SimpleConsumer 实例，请先执行拉取操作或显式初始化"));
        }
        return consumer.changeInvisibleDurationAsync(messageView, invisibleDuration)
                .thenAccept(v -> log.info("[MQ-SIMPLE-RENEW] mq=rocketmq messageId={} topic={} group={} duration={}",
                        messageView.getMessageId(), messageView.getTopic(), consumerGroup, invisibleDuration));
    }

    // =========================================================================
    // 5. 高阶函数式消费闭包 (receiveAndExecute)
    // =========================================================================

    public <T> int receiveAndExecute(
            String topic,
            String consumerGroup,
            Class<T> targetType,
            Consumer<T> consumer) {
        return receiveAndExecute(topic, consumerGroup, targetType, consumer, null);
    }

    public <T> int receiveAndExecute(
            String topic,
            String consumerGroup,
            Class<T> targetType,
            Consumer<T> consumer,
            BiConsumer<MessageView, Throwable> failureHandler) {
        Assert.notNull(consumer, "consumer 不能为空");
        return receiveAndExecute(
                topic,
                consumerGroup,
                "*",
                properties.getSimpleConsumer().getMaxMessageNum(),
                properties.getSimpleConsumer().getInvisibleDuration(),
                targetType,
                (item, view) -> consumer.accept(item),
                failureHandler);
    }

    public <T> int receiveAndExecute(
            String topic,
            String consumerGroup,
            int maxMessageNum,
            Duration invisibleDuration,
            Class<T> targetType,
            Consumer<T> consumer) {
        Assert.notNull(consumer, "consumer 不能为空");
        return receiveAndExecute(
                topic,
                consumerGroup,
                "*",
                maxMessageNum,
                invisibleDuration,
                targetType,
                (item, view) -> consumer.accept(item),
                null);
    }

    public <T> int receiveAndExecute(
            String topic,
            String consumerGroup,
            String tag,
            int maxMessageNum,
            Duration invisibleDuration,
            Class<T> targetType,
            BiConsumer<T, MessageView> biConsumer) {
        return receiveAndExecute(
                topic,
                consumerGroup,
                tag,
                maxMessageNum,
                invisibleDuration,
                targetType,
                biConsumer,
                null);
    }

    /**
     * 高阶流式拉取与单条故障隔离执行闭包（支持受控租约自动续期与单条失败回调）
     *
     * <p>单条消息独立激活与清理四维上下文：捕获 {@link IdempotentException} 时视作幂等重放静默执行 ACK；
     * 其他异常跳过 ACK 保留重投资格、记录告警并回调可选 {@code failureHandler}，绝不阻塞同批剩余消息。</p>
     *
     * @param topic             主题名称
     * @param consumerGroup     消费者组
     * @param tag               过滤标签表达式
     * @param maxMessageNum     最大拉取数量
     * @param invisibleDuration 不可见租约时长
     * @param targetType        反序列化目标类型
     * @param biConsumer        业务消费逻辑
     * @param failureHandler    可选单条失败处理器（为 null 时仅记录告警跳过 ACK）
     * @param <T>               载荷类型
     * @return 成功确认出队的消息数量（含幂等静默确认条目）
     */
    public <T> int receiveAndExecute(
            String topic,
            String consumerGroup,
            String tag,
            int maxMessageNum,
            Duration invisibleDuration,
            Class<T> targetType,
            BiConsumer<T, MessageView> biConsumer,
            BiConsumer<MessageView, Throwable> failureHandler) {
        Assert.notNull(targetType, "targetType 不能为空");
        Assert.notNull(biConsumer, "consumer 不能为空");

        List<MessageView> messages = receive(topic, consumerGroup, tag, maxMessageNum, invisibleDuration);
        if (messages.isEmpty()) {
            return 0;
        }

        int settledCount = 0;
        for (MessageView msg : messages) {
            if (processSingleMessage(consumerGroup, msg, invisibleDuration, targetType, biConsumer, failureHandler)) {
                settledCount++;
            }
        }
        return settledCount;
    }

    private <T> boolean processSingleMessage(
            String consumerGroup,
            MessageView msg,
            Duration invisibleDuration,
            Class<T> targetType,
            BiConsumer<T, MessageView> biConsumer,
            BiConsumer<MessageView, Throwable> failureHandler) {
        Map<String, String> msgProps = msg.getProperties() != null ? msg.getProperties() : Collections.emptyMap();
        try (MessageContextPropagator.ContextScope ignored = MessageContextPropagator.activate(msgProps)) {
            boolean shouldAck = false;
            LeaseRenewalGuard leaseGuard = startLeaseRenewalWatchdog(consumerGroup, msg, invisibleDuration);
            try {
                T entity = parsePayload(msg, msgProps, targetType);
                biConsumer.accept(entity, msg);
                shouldAck = true;
            } catch (Throwable ex) {
                if (ex instanceof Error err) {
                    throw err;
                }
                if (isIdempotentException(ex)) {
                    log.warn("[MQ-SIMPLE-IDEMPOTENT-DUPLICATE] mq=rocketmq topic={} group={} messageId={} 命中防重幂等拦截，静默执行 ACK 确认: {}",
                            msg.getTopic(), consumerGroup, msg.getMessageId(), ex.getMessage());
                    shouldAck = true;
                } else {
                    log.warn("[MQ-SIMPLE-CONSUME-FAIL] mq=rocketmq topic={} group={} messageId={} 单条消费处理异常，跳过 ACK 保留重投资格: {}",
                            msg.getTopic(), consumerGroup, msg.getMessageId(), ex.getMessage(), ex);
                    invokeFailureHandlerSafely(failureHandler, msg, ex);
                }
            } finally {
                stopLeaseRenewalWatchdog(leaseGuard);
            }

            if (shouldAck) {
                ack(consumerGroup, msg);
                return true;
            }
            return false;
        } catch (Throwable ackOrContextEx) {
            if (ackOrContextEx instanceof Error err) {
                throw err;
            }
            log.warn("[MQ-SIMPLE-ACK-FAIL] mq=rocketmq topic={} group={} messageId={} 单条确认或上下文收口异常，保留隔离继续后续消息: {}",
                    msg.getTopic(), consumerGroup, msg.getMessageId(), ackOrContextEx.getMessage(), ackOrContextEx);
            invokeFailureHandlerSafely(failureHandler, msg, ackOrContextEx);
            return false;
        }
    }

    // =========================================================================
    // 内部通用方法与生命周期管理
    // =========================================================================

    public SimpleConsumer getOrCreateConsumer(String topic, String consumerGroup, String tag) {
        if (!running) {
            throw new IllegalStateException("RocketmqSimpleConsumerTemplate 已关闭，无法创建新客户端连接");
        }
        Assert.hasText(topic, "topic 不能为空");
        Assert.hasText(consumerGroup, "consumerGroup 不能为空");
        if (!RocketmqConstants.TOPIC_PATTERN.matcher(topic).matches()) {
            throw new IllegalArgumentException(String.format(
                    "RocketMQ Topic 名称 [%s] 格式非法，必须符合正则 [%s]，严禁包含点号等特殊字符！",
                    topic, RocketmqConstants.TOPIC_REGEX));
        }

        ClientConfiguration config = this.clientConfiguration != null
                ? this.clientConfiguration
                : (this.clientConfigurationProvider != null ? this.clientConfigurationProvider.getIfAvailable() : null);
        if (config == null) {
            throw new IllegalStateException("未配置 ClientConfiguration (请检查 ateng.boot.rocketmq.endpoints)");
        }

        FilterExpression filter = (StringUtils.hasText(tag) && !"*".equals(tag.trim()))
                ? new FilterExpression(tag.trim(), FilterExpressionType.TAG)
                : new FilterExpression("*", FilterExpressionType.TAG);
        String normalizedTopic = topic.trim();
        String normalizedGroup = consumerGroup.trim();
        String targetFingerprint = toFilterFingerprint(filter);
        SubscriptionKey subscriptionKey = new SubscriptionKey(normalizedGroup, normalizedTopic);

        return RocketmqLockHelper.withLock(consumerLock, () -> {
            if (!running) {
                throw new IllegalStateException("RocketmqSimpleConsumerTemplate 已关闭，无法创建新客户端连接");
            }
            SimpleConsumer consumer = consumerCache.get(normalizedGroup);
            if (consumer == null) {
                Map<String, FilterExpression> subs = new HashMap<>();
                subs.put(normalizedTopic, filter);
                Duration awaitDuration = properties.getSimpleConsumer().getAwaitDuration();
                try {
                    consumer = simpleConsumerFactory.createSimpleConsumer(config, normalizedGroup, subs, awaitDuration);
                } catch (Exception ex) {
                    throw new MessageDeliveryException(
                            RocketmqConstants.MQ_TYPE,
                            topic,
                            null,
                            CommonResultCode.INTERNAL_SERVER_ERROR,
                            "初始化 RocketMQ SimpleConsumer 失败: group=" + consumerGroup + ", error=" + ex.getMessage(),
                            ex);
                }
                consumerCache.put(normalizedGroup, consumer);
                subscriptionFilterCache.put(subscriptionKey, targetFingerprint);
                log.info("[ateng-boot-rocketmq] 成功初始化并缓存 SimpleConsumer [group={}, initialTopic={}, filter={}]",
                        normalizedGroup, normalizedTopic, targetFingerprint);
            } else {
                String cachedFingerprint = subscriptionFilterCache.get(subscriptionKey);
                boolean needsSubscribe = false;
                if (cachedFingerprint != null) {
                    needsSubscribe = !cachedFingerprint.equals(targetFingerprint);
                } else {
                    Map<String, FilterExpression> currentSubs = consumer.getSubscriptionExpressions();
                    FilterExpression existingFilter = currentSubs != null ? currentSubs.get(normalizedTopic) : null;
                    if (existingFilter == null || !targetFingerprint.equals(toFilterFingerprint(existingFilter))) {
                        needsSubscribe = true;
                    } else {
                        subscriptionFilterCache.put(subscriptionKey, targetFingerprint);
                    }
                }
                if (needsSubscribe) {
                    try {
                        consumer.subscribe(normalizedTopic, filter);
                        subscriptionFilterCache.put(subscriptionKey, targetFingerprint);
                        log.info("[ateng-boot-rocketmq] SimpleConsumer 订阅过滤表达式已刷新 [group={}, topic={}, filter={}]",
                                normalizedGroup, normalizedTopic, targetFingerprint);
                    } catch (Exception ex) {
                        throw new MessageDeliveryException(
                                RocketmqConstants.MQ_TYPE,
                                topic,
                                null,
                                CommonResultCode.INTERNAL_SERVER_ERROR,
                                "RocketMQ SimpleConsumer 订阅或刷新过滤表达式失败: topic=" + topic + ", group=" + consumerGroup,
                                ex);
                    }
                }
            }
            return consumer;
        });
    }

    private void trackMessageGroup(String messageId, String consumerGroup) {
        if (messageId != null && StringUtils.hasText(consumerGroup)) {
            RocketmqLockHelper.runWithLock(messageGroupLock, () -> messageGroupMap.put(messageId, consumerGroup.trim()));
        }
    }

    private void untrackMessageGroup(MessageView messageView) {
        if (messageView != null && messageView.getMessageId() != null) {
            RocketmqLockHelper.runWithLock(messageGroupLock, () -> messageGroupMap.remove(messageView.getMessageId().toString()));
        }
    }

    private String resolveConsumerGroup(MessageView messageView) {
        Assert.notNull(messageView, "messageView 不能为空");
        String messageId = messageView.getMessageId() != null ? messageView.getMessageId().toString() : null;
        if (messageId != null) {
            String mappedGroup = RocketmqLockHelper.withLock(messageGroupLock, () -> messageGroupMap.get(messageId));
            if (StringUtils.hasText(mappedGroup)) {
                return mappedGroup;
            }
        }
        if (consumerCache.size() == 1) {
            return consumerCache.keySet().iterator().next();
        }
        List<String> matchedGroups = new ArrayList<>();
        for (Map.Entry<String, SimpleConsumer> entry : consumerCache.entrySet()) {
            Map<String, FilterExpression> subs = entry.getValue().getSubscriptionExpressions();
            if (subs != null && subs.containsKey(messageView.getTopic())) {
                matchedGroups.add(entry.getKey());
            }
        }
        if (matchedGroups.size() == 1) {
            return matchedGroups.get(0);
        }
        if (matchedGroups.size() > 1) {
            throw new IllegalStateException(String.format(
                    "检测到存在多个消费者组 %s 同时订阅了主题 [%s]，无法自动推断 MessageView (messageId=%s) 所属 ConsumerGroup，请显式使用带 consumerGroup 参数的方法",
                    matchedGroups, messageView.getTopic(), messageId));
        }
        throw new IllegalStateException("无法自动推断 MessageView (messageId=" + messageId + ", topic=" + messageView.getTopic() + ") 的所属 ConsumerGroup，请显式使用带 consumerGroup 参数的方法");
    }

    private static void validateReceiveParams(int maxMessageNum, Duration invisibleDuration) {
        Assert.isTrue(maxMessageNum > 0, "maxMessageNum 必须大于 0");
        Assert.notNull(invisibleDuration, "invisibleDuration 不能为空");
        Assert.isTrue(!invisibleDuration.isZero() && !invisibleDuration.isNegative(), "invisibleDuration 必须大于 0");
    }

    private static void validateLeaseParams(String consumerGroup, MessageView messageView, Duration invisibleDuration) {
        Assert.hasText(consumerGroup, "consumerGroup 不能为空");
        Assert.notNull(messageView, "messageView 不能为空");
        Assert.notNull(invisibleDuration, "invisibleDuration 不能为空");
        Assert.isTrue(!invisibleDuration.isZero() && !invisibleDuration.isNegative(), "invisibleDuration 必须大于 0");
    }

    @SuppressWarnings("unchecked")
    private <T> T parsePayload(MessageView msg, Map<String, String> properties, Class<T> targetType) {
        if (targetType == null || targetType.equals(MessageView.class)) {
            return (T) msg;
        }

        byte[] bodyBytes = RocketmqMessageHelper.readRawBody(msg.getBody());

        String compression = properties.get(RocketmqConstants.HEADER_COMPRESSION);
        if (RocketmqConstants.COMPRESSION_GZIP.equalsIgnoreCase(compression)) {
            try {
                bodyBytes = RocketmqMessageHelper.decompressGzip(bodyBytes);
            } catch (IOException e) {
                throw new MessageDeliveryException(
                        RocketmqConstants.MQ_TYPE,
                        msg.getTopic(),
                        RocketmqMessageHelper.extractBizKey(msg),
                        CommonResultCode.INTERNAL_SERVER_ERROR,
                        "RocketMQ SimpleConsumer 解压消息体失败: " + e.getMessage(),
                        e);
            }
        }

        if (targetType.equals(byte[].class)) {
            return (T) bodyBytes;
        }
        if (targetType.equals(String.class)) {
            return (T) new String(bodyBytes, StandardCharsets.UTF_8);
        }
        return JsonUtil.parseObject(bodyBytes, targetType);
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    /**
     * 相位设在 Push 监听容器 ({@code Integer.MAX_VALUE - 100}) 之后、Producer 关闭之前，确保流式消费者确定性有序停机
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 200;
    }

    @Override
    public void start() {
        RocketmqLockHelper.runWithLock(consumerLock, () -> this.running = true);
    }

    @Override
    public void stop() {
        RocketmqLockHelper.runWithLock(consumerLock, () -> {
            this.running = false;
            stopAllLeaseWatchdogs();
            if (!consumerCache.isEmpty()) {
                for (Map.Entry<String, SimpleConsumer> entry : consumerCache.entrySet()) {
                    try {
                        entry.getValue().close();
                        log.info("[ateng-boot-rocketmq] 成功优雅关闭 SimpleConsumer [group={}]", entry.getKey());
                    } catch (Exception ex) {
                        log.warn("[ateng-boot-rocketmq] 优雅关闭 SimpleConsumer [group={}] 异常: {}", entry.getKey(), ex.getMessage());
                    }
                }
                consumerCache.clear();
            }
            subscriptionFilterCache.clear();
        });
        RocketmqLockHelper.runWithLock(messageGroupLock, messageGroupMap::clear);
    }

    private LeaseRenewalGuard startLeaseRenewalWatchdog(
            String consumerGroup,
            MessageView messageView,
            Duration invisibleDuration) {
        AtengRocketmqProperties.SimpleConsumer simpleProps = properties.getSimpleConsumer();
        if (!running
                || !simpleProps.isAutoRenewLease()
                || simpleProps.getMaxLeaseRenewAttempts() <= 0
                || invisibleDuration == null
                || invisibleDuration.isZero()
                || invisibleDuration.isNegative()) {
            return null;
        }
        Duration interval = resolveLeaseRenewInterval(invisibleDuration);
        Executor dispatchExecutor = this.taskExecutor != null ? this.taskExecutor : AsyncUtil.getExecutor(null);
        LeaseRenewalGuard guard = new LeaseRenewalGuard(
                consumerGroup,
                messageView,
                invisibleDuration,
                interval,
                simpleProps.getMaxLeaseRenewAttempts(),
                dispatchExecutor,
                AsyncContext.capture());
        activeLeaseWatchdogs.add(guard);
        if (!running) {
            stopLeaseRenewalWatchdog(guard);
            return null;
        }
        guard.start();
        return guard;
    }

    private void stopLeaseRenewalWatchdog(LeaseRenewalGuard guard) {
        if (guard == null) {
            return;
        }
        guard.stop();
        activeLeaseWatchdogs.remove(guard);
    }

    private void stopAllLeaseWatchdogs() {
        for (LeaseRenewalGuard guard : activeLeaseWatchdogs) {
            guard.stop();
        }
        activeLeaseWatchdogs.clear();
    }

    private static Duration resolveLeaseRenewInterval(Duration invisibleDuration) {
        Duration half = invisibleDuration.dividedBy(2);
        return half.compareTo(MIN_LEASE_RENEW_INTERVAL) < 0 ? MIN_LEASE_RENEW_INTERVAL : half;
    }

    private static String toFilterFingerprint(FilterExpression filter) {
        return RocketmqMessageHelper.toFilterFingerprint(filter);
    }

    private static void invokeFailureHandlerSafely(
            BiConsumer<MessageView, Throwable> failureHandler,
            MessageView msg,
            Throwable cause) {
        if (failureHandler == null) {
            return;
        }
        try {
            failureHandler.accept(msg, cause);
        } catch (Throwable handlerEx) {
            if (handlerEx instanceof Error err) {
                throw err;
            }
            log.warn("[MQ-SIMPLE-FAILURE-HANDLER-ERROR] 单条消费失败回调执行异常（已隔离避免阻断同批后续消息）: messageId={}, error={}",
                    msg != null ? msg.getMessageId() : null, handlerEx.getMessage(), handlerEx);
        }
    }

    private static boolean isIdempotentException(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 30) {
            if (current instanceof IdempotentException
                    || "io.github.atengk.boot.core.exception.IdempotentException".equals(current.getClass().getName())) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    private record SubscriptionKey(String consumerGroup, String topic) {
    }

    /**
     * 受控不可见租约自动续期看门狗守卫
     */
    private final class LeaseRenewalGuard {

        private final String consumerGroup;
        private final MessageView messageView;
        private final Duration invisibleDuration;
        private final Duration renewInterval;
        private final int maxAttempts;
        private final Executor dispatchExecutor;
        private final AsyncContext.ContextSnapshot contextSnapshot;

        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicInteger attempts = new AtomicInteger(0);
        private final AtomicReference<CompletableFuture<Void>> pendingTask = new AtomicReference<>();

        private LeaseRenewalGuard(
                String consumerGroup,
                MessageView messageView,
                Duration invisibleDuration,
                Duration renewInterval,
                int maxAttempts,
                Executor dispatchExecutor,
                AsyncContext.ContextSnapshot contextSnapshot) {
            this.consumerGroup = consumerGroup;
            this.messageView = messageView;
            this.invisibleDuration = invisibleDuration;
            this.renewInterval = renewInterval;
            this.maxAttempts = maxAttempts;
            this.dispatchExecutor = dispatchExecutor;
            this.contextSnapshot = contextSnapshot;
        }

        private void start() {
            scheduleNext();
        }

        private void scheduleNext() {
            if (cancelled.get() || !running || attempts.get() >= maxAttempts) {
                return;
            }
            Executor delayedExecutor = CompletableFuture.delayedExecutor(
                    renewInterval.toMillis(), TimeUnit.MILLISECONDS, dispatchExecutor);
            CompletableFuture<Void> next = CompletableFuture.runAsync(this::executeRenewalCycle, delayedExecutor);
            pendingTask.set(next);
            if (cancelled.get() || !running) {
                next.cancel(false);
            }
        }

        private void executeRenewalCycle() {
            if (cancelled.get() || !running) {
                return;
            }
            int currentAttempt = attempts.incrementAndGet();
            if (currentAttempt > maxAttempts) {
                return;
            }
            AsyncContext.ContextSnapshot previousSnapshot = AsyncContext.capturePrevious();
            contextSnapshot.apply();
            try {
                if (cancelled.get() || !running) {
                    return;
                }
                changeInvisibleDuration(consumerGroup, messageView, invisibleDuration);
            } catch (Exception ex) {
                log.warn("[MQ-SIMPLE-LEASE-WATCHDOG-FAIL] mq=rocketmq topic={} group={} messageId={} attempt={}/{} 租约自动续期失败: {}",
                        messageView.getTopic(), consumerGroup, messageView.getMessageId(), currentAttempt, maxAttempts, ex.getMessage());
            } finally {
                previousSnapshot.restore();
                if (!cancelled.get() && running && currentAttempt < maxAttempts) {
                    scheduleNext();
                }
            }
        }

        private void stop() {
            if (cancelled.compareAndSet(false, true)) {
                CompletableFuture<Void> current = pendingTask.getAndSet(null);
                if (current != null) {
                    current.cancel(false);
                }
            }
        }
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        if (callback != null) {
            callback.run();
        }
    }

    @Override
    public void destroy() {
        stop();
    }
}
