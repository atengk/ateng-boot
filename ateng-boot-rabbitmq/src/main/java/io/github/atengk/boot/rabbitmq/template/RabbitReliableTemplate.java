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
import java.util.HashMap;
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
        Assert.hasText(exchange, "exchange 交换机名称不能为空");
        String effectiveRoutingKey = routingKey != null ? routingKey : "";
        Assert.notNull(payload, "payload 消息载荷不能为空");

        MessageProperties messageProperties = new MessageProperties();
        Message message = prepareMessage(exchange, payload, messageProperties, bizKey);
        String effectiveBizKey = MessageContextPropagator.extractString(message.getMessageProperties().getHeaders(), MessageHeaderConstants.BIZ_KEY);
        Duration effectiveTimeout = timeout != null ? timeout : properties.getReliable().getDefaultTimeout();

        doSendReliable(exchange, effectiveRoutingKey, message, payload, effectiveBizKey, effectiveTimeout);
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
        return sendReliableAsync(exchange, routingKey, payload, null);
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
        Assert.hasText(exchange, "exchange 交换机名称不能为空");
        String effectiveRoutingKey = routingKey != null ? routingKey : "";
        Assert.notNull(payload, "payload 消息载荷不能为空");

        MessageProperties messageProperties = new MessageProperties();
        Message message = prepareMessage(exchange, payload, messageProperties, bizKey);
        String effectiveBizKey = MessageContextPropagator.extractString(message.getMessageProperties().getHeaders(), MessageHeaderConstants.BIZ_KEY);

        return doSendInternal(exchange, effectiveRoutingKey, message, effectiveBizKey);
    }

    // =========================================================================
    // 3. 延迟消息发送 (sendDelayed)
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
        Assert.hasText(exchange, "exchange 交换机名称不能为空");
        String effectiveRoutingKey = routingKey != null ? routingKey : "";
        Assert.notNull(payload, "payload 消息载荷不能为空");

        // 1. 立即构建准备消息并执行 Fail-Fast 大小校验与四维上下文快照捕获
        MessageProperties messageProperties = new MessageProperties();
        Message message = prepareMessage(exchange, payload, messageProperties, bizKey);
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

    // =========================================================================
    // 5. 内部消息预处理、网络发送与宏日志
    // =========================================================================

    /**
     * 构建消息对象：注入四维上下文、序列化载荷、报文体积 Fail-Fast 校验与透明 GZIP 压缩
     */
    private Message prepareMessage(String exchange, Object payload, MessageProperties messageProperties, String bizKey) {
        // 1. 抓取当前调用线程四维上下文并注入消息头 (UTF-8 归一化)
        Map<String, Object> headers = new HashMap<>();
        MessageContextPropagator.inject(headers, bizKey);
        headers.forEach(messageProperties::setHeader);

        // 2. 序列化为 Message 对象
        Message message;
        if (payload instanceof Message msg) {
            headers.forEach((k, v) -> msg.getMessageProperties().setHeader(k, v));
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

        // 提取已归一化的业务键
        String effectiveBizKey = MessageContextPropagator.extractString(message.getMessageProperties().getHeaders(), MessageHeaderConstants.BIZ_KEY);

        // 3. 校验出站报文原始体积 (Fail-Fast 防爆)
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

        // 4. 透明 GZIP 压缩
        if (properties.getProducer().isCompressionEnabled()) {
            long compressionThreshold = properties.getProducer().getCompressionThreshold().toBytes();
            if (rawSize > compressionThreshold) {
                message = gzipPostProcessor.postProcessMessage(message);
            }
        }

        return message;
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

        // 挂载异步审计宏日志
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
            long cost = System.currentTimeMillis() - start;
            logMacroSend(exchange, routingKey, bizKey, traceId, size, "FAILED", cost, ex.getMessage());
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
}
