package io.github.atengk.boot.kafka.template;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.kafka.constant.KafkaConstants;
import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import io.github.atengk.boot.kafka.spi.KafkaTransactionFailureCallback;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Kafka 生产级高可靠投递与事务感知门面模版
 *
 * <p>内聚同步阻塞等待确认、四维上下文注入与 UTF-8 String 归一化、Murmur2 Key 分区保序、
 * 物理 Partition 定向投递、三级 bizKey 兜底、大报文 Fail-Fast 校验、本地事务防幽灵发信与标准宏日志。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class KafkaReliableTemplate {

    private static final String MQ_TYPE_KAFKA = "kafka";

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final AtengKafkaProperties properties;
    private final ObjectProvider<KafkaTransactionFailureCallback> failureCallbackProvider;

    @SuppressWarnings("unchecked")
    public KafkaReliableTemplate(KafkaTemplate<?, ?> kafkaTemplate, AtengKafkaProperties properties) {
        this((KafkaTemplate<Object, Object>) kafkaTemplate, properties, (ObjectProvider<KafkaTransactionFailureCallback>) null);
    }

    @SuppressWarnings("unchecked")
    public KafkaReliableTemplate(
            KafkaTemplate<?, ?> kafkaTemplate,
            AtengKafkaProperties properties,
            ObjectProvider<KafkaTransactionFailureCallback> failureCallbackProvider) {
        this.kafkaTemplate = (KafkaTemplate<Object, Object>) Objects.requireNonNull(kafkaTemplate, "kafkaTemplate 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.failureCallbackProvider = failureCallbackProvider;
    }

    @SuppressWarnings("unchecked")
    public KafkaReliableTemplate(
            KafkaTemplate<?, ?> kafkaTemplate,
            AtengKafkaProperties properties,
            KafkaTransactionFailureCallback failureCallback) {
        this((KafkaTemplate<Object, Object>) kafkaTemplate, properties, new ObjectProvider<>() {
            @Override
            public KafkaTransactionFailureCallback getObject(Object... args) {
                return failureCallback;
            }

            @Override
            public KafkaTransactionFailureCallback getIfAvailable() {
                return failureCallback;
            }

            @Override
            public KafkaTransactionFailureCallback getIfUnique() {
                return failureCallback;
            }

            @Override
            public KafkaTransactionFailureCallback getObject() {
                return failureCallback;
            }
        });
    }

    /**
     * 获取底层原生 KafkaTemplate 逃生门
     *
     * @return 原生 KafkaTemplate 实例
     */
    public KafkaTemplate<Object, Object> getKafkaTemplate() {
        return this.kafkaTemplate;
    }

    // =========================================================================
    // 1. 同步可靠发送 (sendReliable)
    // =========================================================================

    /**
     * 同步可靠发送消息至指定 Topic，使用默认超时时间
     */
    public SendResult<Object, Object> sendReliable(String topic, Object payload) {
        return sendReliable(topic, null, null, payload, null, properties.getProducer().getSendTimeout());
    }

    /**
     * 同步可靠发送消息至指定 Topic，携带分区键 key（根据 Murmur2 Hash 保序投递至固定分区）
     */
    public SendResult<Object, Object> sendReliable(String topic, String key, Object payload) {
        return sendReliable(topic, null, key, payload, null, properties.getProducer().getSendTimeout());
    }

    /**
     * 同步可靠发送消息至指定 Topic，携带 key 与业务唯一标识 bizKey
     */
    public SendResult<Object, Object> sendReliable(String topic, String key, Object payload, String bizKey) {
        return sendReliable(topic, null, key, payload, bizKey, properties.getProducer().getSendTimeout());
    }

    /**
     * 同步可靠发送消息至指定 Topic，携带 key 与显式等待超时时间
     */
    public SendResult<Object, Object> sendReliable(String topic, String key, Object payload, Duration timeout) {
        return sendReliable(topic, null, key, payload, null, timeout);
    }

    /**
     * 同步可靠发送消息至指定 Topic，携带 key, bizKey 与显式等待超时时间
     */
    public SendResult<Object, Object> sendReliable(String topic, String key, Object payload, String bizKey, Duration timeout) {
        return sendReliable(topic, null, key, payload, bizKey, timeout);
    }

    /**
     * 同步可靠发送消息至指定物理分区 partition
     */
    public SendResult<Object, Object> sendReliable(String topic, Integer partition, String key, Object payload) {
        return sendReliable(topic, partition, key, payload, null, properties.getProducer().getSendTimeout());
    }

    /**
     * 同步可靠发送消息全参数重载
     *
     * @param topic     目标 Topic
     * @param partition 指定物理分区号（可选，传 null 由 Kafka 分区器决定）
     * @param key       分区键（用于 Hash 保序）
     * @param payload   消息载荷
     * @param bizKey    业务唯一标识（可选，未传时自适应提取或兜底生成）
     * @param timeout   等待确认超时时间
     * @return SendResult 发送结果
     * @throws MessageDeliveryException 发送超时或异常时抛出
     */
    public SendResult<Object, Object> sendReliable(
            String topic,
            Integer partition,
            String key,
            Object payload,
            String bizKey,
            Duration timeout) {
        Assert.hasText(topic, "topic 不能为空");
        Assert.notNull(payload, "payload 消息载荷不能为空");

        ProducerRecord<Object, Object> record = prepareRecord(topic, partition, key, payload, bizKey);
        String effectiveBizKey = extractHeaderString(record, MessageHeaderConstants.BIZ_KEY);
        String traceId = extractHeaderString(record, MessageHeaderConstants.TRACE_ID);
        int payloadSize = calculatePayloadSize(payload);
        Duration effectiveTimeout = timeout != null ? timeout : properties.getProducer().getSendTimeout();

        return doSendReliableSync(record, effectiveBizKey, traceId, payloadSize, effectiveTimeout);
    }

    // =========================================================================
    // 2. 异步可靠发送 (sendReliableAsync)
    // =========================================================================

    /**
     * 异步可靠发送消息，返回 CompletableFuture 结果
     */
    public CompletableFuture<SendResult<Object, Object>> sendReliableAsync(String topic, Object payload) {
        return sendReliableAsync(topic, null, null, payload, null);
    }

    /**
     * 异步可靠发送消息，携带分区键 key
     */
    public CompletableFuture<SendResult<Object, Object>> sendReliableAsync(String topic, String key, Object payload) {
        return sendReliableAsync(topic, null, key, payload, null);
    }

    /**
     * 异步可靠发送消息，携带 key 与业务唯一标识 bizKey
     */
    public CompletableFuture<SendResult<Object, Object>> sendReliableAsync(String topic, String key, Object payload, String bizKey) {
        return sendReliableAsync(topic, null, key, payload, bizKey);
    }

    /**
     * 异步可靠发送消息至指定物理分区 partition
     */
    public CompletableFuture<SendResult<Object, Object>> sendReliableAsync(String topic, Integer partition, String key, Object payload) {
        return sendReliableAsync(topic, partition, key, payload, null);
    }

    /**
     * 异步可靠发送消息全参数重载
     */
    public CompletableFuture<SendResult<Object, Object>> sendReliableAsync(
            String topic,
            Integer partition,
            String key,
            Object payload,
            String bizKey) {
        Assert.hasText(topic, "topic 不能为空");
        Assert.notNull(payload, "payload 消息载荷不能为空");

        ProducerRecord<Object, Object> record = prepareRecord(topic, partition, key, payload, bizKey);
        String effectiveBizKey = extractHeaderString(record, MessageHeaderConstants.BIZ_KEY);
        String traceId = extractHeaderString(record, MessageHeaderConstants.TRACE_ID);
        int payloadSize = calculatePayloadSize(payload);

        return doSendInternal(record, effectiveBizKey, traceId, payloadSize);
    }

    // =========================================================================
    // 3. 本地事务提交后发信 (sendAfterCommit)
    // =========================================================================

    /**
     * 感知本地数据库事务：若存在活跃事务，等待事务物理提交 (afterCommit) 后再发射消息；若无事务，立即同步可靠发送
     */
    public void sendAfterCommit(String topic, Object payload) {
        sendAfterCommit(topic, null, null, payload, null, properties.getProducer().getSendTimeout());
    }

    /**
     * 感知本地数据库事务发信，携带分区键 key
     */
    public void sendAfterCommit(String topic, String key, Object payload) {
        sendAfterCommit(topic, null, key, payload, null, properties.getProducer().getSendTimeout());
    }

    /**
     * 感知本地数据库事务发信，携带 key 与 bizKey
     */
    public void sendAfterCommit(String topic, String key, Object payload, String bizKey) {
        sendAfterCommit(topic, null, key, payload, bizKey, properties.getProducer().getSendTimeout());
    }

    /**
     * 感知本地数据库事务发信，显式指定物理分区 partition
     */
    public void sendAfterCommit(String topic, Integer partition, String key, Object payload) {
        sendAfterCommit(topic, partition, key, payload, null, properties.getProducer().getSendTimeout());
    }

    /**
     * 感知本地数据库事务发信，显式指定物理分区 partition 与 bizKey
     */
    public void sendAfterCommit(String topic, Integer partition, String key, Object payload, String bizKey) {
        sendAfterCommit(topic, partition, key, payload, bizKey, properties.getProducer().getSendTimeout());
    }

    /**
     * 感知本地数据库事务发信全参数重载
     */
    public void sendAfterCommit(
            String topic,
            Integer partition,
            String key,
            Object payload,
            String bizKey,
            Duration timeout) {
        Assert.hasText(topic, "topic 不能为空");
        Assert.notNull(payload, "payload 消息载荷不能为空");

        // 1. 预构建 ProducerRecord 并执行 Fail-Fast 大小校验与四维上下文捕获
        ProducerRecord<Object, Object> record = prepareRecord(topic, partition, key, payload, bizKey);
        String effectiveBizKey = extractHeaderString(record, MessageHeaderConstants.BIZ_KEY);
        String traceId = extractHeaderString(record, MessageHeaderConstants.TRACE_ID);
        int payloadSize = calculatePayloadSize(payload);
        Duration effectiveTimeout = timeout != null ? timeout : properties.getProducer().getSendTimeout();

        // 2. 检测是否存在活跃本地事务
        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        doSendReliableSync(record, effectiveBizKey, traceId, payloadSize, effectiveTimeout);
                    } catch (Throwable ex) {
                        log.error("{} 本地事务提交后异步发信失败: topic={}, partition={}, key={}, bizKey={}, traceId={}, error={}",
                                KafkaConstants.LOG_TX_POST_COMMIT_FAILED_PREFIX,
                                topic, partition, key, effectiveBizKey, traceId, ex.getMessage(), ex);
                        invokeTransactionFailureCallback(topic, partition, key, payload, effectiveBizKey, traceId, ex);
                    }
                }
            });
        } else {
            // 无活跃事务，立即执行同步可靠发送
            doSendReliableSync(record, effectiveBizKey, traceId, payloadSize, effectiveTimeout);
        }
    }

    // =========================================================================
    // 4. 批量可靠发送 (sendBatch)
    // =========================================================================

    /**
     * 批量发送消息键值映射集合
     *
     * @param topic          目标 Topic
     * @param keyPayloadMap  消息键值集合 (Key: 分区键, Value: 消息体)
     * @return 批量发送结果
     */
    public List<SendResult<Object, Object>> sendBatch(String topic, Map<String, Object> keyPayloadMap) {
        return sendBatch(topic, keyPayloadMap, properties.getProducer().getSendTimeout());
    }

    /**
     * 批量发送消息键值映射集合，携带超时时间
     */
    public List<SendResult<Object, Object>> sendBatch(String topic, Map<String, Object> keyPayloadMap, Duration timeout) {
        Assert.hasText(topic, "topic 不能为空");
        Assert.notNull(keyPayloadMap, "keyPayloadMap 不能为空");

        List<CompletableFuture<SendResult<Object, Object>>> futures = new ArrayList<>(keyPayloadMap.size());
        for (Map.Entry<String, Object> entry : keyPayloadMap.entrySet()) {
            futures.add(sendReliableAsync(topic, entry.getKey(), entry.getValue()));
        }

        Duration effectiveTimeout = timeout != null ? timeout : properties.getProducer().getSendTimeout();
        List<SendResult<Object, Object>> results = new ArrayList<>(futures.size());
        for (CompletableFuture<SendResult<Object, Object>> future : futures) {
            try {
                results.add(future.get(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                throw new MessageDeliveryException(MQ_TYPE_KAFKA, topic, null,
                        "Kafka 批量发信超时 (等待 " + effectiveTimeout.toMillis() + "ms): " + e.getMessage(), e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new MessageDeliveryException(MQ_TYPE_KAFKA, topic, null,
                        CommonResultCode.INTERNAL_SERVER_ERROR,
                        "Kafka 批量发送消息底层异常: " + cause.getMessage(), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessageDeliveryException(MQ_TYPE_KAFKA, topic, null,
                        CommonResultCode.INTERNAL_SERVER_ERROR,
                        "Kafka 批量发送线程被中断: " + e.getMessage(), e);
            }
        }
        return results;
    }

    /**
     * 批量发送 ProducerRecord 集合
     */
    public List<SendResult<Object, Object>> sendBatchRecords(List<ProducerRecord<Object, Object>> records) {
        return sendBatchRecords(records, properties.getProducer().getSendTimeout());
    }

    /**
     * 批量发送 ProducerRecord 集合，携带超时时间
     */
    public List<SendResult<Object, Object>> sendBatchRecords(List<ProducerRecord<Object, Object>> records, Duration timeout) {
        Assert.notNull(records, "records 不能为空");

        List<CompletableFuture<SendResult<Object, Object>>> futures = new ArrayList<>(records.size());
        for (ProducerRecord<Object, Object> record : records) {
            String effectiveBizKey = extractHeaderString(record, MessageHeaderConstants.BIZ_KEY);
            String traceId = extractHeaderString(record, MessageHeaderConstants.TRACE_ID);
            int size = calculatePayloadSize(record.value());
            futures.add(doSendInternal(record, effectiveBizKey, traceId, size));
        }

        Duration effectiveTimeout = timeout != null ? timeout : properties.getProducer().getSendTimeout();
        List<SendResult<Object, Object>> results = new ArrayList<>(futures.size());
        for (CompletableFuture<SendResult<Object, Object>> future : futures) {
            try {
                results.add(future.get(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                throw new MessageDeliveryException(MQ_TYPE_KAFKA, null, null,
                        "Kafka 批量发信超时 (等待 " + effectiveTimeout.toMillis() + "ms): " + e.getMessage(), e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new MessageDeliveryException(MQ_TYPE_KAFKA, null, null,
                        CommonResultCode.INTERNAL_SERVER_ERROR,
                        "Kafka 批量发送消息底层异常: " + cause.getMessage(), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessageDeliveryException(MQ_TYPE_KAFKA, null, null,
                        CommonResultCode.INTERNAL_SERVER_ERROR,
                        "Kafka 批量发送线程被中断: " + e.getMessage(), e);
            }
        }
        return results;
    }

    // =========================================================================
    // 5. 内部构造、校验与网络发送
    // =========================================================================

    /**
     * 构建 ProducerRecord 对象并执行四维上下文注入与报文体积 Fail-Fast 校验
     */
    private ProducerRecord<Object, Object> prepareRecord(
            String topic,
            Integer partition,
            String key,
            Object payload,
            String explicitBizKey) {
        // 1. 解析业务键 (优先级：显式 bizKey -> 路由 key -> 反射 @Id/bizId/id -> MDC traceId / fastSimpleUUID)
        String resolvedBizKey = resolveBizKey(payload, key, explicitBizKey);

        // 2. 抓取当前调用线程四维上下文并注入消息头 (UTF-8 归一化)
        Map<String, Object> contextHeaders = new HashMap<>();
        MessageContextPropagator.inject(contextHeaders, resolvedBizKey);

        RecordHeaders headers = new RecordHeaders();
        for (Map.Entry<String, Object> entry : contextHeaders.entrySet()) {
            if (entry.getValue() != null) {
                byte[] valBytes = entry.getValue().toString().getBytes(StandardCharsets.UTF_8);
                headers.add(new RecordHeader(entry.getKey(), valBytes));
            }
        }

        // 3. 校验出站报文原始体积 (Fail-Fast 防爆)
        int rawSize = calculatePayloadSize(payload);
        long maxPayloadSize = properties.getProducer().getMaxPayloadSize().toBytes();
        if (rawSize > maxPayloadSize) {
            throw new MessageDeliveryException(
                    MQ_TYPE_KAFKA,
                    topic,
                    resolvedBizKey,
                    CommonResultCode.BAD_REQUEST,
                    String.format("出站消息报文体积超出最大限制: %d 字节 (最大限制: %d 字节, payload=%s)",
                            rawSize, maxPayloadSize, payload.getClass().getSimpleName()),
                    null);
        }

        return new ProducerRecord<>(topic, partition, key, payload, headers);
    }

    /**
     * 执行底层 KafkaTemplate 发送并装配异步日志回调
     */
    private CompletableFuture<SendResult<Object, Object>> doSendInternal(
            ProducerRecord<Object, Object> record,
            String bizKey,
            String traceId,
            int size) {
        long start = System.currentTimeMillis();
        CompletableFuture<SendResult<Object, Object>> future = kafkaTemplate.send(record);

        future.whenComplete((result, ex) -> {
            long cost = System.currentTimeMillis() - start;
            if (ex != null) {
                Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                logMacroSend(record.topic(), record.partition(), bizKey, traceId, size, "FAILED", cost, cause.getMessage());
            } else {
                RecordMetadata md = result.getRecordMetadata();
                logMacroSend(record.topic(), md.partition(), bizKey, traceId, size, "SUCCESS", cost, null);
            }
        });

        return future;
    }

    /**
     * 同步阻塞等待发送确认
     */
    private SendResult<Object, Object> doSendReliableSync(
            ProducerRecord<Object, Object> record,
            String bizKey,
            String traceId,
            int size,
            Duration timeout) {
        CompletableFuture<SendResult<Object, Object>> future = doSendInternal(record, bizKey, traceId, size);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new MessageDeliveryException(
                    MQ_TYPE_KAFKA,
                    record.topic(),
                    bizKey,
                    String.format("Kafka 同步发送确认超时 (等待 %dms, topic=%s, bizKey=%s)",
                            timeout.toMillis(), record.topic(), bizKey),
                    e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new MessageDeliveryException(
                    MQ_TYPE_KAFKA,
                    record.topic(),
                    bizKey,
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    String.format("Kafka 同步发送失败: topic=%s, bizKey=%s, error=%s",
                            record.topic(), bizKey, cause.getMessage()),
                    cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessageDeliveryException(
                    MQ_TYPE_KAFKA,
                    record.topic(),
                    bizKey,
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    String.format("Kafka 同步发送被中断: topic=%s, bizKey=%s", record.topic(), bizKey),
                    e);
        }
    }

    /**
     * 计算消息体原始体积（字节数）
     */
    private int calculatePayloadSize(Object payload) {
        if (payload == null) {
            return 0;
        }
        if (payload instanceof byte[] bytes) {
            return bytes.length;
        }
        if (payload instanceof String str) {
            return str.getBytes(StandardCharsets.UTF_8).length;
        }
        // 对于 POJO 实体，采用 JsonUtil 序列化后评估体积
        try {
            byte[] jsonBytes = JsonUtil.toJsonBytes(payload);
            return jsonBytes != null ? jsonBytes.length : 0;
        } catch (Exception ex) {
            log.warn("[ateng-boot-kafka] 计算载荷体积序列化异常: {}", ex.getMessage());
            return 0;
        }
    }

    /**
     * 按三级优先级自适应解析业务键 (显式传参 -> key -> Payload 反射属性 -> MDC traceId / fastSimpleUUID)
     */
    private String resolveBizKey(Object payload, String key, String explicitBizKey) {
        if (StringUtils.hasText(explicitBizKey)) {
            return explicitBizKey;
        }
        if (StringUtils.hasText(key)) {
            return key;
        }
        if (payload instanceof Map<?, ?> map) {
            Object mapBizKey = map.get("bizKey");
            if (mapBizKey == null) {
                mapBizKey = map.get("bizId");
            }
            if (mapBizKey == null) {
                mapBizKey = map.get("id");
            }
            if (mapBizKey != null && StringUtils.hasText(mapBizKey.toString())) {
                return mapBizKey.toString();
            }
        }
        if (payload != null && !(payload instanceof byte[]) && !(payload instanceof String)) {
            String reflectiveId = extractIdFromPayload(payload);
            if (StringUtils.hasText(reflectiveId)) {
                return reflectiveId;
            }
        }
        String mdcTraceId = MDC.get(MessageHeaderConstants.TRACE_ID);
        if (StringUtils.hasText(mdcTraceId)) {
            return mdcTraceId;
        }
        return IdUtil.fastSimpleUUID();
    }

    /**
     * 从 Payload 反射提取主键或唯一标识
     */
    private String extractIdFromPayload(Object payload) {
        Class<?> clazz = payload.getClass();
        for (String methodName : new String[]{"getBizKey", "getBizId", "getId", "getOrderId"}) {
            try {
                Method method = clazz.getMethod(methodName);
                Object val = method.invoke(payload);
                if (val != null && StringUtils.hasText(val.toString())) {
                    return val.toString();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 从 ProducerRecord Headers 提取 UTF-8 字符串值
     */
    private String extractHeaderString(ProducerRecord<Object, Object> record, String headerKey) {
        if (record == null || record.headers() == null) {
            return null;
        }
        Header header = record.headers().lastHeader(headerKey);
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    /**
     * 回调事务失败补偿 SPI
     */
    private void invokeTransactionFailureCallback(
            String topic,
            Integer partition,
            String key,
            Object payload,
            String bizKey,
            String traceId,
            Throwable cause) {
        if (failureCallbackProvider != null) {
            KafkaTransactionFailureCallback callback = failureCallbackProvider.getIfAvailable();
            if (callback != null) {
                try {
                    callback.onFailure(topic, partition, key, payload, bizKey, traceId, cause);
                } catch (Throwable cbEx) {
                    log.error("[MQ-TRANSACTION-CALLBACK-ERROR] 事务失败补偿回调执行异常: topic={}, partition={}, key={}, bizKey={}, traceId={}",
                            topic, partition, key, bizKey, traceId, cbEx);
                }
            }
        }
    }

    /**
     * 打印标准结构化出站审计日志
     */
    private void logMacroSend(
            String topic,
            Integer partition,
            String bizKey,
            String traceId,
            int size,
            String status,
            long cost,
            String reason) {
        String prevTraceId = MDC.get(MessageHeaderConstants.TRACE_ID);
        try {
            if (traceId != null && !traceId.isBlank()) {
                MDC.put(MessageHeaderConstants.TRACE_ID, traceId);
            }
            String partitionStr = partition != null ? String.valueOf(partition) : "*";
            if ("SUCCESS".equals(status)) {
                log.info("{} mq=kafka topic={} partition={} bizKey={} traceId={} size={}B status=SUCCESS cost={}ms",
                        KafkaConstants.LOG_SEND_PREFIX, topic, partitionStr, bizKey, traceId, size, cost);
            } else {
                log.error("{} mq=kafka topic={} partition={} bizKey={} traceId={} size={}B status=FAILED cost={}ms error={}",
                        KafkaConstants.LOG_SEND_PREFIX, topic, partitionStr, bizKey, traceId, size, cost, reason != null ? reason : "Unknown");
            }
        } finally {
            if (prevTraceId != null) {
                MDC.put(MessageHeaderConstants.TRACE_ID, prevTraceId);
            } else {
                MDC.remove(MessageHeaderConstants.TRACE_ID);
            }
        }
    }
}
