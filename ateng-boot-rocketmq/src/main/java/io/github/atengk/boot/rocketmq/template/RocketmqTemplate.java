package io.github.atengk.boot.rocketmq.template;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.rocketmq.constant.RocketmqConstants;
import io.github.atengk.boot.rocketmq.helper.RocketmqMessageHelper;
import io.github.atengk.boot.rocketmq.properties.AtengRocketmqProperties;
import io.github.atengk.boot.rocketmq.spi.RocketmqTransactionFailureCallback;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageBuilder;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

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

    private final Producer producer;
    private final AtengRocketmqProperties properties;
    private final ObjectProvider<RocketmqTransactionFailureCallback> failureCallbackProvider;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();

    public RocketmqTemplate(Producer producer, AtengRocketmqProperties properties) {
        this(producer, properties, (ObjectProvider<RocketmqTransactionFailureCallback>) null);
    }

    public RocketmqTemplate(
            Producer producer,
            AtengRocketmqProperties properties,
            ObjectProvider<RocketmqTransactionFailureCallback> failureCallbackProvider) {
        this.producer = Objects.requireNonNull(producer, "producer 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.failureCallbackProvider = failureCallbackProvider;
    }

    public RocketmqTemplate(
            Producer producer,
            AtengRocketmqProperties properties,
            RocketmqTransactionFailureCallback failureCallback) {
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
        });
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

    public SendReceipt sendDelayed(String topic, Object payload, Duration delay) {
        return sendDelayed(topic, null, null, payload, delay);
    }

    public SendReceipt sendDelayed(String topic, String tag, Object payload, Duration delay) {
        return sendDelayed(topic, tag, null, payload, delay);
    }

    public SendReceipt sendDelayed(String topic, String tag, String bizKey, Object payload, Duration delay) {
        Assert.notNull(delay, "delay 不能为空");
        long deliveryTimestamp = System.currentTimeMillis() + delay.toMillis();
        String finalBizKey = RocketmqMessageHelper.resolveBizKey(bizKey, payload);
        Message message = buildMessage(topic, tag, finalBizKey, payload, null, deliveryTimestamp, null);
        return doSendSync(message, topic, tag, finalBizKey);
    }

    public CompletableFuture<SendReceipt> sendDelayedAsync(String topic, String tag, String bizKey, Object payload, Duration delay) {
        Assert.notNull(delay, "delay 不能为空");
        long deliveryTimestamp = System.currentTimeMillis() + delay.toMillis();
        String finalBizKey = RocketmqMessageHelper.resolveBizKey(bizKey, payload);
        Message message = buildMessage(topic, tag, finalBizKey, payload, null, deliveryTimestamp, null);
        return doSendAsync(message, topic, tag, finalBizKey);
    }

    public SendReceipt sendDeliverAt(String topic, Object payload, Instant deliverAt) {
        return sendDeliverAt(topic, null, null, payload, deliverAt);
    }

    public SendReceipt sendDeliverAt(String topic, String tag, Object payload, Instant deliverAt) {
        return sendDeliverAt(topic, tag, null, payload, deliverAt);
    }

    public SendReceipt sendDeliverAt(String topic, String tag, String bizKey, Object payload, Instant deliverAt) {
        Assert.notNull(deliverAt, "deliverAt 不能为空");
        long deliveryTimestamp = deliverAt.toEpochMilli();
        String finalBizKey = RocketmqMessageHelper.resolveBizKey(bizKey, payload);
        Message message = buildMessage(topic, tag, finalBizKey, payload, null, deliveryTimestamp, null);
        return doSendSync(message, topic, tag, finalBizKey);
    }

    public CompletableFuture<SendReceipt> sendDeliverAtAsync(String topic, String tag, String bizKey, Object payload, Instant deliverAt) {
        Assert.notNull(deliverAt, "deliverAt 不能为空");
        long deliveryTimestamp = deliverAt.toEpochMilli();
        String finalBizKey = RocketmqMessageHelper.resolveBizKey(bizKey, payload);
        Message message = buildMessage(topic, tag, finalBizKey, payload, null, deliveryTimestamp, null);
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
        Assert.hasText(messageGroup, "messageGroup 顺序分组标识不能为空");
        String finalBizKey = RocketmqMessageHelper.resolveBizKey(bizKey, payload);
        Message message = buildMessage(topic, tag, finalBizKey, payload, messageGroup, null, null);
        return doSendSync(message, topic, tag, finalBizKey);
    }

    public CompletableFuture<SendReceipt> sendOrderlyAsync(String topic, String tag, String bizKey, Object payload, String messageGroup) {
        Assert.hasText(messageGroup, "messageGroup 顺序分组标识不能为空");
        String finalBizKey = RocketmqMessageHelper.resolveBizKey(bizKey, payload);
        Message message = buildMessage(topic, tag, finalBizKey, payload, messageGroup, null, null);
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
        String finalBizKey = RocketmqMessageHelper.resolveBizKey(bizKey, payload);
        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        send(topic, tag, finalBizKey, payload, userProperties);
                    } catch (Exception ex) {
                        log.error("[MQ-TRANSACTION-POST-COMMIT-FAILED] topic={} tag={} bizKey={} 本地数据库事务已成功提交，但发信失败！",
                                topic, tag, finalBizKey, ex);
                        if (failureCallbackProvider != null) {
                            RocketmqTransactionFailureCallback callback = failureCallbackProvider.getIfAvailable();
                            if (callback != null) {
                                try {
                                    callback.onFailure(topic, finalBizKey, payload, ex);
                                } catch (Exception cbEx) {
                                    log.error("[MQ-TRANSACTION-FAILURE-CALLBACK-ERROR] 回调 RocketmqTransactionFailureCallback 异常", cbEx);
                                }
                            }
                        }
                    }
                }
            });
        } else {
            send(topic, tag, finalBizKey, payload, userProperties);
        }
    }

    // =========================================================================
    // 内部通用构建与执行方法
    // =========================================================================

    public Message buildMessage(
            String topic,
            String tag,
            String bizKey,
            Object payload,
            String messageGroup,
            Long deliveryTimestamp,
            Map<String, String> customProperties) {
        Assert.hasText(topic, "topic 不能为空");

        // 1. 序列化为字节数组
        byte[] bodyBytes = RocketmqMessageHelper.toPayloadBytes(payload);

        // 2. 检查大报文 Fail-Fast
        long maxPayloadBytes = properties.getProducer().getMaxPayloadSize().toBytes();
        if (bodyBytes.length > maxPayloadBytes) {
            throw new MessageDeliveryException(
                    RocketmqConstants.MQ_TYPE,
                    topic,
                    bizKey,
                    CommonResultCode.BAD_REQUEST,
                    String.format("RocketMQ 消息体过大 (%d 字节)，超过配置上限 (%d 字节)", bodyBytes.length, maxPayloadBytes),
                    null);
        }

        // 3. 构建 Header Map 并抓取四维上下文
        Map<String, Object> headerMap = new HashMap<>();
        MessageContextPropagator.inject(headerMap);

        // 4. 检查透明 GZIP 压缩
        long compressionThreshold = properties.getProducer().getCompressionThreshold().toBytes();
        if (bodyBytes.length > compressionThreshold) {
            try {
                bodyBytes = RocketmqMessageHelper.compressGzip(bodyBytes);
                headerMap.put(RocketmqConstants.HEADER_COMPRESSION, RocketmqConstants.COMPRESSION_GZIP);
            } catch (IOException e) {
                log.warn("[ateng-boot-rocketmq] 消息透明 GZIP 压缩失败，回退发送原始报文: topic={}, bizKey={}", topic, bizKey, e);
            }
        }

        // 5. 组装 RocketMQ 原生 Message
        MessageBuilder builder = provider.newMessageBuilder();
        builder.setTopic(topic.trim());
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

        // 6. 添加四维上下文及自定义属性 (强制 UTF-8 String 归一化)
        for (Map.Entry<String, Object> entry : headerMap.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                builder.addProperty(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        builder.addProperty(MessageHeaderConstants.BIZ_KEY, bizKey);

        if (customProperties != null) {
            for (Map.Entry<String, String> entry : customProperties.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    builder.addProperty(entry.getKey(), entry.getValue());
                }
            }
        }

        return builder.build();
    }

    private SendReceipt doSendSync(Message message, String topic, String tag, String bizKey) {
        long startTime = System.currentTimeMillis();
        String traceId = MDC.get(MessageHeaderConstants.TRACE_ID);
        int bodySize = message.getBody().remaining();

        try {
            SendReceipt receipt = producer.send(message);
            long cost = System.currentTimeMillis() - startTime;
            log.info("[MQ-SEND] mq=rocketmq topic={} tag={} bizKey={} traceId={} size={}B status=SUCCESS cost={}ms messageId={}",
                    topic, tag != null ? tag : "", bizKey, traceId != null ? traceId : "",
                    bodySize, cost, receipt.getMessageId());
            return receipt;
        } catch (Exception ex) {
            long cost = System.currentTimeMillis() - startTime;
            log.error("[MQ-SEND] mq=rocketmq topic={} tag={} bizKey={} traceId={} size={}B status=FAILED cost={}ms error={}",
                    topic, tag != null ? tag : "", bizKey, traceId != null ? traceId : "",
                    bodySize, cost, ex.getMessage(), ex);
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
        String traceId = MDC.get(MessageHeaderConstants.TRACE_ID);
        int bodySize = message.getBody().remaining();

        return producer.sendAsync(message)
                .whenComplete((receipt, ex) -> {
                    long cost = System.currentTimeMillis() - startTime;
                    if (ex != null) {
                        log.error("[MQ-SEND] mq=rocketmq topic={} tag={} bizKey={} traceId={} size={}B status=FAILED cost={}ms error={}",
                                topic, tag != null ? tag : "", bizKey, traceId != null ? traceId : "",
                                bodySize, cost, ex.getMessage(), ex);
                    } else {
                        log.info("[MQ-SEND] mq=rocketmq topic={} tag={} bizKey={} traceId={} size={}B status=SUCCESS cost={}ms messageId={}",
                                topic, tag != null ? tag : "", bizKey, traceId != null ? traceId : "",
                                bodySize, cost, receipt.getMessageId());
                    }
                });
    }
}
