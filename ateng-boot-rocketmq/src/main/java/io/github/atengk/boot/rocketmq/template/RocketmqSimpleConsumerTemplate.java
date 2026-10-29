package io.github.atengk.boot.rocketmq.template;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.rocketmq.constant.RocketmqConstants;
import io.github.atengk.boot.rocketmq.consumer.DefaultSimpleConsumerFactory;
import io.github.atengk.boot.rocketmq.consumer.SimpleConsumerFactory;
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
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * RocketMQ 5.x 消费端流式拉取与租约控制高阶门面
 *
 * <p>基于官方 {@link SimpleConsumer} (Pop 机制) 构建，提供批量拉取、消息显式确认 (ACK)、
 * 隐身不可见租约动态续期 (changeInvisibleDuration) 以及高阶推断反序列化消费闭包 (receiveAndExecute)。
 * 按 ConsumerGroup 延迟懒加载并内部线程安全复用，实现 {@link DisposableBean} 在应用停机时统一安全销毁。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
public class RocketmqSimpleConsumerTemplate implements DisposableBean {

    private final AtengRocketmqProperties properties;
    private final ObjectProvider<ClientConfiguration> clientConfigurationProvider;
    private final ClientConfiguration clientConfiguration;
    private final SimpleConsumerFactory simpleConsumerFactory;

    private final Map<String, SimpleConsumer> consumerCache = new ConcurrentHashMap<>();
    private final Map<String, String> messageGroupMap = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private volatile boolean running = true;

    public RocketmqSimpleConsumerTemplate(
            AtengRocketmqProperties properties,
            ClientConfiguration clientConfiguration,
            SimpleConsumerFactory simpleConsumerFactory) {
        this(properties, null, clientConfiguration, simpleConsumerFactory);
    }

    public RocketmqSimpleConsumerTemplate(
            AtengRocketmqProperties properties,
            ObjectProvider<ClientConfiguration> clientConfigurationProvider,
            SimpleConsumerFactory simpleConsumerFactory) {
        this(properties, clientConfigurationProvider, null, simpleConsumerFactory);
    }

