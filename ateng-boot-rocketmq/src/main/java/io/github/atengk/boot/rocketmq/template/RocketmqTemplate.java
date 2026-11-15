package io.github.atengk.boot.rocketmq.template;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.core.util.BatchSliceUtil;
import io.github.atengk.boot.rocketmq.constant.RocketmqConstants;
import io.github.atengk.boot.rocketmq.helper.RocketmqMessageHelper;
import io.github.atengk.boot.rocketmq.properties.AtengRocketmqProperties;
import io.github.atengk.boot.rocketmq.spi.RocketmqLocalTransactionExecutor;
import io.github.atengk.boot.rocketmq.spi.RocketmqTransactionFailureCallback;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageBuilder;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.RecallReceipt;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.client.apis.producer.Transaction;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * RocketMQ 5.x 生产端高阶增强门面
 *
 * <p>内聚同步发送、异步非阻塞发送、毫秒级延时调度、FIFO 顺序保序投递、本地事务提交感知发信（sendAfterCommit + 补偿 SPI）、
 * 四维上下文自动注入与 UTF-8 String 归一化、双写原生 keys 与 X-Biz-Key、4MB 大报文 Fail-Fast 拦截、4KB 透明 GZIP 压缩与标准结构化宏日志。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class RocketmqTemplate {

    /**
     * 系统级追踪头与协议保留头集合（禁止业务自定义 userProperties 覆写污染）
     */
    private static final Set<String> PROTECTED_HEADER_KEYS = Set.of(
            MessageHeaderConstants.TRACE_ID.toLowerCase(Locale.ROOT),
            MessageHeaderConstants.TENANT_ID.toLowerCase(Locale.ROOT),
            MessageHeaderConstants.USER_ID.toLowerCase(Locale.ROOT),
            MessageHeaderConstants.IGNORE_TENANT.toLowerCase(Locale.ROOT),
            MessageHeaderConstants.SEND_TIMESTAMP.toLowerCase(Locale.ROOT),
            MessageHeaderConstants.BIZ_KEY.toLowerCase(Locale.ROOT),
            RocketmqConstants.HEADER_COMPRESSION.toLowerCase(Locale.ROOT),
            "retry_topic",
            "retry_count",
            "retry_flag",
            "reconnect_after",
            "origin_message_id"
    );

    private final Producer producer;
    private final AtengRocketmqProperties properties;
    private final ObjectProvider<RocketmqTransactionFailureCallback> failureCallbackProvider;
    private final Executor taskExecutor;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();

    public RocketmqTemplate(Producer producer, AtengRocketmqProperties properties) {
        this(producer, properties, (ObjectProvider<RocketmqTransactionFailureCallback>) null, (Executor) null);
    }

    public RocketmqTemplate(
            Producer producer,
            AtengRocketmqProperties properties,
            ObjectProvider<RocketmqTransactionFailureCallback> failureCallbackProvider) {
        this(producer, properties, failureCallbackProvider, (Executor) null);
    }

    public RocketmqTemplate(
            Producer producer,
            AtengRocketmqProperties properties,
            ObjectProvider<RocketmqTransactionFailureCallback> failureCallbackProvider,
            ObjectProvider<Executor> taskExecutorProvider) {
        this.producer = Objects.requireNonNull(producer, "producer 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.failureCallbackProvider = failureCallbackProvider;
        this.taskExecutor = taskExecutorProvider != null ? taskExecutorProvider.getIfAvailable() : null;
    }

    public RocketmqTemplate(
            Producer producer,
            AtengRocketmqProperties properties,
            ObjectProvider<RocketmqTransactionFailureCallback> failureCallbackProvider,
            Executor taskExecutor) {
        this.producer = Objects.requireNonNull(producer, "producer 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.failureCallbackProvider = failureCallbackProvider;
        this.taskExecutor = taskExecutor;
    }

    public RocketmqTemplate(
            Producer producer,
            AtengRocketmqProperties properties,
            RocketmqTransactionFailureCallback failureCallback) {
        this(producer, properties, failureCallback, (Executor) null);
    }

    public RocketmqTemplate(
            Producer producer,
            AtengRocketmqProperties properties,
            RocketmqTransactionFailureCallback failureCallback,
            Executor taskExecutor) {
        this(producer, properties, new ObjectProvider<>() {
            @Override
            public RocketmqTransactionFailureCallback getObject(Object... args) {
                return failureCallback;
            }

            @Override
            public RocketmqTransactionFailureCallback getIfAvailable() {
                return failureCallback;
            }

            @Override
            public RocketmqTransactionFailureCallback getIfUnique() {
                return failureCallback;
            }

            @Override
            public RocketmqTransactionFailureCallback getObject() {
                return failureCallback;
            }
        }, taskExecutor);
    }

    /**
     * 获取底层原生 Producer 逃生门
     *
     * @return 原生 Producer 实例
     */
    public Producer getProducer() {
        return this.producer;
    }

    // =========================================================================
    // 1. 同步普通发送 (send)
    // =========================================================================

    public SendReceipt send(String topic, Object payload) {
        return send(topic, null, null, payload, null);
    }

    public SendReceipt send(String topic, String tag, Object payload) {
        return send(topic, tag, null, payload, null);
    }

    public SendReceipt send(String topic, String tag, String bizKey, Object payload) {
        return send(topic, tag, bizKey, payload, null);
    }

    public SendReceipt send(String topic, String tag, String bizKey, Object payload, Map<String, String> userProperties) {
        String finalBizKey = RocketmqMessageHelper.resolveBizKey(bizKey, payload);
        Message message = buildMessage(topic, tag, finalBizKey, payload, null, null, userProperties);
        return doSendSync(message, topic, tag, finalBizKey);
    }

    // =========================================================================
    // 2. 异步非阻塞发送 (sendAsync)
    // =========================================================================

    public CompletableFuture<SendReceipt> sendAsync(String topic, Object payload) {
        return sendAsync(topic, null, null, payload, null);
    }

    public CompletableFuture<SendReceipt> sendAsync(String topic, String tag, Object payload) {
        return sendAsync(topic, tag, null, payload, null);
    }

    public CompletableFuture<SendReceipt> sendAsync(String topic, String tag, String bizKey, Object payload) {
        return sendAsync(topic, tag, bizKey, payload, null);
    }

    public CompletableFuture<SendReceipt> sendAsync(
            String topic,
            String tag,
            String bizKey,
            Object payload,
            Map<String, String> userProperties) {
        String finalBizKey = RocketmqMessageHelper.resolveBizKey(bizKey, payload);
        Message message = buildMessage(topic, tag, finalBizKey, payload, null, null, userProperties);
        return doSendAsync(message, topic, tag, finalBizKey);
    }

    // =========================================================================
    // 3. 延时与定时发送 (sendDelayed / sendDeliverAt)
    // =========================================================================

    /**
     * 同步发送相对延时消息
     *
     * @param topic   目标主题
     * @param payload 消息载荷
     * @param delay   相对延时时长（不能为 null 或负数）
     * @return 发送回执（含 recallHandle）
     */
    public SendReceipt sendDelayed(String topic, Object payload, Duration delay) {
        return sendDelayed(topic, null, null, payload, delay, null);
    }

    /**
     * 同步发送相对延时消息（指定 Tag）
     *
     * @param topic   目标主题
     * @param tag     消息标签
     * @param payload 消息载荷
     * @param delay   相对延时时长（不能为 null 或负数）
     * @return 发送回执（含 recallHandle）
     */
    public SendReceipt sendDelayed(String topic, String tag, Object payload, Duration delay) {
        return sendDelayed(topic, tag, null, payload, delay, null);
    }

    /**
     * 同步发送相对延时消息（指定 Tag 与业务键）
     *
     * @param topic   目标主题
     * @param tag     消息标签
     * @param bizKey  业务唯一键
     * @param payload 消息载荷
     * @param delay   相对延时时长（不能为 null 或负数）
     * @return 发送回执（含 recallHandle）
     */
    public SendReceipt sendDelayed(String topic, String tag, String bizKey, Object payload, Duration delay) {
        return sendDelayed(topic, tag, bizKey, payload, delay, null);
    }

    /**
     * 同步发送相对延时消息（全参，支持自定义属性透传）
     *
     * @param topic          目标主题
     * @param tag            消息标签
     * @param bizKey         业务唯一键
     * @param payload        消息载荷
     * @param delay          相对延时时长（不能为 null 或负数）
     * @param userProperties 自定义扩展属性（系统/追踪保留头将被安全过滤）
     * @return 发送回执（含 recallHandle）
     */
    public SendReceipt sendDelayed(
            String topic,
            String tag,
            String bizKey,
            Object payload,
            Duration delay,
            Map<String, String> userProperties) {
        Assert.notNull(delay, "delay 不能为空");
        Assert.isTrue(!delay.isNegative(), "delay 延时时长不能为负数");
        return sendDeliverAt(topic, tag, bizKey, payload, Instant.now().plus(delay), userProperties);
    }

    /**
     * 异步发送相对延时消息（免传 Tag 与业务键）
     *
     * @param topic   目标主题
     * @param payload 消息载荷
     * @param delay   相对延时时长（不能为 null 或负数）
     * @return 异步发送回执 Future
     */
    public CompletableFuture<SendReceipt> sendDelayedAsync(String topic, Object payload, Duration delay) {
        return sendDelayedAsync(topic, null, null, payload, delay, null);
    }

    /**
     * 异步发送相对延时消息（指定 Tag，免传业务键）
     *
     * @param topic   目标主题
     * @param tag     消息标签
     * @param payload 消息载荷
     * @param delay   相对延时时长（不能为 null 或负数）
     * @return 异步发送回执 Future
     */
    public CompletableFuture<SendReceipt> sendDelayedAsync(String topic, String tag, Object payload, Duration delay) {
        return sendDelayedAsync(topic, tag, null, payload, delay, null);
    }

    /**
     * 异步发送相对延时消息（指定 Tag 与业务键）
     *
     * @param topic   目标主题
     * @param tag     消息标签
     * @param bizKey  业务唯一键
     * @param payload 消息载荷
     * @param delay   相对延时时长（不能为 null 或负数）
     * @return 异步发送回执 Future
     */
    public CompletableFuture<SendReceipt> sendDelayedAsync(String topic, String tag, String bizKey, Object payload, Duration delay) {
        return sendDelayedAsync(topic, tag, bizKey, payload, delay, null);
    }

    /**
     * 异步发送相对延时消息（全参，支持自定义属性透传）
     *
     * @param topic          目标主题
     * @param tag            消息标签
     * @param bizKey         业务唯一键
     * @param payload        消息载荷
     * @param delay          相对延时时长（不能为 null 或负数）
     * @param userProperties 自定义扩展属性（系统/追踪保留头将被安全过滤）
     * @return 异步发送回执 Future
     */
    public CompletableFuture<SendReceipt> sendDelayedAsync(
            String topic,
            String tag,
            String bizKey,
            Object payload,
            Duration delay,
            Map<String, String> userProperties) {
        Assert.notNull(delay, "delay 不能为空");
        Assert.isTrue(!delay.isNegative(), "delay 延时时长不能为负数");
        return sendDeliverAtAsync(topic, tag, bizKey, payload, Instant.now().plus(delay), userProperties);
    }

    /**
     * 同步发送绝对定时消息
     *
     * @param topic     目标主题
     * @param payload   消息载荷
     * @param deliverAt 目标投递时刻（不能为空）
     * @return 发送回执（含 recallHandle）
     */
    public SendReceipt sendDeliverAt(String topic, Object payload, Instant deliverAt) {
        return sendDeliverAt(topic, null, null, payload, deliverAt, null);
    }

    /**
     * 同步发送绝对定时消息（指定 Tag）
     *
     * @param topic     目标主题
     * @param tag       消息标签
     * @param payload   消息载荷
     * @param deliverAt 目标投递时刻（不能为空）
     * @return 发送回执（含 recallHandle）
     */
    public SendReceipt sendDeliverAt(String topic, String tag, Object payload, Instant deliverAt) {
        return sendDeliverAt(topic, tag, null, payload, deliverAt, null);
    }

    /**
     * 同步发送绝对定时消息（指定 Tag 与业务键）
     *
     * @param topic     目标主题
     * @param tag       消息标签
     * @param bizKey    业务唯一键
     * @param payload   消息载荷
     * @param deliverAt 目标投递时刻（不能为空）
     * @return 发送回执（含 recallHandle）
     */
    public SendReceipt sendDeliverAt(String topic, String tag, String bizKey, Object payload, Instant deliverAt) {
        return sendDeliverAt(topic, tag, bizKey, payload, deliverAt, null);
    }

    /**
     * 同步发送绝对定时消息（全参，支持自定义属性透传）
     *
     * @param topic          目标主题
     * @param tag            消息标签
     * @param bizKey         业务唯一键
     * @param payload        消息载荷
     * @param deliverAt      目标投递时刻（不能为空）
     * @param userProperties 自定义扩展属性（系统/追踪保留头将被安全过滤）
     * @return 发送回执（含 recallHandle）
     */
    public SendReceipt sendDeliverAt(
            String topic,
            String tag,
            String bizKey,
            Object payload,
            Instant deliverAt,
            Map<String, String> userProperties) {
        Assert.notNull(deliverAt, "deliverAt 不能为空");
        long deliveryTimestamp = deliverAt.toEpochMilli();
        String finalBizKey = RocketmqMessageHelper.resolveBizKey(bizKey, payload);
        Message message = buildMessage(topic, tag, finalBizKey, payload, null, deliveryTimestamp, userProperties);
        return doSendSync(message, topic, tag, finalBizKey);
    }

    /**
     * 异步发送绝对定时消息（免传 Tag 与业务键）
     *
     * @param topic     目标主题
     * @param payload   消息载荷
     * @param deliverAt 目标投递时刻（不能为空）
     * @return 异步发送回执 Future
     */
    public CompletableFuture<SendReceipt> sendDeliverAtAsync(String topic, Object payload, Instant deliverAt) {
        return sendDeliverAtAsync(topic, null, null, payload, deliverAt, null);
    }

    /**
     * 异步发送绝对定时消息（指定 Tag，免传业务键）
     *
     * @param topic     目标主题
     * @param tag       消息标签
     * @param payload   消息载荷
     * @param deliverAt 目标投递时刻（不能为空）
     * @return 异步发送回执 Future
     */
    public CompletableFuture<SendReceipt> sendDeliverAtAsync(String topic, String tag, Object payload, Instant deliverAt) {
        return sendDeliverAtAsync(topic, tag, null, payload, deliverAt, null);
    }

    /**
     * 异步发送绝对定时消息（指定 Tag 与业务键）
     *
     * @param topic     目标主题
     * @param tag       消息标签
     * @param bizKey    业务唯一键
     * @param payload   消息载荷
     * @param deliverAt 目标投递时刻（不能为空）
     * @return 异步发送回执 Future
     */
    public CompletableFuture<SendReceipt> sendDeliverAtAsync(String topic, String tag, String bizKey, Object payload, Instant deliverAt) {
        return sendDeliverAtAsync(topic, tag, bizKey, payload, deliverAt, null);
    }

    /**
     * 异步发送绝对定时消息（全参，支持自定义属性透传）
     *
     * @param topic          目标主题
     * @param tag            消息标签
     * @param bizKey         业务唯一键
     * @param payload        消息载荷
     * @param deliverAt      目标投递时刻（不能为空）
     * @param userProperties 自定义扩展属性（系统/追踪保留头将被安全过滤）
     * @return 异步发送回执 Future
     */
    public CompletableFuture<SendReceipt> sendDeliverAtAsync(
            String topic,
            String tag,
            String bizKey,
            Object payload,
            Instant deliverAt,
            Map<String, String> userProperties) {
        Assert.notNull(deliverAt, "deliverAt 不能为空");
        long deliveryTimestamp = deliverAt.toEpochMilli();
        String finalBizKey = RocketmqMessageHelper.resolveBizKey(bizKey, payload);
        Message message = buildMessage(topic, tag, finalBizKey, payload, null, deliveryTimestamp, userProperties);
        return doSendAsync(message, topic, tag, finalBizKey);
    }

    // =========================================================================
    // 4. FIFO 顺序保序发送 (sendOrderly)
    // =========================================================================

    public SendReceipt sendOrderly(String topic, Object payload, String messageGroup) {
        return sendOrderly(topic, null, null, payload, messageGroup);
    }

    public SendReceipt sendOrderly(String topic, String tag, Object payload, String messageGroup) {
        return sendOrderly(topic, tag, null, payload, messageGroup);
    }

    public SendReceipt sendOrderly(String topic, String tag, String bizKey, Object payload, String messageGroup) {
        return sendOrderly(topic, tag, bizKey, payload, messageGroup, null);
    }

    public SendReceipt sendOrderly(
            String topic,
            String tag,
            String bizKey,
            Object payload,
            String messageGroup,
            Map<String, String> userProperties) {
        Assert.hasText(messageGroup, "messageGroup 顺序分组标识不能为空");
        String finalBizKey = RocketmqMessageHelper.resolveBizKey(bizKey, payload);
        Message message = buildMessage(topic, tag, finalBizKey, payload, messageGroup, null, userProperties);
        return doSendSync(message, topic, tag, finalBizKey);
    }

    public CompletableFuture<SendReceipt> sendOrderlyAsync(String topic, String tag, String bizKey, Object payload, String messageGroup) {
        return sendOrderlyAsync(topic, tag, bizKey, payload, messageGroup, null);
    }

    public CompletableFuture<SendReceipt> sendOrderlyAsync(
            String topic,
            String tag,
            String bizKey,
            Object payload,
            String messageGroup,
            Map<String, String> userProperties) {
        Assert.hasText(messageGroup, "messageGroup 顺序分组标识不能为空");
        String finalBizKey = RocketmqMessageHelper.resolveBizKey(bizKey, payload);
        Message message = buildMessage(topic, tag, finalBizKey, payload, messageGroup, null, userProperties);
        return doSendAsync(message, topic, tag, finalBizKey);
    }

    // =========================================================================
    // 5. 本地事务提交感知发信 (sendAfterCommit)
    // =========================================================================

    public void sendAfterCommit(String topic, Object payload) {
        sendAfterCommit(topic, null, null, payload, null);
    }

    public void sendAfterCommit(String topic, String tag, Object payload) {
        sendAfterCommit(topic, tag, null, payload, null);
    }

    public void sendAfterCommit(String topic, String tag, String bizKey, Object payload) {
        sendAfterCommit(topic, tag, bizKey, payload, null);
    }

    public void sendAfterCommit(
            String topic,
            String tag,
            String bizKey,
            Object payload,
            Map<String, String> userProperties) {
        String trimmedTopic = validateAndTrimTopic(topic);
        Assert.notNull(payload, "payload 不能为空");
        String finalBizKey = RocketmqMessageHelper.resolveBizKey(bizKey, payload);
        Message preparedMessage = buildMessage(trimmedTopic, tag, finalBizKey, payload, null, null, userProperties);

        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            registerAfterCommitWithContext(() -> {
                String traceId = MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY);
                sendPreparedAfterCommitItem(preparedMessage, trimmedTopic, tag, finalBizKey, payload, traceId);
            });
        } else {
            doSendSync(preparedMessage, trimmedTopic, tag, finalBizKey);
        }
    }

    private void registerAfterCommitWithContext(Runnable action) {
        AsyncContext.ContextSnapshot capturedSnapshot = AsyncContext.capture();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                AsyncContext.ContextSnapshot previousSnapshot = AsyncContext.capturePrevious();
                try {
                    capturedSnapshot.apply();
                    action.run();
                } finally {
                    previousSnapshot.restore();
                }
            }
        });
    }

    private void sendPreparedAfterCommitItem(
            Message message,
            String topic,
            String tag,
            String bizKey,
            Object payload,
            String traceId) {
        try {
            doSendSync(message, topic, tag, bizKey);
        } catch (Exception ex) {
            log.error("[MQ-TRANSACTION-POST-COMMIT-FAILED] topic={} tag={} bizKey={} traceId={} 本地数据库事务已成功提交，但发信失败！",
                    topic, tag, bizKey, traceId, ex);
            notifyTransactionFailureCallback(topic, tag, bizKey, payload, traceId, ex);
        }
    }

    private void notifyTransactionFailureCallback(
            String topic,
            String tag,
            String bizKey,
            Object payload,
            String traceId,
            Exception ex) {
        if (failureCallbackProvider == null) {
            return;
        }
        RocketmqTransactionFailureCallback callback = failureCallbackProvider.getIfAvailable();
        if (callback == null) {
            return;
        }
        try {
            callback.onFailure(topic, tag, bizKey, payload, traceId, ex);
        } catch (Exception cbEx) {
            log.error("[MQ-TRANSACTION-FAILURE-CALLBACK-ERROR] 回调 RocketmqTransactionFailureCallback 异常: topic={}, tag={}, bizKey={}, traceId={}",
                    topic, tag, bizKey, traceId, cbEx);
        }
    }

    /**
     * 事务提交后批量可靠发送消息（默认无 Tag、无扩展属性）
     *
     * @param topic    目标主题
     * @param payloads 消息载荷列表
     */
    public void sendBatchAfterCommit(String topic, List<?> payloads) {
        sendBatchAfterCommit(topic, null, payloads, null);
    }

    /**
     * 事务提交后批量可靠发送消息（携带 SQL92 自定义扩展属性，免传 Tag）
     *
     * @param topic          目标主题
     * @param payloads       消息载荷列表
     * @param userProperties 自定义扩展属性（系统/追踪保留头将被安全过滤）
     */
    public void sendBatchAfterCommit(String topic, List<?> payloads, Map<String, String> userProperties) {
        sendBatchAfterCommit(topic, null, payloads, userProperties);
    }

    /**
     * 事务提交后批量可靠发送消息（指定 Tag）
     *
     * @param topic    目标主题
     * @param tag      消息标签
     * @param payloads 消息载荷列表
     */
    public void sendBatchAfterCommit(String topic, String tag, List<?> payloads) {
        sendBatchAfterCommit(topic, tag, payloads, null);
    }

    /**
     * 事务提交后批量可靠发送消息（注册期前置两阶段校验并固化四维上下文快照，提交后逐条发送并独立隔离补偿失败消息）
     *
     * @param topic          目标主题
     * @param tag            消息标签
     * @param payloads       消息载荷列表（空集或 null 直接返回且不注册同步器；含 null 元素或超限将同步 Fail-Fast 促使本地事务回滚）
     * @param userProperties 自定义扩展属性（系统/追踪保留头将被安全过滤）
     */
    public void sendBatchAfterCommit(
            String topic,
            String tag,
            List<?> payloads,
            Map<String, String> userProperties) {
        String trimmedTopic = validateAndTrimTopic(topic);
        if (payloads == null || payloads.isEmpty()) {
            return;
        }
        List<PreparedBatchItem> preparedItems = prepareBatchItems(trimmedTopic, tag, payloads, userProperties);

        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            registerAfterCommitWithContext(() -> {
                String traceId = MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY);
                for (PreparedBatchItem item : preparedItems) {
                    sendPreparedAfterCommitItem(item.message(), trimmedTopic, tag, item.bizKey(), item.payload(), traceId);
                }
            });
        } else {
            List<Message> preparedMessages = extractPreparedMessages(preparedItems);
            awaitBatchFuture(trimmedTopic, preparedItems.size(), null,
                    dispatchPreparedBatchAsync(trimmedTopic, tag, preparedMessages));
        }
    }

    // =========================================================================
    // 6. 原生 2PC 分布式事务半消息发送 (sendTransaction)
    // =========================================================================

    public SendReceipt sendTransaction(String topic, Object payload, RocketmqLocalTransactionExecutor executor) {
        return sendTransaction(topic, null, null, payload, null, executor);
    }

    public SendReceipt sendTransaction(String topic, String tag, Object payload, RocketmqLocalTransactionExecutor executor) {
        return sendTransaction(topic, tag, null, payload, null, executor);
    }

    public SendReceipt sendTransaction(String topic, String tag, String bizKey, Object payload, RocketmqLocalTransactionExecutor executor) {
        return sendTransaction(topic, tag, bizKey, payload, null, executor);
    }

    public SendReceipt sendTransaction(
            String topic,
            String tag,
            String bizKey,
            Object payload,
            Map<String, String> userProperties,
            RocketmqLocalTransactionExecutor executor) {
        Assert.notNull(executor, "executor 不能为空");
        String finalBizKey = RocketmqMessageHelper.resolveBizKey(bizKey, payload);
        Message message = buildMessage(topic, tag, finalBizKey, payload, null, null, userProperties);

        long startTime = System.currentTimeMillis();
        String traceId = MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY);
        int bodySize = message.getBody().remaining();

        Transaction transaction;
        try {
            transaction = producer.beginTransaction();
        } catch (Exception ex) {
            throw new MessageDeliveryException(
                    RocketmqConstants.MQ_TYPE,
                    topic,
                    finalBizKey,
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "RocketMQ 开启分布式事务失败: " + ex.getMessage(),
                    ex);
        }

        SendReceipt receipt;
        try {
            receipt = producer.send(message, transaction);
        } catch (Exception ex) {
            try {
                transaction.rollback();
            } catch (Exception rbEx) {
                log.warn("[MQ-TRANSACTION-SEND] 事务半消息投递失败后回滚本地事务句柄异常: {}", rbEx.getMessage());
            }
            throw new MessageDeliveryException(
                    RocketmqConstants.MQ_TYPE,
                    topic,
                    finalBizKey,
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "RocketMQ 事务半消息投递失败: " + ex.getMessage(),
                    ex);
        }

        TransactionResolution resolution;
        try {
            resolution = executor.execute(receipt);
            if (resolution == null) {
                resolution = TransactionResolution.UNKNOWN;
            }
        } catch (Throwable t) {
            try {
                transaction.rollback();
            } catch (Exception rbEx) {
                log.warn("[MQ-TRANSACTION-SEND] 本地事务异常触发回滚失败: topic={}, bizKey={}, error={}", topic, finalBizKey, rbEx.getMessage());
            }
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new MessageDeliveryException(
                    RocketmqConstants.MQ_TYPE,
                    topic,
                    finalBizKey,
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "RocketMQ 本地事务执行失败: " + t.getMessage(),
                    t);
        }

        long cost = System.currentTimeMillis() - startTime;
        if (resolution == TransactionResolution.COMMIT) {
            try {
                transaction.commit();
                log.info("[MQ-TRANSACTION-SEND] mq=rocketmq topic={} tag={} bizKey={} traceId={} size={}B status=COMMITTED cost={}ms messageId={}",
                        topic, tag != null ? tag : "", finalBizKey, traceId != null ? traceId : "",
                        bodySize, cost, receipt.getMessageId());
            } catch (Exception ex) {
                throw new MessageDeliveryException(
                        RocketmqConstants.MQ_TYPE,
                        topic,
                        finalBizKey,
                        CommonResultCode.INTERNAL_SERVER_ERROR,
                        "RocketMQ 事务提交失败: " + ex.getMessage(),
                        ex);
            }
        } else if (resolution == TransactionResolution.ROLLBACK) {
            try {
                transaction.rollback();
                log.info("[MQ-TRANSACTION-SEND] mq=rocketmq topic={} tag={} bizKey={} traceId={} size={}B status=ROLLED_BACK cost={}ms messageId={}",
                        topic, tag != null ? tag : "", finalBizKey, traceId != null ? traceId : "",
                        bodySize, cost, receipt.getMessageId());
            } catch (Exception ex) {
                throw new MessageDeliveryException(
                        RocketmqConstants.MQ_TYPE,
                        topic,
                        finalBizKey,
                        CommonResultCode.INTERNAL_SERVER_ERROR,
                        "RocketMQ 事务回滚失败: " + ex.getMessage(),
                        ex);
            }
        } else {
            log.warn("[MQ-TRANSACTION-SEND] mq=rocketmq topic={} tag={} bizKey={} traceId={} size={}B status=UNKNOWN cost={}ms messageId={} (等待 Broker 回查)",
                    topic, tag != null ? tag : "", finalBizKey, traceId != null ? traceId : "",
                    bodySize, cost, receipt.getMessageId());
        }

        return receipt;
    }

    // =========================================================================
    // 7. 单向极速发送 (sendOneway)
    // =========================================================================

    public void sendOneway(String topic, Object payload) {
        sendOneway(topic, null, null, payload, null);
    }

    public void sendOneway(String topic, String tag, Object payload) {
        sendOneway(topic, tag, null, payload, null);
    }

    public void sendOneway(String topic, String tag, String bizKey, Object payload) {
        sendOneway(topic, tag, bizKey, payload, null);
    }

    public void sendOneway(
            String topic,
            String tag,
            String bizKey,
            Object payload,
            Map<String, String> userProperties) {
        String finalBizKey = RocketmqMessageHelper.resolveBizKey(bizKey, payload);
        Message message = buildMessage(topic, tag, finalBizKey, payload, null, null, userProperties);
        long startTime = System.currentTimeMillis();
        String traceId = MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY);
        int bodySize = message.getBody().remaining();

        producer.sendAsync(message)
                .whenComplete((receipt, ex) -> {
                    long cost = System.currentTimeMillis() - startTime;
                    if (ex != null) {
                        Throwable cause = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
                        log.error("[MQ-ONEWAY-SEND] mq=rocketmq topic={} tag={} bizKey={} traceId={} size={}B status=FAILED cost={}ms error={}",
                                topic, tag != null ? tag : "", finalBizKey, traceId != null ? traceId : "",
                                bodySize, cost, cause.getMessage(), cause);
                    } else {
                        log.debug("[MQ-ONEWAY-SEND] mq=rocketmq topic={} tag={} bizKey={} traceId={} size={}B status=SUCCESS cost={}ms messageId={}",
                                topic, tag != null ? tag : "", finalBizKey, traceId != null ? traceId : "",
                                bodySize, cost, receipt.getMessageId());
                    }
                });
    }

    // =========================================================================
    // 8. 批量可靠发送 (sendBatch / sendBatchAsync)
    // =========================================================================

    /**
     * 批量可靠同步发送消息（空集保底返回空不可变集合）
     *
     * @param topic    目标主题
     * @param payloads 消息载荷列表
     * @return 按原序聚合的不可变发送回执列表
     */
    public List<SendReceipt> sendBatch(String topic, List<?> payloads) {
        return sendBatch(topic, null, payloads, null, null);
    }

    /**
     * 批量可靠同步发送消息（携带自定义扩展属性，免传 Tag）
     *
     * @param topic          目标主题
     * @param payloads       消息载荷列表
     * @param userProperties 自定义扩展属性（系统/追踪保留头将被安全过滤）
     * @return 按原序聚合的不可变发送回执列表
     */
    public List<SendReceipt> sendBatch(String topic, List<?> payloads, Map<String, String> userProperties) {
        return sendBatch(topic, null, payloads, null, userProperties);
    }

    /**
     * 批量可靠同步发送消息（指定 Tag）
     *
     * @param topic    目标主题
     * @param tag      消息标签
     * @param payloads 消息载荷列表
     * @return 按原序聚合的不可变发送回执列表
     */
    public List<SendReceipt> sendBatch(String topic, String tag, List<?> payloads) {
        return sendBatch(topic, tag, payloads, null, null);
    }

    /**
     * 批量可靠同步发送消息（指定 Tag 与自定义扩展属性；若需传 null 消歧请调用 5 参重载）
     *
     * @param topic          目标主题
     * @param tag            消息标签
     * @param payloads       消息载荷列表
     * @param userProperties 自定义扩展属性（系统/追踪保留头将被安全过滤）
     * @return 按原序聚合的不可变发送回执列表
     */
    public List<SendReceipt> sendBatch(String topic, String tag, List<?> payloads, Map<String, String> userProperties) {
        return sendBatch(topic, tag, payloads, null, userProperties);
    }

    /**
     * 批量可靠同步发送消息（指定 Tag 与等待超时时间）
     *
     * @param topic    目标主题
     * @param tag      消息标签
     * @param payloads 消息载荷列表
     * @param timeout  批量聚合等待超时（非正数或 null 时回退使用默认 sendTimeout）
     * @return 按原序聚合的不可变发送回执列表
     */
    public List<SendReceipt> sendBatch(String topic, String tag, List<?> payloads, Duration timeout) {
        return sendBatch(topic, tag, payloads, timeout, null);
    }

    /**
     * 批量可靠同步发送消息（全参两阶段契约：整批前置 Fail-Fast 校验 + {@link BatchSliceUtil} 分片并发汇聚）
     *
     * @param topic          目标主题
     * @param tag            消息标签
     * @param payloads       消息载荷列表（允许空集或 null，此时返回空不可变列表；若含 null 元素或单条压缩后超过 4MB 将零发送 Fail-Fast）
     * @param timeout        批量聚合等待超时（非正数或 null 时回退使用默认 sendTimeout）
     * @param userProperties 自定义扩展属性（系统/追踪保留头将被安全过滤）
     * @return 按原序聚合的不可变发送回执列表
     * @throws MessageDeliveryException 当单包超限、投递等待超时或底层 SDK 发送失败时抛出（完整保留原始 cause）
     */
    public List<SendReceipt> sendBatch(
            String topic,
            String tag,
            List<?> payloads,
            Duration timeout,
            Map<String, String> userProperties) {
        CompletableFuture<List<SendReceipt>> batchFuture = sendBatchAsync(topic, tag, payloads, userProperties);
        if (payloads == null || payloads.isEmpty()) {
            return batchFuture.join();
        }
        return awaitBatchFuture(topic, payloads.size(), timeout, batchFuture);
    }

    private List<SendReceipt> awaitBatchFuture(
            String topic,
            int batchSize,
            Duration timeout,
            CompletableFuture<List<SendReceipt>> batchFuture) {
        Duration effectiveTimeout = (timeout != null && !timeout.isZero() && !timeout.isNegative())
                ? timeout : properties.getProducer().getSendTimeout();

        try {
            return batchFuture.get(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new MessageDeliveryException(
                    RocketmqConstants.MQ_TYPE,
                    topic,
                    null,
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    String.format("RocketMQ 批量发送等待超时 (%s): topic=%s, batchSize=%d", effectiveTimeout, topic, batchSize),
                    ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MessageDeliveryException(
                    RocketmqConstants.MQ_TYPE,
                    topic,
                    null,
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    String.format("RocketMQ 批量发送等待被中断: topic=%s, batchSize=%d", topic, batchSize),
                    ex);
        } catch (Exception ex) {
            Throwable cause = unwrapExecutionCause(ex);
            if (cause instanceof MessageDeliveryException mde) {
                throw mde;
            }
            throw new MessageDeliveryException(
                    RocketmqConstants.MQ_TYPE,
                    topic,
                    null,
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "RocketMQ 批量发送失败: " + cause.getMessage(),
                    cause);
        }
    }

    /**
     * 批量可靠异步发送消息（空集保底返回已完成的空不可变集合 Future）
     *
     * @param topic    目标主题
     * @param payloads 消息载荷列表
     * @return 按原序聚合的不可变发送回执列表 Future
     */
    public CompletableFuture<List<SendReceipt>> sendBatchAsync(String topic, List<?> payloads) {
        return sendBatchAsync(topic, null, payloads, null);
    }

    /**
     * 批量可靠异步发送消息（携带自定义扩展属性，免传 Tag）
     *
     * @param topic          目标主题
     * @param payloads       消息载荷列表
     * @param userProperties 自定义扩展属性（系统/追踪保留头将被安全过滤）
     * @return 按原序聚合的不可变发送回执列表 Future
     */
    public CompletableFuture<List<SendReceipt>> sendBatchAsync(
            String topic,
            List<?> payloads,
            Map<String, String> userProperties) {
        return sendBatchAsync(topic, null, payloads, userProperties);
    }

    /**
     * 批量可靠异步发送消息（指定 Tag）
     *
     * @param topic    目标主题
     * @param tag      消息标签
     * @param payloads 消息载荷列表
     * @return 按原序聚合的不可变发送回执列表 Future
     */
    public CompletableFuture<List<SendReceipt>> sendBatchAsync(String topic, String tag, List<?> payloads) {
        return sendBatchAsync(topic, tag, payloads, null);
    }

    /**
     * 批量可靠异步发送消息（全参两阶段契约：整批前置 Fail-Fast 校验 + {@link BatchSliceUtil} 分片并发汇聚）
     *
     * @param topic          目标主题
     * @param tag            消息标签
     * @param payloads       消息载荷列表（允许空集或 null，此时立即返回空不可变列表；含 null 元素或超限将同步 Fail-Fast 且零底层发送）
     * @param userProperties 自定义扩展属性（系统/追踪保留头将被安全过滤）
     * @return 按原序聚合的不可变发送回执列表 Future（异步异常统一转译为 {@link MessageDeliveryException} 并保留 cause）
     */
    public CompletableFuture<List<SendReceipt>> sendBatchAsync(
            String topic,
            String tag,
            List<?> payloads,
            Map<String, String> userProperties) {
        String trimmedTopic = validateAndTrimTopic(topic);
        if (payloads == null || payloads.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        List<PreparedBatchItem> preparedItems = prepareBatchItems(trimmedTopic, tag, payloads, userProperties);
        List<Message> preparedMessages = extractPreparedMessages(preparedItems);
        return dispatchPreparedBatchAsync(trimmedTopic, tag, preparedMessages);
    }

    private List<PreparedBatchItem> prepareBatchItems(
            String trimmedTopic,
            String tag,
            List<?> payloads,
            Map<String, String> userProperties) {
        int maxBatchSize = properties.getProducer().getMaxBatchSize();
        Assert.isTrue(maxBatchSize > 0, "maxBatchSize 必须大于 0");
        Assert.isTrue(payloads.size() <= maxBatchSize,
                String.format("批量发送消息数量 [%d] 超出配置上限 [%d]", payloads.size(), maxBatchSize));

        List<PreparedBatchItem> preparedItems = new ArrayList<>(payloads.size());
        for (int i = 0; i < payloads.size(); i++) {
            Object payload = payloads.get(i);
            if (payload == null) {
                throw new IllegalArgumentException("批量发送消息列表中存在 null 元素: index=" + i);
            }
            String finalBizKey = RocketmqMessageHelper.resolveBizKey(null, payload);
            Message message = buildMessage(trimmedTopic, tag, finalBizKey, payload, null, null, userProperties);
            preparedItems.add(new PreparedBatchItem(finalBizKey, payload, message));
        }
        return preparedItems;
    }

    private List<Message> extractPreparedMessages(List<PreparedBatchItem> preparedItems) {
        List<Message> messages = new ArrayList<>(preparedItems.size());
        for (PreparedBatchItem item : preparedItems) {
            messages.add(item.message());
        }
        return messages;
    }

    private CompletableFuture<List<SendReceipt>> dispatchPreparedBatchAsync(
            String trimmedTopic,
            String tag,
            List<Message> preparedMessages) {
        int batchSliceSize = properties.getProducer().getBatchSliceSize();
        Assert.isTrue(batchSliceSize > 0, "batchSliceSize 必须大于 0");

        // 2. 第二阶段自动分片并发投递：复用 BatchSliceUtil 按 batchSliceSize 切分并汇聚按序回执（异常统一因果转译）
        long startTime = System.currentTimeMillis();
        List<List<Message>> slices = BatchSliceUtil.slice(preparedMessages, batchSliceSize);
        List<CompletableFuture<List<SendReceipt>>> sliceFutures = new ArrayList<>(slices.size());
        for (List<Message> slice : slices) {
            sliceFutures.add(dispatchBatchSliceAsync(slice));
        }

        return CompletableFuture.allOf(sliceFutures.toArray(new CompletableFuture[0]))
                .handle((ignored, ex) -> {
                    long cost = System.currentTimeMillis() - startTime;
                    if (ex != null) {
                        Throwable cause = unwrapExecutionCause(ex);
                        log.warn("[MQ-BATCH-SEND] mq=rocketmq topic={} tag={} count={} slices={} sliceSize={} status=FAILED cost={}ms error={}",
                                trimmedTopic, tag != null ? tag : "", preparedMessages.size(), slices.size(), batchSliceSize, cost, cause.getMessage());
                        if (cause instanceof MessageDeliveryException mde) {
                            throw mde;
                        }
                        throw new MessageDeliveryException(
                                RocketmqConstants.MQ_TYPE,
                                trimmedTopic,
                                null,
                                CommonResultCode.INTERNAL_SERVER_ERROR,
                                "RocketMQ 批量发送失败: " + cause.getMessage(),
                                cause);
                    }
                    List<SendReceipt> receipts = new ArrayList<>(preparedMessages.size());
                    for (CompletableFuture<List<SendReceipt>> sliceFuture : sliceFutures) {
                        receipts.addAll(sliceFuture.join());
                    }
                    log.info("[MQ-BATCH-SEND] mq=rocketmq topic={} tag={} count={} slices={} sliceSize={} status=SUCCESS cost={}ms",
                            trimmedTopic, tag != null ? tag : "", preparedMessages.size(), slices.size(), batchSliceSize, cost);
                    return Collections.unmodifiableList(receipts);
                });
    }

    private record PreparedBatchItem(String bizKey, Object payload, Message message) {
    }

    private CompletableFuture<List<SendReceipt>> dispatchBatchSliceAsync(List<Message> slice) {
        try {
            if (taskExecutor != null) {
                return CompletableFuture.supplyAsync(() -> sendPreparedSliceAsync(slice), taskExecutor)
                        .thenCompose(Function.identity());
            }
            return sendPreparedSliceAsync(slice);
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    private CompletableFuture<List<SendReceipt>> sendPreparedSliceAsync(List<Message> slice) {
        List<CompletableFuture<SendReceipt>> messageFutures = new ArrayList<>(slice.size());
        for (Message message : slice) {
            messageFutures.add(producer.sendAsync(message));
        }
        return CompletableFuture.allOf(messageFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<SendReceipt> sliceReceipts = new ArrayList<>(messageFutures.size());
                    for (CompletableFuture<SendReceipt> future : messageFutures) {
                        sliceReceipts.add(future.join());
                    }
                    return sliceReceipts;
                });
    }

    private Throwable unwrapExecutionCause(Throwable ex) {
        Throwable cause = ex;
        while ((cause instanceof java.util.concurrent.ExecutionException || cause instanceof CompletionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    // =========================================================================
    // 9. 定时消息精准撤回 (recallMessage / recallMessageAsync)
    // =========================================================================

    public RecallReceipt recallMessage(String topic, String recallHandle) {
        return recallMessage(topic, recallHandle, properties.getProducer().getSendTimeout());
    }

    public RecallReceipt recallMessage(String topic, String recallHandle, Duration timeout) {
        Assert.hasText(topic, "topic 不能为空");
        Assert.hasText(recallHandle, "recallHandle 不能为空");
        Duration effectiveTimeout = (timeout != null && !timeout.isZero() && !timeout.isNegative())
                ? timeout : properties.getProducer().getSendTimeout();

        try {
            return recallMessageAsync(topic, recallHandle).get(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new MessageDeliveryException(
                    RocketmqConstants.MQ_TYPE,
                    topic,
                    recallHandle,
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    String.format("RocketMQ 撤回消息等待超时 (%s): topic=%s, recallHandle=%s", effectiveTimeout, topic, recallHandle),
                    ex);
        } catch (Exception ex) {
            Throwable cause = ex;
            while ((cause instanceof java.util.concurrent.ExecutionException || cause instanceof CompletionException)
                    && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof MessageDeliveryException mde) {
                throw mde;
            }
            throw new MessageDeliveryException(
                    RocketmqConstants.MQ_TYPE,
                    topic,
                    recallHandle,
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "RocketMQ 撤回定时消息失败: " + cause.getMessage(),
                    cause);
        }
    }

    public CompletableFuture<RecallReceipt> recallMessageAsync(String topic, String recallHandle) {
        Assert.hasText(topic, "topic 不能为空");
        Assert.hasText(recallHandle, "recallHandle 不能为空");

        long startTime = System.currentTimeMillis();
        CompletableFuture<RecallReceipt> resultFuture = new CompletableFuture<>();
        producer.recallMessageAsync(topic.trim(), recallHandle.trim())
                .whenComplete((receipt, ex) -> {
                    long cost = System.currentTimeMillis() - startTime;
                    if (ex != null) {
                        Throwable cause = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
                        log.warn("[MQ-RECALL] mq=rocketmq topic={} recallHandle={} status=FAILED cost={}ms error={}",
                                topic, recallHandle, cost, cause.getMessage());
                        MessageDeliveryException deliveryException = new MessageDeliveryException(
                                RocketmqConstants.MQ_TYPE,
                                topic,
                                recallHandle,
                                CommonResultCode.INTERNAL_SERVER_ERROR,
                                "RocketMQ 撤回定时消息失败: " + cause.getMessage(),
                                cause);
                        resultFuture.completeExceptionally(deliveryException);
                    } else {
                        log.info("[MQ-RECALL] mq=rocketmq topic={} recallHandle={} status=SUCCESS cost={}ms messageId={}",
                                topic, recallHandle, cost, receipt.getMessageId());
                        resultFuture.complete(receipt);
                    }
                });
        return resultFuture;
    }

    // =========================================================================
    // 内部通用构建与执行方法
    // =========================================================================

    /**
     * 构建符合规范的 RocketMQ 原生消息对象（执行 Topic 防卫、序列化、GZIP 压缩、4MB 拦截与四维上下文安全注入）
     *
     * @param topic             目标主题
     * @param tag               消息标签
     * @param bizKey            业务唯一键（不能为空）
     * @param payload           消息载荷
     * @param messageGroup      顺序分组键
     * @param deliveryTimestamp 绝对定时投递毫秒时间戳
     * @param customProperties  业务自定义扩展属性（系统/追踪保留头将被自动过滤）
     * @return RocketMQ 原生消息对象
     */
    public Message buildMessage(
            String topic,
            String tag,
            String bizKey,
            Object payload,
            String messageGroup,
            Long deliveryTimestamp,
            Map<String, String> customProperties) {
        String trimmedTopic = validateAndTrimTopic(topic);
        Assert.hasText(bizKey, "bizKey 不能为空");

        // 1. 序列化为字节数组并前置拦截原始超限大报文
        byte[] bodyBytes = RocketmqMessageHelper.toPayloadBytes(payload);
        long maxPayloadBytes = properties.getProducer().getMaxPayloadSize().toBytes();
        checkMaxPayloadSize(bodyBytes.length, maxPayloadBytes, trimmedTopic, bizKey);

        // 2. 抓取四维上下文与业务键并按需执行透明 GZIP 压缩
        Map<String, Object> headerMap = new HashMap<>();
        MessageContextPropagator.inject(headerMap, bizKey);

        long compressionThreshold = properties.getProducer().getCompressionThreshold().toBytes();
        if (bodyBytes.length > compressionThreshold) {
            try {
                bodyBytes = RocketmqMessageHelper.compressGzip(bodyBytes);
                headerMap.put(RocketmqConstants.HEADER_COMPRESSION, RocketmqConstants.COMPRESSION_GZIP);
            } catch (IOException e) {
                log.warn("[ateng-boot-rocketmq] 消息透明 GZIP 压缩失败，回退发送原始报文: topic={}, bizKey={}", trimmedTopic, bizKey, e);
            }
        }

        // 3. 二次校验编码压缩后载荷体积上限，确保最终出站字节严格 <= maxPayloadSize
        checkMaxPayloadSize(bodyBytes.length, maxPayloadBytes, trimmedTopic, bizKey);

        // 4. 组装 RocketMQ 原生 Message
        MessageBuilder builder = provider.newMessageBuilder();
        builder.setTopic(trimmedTopic);
        if (StringUtils.hasText(tag)) {
            builder.setTag(tag.trim());
        }
        builder.setKeys(bizKey);
        if (StringUtils.hasText(messageGroup)) {
            builder.setMessageGroup(messageGroup.trim());
        }
        if (deliveryTimestamp != null) {
            builder.setDeliveryTimestamp(deliveryTimestamp);
        }
        builder.setBody(bodyBytes);

        // 5. 先写入业务自定义扩展属性（过滤系统保留与追踪头，严禁污染）
        if (customProperties != null) {
            for (Map.Entry<String, String> entry : customProperties.entrySet()) {
                String key = entry.getKey();
                if (!isProtectedHeader(key) && entry.getValue() != null) {
                    builder.addProperty(key.trim(), entry.getValue());
                }
            }
        }

        // 6. 最后覆写四维上下文及系统权威标识头 (强制 UTF-8 String 归一化)
        for (Map.Entry<String, Object> entry : headerMap.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                builder.addProperty(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }

        return builder.build();
    }

    private String validateAndTrimTopic(String topic) {
        Assert.hasText(topic, "topic 不能为空");
        String trimmedTopic = topic.trim();
        if (!RocketmqConstants.TOPIC_PATTERN.matcher(trimmedTopic).matches()) {
            throw new IllegalArgumentException(String.format(
                    "RocketMQ Topic 名称 [%s] 格式非法，必须符合正则 [%s]，严禁包含点号等特殊字符！",
                    trimmedTopic, RocketmqConstants.TOPIC_REGEX));
        }
        return trimmedTopic;
    }

    private void checkMaxPayloadSize(long actualBytes, long maxPayloadBytes, String topic, String bizKey) {
        if (actualBytes > maxPayloadBytes) {
            throw new MessageDeliveryException(
                    RocketmqConstants.MQ_TYPE,
                    topic,
                    bizKey,
                    CommonResultCode.BAD_REQUEST,
                    String.format("RocketMQ 消息体过大 (%d 字节)，超过配置上限 (%d 字节)", actualBytes, maxPayloadBytes),
                    null);
        }
    }

    private boolean isProtectedHeader(String key) {
        if (!StringUtils.hasText(key)) {
            return true;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("__")
                || normalized.startsWith("%retry%")
                || normalized.startsWith("%dlq%")
                || PROTECTED_HEADER_KEYS.contains(normalized);
    }

    private SendReceipt doSendSync(Message message, String topic, String tag, String bizKey) {
        long startTime = System.currentTimeMillis();
        String traceId = MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY);
        int bodySize = message.getBody().remaining();

        try {
            SendReceipt receipt = producer.send(message);
            long cost = System.currentTimeMillis() - startTime;
            log.info("[MQ-SEND] mq=rocketmq topic={} tag={} bizKey={} traceId={} size={}B status=SUCCESS cost={}ms messageId={}",
                    topic, tag != null ? tag : "", bizKey, traceId != null ? traceId : "",
                    bodySize, cost, receipt.getMessageId());
            return receipt;
        } catch (Exception ex) {
            throw new MessageDeliveryException(
                    RocketmqConstants.MQ_TYPE,
                    topic,
                    bizKey,
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "RocketMQ 消息发送失败: " + ex.getMessage(),
                    ex);
        }
    }

    private CompletableFuture<SendReceipt> doSendAsync(Message message, String topic, String tag, String bizKey) {
        long startTime = System.currentTimeMillis();
        String traceId = MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY);
        int bodySize = message.getBody().remaining();

        CompletableFuture<SendReceipt> resultFuture = new CompletableFuture<>();
        producer.sendAsync(message)
                .whenComplete((receipt, ex) -> {
                    long cost = System.currentTimeMillis() - startTime;
                    if (ex != null) {
                        Throwable cause = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
                        log.warn("[MQ-SEND] mq=rocketmq topic={} tag={} bizKey={} traceId={} size={}B status=FAILED cost={}ms error={}",
                                topic, tag != null ? tag : "", bizKey, traceId != null ? traceId : "",
                                bodySize, cost, cause.getMessage());
                        MessageDeliveryException deliveryException = new MessageDeliveryException(
                                RocketmqConstants.MQ_TYPE,
                                topic,
                                bizKey,
                                CommonResultCode.INTERNAL_SERVER_ERROR,
                                "RocketMQ 异步消息发送失败: " + cause.getMessage(),
                                cause);
                        resultFuture.completeExceptionally(deliveryException);
                    } else {
                        log.info("[MQ-SEND] mq=rocketmq topic={} tag={} bizKey={} traceId={} size={}B status=SUCCESS cost={}ms messageId={}",
                                topic, tag != null ? tag : "", bizKey, traceId != null ? traceId : "",
                                bodySize, cost, receipt.getMessageId());
                        resultFuture.complete(receipt);
                    }
                });
        return resultFuture;
    }
}
