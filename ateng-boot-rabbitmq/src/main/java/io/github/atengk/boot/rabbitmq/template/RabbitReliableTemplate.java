package io.github.atengk.boot.rabbitmq.template;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.rabbitmq.callback.RabbitTransactionFailureCallback;
import io.github.atengk.boot.rabbitmq.properties.RabbitmqProperties;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.amqp.support.postprocessor.GZipPostProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RabbitMQ 生产级高可靠投递与事务感知门面模版
 * 内聚 Publisher Confirm 可靠等待、四维上下文注入、三级 bizKey 兜底、大报文防爆、透明 GZIP 压缩与本地事务防幽灵发信
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class RabbitReliableTemplate {

    private static final String MQ_TYPE_RABBITMQ = "rabbitmq";
    public static final int HARD_MAX_BATCH_SIZE = 1000;

    private final RabbitTemplate rabbitTemplate;
    private final RabbitmqProperties properties;
    private final ObjectProvider<RabbitTransactionFailureCallback> transactionFailureCallbackProvider;
    private final GZipPostProcessor gzipPostProcessor = new GZipPostProcessor();

    public RabbitReliableTemplate(RabbitTemplate rabbitTemplate, RabbitmqProperties properties) {
        this(rabbitTemplate, properties, (ObjectProvider<RabbitTransactionFailureCallback>) null);
    }

    public RabbitReliableTemplate(
            RabbitTemplate rabbitTemplate,
            RabbitmqProperties properties,
            ObjectProvider<RabbitTransactionFailureCallback> transactionFailureCallbackProvider) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.transactionFailureCallbackProvider = transactionFailureCallbackProvider;
    }

    public RabbitReliableTemplate(
            RabbitTemplate rabbitTemplate,
            RabbitmqProperties properties,
            RabbitTransactionFailureCallback transactionFailureCallback) {
        this(rabbitTemplate, properties, new ObjectProvider<>() {
            @Override
            public RabbitTransactionFailureCallback getObject(Object... args) {
                return transactionFailureCallback;
            }

            @Override
            public RabbitTransactionFailureCallback getIfAvailable() {
                return transactionFailureCallback;
            }

            @Override
            public RabbitTransactionFailureCallback getIfUnique() {
                return transactionFailureCallback;
            }

            @Override
            public RabbitTransactionFailureCallback getObject() {
                return transactionFailureCallback;
            }
        });
    }

    /**
     * 获取底层原生 RabbitTemplate 逃生门
     *
     * @return 原生 RabbitTemplate 实例
     */
    public RabbitTemplate getRabbitTemplate() {
        return this.rabbitTemplate;
    }

    // =========================================================================
    // 1. 同步可靠发送 (sendReliable)
    // =========================================================================

    /**
     * 同步可靠发送消息至指定交换机，使用默认确认超时时间与自适应 bizKey
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @throws MessageDeliveryException 发送被拒、超时或底层异常时抛出
     */
    public void sendReliable(String exchange, String routingKey, Object payload) {
        sendReliable(exchange, routingKey, payload, null, properties.getReliable().getDefaultTimeout());
    }

    /**
     * 同步可靠发送消息至指定交换机，携带业务唯一标识 bizKey，使用默认确认超时时间
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param bizKey     业务唯一标识
     * @throws MessageDeliveryException 发送被拒、超时或底层异常时抛出
     */
    public void sendReliable(String exchange, String routingKey, Object payload, String bizKey) {
        sendReliable(exchange, routingKey, payload, bizKey, properties.getReliable().getDefaultTimeout());
    }

    /**
     * 同步可靠发送消息至指定交换机，显式指定确认超时时间
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param timeout    等待 Broker ACK 的超时时间
     * @throws MessageDeliveryException 发送被拒、超时或底层异常时抛出
     */
    public void sendReliable(String exchange, String routingKey, Object payload, Duration timeout) {
        sendReliable(exchange, routingKey, payload, null, timeout);
    }

    /**
     * 同步可靠发送消息至指定交换机，携带业务唯一标识 bizKey 与显式确认超时时间
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param bizKey     业务唯一标识
     * @param timeout    等待 Broker ACK 的超时时间
     * @throws MessageDeliveryException 发送被拒、超时或底层异常时抛出
     */
    public void sendReliable(String exchange, String routingKey, Object payload, String bizKey, Duration timeout) {
        sendReliable(exchange, routingKey, payload, bizKey, timeout, (MessagePostProcessor[]) null);
    }

    /**
     * 同步可靠发送消息至指定交换机，挂载自定义 MessagePostProcessor 处理器
     *
     * @param exchange    目标交换机
     * @param routingKey  目标路由键
     * @param payload     消息载荷对象
     * @param customizers 消息属性后置定制器 (如优先级、扩展消息头)
     * @throws MessageDeliveryException 发送被拒、超时或底层异常时抛出
     */
    public void sendReliable(String exchange, String routingKey, Object payload, MessagePostProcessor... customizers) {
        sendReliable(exchange, routingKey, payload, null, properties.getReliable().getDefaultTimeout(), customizers);
    }

    /**
     * 同步可靠发送消息至指定交换机，携带业务唯一标识 bizKey 并挂载自定义 MessagePostProcessor 处理器
     *
     * @param exchange    目标交换机
     * @param routingKey  目标路由键
     * @param payload     消息载荷对象
     * @param bizKey      业务唯一标识
     * @param customizers 消息属性后置定制器 (如优先级、扩展消息头)
     * @throws MessageDeliveryException 发送被拒、超时或底层异常时抛出
     */
    public void sendReliable(String exchange, String routingKey, Object payload, String bizKey, MessagePostProcessor... customizers) {
        sendReliable(exchange, routingKey, payload, bizKey, properties.getReliable().getDefaultTimeout(), customizers);
    }

    /**
     * 同步可靠发送消息至指定交换机，携带显式确认超时时间并挂载自定义 MessagePostProcessor 处理器
     *
     * @param exchange    目标交换机
     * @param routingKey  目标路由键
     * @param payload     消息载荷对象
     * @param timeout     等待 Broker ACK 的超时时间
     * @param customizers 消息属性后置定制器 (如优先级、扩展消息头)
     * @throws MessageDeliveryException 发送被拒、超时或底层异常时抛出
     */
    public void sendReliable(String exchange, String routingKey, Object payload, Duration timeout, MessagePostProcessor... customizers) {
        sendReliable(exchange, routingKey, payload, null, timeout, customizers);
    }

    /**
     * 同步可靠发送消息至指定交换机，携带业务唯一标识 bizKey、显式确认超时时间并挂载自定义 MessagePostProcessor 处理器
     *
     * @param exchange    目标交换机
     * @param routingKey  目标路由键
     * @param payload     消息载荷对象
     * @param bizKey      业务唯一标识
     * @param timeout     等待 Broker ACK 的超时时间
     * @param customizers 消息属性后置定制器 (如优先级、扩展消息头)
     * @throws MessageDeliveryException 发送被拒、超时或底层异常时抛出
     */
    public void sendReliable(String exchange, String routingKey, Object payload, String bizKey, Duration timeout, MessagePostProcessor... customizers) {
        Assert.hasText(exchange, "exchange 交换机名称不能为空");
        String effectiveRoutingKey = routingKey != null ? routingKey : "";
        Assert.notNull(payload, "payload 消息载荷不能为空");

        MessageProperties messageProperties = new MessageProperties();
        Message message = prepareMessage(exchange, payload, messageProperties, bizKey, customizers);
        String effectiveBizKey = MessageContextPropagator.extractString(message.getMessageProperties().getHeaders(), MessageHeaderConstants.BIZ_KEY);
        Duration effectiveTimeout = timeout != null ? timeout : properties.getReliable().getDefaultTimeout();

        doSendReliable(exchange, effectiveRoutingKey, message, payload, effectiveBizKey, effectiveTimeout);
    }

    /**
     * 同步可靠发送优先级消息至指定交换机，使用默认确认超时时间与自适应 bizKey
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param priority   消息优先级 (0-255，对应队列 x-max-priority)
     * @throws MessageDeliveryException 发送被拒、超时或底层异常时抛出
     */
    public void sendReliableWithPriority(String exchange, String routingKey, Object payload, int priority) {
        sendReliableWithPriority(exchange, routingKey, payload, priority, null, properties.getReliable().getDefaultTimeout());
    }

    /**
     * 同步可靠发送优先级消息至指定交换机，携带业务唯一标识 bizKey，使用默认确认超时时间
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param priority   消息优先级 (0-255，对应队列 x-max-priority)
     * @param bizKey     业务唯一标识
     * @throws MessageDeliveryException 发送被拒、超时或底层异常时抛出
     */
    public void sendReliableWithPriority(String exchange, String routingKey, Object payload, int priority, String bizKey) {
        sendReliableWithPriority(exchange, routingKey, payload, priority, bizKey, properties.getReliable().getDefaultTimeout());
    }

    /**
     * 同步可靠发送优先级消息至指定交换机，显式指定确认超时时间
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param priority   消息优先级 (0-255，对应队列 x-max-priority)
     * @param timeout    等待 Broker ACK 的超时时间
     * @throws MessageDeliveryException 发送被拒、超时或底层异常时抛出
     */
    public void sendReliableWithPriority(String exchange, String routingKey, Object payload, int priority, Duration timeout) {
        sendReliableWithPriority(exchange, routingKey, payload, priority, null, timeout);
    }

    /**
     * 同步可靠发送优先级消息至指定交换机，携带业务唯一标识 bizKey 与显式确认超时时间
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param priority   消息优先级 (0-255，对应队列 x-max-priority)
     * @param bizKey     业务唯一标识
     * @param timeout    等待 Broker ACK 的超时时间
     * @throws MessageDeliveryException 发送被拒、超时或底层异常时抛出
     */
    public void sendReliableWithPriority(String exchange, String routingKey, Object payload, int priority, String bizKey, Duration timeout) {
        Assert.isTrue(priority >= 0 && priority <= 255, "priority 消息优先级必须在 0 到 255 之间");
        sendReliable(exchange, routingKey, payload, bizKey, timeout, msg -> {
            msg.getMessageProperties().setPriority(priority);
            return msg;
        });
    }

    // =========================================================================
    // 2. 异步可靠发送 (sendReliableAsync)
    // =========================================================================

    /**
     * 异步可靠发送消息，返回 Publisher Confirm 异步确认 Future
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @return CompletableFuture 确认结果
     */
    public CompletableFuture<CorrelationData.Confirm> sendReliableAsync(String exchange, String routingKey, Object payload) {
        return sendReliableAsync(exchange, routingKey, payload, (String) null);
    }

    /**
     * 异步可靠发送消息，携带业务唯一标识 bizKey，返回 Publisher Confirm 异步确认 Future
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param bizKey     业务唯一标识
     * @return CompletableFuture 确认结果
     */
    public CompletableFuture<CorrelationData.Confirm> sendReliableAsync(String exchange, String routingKey, Object payload, String bizKey) {
        return sendReliableAsync(exchange, routingKey, payload, bizKey, (MessagePostProcessor[]) null);
    }

    /**
     * 异步可靠发送消息，挂载自定义 MessagePostProcessor 处理器，返回 Publisher Confirm 异步确认 Future
     *
     * @param exchange    目标交换机
     * @param routingKey  目标路由键
     * @param payload     消息载荷对象
     * @param customizers 消息属性后置定制器
     * @return CompletableFuture 确认结果
     */
    public CompletableFuture<CorrelationData.Confirm> sendReliableAsync(String exchange, String routingKey, Object payload, MessagePostProcessor... customizers) {
        return sendReliableAsync(exchange, routingKey, payload, null, customizers);
    }

    /**
     * 异步可靠发送消息，携带业务唯一标识 bizKey 并挂载自定义 MessagePostProcessor 处理器，返回 Publisher Confirm 异步确认 Future
     *
     * @param exchange    目标交换机
     * @param routingKey  目标路由键
     * @param payload     消息载荷对象
     * @param bizKey      业务唯一标识
     * @param customizers 消息属性后置定制器
     * @return CompletableFuture 确认结果
     */
    public CompletableFuture<CorrelationData.Confirm> sendReliableAsync(String exchange, String routingKey, Object payload, String bizKey, MessagePostProcessor... customizers) {
        Assert.hasText(exchange, "exchange 交换机名称不能为空");
        String effectiveRoutingKey = routingKey != null ? routingKey : "";
        Assert.notNull(payload, "payload 消息载荷不能为空");

        MessageProperties messageProperties = new MessageProperties();
        Message message = prepareMessage(exchange, payload, messageProperties, bizKey, customizers);
        String effectiveBizKey = MessageContextPropagator.extractString(message.getMessageProperties().getHeaders(), MessageHeaderConstants.BIZ_KEY);

        return doSendInternal(exchange, effectiveRoutingKey, message, effectiveBizKey);
    }

    /**
     * 异步可靠发送优先级消息，返回 Publisher Confirm 异步确认 Future
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param priority   消息优先级 (0-255，对应队列 x-max-priority)
     * @return CompletableFuture 确认结果
     */
    public CompletableFuture<CorrelationData.Confirm> sendReliableAsyncWithPriority(String exchange, String routingKey, Object payload, int priority) {
        return sendReliableAsyncWithPriority(exchange, routingKey, payload, priority, null);
    }

    /**
     * 异步可靠发送优先级消息，携带业务唯一标识 bizKey，返回 Publisher Confirm 异步确认 Future
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param priority   消息优先级 (0-255，对应队列 x-max-priority)
     * @param bizKey     业务唯一标识
     * @return CompletableFuture 确认结果
     */
    public CompletableFuture<CorrelationData.Confirm> sendReliableAsyncWithPriority(String exchange, String routingKey, Object payload, int priority, String bizKey) {
        Assert.isTrue(priority >= 0 && priority <= 255, "priority 消息优先级必须在 0 到 255 之间");
        return sendReliableAsync(exchange, routingKey, payload, bizKey, msg -> {
            msg.getMessageProperties().setPriority(priority);
            return msg;
        });
    }

    // =========================================================================
    // 3. 批量可靠发送 (sendBatchReliable & sendBatchReliableAsync)
    // =========================================================================

    /**
     * 批量同步可靠发送消息列表至指定交换机，使用默认确认超时时间
     * 每条消息拥有独立四维上下文快照与独立 CorrelationData，并发异步发送并聚合等待所有 Broker Confirm ACK
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payloads   消息载荷列表 (不允许为 null 或空，单批上限 1000 条，且列表内元素不允许为 null)
     * @throws MessageDeliveryException 任何消息被拒、超时或底层异常时抛出，携带失败条目的索引与业务键
     */
    public void sendBatchReliable(String exchange, String routingKey, List<?> payloads) {
        sendBatchReliable(exchange, routingKey, payloads, properties.getReliable().getDefaultTimeout());
    }

    /**
     * 批量同步可靠发送消息列表至指定交换机，显式指定确认超时时间
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payloads   消息载荷列表 (不允许为 null 或空，单批上限 1000 条，且列表内元素不允许为 null)
     * @param timeout    等待全部消息 Broker ACK 的总超时时间
     * @throws MessageDeliveryException 任何消息被拒、超时或底层异常时抛出，携带失败条目的索引与业务键
     */
    public void sendBatchReliable(String exchange, String routingKey, List<?> payloads, Duration timeout) {
        Assert.hasText(exchange, "exchange 交换机名称不能为空");
        String effectiveRoutingKey = routingKey != null ? routingKey : "";
        Duration effectiveTimeout = timeout != null ? timeout : properties.getReliable().getDefaultTimeout();

        if (Thread.currentThread().isInterrupted()) {
            throw new MessageDeliveryException(
                    MQ_TYPE_RABBITMQ,
                    exchange,
                    "batch[" + (payloads != null ? payloads.size() : 0) + "]",
                    String.format("RabbitMQ 批量消息发送等待 Broker ACK 被中断: exchange=%s, routingKey=%s",
                            exchange, effectiveRoutingKey),
                    new InterruptedException("Current thread was interrupted before waiting for batch confirms"));
        }

        try {
            sendBatchReliableAsync(exchange, routingKey, payloads, effectiveTimeout).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessageDeliveryException(
                    MQ_TYPE_RABBITMQ,
                    exchange,
                    "batch[" + (payloads != null ? payloads.size() : 0) + "]",
                    String.format("RabbitMQ 批量消息发送等待 Broker ACK 被中断: exchange=%s, routingKey=%s",
                            exchange, effectiveRoutingKey),
                    e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof MessageDeliveryException mde) {
                throw mde;
            }
            throw new MessageDeliveryException(
                    MQ_TYPE_RABBITMQ,
                    exchange,
                    "batch[" + (payloads != null ? payloads.size() : 0) + "]",
                    cause.getMessage(),
                    cause);
        }
    }

    /**
     * 批量异步可靠发送消息列表至指定交换机，使用默认确认超时时间，返回 CompletableFuture 聚合确认结果
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payloads   消息载荷列表 (不允许为 null 或空，单批上限 1000 条，且列表内元素不允许为 null)
     * @return CompletableFuture 聚合确认 Future
     */
    public CompletableFuture<Void> sendBatchReliableAsync(String exchange, String routingKey, List<?> payloads) {
        return sendBatchReliableAsync(exchange, routingKey, payloads, properties.getReliable().getDefaultTimeout());
    }

    /**
     * 批量异步可靠发送消息列表至指定交换机，显式指定确认超时时间，返回 CompletableFuture 聚合确认结果
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payloads   消息载荷列表 (不允许为 null 或空，单批上限 1000 条，且列表内元素不允许为 null)
     * @param timeout    等待全部消息 Broker ACK 的总超时时间
     * @return CompletableFuture 聚合确认 Future
     */
    public CompletableFuture<Void> sendBatchReliableAsync(String exchange, String routingKey, List<?> payloads, Duration timeout) {
        Assert.hasText(exchange, "exchange 交换机名称不能为空");
        String effectiveRoutingKey = routingKey != null ? routingKey : "";
        Assert.notNull(payloads, "payloads 消息载荷列表不能为空");
        Assert.notEmpty(payloads, "payloads 消息载荷列表不能为空列表");
        Assert.isTrue(payloads.size() <= HARD_MAX_BATCH_SIZE,
                String.format("payloads 批量投递数量不能超过 %d 条 (当前: %d)", HARD_MAX_BATCH_SIZE, payloads.size()));

        Duration effectiveTimeout = timeout != null ? timeout : properties.getReliable().getDefaultTimeout();
        int size = payloads.size();

        // 1. 前置分层防卫与 Fail-Fast：在发起任何网络请求前逐条序列化与体积校验，保证批次原子防御
        List<PreparedBatchItem> preparedItems = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Object payload = payloads.get(i);
            Assert.notNull(payload, "payloads[" + i + "] 消息载荷不能为 null");
            MessageProperties messageProperties = new MessageProperties();
            Message message = prepareMessage(exchange, payload, messageProperties, null);
            String bizKey = MessageContextPropagator.extractString(message.getMessageProperties().getHeaders(), MessageHeaderConstants.BIZ_KEY);
            preparedItems.add(new PreparedBatchItem(i, message, bizKey));
        }

        // 2. 并发异步发射所有消息并收集 Future
        List<BatchFutureItem> futureItems = new ArrayList<>(size);
        List<CompletableFuture<?>> allFutures = new ArrayList<>(size);

        for (PreparedBatchItem item : preparedItems) {
            CompletableFuture<CorrelationData.Confirm> future = doSendInternal(exchange, effectiveRoutingKey, item.message(), item.bizKey());
            futureItems.add(new BatchFutureItem(item.index(), item.bizKey(), future));
            allFutures.add(future);
        }

        // 3. 异步聚合等待所有消息的确认结果并处理超时与 NACK
        CompletableFuture<Void> resultFuture = new CompletableFuture<>();
        CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0]))
                .orTimeout(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((ignored, ex) -> {
                    if (ex != null) {
                        Throwable rootErr = ex.getCause() != null ? ex.getCause() : ex;
                        if (rootErr instanceof TimeoutException) {
                            List<String> failedDetails = new ArrayList<>();
                            for (BatchFutureItem item : futureItems) {
                                if (!item.future().isDone()) {
                                    failedDetails.add(String.format("index=%d(bizKey=%s, status=TIMEOUT)", item.index(), item.bizKey()));
                                } else if (item.future().isCompletedExceptionally()) {
                                    Throwable itemErr = null;
                                    try {
                                        item.future().getNow(null);
                                    } catch (Throwable t) {
                                        itemErr = t.getCause() != null ? t.getCause() : t;
                                    }
                                    String errMsg = itemErr != null ? itemErr.getMessage() : "unknown";
                                    failedDetails.add(String.format("index=%d(bizKey=%s, status=FAILED, error=%s)", item.index(), item.bizKey(), errMsg));
                                } else {
                                    CorrelationData.Confirm confirm = item.future().getNow(null);
                                    if (confirm != null && !confirm.isAck()) {
                                        failedDetails.add(String.format("index=%d(bizKey=%s, status=NACK, reason=%s)", item.index(), item.bizKey(), confirm.getReason()));
                                    }
                                }
                            }
                            resultFuture.completeExceptionally(new MessageDeliveryException(
                                    MQ_TYPE_RABBITMQ,
                                    exchange,
                                    "batch[" + size + "]",
                                    String.format("RabbitMQ 批量消息发送等待 Broker ACK 超时 (%dms), 失败明细: %s",
                                            effectiveTimeout.toMillis(), failedDetails),
                                    rootErr));
                            return;
                        }
                    }

                    // 4. 精确检查每条消息的 Confirm 结果与异常
                    List<String> failedItems = new ArrayList<>();
                    Throwable firstCause = null;

                    for (BatchFutureItem item : futureItems) {
                        try {
                            CorrelationData.Confirm confirm = item.future().getNow(null);
                            if (confirm == null || !confirm.isAck()) {
                                String reason = confirm != null ? confirm.getReason() : "Broker returned null confirm";
                                failedItems.add(String.format("index=%d(bizKey=%s, reason=%s)", item.index(), item.bizKey(), reason));
                            }
                        } catch (Throwable itemEx) {
                            Throwable cause = itemEx.getCause() != null ? itemEx.getCause() : itemEx;
                            if (firstCause == null) {
                                firstCause = cause;
                            }
                            String detail = cause.getCause() != null
                                    ? cause.getMessage() + " (" + cause.getCause().getMessage() + ")"
                                    : cause.getMessage();
                            failedItems.add(String.format("index=%d(bizKey=%s, error=%s)", item.index(), item.bizKey(), detail));
                        }
                    }

                    if (!failedItems.isEmpty()) {
                        resultFuture.completeExceptionally(new MessageDeliveryException(
                                MQ_TYPE_RABBITMQ,
                                exchange,
                                "batch[" + size + "]",
                                String.format("RabbitMQ 批量消息部分发送被 Broker 拒绝或异常: exchange=%s, routingKey=%s, 失败条数=%d/%d, 失败明细: %s",
                                        exchange, effectiveRoutingKey, failedItems.size(), size, failedItems),
                                firstCause != null ? firstCause : ex));
                    } else {
                        resultFuture.complete(null);
                    }
                });

        return resultFuture;
    }

    // =========================================================================
    // 4. 延迟消息发送 (sendDelayed)
    // =========================================================================

    /**
     * 发送延迟消息至延迟交换机 (基于 rabbitmq-delayed-message-exchange 插件，注入 x-delay 消息头)
     *
     * @param exchange   延迟交换机名称
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param delay      延迟投递时间
     */
    public void sendDelayed(String exchange, String routingKey, Object payload, Duration delay) {
        sendDelayed(exchange, routingKey, payload, null, delay, properties.getReliable().getDefaultTimeout());
    }

    /**
     * 发送延迟消息至延迟交换机，携带业务唯一标识 bizKey
     *
     * @param exchange   延迟交换机名称
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param bizKey     业务唯一标识
     * @param delay      延迟投递时间
     */
    public void sendDelayed(String exchange, String routingKey, Object payload, String bizKey, Duration delay) {
        sendDelayed(exchange, routingKey, payload, bizKey, delay, properties.getReliable().getDefaultTimeout());
    }

    /**
     * 发送延迟消息至延迟交换机，携带业务标识 bizKey、延迟时间与显式确认超时
     *
     * @param exchange   延迟交换机名称
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param bizKey     业务唯一标识
     * @param delay      延迟投递时间
     * @param timeout    等待 Broker ACK 的超时时间
     */
    public void sendDelayed(String exchange, String routingKey, Object payload, String bizKey, Duration delay, Duration timeout) {
        Assert.hasText(exchange, "exchange 交换机名称不能为空");
        String effectiveRoutingKey = routingKey != null ? routingKey : "";
        Assert.notNull(payload, "payload 消息载荷不能为空");
        Assert.notNull(delay, "delay 延迟时间不能为空");
        Assert.isTrue(!delay.isNegative(), "delay 延迟时间不能为负数");

        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setHeader("x-delay", delay.toMillis());
        Message message = prepareMessage(exchange, payload, messageProperties, bizKey);
        String effectiveBizKey = MessageContextPropagator.extractString(message.getMessageProperties().getHeaders(), MessageHeaderConstants.BIZ_KEY);
        Duration effectiveTimeout = timeout != null ? timeout : properties.getReliable().getDefaultTimeout();

        doSendReliable(exchange, effectiveRoutingKey, message, payload, effectiveBizKey, effectiveTimeout);
    }

    /**
     * 异步发送延迟消息至延迟交换机
     *
     * @param exchange   延迟交换机名称
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param delay      延迟投递时间
     * @return CompletableFuture 确认结果
     */
    public CompletableFuture<CorrelationData.Confirm> sendDelayedAsync(String exchange, String routingKey, Object payload, Duration delay) {
        return sendDelayedAsync(exchange, routingKey, payload, null, delay);
    }

    /**
     * 异步发送延迟消息至延迟交换机，携带业务标识 bizKey
     *
     * @param exchange   延迟交换机名称
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param bizKey     业务唯一标识
     * @param delay      延迟投递时间
     * @return CompletableFuture 确认结果
     */
    public CompletableFuture<CorrelationData.Confirm> sendDelayedAsync(String exchange, String routingKey, Object payload, String bizKey, Duration delay) {
        Assert.hasText(exchange, "exchange 交换机名称不能为空");
        String effectiveRoutingKey = routingKey != null ? routingKey : "";
        Assert.notNull(payload, "payload 消息载荷不能为空");
        Assert.notNull(delay, "delay 延迟时间不能为空");
        Assert.isTrue(!delay.isNegative(), "delay 延迟时间不能为负数");

        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setHeader("x-delay", delay.toMillis());
        Message message = prepareMessage(exchange, payload, messageProperties, bizKey);
        String effectiveBizKey = MessageContextPropagator.extractString(message.getMessageProperties().getHeaders(), MessageHeaderConstants.BIZ_KEY);

        return doSendInternal(exchange, effectiveRoutingKey, message, effectiveBizKey);
    }

    // =========================================================================
    // 4. 本地事务提交后发信 (sendAfterCommit)
    // =========================================================================

    /**
     * 感知本地数据库事务：若存在活跃事务，等待事务物理提交 (afterCommit) 后再发射消息；若无事务，立即同步可靠发送
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     */
    public void sendAfterCommit(String exchange, String routingKey, Object payload) {
        sendAfterCommit(exchange, routingKey, payload, null, properties.getReliable().getDefaultTimeout());
    }

    /**
     * 感知本地数据库事务发信，携带业务唯一标识 bizKey
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param bizKey     业务唯一标识
     */
    public void sendAfterCommit(String exchange, String routingKey, Object payload, String bizKey) {
        sendAfterCommit(exchange, routingKey, payload, bizKey, properties.getReliable().getDefaultTimeout());
    }

    /**
     * 感知本地数据库事务发信，携带业务唯一标识 bizKey 与显式确认超时时间
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param bizKey     业务唯一标识
     * @param timeout    等待 Broker ACK 的超时时间
     */
    public void sendAfterCommit(String exchange, String routingKey, Object payload, String bizKey, Duration timeout) {
        sendAfterCommit(exchange, routingKey, payload, bizKey, timeout, (MessagePostProcessor[]) null);
    }

    /**
     * 感知本地数据库事务发信，挂载自定义 MessagePostProcessor 处理器
     *
     * @param exchange    目标交换机
     * @param routingKey  目标路由键
     * @param payload     消息载荷对象
     * @param customizers 消息属性后置定制器
     */
    public void sendAfterCommit(String exchange, String routingKey, Object payload, MessagePostProcessor... customizers) {
        sendAfterCommit(exchange, routingKey, payload, null, properties.getReliable().getDefaultTimeout(), customizers);
    }

    /**
     * 感知本地数据库事务发信，携带业务唯一标识 bizKey 并挂载自定义 MessagePostProcessor 处理器
     *
     * @param exchange    目标交换机
     * @param routingKey  目标路由键
     * @param payload     消息载荷对象
     * @param bizKey      业务唯一标识
     * @param customizers 消息属性后置定制器
     */
    public void sendAfterCommit(String exchange, String routingKey, Object payload, String bizKey, MessagePostProcessor... customizers) {
        sendAfterCommit(exchange, routingKey, payload, bizKey, properties.getReliable().getDefaultTimeout(), customizers);
    }

    /**
     * 感知本地数据库事务发信，携带显式确认超时时间并挂载自定义 MessagePostProcessor 处理器
     *
     * @param exchange    目标交换机
     * @param routingKey  目标路由键
     * @param payload     消息载荷对象
     * @param timeout     等待 Broker ACK 的超时时间
     * @param customizers 消息属性后置定制器
     */
    public void sendAfterCommit(String exchange, String routingKey, Object payload, Duration timeout, MessagePostProcessor... customizers) {
        sendAfterCommit(exchange, routingKey, payload, null, timeout, customizers);
    }

    /**
     * 感知本地数据库事务发信，携带业务唯一标识 bizKey、显式确认超时时间并挂载自定义 MessagePostProcessor 处理器
     *
     * @param exchange    目标交换机
     * @param routingKey  目标路由键
     * @param payload     消息载荷对象
     * @param bizKey      业务唯一标识
     * @param timeout     等待 Broker ACK 的超时时间
     * @param customizers 消息属性后置定制器
     */
    public void sendAfterCommit(String exchange, String routingKey, Object payload, String bizKey, Duration timeout, MessagePostProcessor... customizers) {
        Assert.hasText(exchange, "exchange 交换机名称不能为空");
        String effectiveRoutingKey = routingKey != null ? routingKey : "";
        Assert.notNull(payload, "payload 消息载荷不能为空");

        // 1. 立即构建准备消息并执行 Fail-Fast 大小校验与四维上下文快照捕获
        MessageProperties messageProperties = new MessageProperties();
        Message message = prepareMessage(exchange, payload, messageProperties, bizKey, customizers);
        String effectiveBizKey = MessageContextPropagator.extractString(message.getMessageProperties().getHeaders(), MessageHeaderConstants.BIZ_KEY);
        Duration effectiveTimeout = timeout != null ? timeout : properties.getReliable().getDefaultTimeout();

        // 2. 检测是否存在活跃本地事务
        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        doSendReliable(exchange, effectiveRoutingKey, message, payload, effectiveBizKey, effectiveTimeout);
                    } catch (Throwable ex) {
                        log.error("[MQ-TRANSACTION-POST-COMMIT-FAILED] 本地事务提交后异步发信失败: exchange={}, routingKey={}, bizKey={}, error={}",
                                exchange, effectiveRoutingKey, effectiveBizKey, ex.getMessage(), ex);
                        invokeTransactionFailureCallback(exchange, effectiveRoutingKey, payload, effectiveBizKey, ex);
                    }
                }
            });
        } else {
            // 无活跃事务，立即执行可靠发送
            doSendReliable(exchange, effectiveRoutingKey, message, payload, effectiveBizKey, effectiveTimeout);
        }
    }

    /**
     * 感知本地数据库事务发送优先级消息，使用默认确认超时时间与自适应 bizKey
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param priority   消息优先级 (0-255，对应队列 x-max-priority)
     */
    public void sendAfterCommitWithPriority(String exchange, String routingKey, Object payload, int priority) {
        sendAfterCommitWithPriority(exchange, routingKey, payload, priority, null, properties.getReliable().getDefaultTimeout());
    }

    /**
     * 感知本地数据库事务发送优先级消息，携带业务唯一标识 bizKey，使用默认确认超时时间
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param priority   消息优先级 (0-255，对应队列 x-max-priority)
     * @param bizKey     业务唯一标识
     */
    public void sendAfterCommitWithPriority(String exchange, String routingKey, Object payload, int priority, String bizKey) {
        sendAfterCommitWithPriority(exchange, routingKey, payload, priority, bizKey, properties.getReliable().getDefaultTimeout());
    }

    /**
     * 感知本地数据库事务发送优先级消息，显式指定确认超时时间
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param priority   消息优先级 (0-255，对应队列 x-max-priority)
     * @param timeout    等待 Broker ACK 的超时时间
     */
    public void sendAfterCommitWithPriority(String exchange, String routingKey, Object payload, int priority, Duration timeout) {
        sendAfterCommitWithPriority(exchange, routingKey, payload, priority, null, timeout);
    }

    /**
     * 感知本地数据库事务发送优先级消息，携带业务唯一标识 bizKey 与显式确认超时时间
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    消息载荷对象
     * @param priority   消息优先级 (0-255，对应队列 x-max-priority)
     * @param bizKey     业务唯一标识
     * @param timeout    等待 Broker ACK 的超时时间
     */
    public void sendAfterCommitWithPriority(String exchange, String routingKey, Object payload, int priority, String bizKey, Duration timeout) {
        Assert.isTrue(priority >= 0 && priority <= 255, "priority 消息优先级必须在 0 到 255 之间");
        sendAfterCommit(exchange, routingKey, payload, bizKey, timeout, msg -> {
            msg.getMessageProperties().setPriority(priority);
            return msg;
        });
    }

    // =========================================================================
    // 5. 内部消息预处理、网络发送与宏日志
    // =========================================================================

    /**
     * 构建消息对象：注入四维上下文、序列化载荷、自定义后置处理、报文体积 Fail-Fast 校验与透明 GZIP 压缩
     */
    private Message prepareMessage(String exchange, Object payload, MessageProperties messageProperties, String bizKey, MessagePostProcessor... customizers) {
        // 1. 尝试按优先级解析有效业务键：显式传参 -> Payload/Message 原生 Header -> 兜底由 inject 赋 traceId
        String resolvedBizKey = resolveBizKey(payload, messageProperties, bizKey);

        // 2. 抓取当前调用线程四维上下文并注入消息头 (UTF-8 归一化)
        Map<String, Object> headers = new HashMap<>();
        MessageContextPropagator.inject(headers, resolvedBizKey);
        headers.forEach(messageProperties::setHeader);

        // 3. 序列化为 Message 对象
        Message message;
        if (payload instanceof Message msg) {
            MessageProperties props = msg.getMessageProperties();
            headers.forEach((k, v) -> {
                if (!MessageHeaderConstants.BIZ_KEY.equals(k) || bizKey != null) {
                    props.setHeader(k, v);
                } else if (props.getHeader(k) == null) {
                    props.setHeader(k, v);
                }
            });
            message = msg;
        } else if (payload instanceof byte[] bytes) {
            message = new Message(bytes, messageProperties);
        } else {
            MessageConverter converter = rabbitTemplate.getMessageConverter();
            if (converter == null) {
                converter = new SimpleMessageConverter();
            }
            message = converter.toMessage(payload, messageProperties);
        }

        // 4. 应用外部 MessagePostProcessor 自定义处理器 (如优先级、扩展消息头定制)
        if (customizers != null) {
            for (MessagePostProcessor customizer : customizers) {
                if (customizer != null) {
                    Message postProcessed = customizer.postProcessMessage(message);
                    if (postProcessed != null) {
                        message = postProcessed;
                    }
                }
            }
        }

        // 提取已归一化的业务键
        String effectiveBizKey = MessageContextPropagator.extractString(message.getMessageProperties().getHeaders(), MessageHeaderConstants.BIZ_KEY);

        // 5. 校验出站报文原始体积 (Fail-Fast 防爆)
        byte[] body = message.getBody();
        long rawSize = body != null ? body.length : 0L;
        long maxPayloadSize = properties.getProducer().getMaxPayloadSize().toBytes();
        if (rawSize > maxPayloadSize) {
            throw new MessageDeliveryException(
                    MQ_TYPE_RABBITMQ,
                    exchange,
                    effectiveBizKey,
                    String.format("出站消息报文体积超出最大限制: %d 字节 (最大限制: %d 字节, payload=%s)",
                            rawSize, maxPayloadSize, payload.getClass().getSimpleName()));
        }

        // 6. 透明 GZIP 压缩
        if (properties.getProducer().isCompressionEnabled()) {
            long compressionThreshold = properties.getProducer().getCompressionThreshold().toBytes();
            if (rawSize > compressionThreshold) {
                message = gzipPostProcessor.postProcessMessage(message);
            }
        }

        return message;
    }

    /**
     * 按三级优先级自适应解析业务键 (显式传参 -> Payload/Message 原生 Header/实体属性 -> null)
     */
    private String resolveBizKey(Object payload, MessageProperties messageProperties, String explicitBizKey) {
        if (explicitBizKey != null && !explicitBizKey.isBlank()) {
            return explicitBizKey;
        }
        if (payload instanceof Message msg) {
            String msgBizKey = MessageContextPropagator.extractString(msg.getMessageProperties().getHeaders(), MessageHeaderConstants.BIZ_KEY);
            if (msgBizKey != null && !msgBizKey.isBlank()) {
                return msgBizKey;
            }
        }
        if (messageProperties != null) {
            String propBizKey = MessageContextPropagator.extractString(messageProperties.getHeaders(), MessageHeaderConstants.BIZ_KEY);
            if (propBizKey != null && !propBizKey.isBlank()) {
                return propBizKey;
            }
        }
        if (payload instanceof Map<?, ?> map) {
            Object mapBizKey = map.get("bizKey");
            if (mapBizKey == null) {
                mapBizKey = map.get("bizId");
            }
            if (mapBizKey == null) {
                mapBizKey = map.get("id");
            }
            if (mapBizKey != null) {
                String str = String.valueOf(mapBizKey);
                if (!str.isBlank()) {
                    return str;
                }
            }
        }
        return null;
    }

    /**
     * 执行底层网络发送，装配全局唯一 CorrelationData，并挂载异步完成日志回调
     */
    private CompletableFuture<CorrelationData.Confirm> doSendInternal(
            String exchange,
            String routingKey,
            Message message,
            String bizKey) {
        long start = System.currentTimeMillis();
        String traceId = MessageContextPropagator.extractString(message.getMessageProperties().getHeaders(), MessageHeaderConstants.TRACE_ID);
        int size = message.getBody() != null ? message.getBody().length : 0;

        // 全局唯一 CorrelationData ID (bizKey:UUID) 防止 RabbitTemplate.pendingConfirms 并发覆盖
        String correlationId = (bizKey != null && !bizKey.isBlank() ? bizKey : traceId) + ":" + IdUtil.fastSimpleUUID();
        CorrelationData correlationData = new CorrelationData(correlationId);
        CompletableFuture<CorrelationData.Confirm> future = correlationData.getFuture();

        // 挂载异步审计宏日志 (单次发送的唯一日志出口，防双重打印)
        future.whenComplete((confirm, ex) -> {
            long cost = System.currentTimeMillis() - start;
            if (ex != null || (confirm != null && !confirm.isAck())) {
                String reason = ex != null ? ex.getMessage() : (confirm != null ? confirm.getReason() : "Unknown");
                logMacroSend(exchange, routingKey, bizKey, traceId, size, "FAILED", cost, reason);
            } else {
                logMacroSend(exchange, routingKey, bizKey, traceId, size, "SUCCESS", cost, null);
            }
        });

        try {
            rabbitTemplate.send(exchange, routingKey, message, correlationData);
        } catch (Throwable ex) {
            MessageDeliveryException mde = new MessageDeliveryException(
                    MQ_TYPE_RABBITMQ,
                    exchange,
                    bizKey,
                    String.format("RabbitMQ 消息发送异常: exchange=%s, routingKey=%s, bizKey=%s",
                            exchange, routingKey, bizKey),
                    ex);
            future.completeExceptionally(mde);
            return future;
        }

        return future;
    }

    /**
     * 同步等待 Broker ACK 确认结果
     */
    private void doSendReliable(
            String exchange,
            String routingKey,
            Message message,
            Object originalPayload,
            String bizKey,
            Duration timeout) {
        CompletableFuture<CorrelationData.Confirm> future = doSendInternal(exchange, routingKey, message, bizKey);
        try {
            CorrelationData.Confirm confirm = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.isAck()) {
                String reason = confirm != null ? confirm.getReason() : "Broker returned null confirm";
                throw new MessageDeliveryException(
                        MQ_TYPE_RABBITMQ,
                        exchange,
                        bizKey,
                        String.format("RabbitMQ 消息发送被 Broker NACK 拒绝: exchange=%s, routingKey=%s, bizKey=%s, reason=%s",
                                exchange, routingKey, bizKey, reason));
            }
        } catch (TimeoutException e) {
            throw new MessageDeliveryException(
                    MQ_TYPE_RABBITMQ,
                    exchange,
                    bizKey,
                    String.format("RabbitMQ 消息发送等待 Broker ACK 超时 (%dms): exchange=%s, routingKey=%s, bizKey=%s",
                            timeout.toMillis(), exchange, routingKey, bizKey),
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessageDeliveryException(
                    MQ_TYPE_RABBITMQ,
                    exchange,
                    bizKey,
                    String.format("RabbitMQ 消息发送等待 Broker ACK 被中断: exchange=%s, routingKey=%s, bizKey=%s",
                            exchange, routingKey, bizKey),
                    e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof MessageDeliveryException mde) {
                throw mde;
            }
            throw new MessageDeliveryException(
                    MQ_TYPE_RABBITMQ,
                    exchange,
                    bizKey,
                    String.format("RabbitMQ 消息发送确认执行异常: exchange=%s, routingKey=%s, bizKey=%s",
                            exchange, routingKey, bizKey),
                    cause);
        }
    }

    /**
     * 回调事务失败补偿 SPI
     */
    private void invokeTransactionFailureCallback(String exchange, String routingKey, Object payload, String bizKey, Throwable cause) {
        if (transactionFailureCallbackProvider != null) {
            RabbitTransactionFailureCallback callback = transactionFailureCallbackProvider.getIfAvailable();
            if (callback != null) {
                try {
                    callback.onFailure(exchange, routingKey, payload, bizKey, cause);
                } catch (Throwable cbEx) {
                    log.error("[MQ-TRANSACTION-CALLBACK-ERROR] 事务失败补偿回调执行异常: exchange={}, routingKey={}, bizKey={}",
                            exchange, routingKey, bizKey, cbEx);
                }
            }
        }
    }

    /**
     * 打印标准结构化出站审计日志
     */
    private void logMacroSend(
            String exchange,
            String routingKey,
            String bizKey,
            String traceId,
            int size,
            String status,
            long cost,
            String reason) {
        if (!properties.getLogging().isEnabled()) {
            return;
        }
        String prevTraceId = MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY);
        try {
            if (traceId != null && !traceId.isBlank()) {
                MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, traceId);
            }
            if ("SUCCESS".equals(status)) {
                log.info("[MQ-SEND] mq=rabbitmq topic={} routingKey={} bizKey={} traceId={} size={}B status=SUCCESS cost={}ms",
                        exchange, routingKey, bizKey, traceId, size, cost);
            } else {
                log.error("[MQ-SEND] mq=rabbitmq topic={} routingKey={} bizKey={} traceId={} size={}B status=FAILED cost={}ms error={}",
                        exchange, routingKey, bizKey, traceId, size, cost, reason != null ? reason : "Unknown");
            }
        } finally {
            if (prevTraceId != null) {
                MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, prevTraceId);
            } else {
                MDC.remove(MessageContextPropagator.MDC_TRACE_ID_KEY);
            }
        }
    }

    private record PreparedBatchItem(int index, Message message, String bizKey) {
    }

    private record BatchFutureItem(int index, String bizKey, CompletableFuture<CorrelationData.Confirm> future) {
    }
}
