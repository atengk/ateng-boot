package io.github.atengk.boot.kafka.template;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.AsyncContext;
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
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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

    /**
     * 框架保留的系统上下文与幂等消息头集合（小写归一化，禁止自定义头覆盖或伪造）
     */
    private static final Set<String> RESERVED_SYSTEM_HEADER_KEYS = Set.of(
            MessageHeaderConstants.TRACE_ID.toLowerCase(Locale.ROOT),
            MessageHeaderConstants.TENANT_ID.toLowerCase(Locale.ROOT),
            MessageHeaderConstants.USER_ID.toLowerCase(Locale.ROOT),
            MessageHeaderConstants.IGNORE_TENANT.toLowerCase(Locale.ROOT),
            MessageHeaderConstants.SEND_TIMESTAMP.toLowerCase(Locale.ROOT),
            MessageHeaderConstants.BIZ_KEY.toLowerCase(Locale.ROOT)
    );

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
        if (this.kafkaTemplate.isTransactional()) {
            this.kafkaTemplate.setAllowNonTransactional(true);
        }
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
     * 同步可靠发送消息至指定 Topic，携带 key 与自定义消息头
     */
    public SendResult<Object, Object> sendReliable(String topic, String key, Object payload, Map<String, String> customHeaders) {
        return sendReliable(topic, null, key, payload, null, properties.getProducer().getSendTimeout(), customHeaders);
    }

    /**
     * 同步可靠发送消息至指定 Topic，携带 key、bizKey 与自定义消息头
     */
    public SendResult<Object, Object> sendReliable(String topic, String key, Object payload, String bizKey, Map<String, String> customHeaders) {
        return sendReliable(topic, null, key, payload, bizKey, properties.getProducer().getSendTimeout(), customHeaders);
    }

    /**
     * 同步可靠发送消息至指定物理分区 partition
     */
    public SendResult<Object, Object> sendReliable(String topic, Integer partition, String key, Object payload) {
        return sendReliable(topic, partition, key, payload, null, properties.getProducer().getSendTimeout(), null);
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
        return sendReliable(topic, partition, key, payload, bizKey, timeout, null);
    }

    /**
     * 同步可靠发送消息全参数重载（支持扩展自定义业务头）
     *
     * @param topic         目标 Topic
     * @param partition     指定物理分区号（可选，传 null 由 Kafka 分区器决定）
     * @param key           分区键（用于 Hash 保序）
     * @param payload       消息载荷
     * @param bizKey        业务唯一标识（可选，未传时自适应提取或兜底生成）
     * @param timeout       等待确认超时时间
     * @param customHeaders 自定义业务消息头（允许 null 或空映射；不会覆盖框架保留系统头）
     * @return SendResult 发送结果
     * @throws MessageDeliveryException 发送超时或异常时抛出
     */
    public SendResult<Object, Object> sendReliable(
            String topic,
            Integer partition,
            String key,
            Object payload,
            String bizKey,
            Duration timeout,
            Map<String, String> customHeaders) {
        Assert.hasText(topic, "topic 不能为空");
        Assert.notNull(payload, "payload 消息载荷不能为空");

        PreparedRecord prepared = prepareRecord(topic, partition, key, payload, bizKey, customHeaders);
        Duration effectiveTimeout = timeout != null ? timeout : properties.getProducer().getSendTimeout();

        return doSendReliableSync(prepared.record(), prepared.bizKey(), prepared.traceId(), prepared.payloadSize(), effectiveTimeout);
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
     * 异步可靠发送消息，携带 key 与自定义消息头
     */
    public CompletableFuture<SendResult<Object, Object>> sendReliableAsync(
            String topic, String key, Object payload, Map<String, String> customHeaders) {
        return sendReliableAsync(topic, null, key, payload, null, customHeaders);
    }

    /**
     * 异步可靠发送消息，携带 key、bizKey 与自定义消息头
     */
    public CompletableFuture<SendResult<Object, Object>> sendReliableAsync(
            String topic, String key, Object payload, String bizKey, Map<String, String> customHeaders) {
        return sendReliableAsync(topic, null, key, payload, bizKey, customHeaders);
    }

    /**
     * 异步可靠发送消息至指定物理分区 partition
     */
    public CompletableFuture<SendResult<Object, Object>> sendReliableAsync(String topic, Integer partition, String key, Object payload) {
        return sendReliableAsync(topic, partition, key, payload, null, null);
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
        return sendReliableAsync(topic, partition, key, payload, bizKey, null);
    }

    /**
     * 异步可靠发送消息全参数重载（支持扩展自定义业务头）
     *
     * @param topic         目标 Topic
     * @param partition     指定物理分区号（可选）
     * @param key           分区键
     * @param payload       消息载荷
     * @param bizKey        业务唯一标识
     * @param customHeaders 自定义业务消息头（允许 null 或空映射；不会覆盖框架保留系统头）
     * @return 异步发送 Future
     */
    public CompletableFuture<SendResult<Object, Object>> sendReliableAsync(
            String topic,
            Integer partition,
            String key,
            Object payload,
            String bizKey,
            Map<String, String> customHeaders) {
        Assert.hasText(topic, "topic 不能为空");
        Assert.notNull(payload, "payload 消息载荷不能为空");

        PreparedRecord prepared = prepareRecord(topic, partition, key, payload, bizKey, customHeaders);

        return doSendInternal(prepared.record(), prepared.bizKey(), prepared.traceId(), prepared.payloadSize());
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
        sendAfterCommit(topic, partition, key, payload, bizKey, properties.getProducer().getSendTimeout(), null);
    }

    /**
     * 感知本地数据库事务发信，携带 key 与自定义消息头
     */
    public void sendAfterCommit(String topic, String key, Object payload, Map<String, String> customHeaders) {
        sendAfterCommit(topic, null, key, payload, null, properties.getProducer().getSendTimeout(), customHeaders);
    }

    /**
     * 感知本地数据库事务发信，携带 key、bizKey 与自定义消息头
     */
    public void sendAfterCommit(String topic, String key, Object payload, String bizKey, Map<String, String> customHeaders) {
        sendAfterCommit(topic, null, key, payload, bizKey, properties.getProducer().getSendTimeout(), customHeaders);
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
        sendAfterCommit(topic, partition, key, payload, bizKey, timeout, null);
    }

    /**
     * 感知本地数据库事务发信全参数重载（支持扩展自定义业务头）
     *
     * @param topic         目标 Topic
     * @param partition     指定物理分区号（可选）
     * @param key           分区键
     * @param payload       消息载荷
     * @param bizKey        业务唯一标识
     * @param timeout       等待确认超时时间
     * @param customHeaders 自定义业务消息头（允许 null 或空映射；不会覆盖框架保留系统头）
     */
    public void sendAfterCommit(
            String topic,
            Integer partition,
            String key,
            Object payload,
            String bizKey,
            Duration timeout,
            Map<String, String> customHeaders) {
        Assert.hasText(topic, "topic 不能为空");
        Assert.notNull(payload, "payload 消息载荷不能为空");

        // 1. 预构建 ProducerRecord 并执行 Fail-Fast 大小校验与四维上下文捕获
        PreparedRecord prepared = prepareRecord(topic, partition, key, payload, bizKey, customHeaders);
        Duration effectiveTimeout = timeout != null ? timeout : properties.getProducer().getSendTimeout();

        // 2. 检测是否存在活跃本地事务
        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        doSendReliableSync(prepared.record(), prepared.bizKey(), prepared.traceId(), prepared.payloadSize(), effectiveTimeout);
                    } catch (Throwable ex) {
                        log.error("{} 本地事务提交后异步发信失败: topic={}, partition={}, key={}, bizKey={}, traceId={}, error={}",
                                KafkaConstants.LOG_TX_POST_COMMIT_FAILED_PREFIX,
                                topic, partition, key, prepared.bizKey(), prepared.traceId(), ex.getMessage(), ex);
                        invokeTransactionFailureCallback(topic, partition, key, payload, prepared.bizKey(), prepared.traceId(), ex);
                    }
                }
            });
        } else {
            // 无活跃事务，立即执行同步可靠发送
            doSendReliableSync(prepared.record(), prepared.bizKey(), prepared.traceId(), prepared.payloadSize(), effectiveTimeout);
        }
    }

    // =========================================================================
    // 4. 原生 Kafka 分布式事务执行 (executeInTransaction)
    // =========================================================================

    /**
     * 在原生 Kafka 分布式事务中安全执行原子发送或 Consume-Transform-Produce 流式操作
     *
     * <p>自动透传四维上下文标头（TraceId、TenantId、UserId、BizKey）至事务中发送的每条消息，
     * 并在事务执行失败时转译为统一的受管 {@link MessageDeliveryException}，同时保留底层原始因果链。</p>
     *
     * @param callback 事务执行回调，持有事务受管的 {@link KafkaOperations} 实例
     * @param <T>      回调返回值泛型
     * @return 事务回调执行结果
     * @throws MessageDeliveryException 事务执行超时、中断或底层异常时抛出
     */
    public <T> T executeInTransaction(KafkaOperations.OperationsCallback<Object, Object, T> callback) {
        Assert.notNull(callback, "callback 不能为空");
        try {
            return kafkaTemplate.executeInTransaction(operations -> {
                KafkaOperations<Object, Object> wrappedOperations = wrapTransactionalOperations(operations);
                return callback.doInOperations(wrappedOperations);
            });
        } catch (MessageDeliveryException ex) {
            throw ex;
        } catch (Throwable ex) {
            Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
            throw new MessageDeliveryException(
                    MQ_TYPE_KAFKA,
                    null,
                    null,
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "Kafka 原生分布式事务执行失败: " + cause.getMessage(),
                    cause);
        }
    }

    // =========================================================================
    // 5. 两阶段批量可靠发送 (sendBatch / sendBatchAsync / sendBatchRecords / sendBatchRecordsAsync)
    // =========================================================================

    /**
     * 批量可靠发送消息载荷集合（每个载荷自适应提取 bizKey 并由 Kafka 默认路由分区）
     *
     * @param topic    目标 Topic
     * @param payloads 消息体集合
     * @return 批量发送结果
     */
    public List<SendResult<Object, Object>> sendBatch(String topic, List<?> payloads) {
        return sendBatch(topic, payloads, properties.getProducer().getSendTimeout());
    }

    /**
     * 批量可靠发送消息载荷集合，携带超时时间
     */
    public List<SendResult<Object, Object>> sendBatch(String topic, List<?> payloads, Duration timeout) {
        return sendBatch(topic, null, payloads, timeout, null);
    }

    /**
     * 批量可靠发送消息载荷集合，指定分区键 key（同 key 消息投递至同一分区保序）
     *
     * @param topic    目标 Topic
     * @param key      分区键（用于 Hash 保序）
     * @param payloads 消息体集合
     * @return 批量发送结果
     */
    public List<SendResult<Object, Object>> sendBatch(String topic, String key, List<?> payloads) {
        return sendBatch(topic, key, payloads, properties.getProducer().getSendTimeout(), null);
    }

    /**
     * 批量可靠发送消息载荷集合，指定分区键 key 与超时时间
     */
    public List<SendResult<Object, Object>> sendBatch(String topic, String key, List<?> payloads, Duration timeout) {
        return sendBatch(topic, key, payloads, timeout, null);
    }

    /**
     * 批量可靠发送消息载荷集合，指定分区键 key 与自定义业务消息头
     */
    public List<SendResult<Object, Object>> sendBatch(
            String topic, String key, List<?> payloads, Map<String, String> customHeaders) {
        return sendBatch(topic, key, payloads, properties.getProducer().getSendTimeout(), customHeaders);
    }

    /**
     * 批量可靠发送消息载荷集合，指定分区键 key、超时时间与自定义业务消息头（两阶段原子前置校验）
     */
    public List<SendResult<Object, Object>> sendBatch(
            String topic, String key, List<?> payloads, Duration timeout, Map<String, String> customHeaders) {
        Duration effectiveTimeout = resolveEffectiveTimeout(timeout);
        List<PreparedRecord> preparedRecords = prepareBatchPayloads(topic, key, payloads, customHeaders);
        return sendPreparedSync(topic, preparedRecords, effectiveTimeout);
    }

    /**
     * 批量发送消息键值映射集合
     *
     * @param topic         目标 Topic
     * @param keyPayloadMap 消息键值集合 (Key: 分区键, Value: 消息体)
     * @return 批量发送结果
     */
    public List<SendResult<Object, Object>> sendBatch(String topic, Map<String, Object> keyPayloadMap) {
        return sendBatch(topic, keyPayloadMap, properties.getProducer().getSendTimeout(), null);
    }

    /**
     * 批量发送消息键值映射集合，携带超时时间
     */
    public List<SendResult<Object, Object>> sendBatch(String topic, Map<String, Object> keyPayloadMap, Duration timeout) {
        return sendBatch(topic, keyPayloadMap, timeout, null);
    }

    /**
     * 批量发送消息键值映射集合，携带自定义业务消息头
     */
    public List<SendResult<Object, Object>> sendBatch(
            String topic, Map<String, Object> keyPayloadMap, Map<String, String> customHeaders) {
        return sendBatch(topic, keyPayloadMap, properties.getProducer().getSendTimeout(), customHeaders);
    }

    /**
     * 批量发送消息键值映射集合，携带超时时间与自定义业务消息头（两阶段原子前置校验）
     */
    public List<SendResult<Object, Object>> sendBatch(
            String topic, Map<String, Object> keyPayloadMap, Duration timeout, Map<String, String> customHeaders) {
        Duration effectiveTimeout = resolveEffectiveTimeout(timeout);
        List<PreparedRecord> preparedRecords = prepareBatchMap(topic, keyPayloadMap, customHeaders);
        return sendPreparedSync(topic, preparedRecords, effectiveTimeout);
    }

    /**
     * 批量发送 ProducerRecord 集合
     */
    public List<SendResult<Object, Object>> sendBatchRecords(List<ProducerRecord<Object, Object>> records) {
        return sendBatchRecords(records, properties.getProducer().getSendTimeout());
    }

    /**
     * 批量发送 ProducerRecord 集合，携带超时时间（两阶段前置校验、1MB 报文防御与四维上下文补全）
     */
    public List<SendResult<Object, Object>> sendBatchRecords(List<ProducerRecord<Object, Object>> records, Duration timeout) {
        Duration effectiveTimeout = resolveEffectiveTimeout(timeout);
        List<PreparedRecord> preparedRecords = prepareBatchRecords(records);
        return sendPreparedSync(null, preparedRecords, effectiveTimeout);
    }

    /**
     * 异步批量发送消息载荷集合，返回聚合 Future
     */
    public CompletableFuture<List<SendResult<Object, Object>>> sendBatchAsync(String topic, List<?> payloads) {
        return sendBatchAsync(topic, null, payloads, null);
    }

    /**
     * 异步批量发送消息载荷集合，指定分区键 key
     */
    public CompletableFuture<List<SendResult<Object, Object>>> sendBatchAsync(String topic, String key, List<?> payloads) {
        return sendBatchAsync(topic, key, payloads, null);
    }

    /**
     * 异步批量发送消息载荷集合，指定分区键 key 与自定义业务消息头
     */
    public CompletableFuture<List<SendResult<Object, Object>>> sendBatchAsync(
            String topic, String key, List<?> payloads, Map<String, String> customHeaders) {
        List<PreparedRecord> preparedRecords = prepareBatchPayloads(topic, key, payloads, customHeaders);
        return aggregateBatchFuture(preparedRecords);
    }

    /**
     * 异步批量发送消息键值映射集合，返回聚合 Future
     */
    public CompletableFuture<List<SendResult<Object, Object>>> sendBatchAsync(String topic, Map<String, Object> keyPayloadMap) {
        return sendBatchAsync(topic, keyPayloadMap, null);
    }

    /**
     * 异步批量发送消息键值映射集合，携带自定义业务消息头
     */
    public CompletableFuture<List<SendResult<Object, Object>>> sendBatchAsync(
            String topic, Map<String, Object> keyPayloadMap, Map<String, String> customHeaders) {
        List<PreparedRecord> preparedRecords = prepareBatchMap(topic, keyPayloadMap, customHeaders);
        return aggregateBatchFuture(preparedRecords);
    }

    /**
     * 异步批量发送 ProducerRecord 集合，返回聚合 Future
     */
    public CompletableFuture<List<SendResult<Object, Object>>> sendBatchRecordsAsync(
            List<ProducerRecord<Object, Object>> records) {
        List<PreparedRecord> preparedRecords = prepareBatchRecords(records);
        return aggregateBatchFuture(preparedRecords);
    }

    // =========================================================================
    // 6. 本地事务提交后批量发信 (sendBatchAfterCommit)
    // =========================================================================

    /**
     * 感知本地数据库事务批量发信（使用默认等待确认超时）：事务提交前完成阶段 1 全量校验与四维上下文捕获
     */
    public void sendBatchAfterCommit(String topic, List<?> payloads) {
        sendBatchAfterCommit(topic, null, payloads, properties.getProducer().getSendTimeout(), null);
    }

    /**
     * 感知本地数据库事务批量发信，指定超时时间
     */
    public void sendBatchAfterCommit(String topic, List<?> payloads, Duration timeout) {
        sendBatchAfterCommit(topic, null, payloads, timeout, null);
    }

    /**
     * 感知本地数据库事务批量发信，指定分区键 key
     */
    public void sendBatchAfterCommit(String topic, String key, List<?> payloads) {
        sendBatchAfterCommit(topic, key, payloads, properties.getProducer().getSendTimeout(), null);
    }

    /**
     * 感知本地数据库事务批量发信，指定分区键 key 与超时时间
     */
    public void sendBatchAfterCommit(String topic, String key, List<?> payloads, Duration timeout) {
        sendBatchAfterCommit(topic, key, payloads, timeout, null);
    }

    /**
     * 感知本地数据库事务批量发信，指定分区键 key 与自定义业务消息头
     */
    public void sendBatchAfterCommit(
            String topic, String key, List<?> payloads, Map<String, String> customHeaders) {
        sendBatchAfterCommit(topic, key, payloads, properties.getProducer().getSendTimeout(), customHeaders);
    }

    /**
     * 感知本地数据库事务批量发信全参数重载（List 载荷）
     */
    public void sendBatchAfterCommit(
            String topic, String key, List<?> payloads, Duration timeout, Map<String, String> customHeaders) {
        Duration effectiveTimeout = resolveEffectiveTimeout(timeout);
        List<PreparedRecord> preparedRecords = prepareBatchPayloads(topic, key, payloads, customHeaders);
        registerOrSendBatchAfterCommit(topic, preparedRecords, effectiveTimeout);
    }

    /**
     * 感知本地数据库事务批量发信（Key-Payload 映射集合，使用默认等待确认超时）
     */
    public void sendBatchAfterCommit(String topic, Map<String, Object> keyPayloadMap) {
        sendBatchAfterCommit(topic, keyPayloadMap, properties.getProducer().getSendTimeout(), null);
    }

    /**
     * 感知本地数据库事务批量发信（Key-Payload 映射集合，指定超时时间）
     */
    public void sendBatchAfterCommit(String topic, Map<String, Object> keyPayloadMap, Duration timeout) {
        sendBatchAfterCommit(topic, keyPayloadMap, timeout, null);
    }

    /**
     * 感知本地数据库事务批量发信（Key-Payload 映射集合，携带自定义业务消息头）
     */
    public void sendBatchAfterCommit(
            String topic, Map<String, Object> keyPayloadMap, Map<String, String> customHeaders) {
        sendBatchAfterCommit(topic, keyPayloadMap, properties.getProducer().getSendTimeout(), customHeaders);
    }

    /**
     * 感知本地数据库事务批量发信全参数重载（Key-Payload 映射集合）
     */
    public void sendBatchAfterCommit(
            String topic, Map<String, Object> keyPayloadMap, Duration timeout, Map<String, String> customHeaders) {
        Duration effectiveTimeout = resolveEffectiveTimeout(timeout);
        List<PreparedRecord> preparedRecords = prepareBatchMap(topic, keyPayloadMap, customHeaders);
        registerOrSendBatchAfterCommit(topic, preparedRecords, effectiveTimeout);
    }

    /**
     * 感知本地数据库事务批量发信（ProducerRecord 集合，使用默认等待确认超时）
     */
    public void sendBatchRecordsAfterCommit(List<ProducerRecord<Object, Object>> records) {
        sendBatchRecordsAfterCommit(records, properties.getProducer().getSendTimeout());
    }

    /**
     * 感知本地数据库事务批量发信（ProducerRecord 集合，指定超时时间）
     */
    public void sendBatchRecordsAfterCommit(List<ProducerRecord<Object, Object>> records, Duration timeout) {
        Duration effectiveTimeout = resolveEffectiveTimeout(timeout);
        List<PreparedRecord> preparedRecords = prepareBatchRecords(records);
        registerOrSendBatchAfterCommit(null, preparedRecords, effectiveTimeout);
    }

    /**
     * 在活跃事务中捕获四维上下文并注册单次 afterCommit 钩子，无事务时立即同步批量发送
     */
    private void registerOrSendBatchAfterCommit(
            String topic, List<PreparedRecord> preparedRecords, Duration effectiveTimeout) {
        if (preparedRecords.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            AsyncContext.ContextSnapshot capturedContext = AsyncContext.capture();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    AsyncContext.ContextSnapshot previousContext = AsyncContext.capturePrevious();
                    try {
                        capturedContext.apply();
                        sendPreparedAfterCommit(preparedRecords, effectiveTimeout);
                    } finally {
                        previousContext.restore();
                    }
                }
            });
        } else {
            sendPreparedSync(topic, preparedRecords, effectiveTimeout);
        }
    }

    /**
     * 事务提交后在恢复的四维上下文中发送预构建批次，单条失败独立回调 SPI 且不阻断其余记录
     */
    private void sendPreparedAfterCommit(List<PreparedRecord> preparedRecords, Duration effectiveTimeout) {
        List<CompletableFuture<SendResult<Object, Object>>> futures = dispatchPreparedAsync(preparedRecords);
        long deadlineNanos = System.nanoTime() + effectiveTimeout.toNanos();
        boolean interrupted = false;
        InterruptedException interruptedCause = null;

        for (int i = 0; i < preparedRecords.size(); i++) {
            PreparedRecord prepared = preparedRecords.get(i);
            CompletableFuture<SendResult<Object, Object>> future = futures.get(i);
            if (interrupted) {
                inspectCompletedOrReportInterrupted(prepared, future, interruptedCause);
                continue;
            }
            long remainingNanos = Math.max(0L, deadlineNanos - System.nanoTime());
            try {
                future.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                notifyAfterCommitRecordFailure(prepared,
                        translateTimeoutException("Kafka 事务后批量发信", prepared.record().topic(), prepared.bizKey(), effectiveTimeout, e));
            } catch (ExecutionException e) {
                notifyAfterCommitRecordFailure(prepared,
                        translateExecutionException("Kafka 事务后批量发信", prepared.record().topic(), prepared.bizKey(), e));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted = true;
                interruptedCause = e;
                notifyAfterCommitRecordFailure(prepared,
                        translateInterruptedException("Kafka 事务后批量发信", prepared.record().topic(), prepared.bizKey(), e));
            }
        }
    }

    /**
     * 线程中断发生后，非阻塞收口后续条目状态：已完成读取真实结果，未完成直接上报中断异常
     */
    private void inspectCompletedOrReportInterrupted(
            PreparedRecord prepared,
            CompletableFuture<SendResult<Object, Object>> future,
            InterruptedException interruptedCause) {
        if (!future.isDone()) {
            notifyAfterCommitRecordFailure(prepared,
                    translateInterruptedException("Kafka 事务后批量发信", prepared.record().topic(), prepared.bizKey(), interruptedCause));
            return;
        }
        try {
            future.get();
        } catch (ExecutionException e) {
            notifyAfterCommitRecordFailure(prepared,
                    translateExecutionException("Kafka 事务后批量发信", prepared.record().topic(), prepared.bizKey(), e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notifyAfterCommitRecordFailure(prepared,
                    translateInterruptedException("Kafka 事务后批量发信", prepared.record().topic(), prepared.bizKey(), e));
        }
    }

    /**
     * 记录事务提交后单条发信失败日志并触发补偿回调 SPI
     */
    private void notifyAfterCommitRecordFailure(PreparedRecord prepared, Throwable ex) {
        ProducerRecord<Object, Object> record = prepared.record();
        String keyStr = record.key() != null ? record.key().toString() : null;
        log.error("{} 本地事务提交后批量发信单条失败: topic={}, partition={}, key={}, bizKey={}, traceId={}, error={}",
                KafkaConstants.LOG_TX_POST_COMMIT_FAILED_PREFIX,
                record.topic(), record.partition(), keyStr, prepared.bizKey(), prepared.traceId(), ex.getMessage(), ex);
        invokeTransactionFailureCallback(
                record.topic(), record.partition(), keyStr, record.value(), prepared.bizKey(), prepared.traceId(), ex);
    }

    /**
     * 校验并规范化显式传入的等待确认超时时间（Fail-Fast）
     */
    private Duration resolveEffectiveTimeout(Duration timeout) {
        if (timeout == null) {
            return properties.getProducer().getSendTimeout();
        }
        Assert.isTrue(!timeout.isNegative() && !timeout.isZero() && timeout.toMillis() > 0,
                "timeout 必须为至少 1 毫秒的正数时长");
        return timeout;
    }

    /**
     * 阶段 1：校验并预构建载荷列表
     */
    private List<PreparedRecord> prepareBatchPayloads(
            String topic, String key, List<?> payloads, Map<String, String> customHeaders) {
        Assert.hasText(topic, "topic 不能为空");
        Assert.notNull(payloads, "payloads 不能为空");
        if (payloads.isEmpty()) {
            return Collections.emptyList();
        }
        validateBatchLimit(payloads.size());

        List<PreparedRecord> preparedRecords = new ArrayList<>(payloads.size());
        int index = 0;
        for (Object payload : payloads) {
            Assert.notNull(payload, "payloads[" + index + "] 消息载荷不能为空");
            preparedRecords.add(prepareRecord(topic, null, key, payload, null, customHeaders));
            index++;
        }
        return preparedRecords;
    }

    /**
     * 阶段 1：校验并预构建键值映射批次
     */
    private List<PreparedRecord> prepareBatchMap(
            String topic, Map<String, Object> keyPayloadMap, Map<String, String> customHeaders) {
        Assert.hasText(topic, "topic 不能为空");
        Assert.notNull(keyPayloadMap, "keyPayloadMap 不能为空");
        if (keyPayloadMap.isEmpty()) {
            return Collections.emptyList();
        }
        validateBatchLimit(keyPayloadMap.size());

        List<PreparedRecord> preparedRecords = new ArrayList<>(keyPayloadMap.size());
        for (Map.Entry<String, Object> entry : keyPayloadMap.entrySet()) {
            Object payload = entry.getValue();
            Assert.notNull(payload, "keyPayloadMap[" + entry.getKey() + "] 消息载荷不能为空");
            preparedRecords.add(prepareRecord(topic, null, entry.getKey(), payload, null, customHeaders));
        }
        return preparedRecords;
    }

    /**
     * 阶段 1：校验并预构建外部 ProducerRecord 批次（全量校验通过后再注入缺失头，保证失败零副作用）
     */
    private List<PreparedRecord> prepareBatchRecords(List<ProducerRecord<Object, Object>> records) {
        Assert.notNull(records, "records 不能为空");
        if (records.isEmpty()) {
            return Collections.emptyList();
        }
        validateBatchLimit(records.size());

        List<ProducerRecord<Object, Object>> recordList =
                records instanceof RandomAccess ? records : new ArrayList<>(records);
        int count = recordList.size();
        String[] resolvedBizKeys = new String[count];
        int[] payloadSizes = new int[count];
        for (int i = 0; i < count; i++) {
            ProducerRecord<Object, Object> record = recordList.get(i);
            Assert.notNull(record, "records[" + i + "] 消息记录不能为空");
            Assert.hasText(record.topic(), "records[" + i + "].topic 不能为空");

            String keyStr = record.key() != null ? record.key().toString() : null;
            String existingBizKey = extractHeaderString(record, MessageHeaderConstants.BIZ_KEY);
            String effectiveBizKey = StringUtils.hasText(existingBizKey)
                    ? existingBizKey
                    : resolveBizKey(record.value(), keyStr, null);
            resolvedBizKeys[i] = effectiveBizKey;
            payloadSizes[i] = validatePayloadSize(record.topic(), effectiveBizKey, record.value());
        }

        List<PreparedRecord> preparedRecords = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ProducerRecord<Object, Object> record = recordList.get(i);
            ensureContextHeaders(record, resolvedBizKeys[i]);
            String traceId = extractHeaderString(record, MessageHeaderConstants.TRACE_ID);
            preparedRecords.add(new PreparedRecord(record, resolvedBizKeys[i], traceId, payloadSizes[i]));
        }
        return preparedRecords;
    }

    /**
     * 校验批量发送单批数量上限（Fail-Fast）
     */
    private void validateBatchLimit(int batchSize) {
        int maxBatchSize = properties.getProducer().getMaxBatchSize();
        Assert.isTrue(maxBatchSize > 0, "ateng.boot.kafka.producer.max-batch-size 必须为大于 0 的整数");
        Assert.isTrue(batchSize <= maxBatchSize,
                String.format("批量发送消息条数超出最大限制: %d 条 (最大限制: %d 条)", batchSize, maxBatchSize));
    }

    /**
     * 阶段 2：同步批量下发并等待确认
     */
    private List<SendResult<Object, Object>> sendPreparedSync(
            String topic, List<PreparedRecord> preparedRecords, Duration effectiveTimeout) {
        if (preparedRecords.isEmpty()) {
            return Collections.emptyList();
        }
        return waitBatchFutures(topic, dispatchPreparedAsync(preparedRecords), effectiveTimeout);
    }

    /**
     * 阶段 2：异步批量下发并返回逐条 Future 列表（底层同步异常统一转译并包装为失败 Future）
     */
    private List<CompletableFuture<SendResult<Object, Object>>> dispatchPreparedAsync(
            List<PreparedRecord> preparedRecords) {
        List<CompletableFuture<SendResult<Object, Object>>> futures = new ArrayList<>(preparedRecords.size());
        for (PreparedRecord prepared : preparedRecords) {
            try {
                futures.add(doSendInternal(prepared.record(), prepared.bizKey(), prepared.traceId(), prepared.payloadSize()));
            } catch (RuntimeException ex) {
                futures.add(CompletableFuture.failedFuture(translateSyncDispatchException(prepared, ex)));
            }
        }
        return futures;
    }

    /**
     * 阶段 2：异步批量下发并聚合为单一 CompletableFuture
     */
    private CompletableFuture<List<SendResult<Object, Object>>> aggregateBatchFuture(
            List<PreparedRecord> preparedRecords) {
        if (preparedRecords.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        List<CompletableFuture<SendResult<Object, Object>>> futures = dispatchPreparedAsync(preparedRecords);
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }

    /**
     * 统一阻塞等待批量发送 Future 确认（共享批次总截止时间，避免超时线性叠加）
     */
    private List<SendResult<Object, Object>> waitBatchFutures(
            String topic,
            List<CompletableFuture<SendResult<Object, Object>>> futures,
            Duration effectiveTimeout) {
        long deadlineNanos = System.nanoTime() + effectiveTimeout.toNanos();
        List<SendResult<Object, Object>> results = new ArrayList<>(futures.size());
        for (CompletableFuture<SendResult<Object, Object>> future : futures) {
            long remainingNanos = Math.max(0L, deadlineNanos - System.nanoTime());
            results.add(awaitFutureWithTranslation(
                    "Kafka 批量发信", topic, null, future, effectiveTimeout, remainingNanos));
        }
        return results;
    }

    /**
     * 统一等待单条 Future 并将超时、执行与中断异常转译为语义化 MessageDeliveryException
     */
    private SendResult<Object, Object> awaitFutureWithTranslation(
            String operation,
            String topic,
            String bizKey,
            CompletableFuture<SendResult<Object, Object>> future,
            Duration totalTimeout,
            long remainingNanos) {
        try {
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw translateTimeoutException(operation, topic, bizKey, totalTimeout, e);
        } catch (ExecutionException e) {
            throw translateExecutionException(operation, topic, bizKey, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw translateInterruptedException(operation, topic, bizKey, e);
        }
    }

    private MessageDeliveryException translateSyncDispatchException(PreparedRecord prepared, RuntimeException ex) {
        if (ex instanceof MessageDeliveryException mde) {
            return mde;
        }
        return new MessageDeliveryException(
                MQ_TYPE_KAFKA,
                prepared.record().topic(),
                prepared.bizKey(),
                CommonResultCode.INTERNAL_SERVER_ERROR,
                String.format("Kafka 批量发信底层同步异常: topic=%s, bizKey=%s, error=%s",
                        prepared.record().topic(), prepared.bizKey(), ex.getMessage()),
                ex);
    }

    private MessageDeliveryException translateTimeoutException(
            String operation, String topic, String bizKey, Duration totalTimeout, TimeoutException ex) {
        String message = bizKey != null
                ? String.format("%s确认超时 (等待 %dms, topic=%s, bizKey=%s)",
                operation, totalTimeout.toMillis(), topic, bizKey)
                : String.format("%s超时 (等待 %dms, topic=%s): %s",
                operation, totalTimeout.toMillis(), topic, ex.getMessage());
        return new MessageDeliveryException(MQ_TYPE_KAFKA, topic, bizKey, message, ex);
    }

    private MessageDeliveryException translateExecutionException(
            String operation, String topic, String bizKey, ExecutionException ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        if (cause instanceof MessageDeliveryException mde) {
            return mde;
        }
        String message = bizKey != null
                ? String.format("%s失败: topic=%s, bizKey=%s, error=%s",
                operation, topic, bizKey, cause.getMessage())
                : String.format("%s底层异常: %s", operation, cause.getMessage());
        return new MessageDeliveryException(
                MQ_TYPE_KAFKA, topic, bizKey, CommonResultCode.INTERNAL_SERVER_ERROR, message, cause);
    }

    private MessageDeliveryException translateInterruptedException(
            String operation, String topic, String bizKey, InterruptedException ex) {
        String message = bizKey != null
                ? String.format("%s被中断: topic=%s, bizKey=%s", operation, topic, bizKey)
                : String.format("%s线程被中断: %s", operation, ex.getMessage());
        return new MessageDeliveryException(
                MQ_TYPE_KAFKA, topic, bizKey, CommonResultCode.INTERNAL_SERVER_ERROR, message, ex);
    }

    // =========================================================================
    // 7. 内部构造、校验与网络发送
    // =========================================================================

    /**
     * 内部解析完毕的出站消息元数据载体，消除重复计算与参数泥团 (Data Clumps)
     */
    private record PreparedRecord(
            ProducerRecord<Object, Object> record,
            String bizKey,
            String traceId,
            int payloadSize
    ) {}

    /**
     * 构建 ProducerRecord 对象并执行四维上下文注入与报文体积 Fail-Fast 校验
     */
    private PreparedRecord prepareRecord(
            String topic,
            Integer partition,
            String key,
            Object payload,
            String explicitBizKey,
            Map<String, String> customHeaders) {
        // 1. 解析业务键 (优先级：显式 bizKey -> 路由 key -> 反射 @Id/bizId/id -> MDC traceId / fastSimpleUUID)
        String resolvedBizKey = resolveBizKey(payload, key, explicitBizKey);

        // 2. 校验出站报文原始体积 (Fail-Fast 防爆)
        int rawSize = validatePayloadSize(topic, resolvedBizKey, payload);

        // 3. 先安全注入业务自定义消息头，再写入框架四维上下文与系统保留头（禁止自定义头覆盖或伪造系统头）
        RecordHeaders headers = new RecordHeaders();
        appendCustomHeaders(headers, customHeaders);

        Map<String, Object> contextHeaders = new HashMap<>();
        MessageContextPropagator.inject(contextHeaders, resolvedBizKey);

        for (Map.Entry<String, Object> entry : contextHeaders.entrySet()) {
            if (entry.getValue() != null) {
                byte[] valBytes = entry.getValue().toString().getBytes(StandardCharsets.UTF_8);
                headers.add(new RecordHeader(entry.getKey(), valBytes));
            }
        }

        Object traceIdObj = contextHeaders.get(MessageHeaderConstants.TRACE_ID);
        String traceId = traceIdObj != null ? traceIdObj.toString() : null;

        ProducerRecord<Object, Object> record = new ProducerRecord<>(topic, partition, key, payload, headers);
        return new PreparedRecord(record, resolvedBizKey, traceId, rawSize);
    }

    /**
     * 安全追加自定义业务消息头，自动过滤 null/空白键名、null 值与框架系统保留头
     */
    private void appendCustomHeaders(RecordHeaders headers, Map<String, String> customHeaders) {
        if (customHeaders == null || customHeaders.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
            String rawKey = entry.getKey();
            String value = entry.getValue();
            if (!StringUtils.hasText(rawKey) || value == null) {
                continue;
            }
            String trimmedKey = rawKey.trim();
            if (RESERVED_SYSTEM_HEADER_KEYS.contains(trimmedKey.toLowerCase(Locale.ROOT))) {
                log.warn("[ateng-boot-kafka] 忽略覆盖系统保留消息头的自定义请求头: key={}", trimmedKey);
                continue;
            }
            headers.add(new RecordHeader(trimmedKey, value.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * 统一校验出站消息载荷字节大小不超过配置上限（Fail-Fast 阻断）
     */
    private int validatePayloadSize(String topic, String bizKey, Object payload) {
        int rawSize = calculatePayloadSize(payload);
        long maxPayloadSize = properties.getProducer().getMaxPayloadSize().toBytes();
        if (rawSize > maxPayloadSize) {
            throw new MessageDeliveryException(
                    MQ_TYPE_KAFKA,
                    topic,
                    bizKey,
                    CommonResultCode.BAD_REQUEST,
                    String.format("出站消息报文体积超出最大限制: %d 字节 (最大限制: %d 字节, payload=%s)",
                            rawSize, maxPayloadSize, payload != null ? payload.getClass().getSimpleName() : "null"),
                    null);
        }
        return rawSize;
    }

    /**
     * 防御性补全外部 ProducerRecord 缺失的四维上下文头
     */
    private void ensureContextHeaders(ProducerRecord<Object, Object> record) {
        if (record == null || record.headers() == null) {
            return;
        }
        String keyStr = record.key() != null ? record.key().toString() : null;
        String resolvedBizKey = resolveBizKey(record.value(), keyStr, null);
        ensureContextHeaders(record, resolvedBizKey);
    }

    /**
     * 防御性补全外部 ProducerRecord 缺失的四维上下文头（复用已解析 bizKey）
     */
    private void ensureContextHeaders(ProducerRecord<Object, Object> record, String resolvedBizKey) {
        if (record == null || record.headers() == null) {
            return;
        }
        Map<String, Object> contextHeaders = new HashMap<>();
        MessageContextPropagator.inject(contextHeaders, resolvedBizKey);
        for (Map.Entry<String, Object> entry : contextHeaders.entrySet()) {
            if (entry.getValue() != null && record.headers().lastHeader(entry.getKey()) == null) {
                record.headers().add(new RecordHeader(entry.getKey(), entry.getValue().toString().getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

    /**
     * 包装事务操作句柄以在消息发送时自动透传四维上下文与载荷体积 Fail-Fast 防线
     */
    @SuppressWarnings("unchecked")
    private KafkaOperations<Object, Object> wrapTransactionalOperations(KafkaOperations<Object, Object> operations) {
        if (operations == null) {
            return null;
        }
        return (KafkaOperations<Object, Object>) Proxy.newProxyInstance(
                KafkaOperations.class.getClassLoader(),
                new Class<?>[]{KafkaOperations.class},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    if ("send".equals(methodName) && args != null && args.length > 0) {
                        if (args[0] instanceof ProducerRecord<?, ?> rec) {
                            prepareTransactionalRecord((ProducerRecord<Object, Object>) rec);
                            return invokeWithUnwrappedException(method, operations, args);
                        } else if (args[0] instanceof String) {
                            ProducerRecord<Object, Object> rec = convertToProducerRecord(args);
                            prepareTransactionalRecord(rec);
                            return operations.send(rec);
                        } else if (args[0] instanceof Message<?> msg) {
                            Message<?> enrichedMsg = prepareTransactionalMessage(msg);
                            return operations.send(enrichedMsg);
                        }
                    } else if ("sendDefault".equals(methodName) && args != null && args.length > 0) {
                        return handleTransactionalSendDefault(operations, method, args);
                    }
                    return invokeWithUnwrappedException(method, operations, args);
                }
        );
    }

    /**
     * 事务代理统一补全四维上下文头并执行单条报文大小 Fail-Fast 阻断
     */
    private void prepareTransactionalRecord(ProducerRecord<Object, Object> record) {
        ensureContextHeaders(record);
        String bizKey = extractHeaderString(record, MessageHeaderConstants.BIZ_KEY);
        validatePayloadSize(record.topic(), bizKey, record.value());
    }

    /**
     * 事务代理拦截 send(Message<?>)：前置校验报文大小并按需补全缺失的四维上下文头
     */
    private Message<?> prepareTransactionalMessage(Message<?> message) {
        Object topicHeader = message.getHeaders().get(KafkaHeaders.TOPIC);
        String topic = topicHeader != null ? topicHeader.toString() : kafkaTemplate.getDefaultTopic();
        Object keyHeader = message.getHeaders().get(KafkaHeaders.KEY);
        String keyStr = keyHeader != null ? keyHeader.toString() : null;
        Object existingBizKey = message.getHeaders().get(MessageHeaderConstants.BIZ_KEY);
        String resolvedBizKey = existingBizKey != null && StringUtils.hasText(existingBizKey.toString())
                ? existingBizKey.toString()
                : resolveBizKey(message.getPayload(), keyStr, null);

        validatePayloadSize(topic, resolvedBizKey, message.getPayload());

        Map<String, Object> contextHeaders = new HashMap<>();
        MessageContextPropagator.inject(contextHeaders, resolvedBizKey);
        return MessageBuilder.fromMessage(message)
                .copyHeadersIfAbsent(contextHeaders)
                .build();
    }

    /**
     * 事务代理拦截 sendDefault(...)：执行报文体积 Fail-Fast 防线；若配置了默认主题则转为带上下文的 ProducerRecord 投递
     */
    private Object handleTransactionalSendDefault(
            KafkaOperations<Object, Object> operations,
            Method method,
            Object[] args) throws Throwable {
        Integer partition = null;
        Long timestamp = null;
        Object key = null;
        Object payload;
        if (args.length == 1) {
            payload = args[0];
        } else if (args.length == 2) {
            key = args[0];
            payload = args[1];
        } else if (args.length == 3) {
            partition = (Integer) args[0];
            key = args[1];
            payload = args[2];
        } else {
            partition = (Integer) args[0];
            timestamp = (Long) args[1];
            key = args[2];
            payload = args[3];
        }

        String defaultTopic = kafkaTemplate.getDefaultTopic();
        if (StringUtils.hasText(defaultTopic)) {
            ProducerRecord<Object, Object> rec = new ProducerRecord<>(defaultTopic, partition, timestamp, key, payload);
            prepareTransactionalRecord(rec);
            return operations.send(rec);
        }

        String keyStr = key != null ? key.toString() : null;
        String resolvedBizKey = resolveBizKey(payload, keyStr, null);
        validatePayloadSize(null, resolvedBizKey, payload);
        return invokeWithUnwrappedException(method, operations, args);
    }

    /**
     * 代理反射调用并解包底层真实异常
     */
    private Object invokeWithUnwrappedException(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ite) {
            throw ite.getCause() != null ? ite.getCause() : ite;
        }
    }

    /**
     * 将 send(topic, ...) 的重载可变参数转换为标准的 ProducerRecord 实例
     */
    private ProducerRecord<Object, Object> convertToProducerRecord(Object[] args) {
        String topic = (String) args[0];
        if (args.length == 2) {
            return new ProducerRecord<>(topic, args[1]);
        } else if (args.length == 3) {
            return new ProducerRecord<>(topic, args[1], args[2]);
        } else if (args.length == 4) {
            return new ProducerRecord<>(topic, (Integer) args[1], args[2], args[3]);
        } else if (args.length >= 5) {
            return new ProducerRecord<>(topic, (Integer) args[1], (Long) args[2], args[3], args[4]);
        }
        throw new IllegalArgumentException("不支持的 send 参数组合");
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
        return awaitFutureWithTranslation(
                "Kafka 同步发送", record.topic(), bizKey, future, timeout, timeout.toNanos());
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
        String mdcTraceId = MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY);
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
        String prevTraceId = MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY);
        try {
            if (traceId != null && !traceId.isBlank()) {
                MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, traceId);
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
                MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, prevTraceId);
            } else {
                MDC.remove(MessageContextPropagator.MDC_TRACE_ID_KEY);
            }
        }
    }
}