    private RocketmqSimpleConsumerTemplate(
            AtengRocketmqProperties properties,
            ObjectProvider<ClientConfiguration> clientConfigurationProvider,
            ClientConfiguration clientConfiguration,
            SimpleConsumerFactory simpleConsumerFactory) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.clientConfigurationProvider = clientConfigurationProvider;
        this.clientConfiguration = clientConfiguration;
        this.simpleConsumerFactory = simpleConsumerFactory != null ? simpleConsumerFactory : new DefaultSimpleConsumerFactory();
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
        Assert.isTrue(maxMessageNum > 0, "maxMessageNum 必须大于 0");
        Assert.notNull(invisibleDuration, "invisibleDuration 不能为空");
        Assert.isTrue(!invisibleDuration.isZero() && !invisibleDuration.isNegative(), "invisibleDuration 必须大于 0");

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
        Assert.isTrue(maxMessageNum > 0, "maxMessageNum 必须大于 0");
        Assert.notNull(invisibleDuration, "invisibleDuration 不能为空");
        Assert.isTrue(!invisibleDuration.isZero() && !invisibleDuration.isNegative(), "invisibleDuration 必须大于 0");

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
                .thenAccept(v -> log.info("[MQ-SIMPLE-ACK] mq=rocketmq messageId={} topic={} group={}",
                        messageView.getMessageId(), messageView.getTopic(), consumerGroup));
    }

    // =========================================================================
    // 4. 隐身不可见租约动态续期 (changeInvisibleDuration)
    // =========================================================================

    public void changeInvisibleDuration(MessageView messageView, Duration invisibleDuration) {
        changeInvisibleDuration(resolveConsumerGroup(messageView), messageView, invisibleDuration);
    }

    public void changeInvisibleDuration(String consumerGroup, MessageView messageView, Duration invisibleDuration) {
        Assert.hasText(consumerGroup, "consumerGroup 不能为空");
        Assert.notNull(messageView, "messageView 不能为空");
        Assert.notNull(invisibleDuration, "invisibleDuration 不能为空");
        Assert.isTrue(!invisibleDuration.isZero() && !invisibleDuration.isNegative(), "invisibleDuration 必须大于 0");

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
        Assert.hasText(consumerGroup, "consumerGroup 不能为空");
        Assert.notNull(messageView, "messageView 不能为空");
        Assert.notNull(invisibleDuration, "invisibleDuration 不能为空");
        Assert.isTrue(!invisibleDuration.isZero() && !invisibleDuration.isNegative(), "invisibleDuration 必须大于 0");

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
        return receiveAndExecute(
                topic,
                consumerGroup,
                "*",
                properties.getSimpleConsumer().getMaxMessageNum(),
                properties.getSimpleConsumer().getInvisibleDuration(),
                targetType,
                (item, view) -> consumer.accept(item));
    }

    public <T> int receiveAndExecute(
            String topic,
            String consumerGroup,
            int maxMessageNum,
            Duration invisibleDuration,
            Class<T> targetType,
            Consumer<T> consumer) {
        return receiveAndExecute(
                topic,
                consumerGroup,
                "*",
                maxMessageNum,
                invisibleDuration,
                targetType,
                (item, view) -> consumer.accept(item));
    }

    public <T> int receiveAndExecute(
            String topic,
            String consumerGroup,
            String tag,
            int maxMessageNum,
            Duration invisibleDuration,
            Class<T> targetType,
            BiConsumer<T, MessageView> biConsumer) {
        Assert.notNull(targetType, "targetType 不能为空");
        Assert.notNull(biConsumer, "consumer 不能为空");

        List<MessageView> messages = receive(topic, consumerGroup, tag, maxMessageNum, invisibleDuration);
        if (messages.isEmpty()) {
            return 0;
        }

        int successCount = 0;
        for (MessageView msg : messages) {
            Map<String, String> msgProps = msg.getProperties() != null ? msg.getProperties() : Collections.emptyMap();
            try (MessageContextPropagator.ContextScope ignored = MessageContextPropagator.activate(msgProps)) {
                T entity = parsePayload(msg, msgProps, targetType);
                biConsumer.accept(entity, msg);
                ack(consumerGroup, msg);
                successCount++;
            }
        }
        return successCount;
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

        synchronized (lock) {
            SimpleConsumer consumer = consumerCache.get(consumerGroup.trim());
            if (consumer == null) {
                Map<String, FilterExpression> subs = new HashMap<>();
                subs.put(topic.trim(), filter);
                Duration awaitDuration = properties.getSimpleConsumer().getAwaitDuration();
                try {
                    consumer = simpleConsumerFactory.createSimpleConsumer(config, consumerGroup.trim(), subs, awaitDuration);
                } catch (Exception ex) {
                    throw new MessageDeliveryException(
                            RocketmqConstants.MQ_TYPE,
                            topic,
                            null,
                            CommonResultCode.INTERNAL_SERVER_ERROR,
                            "初始化 RocketMQ SimpleConsumer 失败: group=" + consumerGroup + ", error=" + ex.getMessage(),
                            ex);
                }
                consumerCache.put(consumerGroup.trim(), consumer);
                log.info("[ateng-boot-rocketmq] 成功初始化并缓存 SimpleConsumer [group={}, initialTopic={}]", consumerGroup, topic);
            } else {
                Map<String, FilterExpression> currentSubs = consumer.getSubscriptionExpressions();
                if (currentSubs == null || !currentSubs.containsKey(topic.trim())) {
                    try {
                        consumer.subscribe(topic.trim(), filter);
                        log.info("[ateng-boot-rocketmq] SimpleConsumer 成功追加订阅 [group={}, topic={}]", consumerGroup, topic);
                    } catch (Exception ex) {
                        throw new MessageDeliveryException(
                                RocketmqConstants.MQ_TYPE,
                                topic,
                                null,
                                CommonResultCode.INTERNAL_SERVER_ERROR,
                                "RocketMQ SimpleConsumer 追加订阅主题失败: topic=" + topic + ", group=" + consumerGroup,
                                ex);
                    }
                }
            }
            return consumer;
        }
    }

    private void trackMessageGroup(String messageId, String consumerGroup) {
        if (messageGroupMap.size() > 10000) {
            messageGroupMap.clear();
        }
        messageGroupMap.put(messageId, consumerGroup.trim());
    }

    private String resolveConsumerGroup(MessageView messageView) {
        Assert.notNull(messageView, "messageView 不能为空");
        String messageId = messageView.getMessageId() != null ? messageView.getMessageId().toString() : null;
        if (messageId != null && messageGroupMap.containsKey(messageId)) {
            return messageGroupMap.get(messageId);
        }
        if (consumerCache.size() == 1) {
            return consumerCache.keySet().iterator().next();
        }
        for (Map.Entry<String, SimpleConsumer> entry : consumerCache.entrySet()) {
            Map<String, FilterExpression> subs = entry.getValue().getSubscriptionExpressions();
            if (subs != null && subs.containsKey(messageView.getTopic())) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("无法自动推断 MessageView (messageId=" + messageId + ", topic=" + messageView.getTopic() + ") 的所属 ConsumerGroup，请显式使用带 consumerGroup 参数的方法");
    }

    @SuppressWarnings("unchecked")
    private <T> T parsePayload(MessageView msg, Map<String, String> properties, Class<T> targetType) {
        if (targetType == null || targetType.equals(MessageView.class)) {
            return (T) msg;
        }

        ByteBuffer byteBuffer = msg.getBody();
        byte[] bodyBytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(bodyBytes);

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
    public void destroy() {
        running = false;
        synchronized (lock) {
            for (Map.Entry<String, SimpleConsumer> entry : consumerCache.entrySet()) {
                try {
                    entry.getValue().close();
                    log.info("[ateng-boot-rocketmq] 成功优雅关闭 SimpleConsumer [group={}]", entry.getKey());
                } catch (Exception ex) {
                    log.warn("[ateng-boot-rocketmq] 优雅关闭 SimpleConsumer [group={}] 异常: {}", entry.getKey(), ex.getMessage());
                }
            }
            consumerCache.clear();
            messageGroupMap.clear();
        }
    }
}
