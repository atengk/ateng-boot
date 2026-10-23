package io.github.atengk.boot.mqtt.template;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.mqtt.codec.MqttMessageConverter;
import io.github.atengk.boot.mqtt.context.MqttContextHolder;
import io.github.atengk.boot.mqtt.defense.PayloadGuard;
import io.github.atengk.boot.mqtt.exception.MqttTimeoutException;
import io.github.atengk.boot.mqtt.handler.DefaultMqttSendFailureHandler;
import io.github.atengk.boot.mqtt.handler.MqttSendFailureHandler;
import io.github.atengk.boot.mqtt.metrics.MqttMetrics;
import io.github.atengk.boot.mqtt.registry.MqttClientHandle;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import io.github.atengk.boot.mqtt.requestreply.MqttRequestReplyManager;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttActionListener;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * MQTT 核心发布与事务协同操作门面
 * 整合多命名客户端路由、载荷自适应转译、双轨四维上下文透传、本地事务感知安全发信与大报文尺寸防卫
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class MqttTemplate {

    private static final Logger log = LoggerFactory.getLogger(MqttTemplate.class);

    private static final boolean SPRING_TX_PRESENT = ClassUtils.isPresent(
            "org.springframework.transaction.support.TransactionSynchronizationManager",
            MqttTemplate.class.getClassLoader()
    );

    private final NamedMqttClientRegistry clientRegistry;
    private final PayloadGuard payloadGuard;
    private final MqttSendFailureHandler failureHandler;
    private final Executor executor;
    private final MqttRequestReplyManager requestReplyManager;
    private final MqttMessageConverter messageConverter;
    private final MqttMetrics metrics;

    public MqttTemplate(NamedMqttClientRegistry clientRegistry, PayloadGuard payloadGuard) {
        this(clientRegistry, payloadGuard, new DefaultMqttSendFailureHandler(), null, null, null, null);
    }

    public MqttTemplate(NamedMqttClientRegistry clientRegistry,
                        PayloadGuard payloadGuard,
                        MqttSendFailureHandler failureHandler) {
        this(clientRegistry, payloadGuard, failureHandler, null, null, null, null);
    }

    public MqttTemplate(NamedMqttClientRegistry clientRegistry,
                        PayloadGuard payloadGuard,
                        MqttSendFailureHandler failureHandler,
                        Executor executor) {
        this(clientRegistry, payloadGuard, failureHandler, executor, null, null, null);
    }

    public MqttTemplate(NamedMqttClientRegistry clientRegistry,
                        PayloadGuard payloadGuard,
                        MqttSendFailureHandler failureHandler,
                        Executor executor,
                        MqttRequestReplyManager requestReplyManager) {
        this(clientRegistry, payloadGuard, failureHandler, executor, requestReplyManager, null, null);
    }

    public MqttTemplate(NamedMqttClientRegistry clientRegistry,
                        PayloadGuard payloadGuard,
                        MqttSendFailureHandler failureHandler,
                        Executor executor,
                        MqttRequestReplyManager requestReplyManager,
                        MqttMessageConverter messageConverter,
                        MqttMetrics metrics) {
        this.clientRegistry = Objects.requireNonNull(clientRegistry, "clientRegistry must not be null");
        this.payloadGuard = payloadGuard != null ? payloadGuard : new PayloadGuard();
        this.failureHandler = failureHandler != null ? failureHandler : new DefaultMqttSendFailureHandler();
        this.executor = executor;
        this.requestReplyManager = requestReplyManager;
        this.messageConverter = messageConverter;
        this.metrics = metrics;
    }

    // ==================== 默认客户端同步发信接口 ====================

    /**
     * 向目标主题发布消息（QoS 0，非保留）
     *
     * @param topic   目标主题
     * @param payload 载荷对象（支持 byte[]、String、POJO）
     */
    public void publish(String topic, Object payload) {
        publish(topic, payload, 0, false);
    }

    /**
     * 向目标主题发布指定 QoS 的消息（非保留）
     *
     * @param topic   目标主题
     * @param payload 载荷对象
     * @param qos     QoS 等级 (0, 1, 2)
     */
    public void publish(String topic, Object payload, int qos) {
        publish(topic, payload, qos, false);
    }

    /**
     * 向目标主题发布指定 QoS 与 Retained 属性的消息
     *
     * @param topic    目标主题
     * @param payload  载荷对象
     * @param qos      QoS 等级 (0, 1, 2)
     * @param retained 是否保留消息
     */
    public void publish(String topic, Object payload, int qos, boolean retained) {
        publishToClient(null, topic, payload, qos, retained);
    }

    /**
     * 向目标主题发布保留消息（QoS 0，Retained）
     *
     * @param topic   目标主题
     * @param payload 载荷对象
     */
    public void publishRetained(String topic, Object payload) {
        publishRetained(topic, payload, 0);
    }

    /**
     * 向目标主题发布指定 QoS 的保留消息
     *
     * @param topic   目标主题
     * @param payload 载荷对象
     * @param qos     QoS 等级 (0, 1, 2)
     */
    public void publishRetained(String topic, Object payload, int qos) {
        publish(topic, payload, qos, true);
    }

    // ==================== 显式指定客户端同步发信接口 ====================

    /**
     * 指定客户端向目标主题发布消息（QoS 0，非保留）
     *
     * @param clientName 客户端逻辑名称
     * @param topic      目标主题
     * @param payload    载荷对象
     */
    public void publishToClient(String clientName, String topic, Object payload) {
        publishToClient(clientName, topic, payload, 0, false);
    }

    /**
     * 指定客户端向目标主题发布指定 QoS 的消息（非保留）
     *
     * @param clientName 客户端逻辑名称
     * @param topic      目标主题
     * @param payload    载荷对象
     * @param qos        QoS 等级 (0, 1, 2)
     */
    public void publishToClient(String clientName, String topic, Object payload, int qos) {
        publishToClient(clientName, topic, payload, qos, false);
    }

    /**
     * 指定客户端向目标主题发布指定 QoS 与 Retained 属性的消息
     *
     * @param clientName 客户端逻辑名称
     * @param topic      目标主题
     * @param payload    载荷对象
     * @param qos        QoS 等级 (0, 1, 2)
     * @param retained   是否保留消息
     */
    public void publishToClient(String clientName, String topic, Object payload, int qos, boolean retained) {
        byte[] payloadBytes = toPayloadBytes(payload);
        doPublish(clientName, topic, payloadBytes, qos, retained);
    }

    /**
     * 指定客户端向目标主题发布保留消息
     *
     * @param clientName 客户端逻辑名称
     * @param topic      目标主题
     * @param payload    载荷对象
     * @param qos        QoS 等级 (0, 1, 2)
     */
    public void publishRetainedToClient(String clientName, String topic, Object payload, int qos) {
        publishToClient(clientName, topic, payload, qos, true);
    }

    // ==================== 本地事务感知发信接口 (sendAfterCommit) ====================

    /**
     * 在当前物理事务成功提交后同步发信（QoS 0，非保留）；若无事务则平滑降级就地发信
     *
     * @param topic   目标主题
     * @param payload 载荷对象
     */
    public void sendAfterCommit(String topic, Object payload) {
        sendAfterCommit(topic, payload, 0, false);
    }

    /**
     * 在当前物理事务成功提交后同步发信（非保留）；若无事务则平滑降级就地发信
     *
     * @param topic   目标主题
     * @param payload 载荷对象
     * @param qos     QoS 等级 (0, 1, 2)
     */
    public void sendAfterCommit(String topic, Object payload, int qos) {
        sendAfterCommit(topic, payload, qos, false);
    }

    /**
     * 在当前物理事务成功提交后同步发信；若无事务则平滑降级就地发信
     *
     * @param topic    目标主题
     * @param payload  载荷对象
     * @param qos      QoS 等级 (0, 1, 2)
     * @param retained 是否保留消息
     */
    public void sendAfterCommit(String topic, Object payload, int qos, boolean retained) {
        sendAfterCommitToClient(null, topic, payload, qos, retained);
    }

    /**
     * 指定客户端在当前物理事务成功提交后发信；若无事务则平滑降级就地发信
     *
     * @param clientName 客户端逻辑名称
     * @param topic      目标主题
     * @param payload    载荷对象
     * @param qos        QoS 等级 (0, 1, 2)
     * @param retained   是否保留消息
     */
    public void sendAfterCommitToClient(String clientName, String topic, Object payload, int qos, boolean retained) {
        // 1. 前置快速失败防御 (Fail-Fast)
        validateQos(qos);
        validateTopic(topic);
        byte[] payloadBytes = toPayloadBytes(payload);
        String effectiveClient = resolveEffectiveClientName(clientName);
        MqttClientHandle handle = resolveClientHandle(effectiveClient);
        payloadGuard.validate(payloadBytes, handle.getClientProperties().getMaxPayloadSize(), topic);

        // 2. 事务同步注册或降级执行
        if (isTxActive()) {
            Runnable deliveryTask = AsyncContext.wrap(() -> {
                try {
                    doPublishWithResolvedHandle(handle, topic, payloadBytes, qos, retained);
                } catch (Throwable e) {
                    triggerFailureHandler(effectiveClient, topic, payloadBytes, qos, retained, e);
                    throw e;
                }
            });
            TxSynchronizationDelegate.registerAfterCommit(deliveryTask, topic, effectiveClient);
        } else {
            doPublishWithResolvedHandle(handle, topic, payloadBytes, qos, retained);
        }
    }

    // ==================== 异步发信接口 (publishAsync) ====================

    /**
     * 异步发布消息
     *
     * @param topic    目标主题
     * @param payload  载荷对象
     * @param qos      QoS 等级 (0, 1, 2)
     * @param retained 是否保留消息
     * @return 异步执行 Future
     */
    public CompletableFuture<Void> publishAsync(String topic, Object payload, int qos, boolean retained) {
        return publishAsyncToClient(null, topic, payload, qos, retained);
    }

    /**
     * 指定客户端异步发布消息
     *
     * @param clientName 客户端逻辑名称
     * @param topic      目标主题
     * @param payload    载荷对象
     * @param qos        QoS 等级 (0, 1, 2)
     * @param retained   是否保留消息
     * @return 异步执行 Future
     */
    public CompletableFuture<Void> publishAsyncToClient(String clientName, String topic, Object payload, int qos, boolean retained) {
        validateQos(qos);
        validateTopic(topic);
        byte[] payloadBytes = toPayloadBytes(payload);
        String effectiveClient = resolveEffectiveClientName(clientName);
        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            MqttClientHandle handle = resolveClientHandle(effectiveClient);
            payloadGuard.validate(payloadBytes, handle.getClientProperties().getMaxPayloadSize(), topic);

            MqttMessage message = buildMqttMessage(handle, payloadBytes, qos, retained);
            IMqttToken token = handle.getAsyncClient().publish(topic, message);
            token.setActionCallback(new MqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    if (metrics != null) {
                        metrics.recordPublished(effectiveClient, topic, qos);
                    }
                    future.complete(null);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    triggerFailureHandler(effectiveClient, topic, payloadBytes, qos, retained, exception);
                    future.completeExceptionally(createDeliveryException(effectiveClient, topic, exception));
                }
            });
        } catch (Throwable e) {
            triggerFailureHandler(effectiveClient, topic, payloadBytes, qos, retained, e);
            future.completeExceptionally(createDeliveryException(effectiveClient, topic, e));
        }

        return future;
    }

    // ==================== 内部底层核心投递逻辑 ====================

    private void doPublish(String clientName, String topic, byte[] payloadBytes, int qos, boolean retained) {
        validateQos(qos);
        validateTopic(topic);
        String effectiveClient = resolveEffectiveClientName(clientName);
        try {
            MqttClientHandle handle = resolveClientHandle(effectiveClient);
            payloadGuard.validate(payloadBytes, handle.getClientProperties().getMaxPayloadSize(), topic);
            doPublishWithResolvedHandle(handle, topic, payloadBytes, qos, retained);
        } catch (Throwable e) {
            triggerFailureHandler(effectiveClient, topic, payloadBytes, qos, retained, e);
            throw createDeliveryException(effectiveClient, topic, e);
        }
    }

    private void doPublishWithResolvedHandle(MqttClientHandle handle, String topic, byte[] payloadBytes, int qos, boolean retained) {
        MqttMessage message = buildMqttMessage(handle, payloadBytes, qos, retained);
        Duration timeout = handle.getClientProperties().getPublishTimeout();
        long timeoutMs = (timeout != null && !timeout.isNegative() && !timeout.isZero()) ? timeout.toMillis() : 10000L;

        try {
            IMqttToken token = handle.getAsyncClient().publish(topic, message);
            token.waitForCompletion(timeoutMs);
            if (metrics != null) {
                metrics.recordPublished(handle.getClientName(), topic, qos);
            }
        } catch (Throwable e) {
            throw createDeliveryException(handle.getClientName(), topic, e);
        }
    }

    /**
     * 根据客户端协议版本自适应组装报文（MQTT 5.0 注入四维 User Properties，3.1.1 保持零侵入纯真载荷）
     */
    private MqttMessage buildMqttMessage(MqttClientHandle handle, byte[] payloadBytes, int qos, boolean retained) {
        MqttProperties properties = null;

        if (isMqttV5(handle)) {
            properties = new MqttProperties();
            List<UserProperty> userProperties = new ArrayList<>();

            // 1. TraceId (优先抓取 MDC，无则纯原生快速生成)
            String traceId = MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY);
            if (!StringUtils.hasText(traceId)) {
                traceId = IdUtil.fastSimpleUUID();
            }
            userProperties.add(new UserProperty(MessageHeaderConstants.TRACE_ID, traceId));

            // 2. TenantId
            String tenantId = TenantContextHolder.getTenantId();
            if (StringUtils.hasText(tenantId)) {
                userProperties.add(new UserProperty(MessageHeaderConstants.TENANT_ID, tenantId));
            }

            // 3. UserId
            String userId = UserContextHolder.get();
            if (StringUtils.hasText(userId)) {
                userProperties.add(new UserProperty(MessageHeaderConstants.USER_ID, userId));
            }

            // 4. IgnoreTenant
            if (TenantContextHolder.isIgnoreTenant()) {
                userProperties.add(new UserProperty(MessageHeaderConstants.IGNORE_TENANT, "true"));
            }

            // 5. SendTimestamp
            userProperties.add(new UserProperty(MessageHeaderConstants.SEND_TIMESTAMP, String.valueOf(System.currentTimeMillis())));

            properties.setUserProperties(userProperties);
        }

        return new MqttMessage(payloadBytes, qos, retained, properties);
    }

    /**
     * 判断当前客户端连接是否为 MQTT 5.0
     */
    private boolean isMqttV5(MqttClientHandle handle) {
        if (handle.getClientProperties() != null && StringUtils.hasText(handle.getClientProperties().getProtocolVersion())) {
            String version = handle.getClientProperties().getProtocolVersion().trim();
            if ("3.1.1".equalsIgnoreCase(version) || "3.1".equalsIgnoreCase(version)) {
                return false;
            }
            if ("5.0".equalsIgnoreCase(version) || "5".equalsIgnoreCase(version)) {
                return true;
            }
        }
        if (handle.getConnectionOptions() != null) {
            return handle.getConnectionOptions().getMqttVersion() == 5;
        }
        return true;
    }

    /**
     * 解析生效的受管 MQTT 客户端逻辑名称
     */
    private String resolveEffectiveClientName(String clientName) {
        if (StringUtils.hasText(clientName)) {
            return clientName;
        }
        String contextName = MqttContextHolder.getClientName();
        if (StringUtils.hasText(contextName)) {
            return contextName;
        }
        return clientRegistry.getDefaultClientName();
    }

    /**
     * 解析生效的受管 MQTT 客户端句柄
     */
    private MqttClientHandle resolveClientHandle(String effectiveName) {
        MqttClientHandle handle;
        if (StringUtils.hasText(effectiveName)) {
            handle = clientRegistry.getClientHandle(effectiveName);
        } else {
            handle = clientRegistry.getDefaultClientHandle();
        }

        if (handle == null) {
            throw new BusinessException("No MQTT client handle available for name [" + effectiveName + "]");
        }
        if (!handle.isConnected()) {
            throw new BusinessException("MQTT client [" + handle.getClientName() + "] is not connected to broker");
        }
        return handle;
    }

    /**
     * 载荷对象多态转译为原始字节数组
     */
    private byte[] toPayloadBytes(Object payload) {
        if (payload == null) {
            throw new IllegalArgumentException("MQTT message payload must not be null");
        }
        if (messageConverter != null) {
            return messageConverter.toPayload(payload);
        }
        if (payload instanceof byte[] bytes) {
            return bytes;
        }
        if (payload instanceof String str) {
            return str.getBytes(StandardCharsets.UTF_8);
        }
        return JsonUtil.toJsonBytes(payload);
    }

    private void validateTopic(String topic) {
        if (!StringUtils.hasText(topic)) {
            throw new IllegalArgumentException("MQTT topic must not be null or blank");
        }
    }

    private void validateQos(int qos) {
        if (qos < 0 || qos > 2) {
            throw new IllegalArgumentException("Invalid MQTT QoS [" + qos + "]. Allowed values are 0, 1, or 2");
        }
    }

    private boolean isTxActive() {
        return SPRING_TX_PRESENT && TxSynchronizationDelegate.isSynchronizationActive();
    }

    /**
     * 事务同步隔离委托类（当且仅当 Spring 事务相关类在 classpath 存在时才会被类加载器加载）
     */
    private static class TxSynchronizationDelegate {
        static boolean isSynchronizationActive() {
            return org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive();
        }

        static void registerAfterCommit(Runnable deliveryTask, String topic, String effectiveClient) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            deliveryTask.run();
                        }

                        @Override
                        public void afterCompletion(int status) {
                            if (status != org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED) {
                                log.warn("[MQTT] Transaction uncommitted or rolled back (status: {}), canceled delivery to topic [{}] on client [{}]",
                                        status, topic, effectiveClient);
                            }
                        }
                    }
            );
        }
    }

    private void triggerFailureHandler(String clientName, String topic, byte[] payload, int qos, boolean retained, Throwable cause) {
        if (failureHandler != null) {
            try {
                failureHandler.onFailure(clientName, topic, payload, qos, retained, cause);
            } catch (Throwable handlerEx) {
                log.warn("[MQTT] Error executing MqttSendFailureHandler: {}", handlerEx.getMessage(), handlerEx);
            }
        }
    }

    private RuntimeException createDeliveryException(String clientName, String topic, Throwable cause) {
        if (cause instanceof BusinessException be) {
            return be;
        }
        String traceId = MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY);
        String msg = String.format("Failed to publish MQTT message to topic [%s] on client [%s]: %s",
                topic, clientName != null ? clientName : "unknown", cause.getMessage());
        return new MessageDeliveryException("mqtt", topic, traceId, msg, cause);
    }

    // ==================== 编程式动态订阅与退订管理接口 ====================

    /**
     * 编程式订阅指定主题
     *
     * @param topicFilter 订阅主题过滤器
     * @param qos         QoS 等级 (0, 1, 2)
     * @param callback    消息回调函数
     */
    public void subscribe(String topicFilter, int qos, Consumer<MqttMessage> callback) {
        subscribeToClient(null, topicFilter, qos, callback);
    }

    /**
     * 编程式订阅指定主题（双参数主题与载荷回调）
     *
     * @param topicFilter 订阅主题过滤器
     * @param qos         QoS 等级 (0, 1, 2)
     * @param callback    (topic, payload) 回调函数
     */
    public void subscribe(String topicFilter, int qos, BiConsumer<String, byte[]> callback) {
        subscribeToClient(null, topicFilter, qos, callback);
    }

    /**
     * 指定客户端编程式订阅指定主题
     *
     * @param clientName  客户端逻辑名称
     * @param topicFilter 订阅主题过滤器
     * @param qos         QoS 等级 (0, 1, 2)
     * @param callback    消息回调函数
     */
    public void subscribeToClient(String clientName, String topicFilter, int qos, Consumer<MqttMessage> callback) {
        validateTopic(topicFilter);
        validateQos(qos);
        Objects.requireNonNull(callback, "callback must not be null");

        String effectiveClient = resolveEffectiveClientName(clientName);
        MqttClientHandle handle = resolveClientHandle(effectiveClient);

        try {
            org.eclipse.paho.mqttv5.common.MqttSubscription subscription = new MqttSubscription(topicFilter, qos);
            org.eclipse.paho.mqttv5.client.IMqttMessageListener listener = (actualTopic, message) -> {
                Runnable task = () -> {
                    Map<String, Object> headers = extractHeaders(message);
                    MessageContextPropagator.runInContext(headers, () -> callback.accept(message));
                };
                if (executor != null) {
                    executor.execute(task);
                } else {
                    task.run();
                }
            };
            // 使用数组重载以规避 Paho 1.2.5 单参重载中的 subscriptionIdentifiers 空指针/越界 Bug
            IMqttToken token = handle.getAsyncClient().subscribe(
                    new org.eclipse.paho.mqttv5.common.MqttSubscription[]{subscription},
                    null,
                    null,
                    new org.eclipse.paho.mqttv5.client.IMqttMessageListener[]{listener},
                    null
            );
            long timeoutMs = handle.getClientProperties().getConnectionTimeout().toMillis();
            token.waitForCompletion(timeoutMs > 0 ? timeoutMs : 5000L);
            log.info("[MQTT] Client [{}] subscribed to [{}] with QoS [{}] dynamically", effectiveClient, topicFilter, qos);
        } catch (MqttException e) {
            log.error("[MQTT] Dynamic subscribe failed on client [{}] to topic [{}]: {}", effectiveClient, topicFilter, e.getMessage(), e);
            throw new BusinessException("Failed to subscribe MQTT topic [" + topicFilter + "] on client [" + effectiveClient + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 指定客户端编程式订阅指定主题（双参数回调）
     *
     * @param clientName  客户端逻辑名称
     * @param topicFilter 订阅主题过滤器
     * @param qos         QoS 等级 (0, 1, 2)
     * @param callback    (topic, payload) 回调函数
     */
    public void subscribeToClient(String clientName, String topicFilter, int qos, BiConsumer<String, byte[]> callback) {
        Objects.requireNonNull(callback, "callback must not be null");
        subscribeToClient(clientName, topicFilter, qos, message -> {
            byte[] payload = message != null ? message.getPayload() : new byte[0];
            callback.accept(topicFilter, payload);
        });
    }

    /**
     * 退订指定主题
     *
     * @param topicFilter 订阅主题过滤器
     */
    public void unsubscribe(String topicFilter) {
        unsubscribeFromClient(null, topicFilter);
    }

    /**
     * 指定客户端退订指定主题
     *
     * @param clientName  客户端逻辑名称
     * @param topicFilter 订阅主题过滤器
     */
    public void unsubscribeFromClient(String clientName, String topicFilter) {
        validateTopic(topicFilter);
        String effectiveClient = resolveEffectiveClientName(clientName);
        MqttClientHandle handle = resolveClientHandle(effectiveClient);

        try {
            IMqttToken token = handle.getAsyncClient().unsubscribe(topicFilter);
            long timeoutMs = handle.getClientProperties().getConnectionTimeout().toMillis();
            token.waitForCompletion(timeoutMs > 0 ? timeoutMs : 5000L);
            log.info("[MQTT] Client [{}] unsubscribed from [{}] dynamically", effectiveClient, topicFilter);
        } catch (MqttException e) {
            log.error("[MQTT] Dynamic unsubscribe failed on client [{}] from topic [{}]: {}", effectiveClient, topicFilter, e.getMessage(), e);
            throw new BusinessException("Failed to unsubscribe MQTT topic [" + topicFilter + "] on client [" + effectiveClient + "]: " + e.getMessage(), e);
        }
    }

    // ==================== 请求-响应模式 (Request-Response Pattern) 双向控制闭环接口 ====================

    /**
     * 发送请求并同步阻塞等待回执响应（默认客户端，QoS 1）
     *
     * @param topic   目标请求主题
     * @param payload 请求载荷
     * @param timeout 超时阈值
     * @return 响应报文
     */
    public MqttMessage sendAndReceive(String topic, Object payload, Duration timeout) {
        return sendAndReceive(null, topic, payload, 1, timeout);
    }

    /**
     * 发送请求并同步阻塞等待回执响应（默认客户端）
     *
     * @param topic   目标请求主题
     * @param payload 请求载荷
     * @param qos     QoS 等级
     * @param timeout 超时阈值
     * @return 响应报文
     */
    public MqttMessage sendAndReceive(String topic, Object payload, int qos, Duration timeout) {
        return sendAndReceive(null, topic, payload, qos, timeout);
    }

    /**
     * 指定客户端发送请求并同步阻塞等待回执响应（QoS 1）
     *
     * @param clientName 客户端逻辑名称
     * @param topic      目标请求主题
     * @param payload    请求载荷
     * @param timeout    超时阈值
     * @return 响应报文
     */
    public MqttMessage sendAndReceive(String clientName, String topic, Object payload, Duration timeout) {
        return sendAndReceive(clientName, topic, payload, 1, timeout);
    }

    /**
     * 指定客户端发送请求并同步阻塞等待回执响应
     *
     * @param clientName 客户端逻辑名称
     * @param topic      目标请求主题
     * @param payload    请求载荷
     * @param qos        QoS 等级
     * @param timeout    超时阈值
     * @return 响应报文
     */
    public MqttMessage sendAndReceive(String clientName, String topic, Object payload, int qos, Duration timeout) {
        try {
            CompletableFuture<MqttMessage> future = sendAndReceiveAsync(clientName, topic, payload, qos, timeout);
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new MqttTimeoutException("Request-Response timed out after " + timeout.toMillis() + "ms for topic [" + topic + "]", topic, null, timeout, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new BusinessException("Request-Response execution failed for topic [" + topic + "]: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Request-Response execution interrupted for topic [" + topic + "]", e);
        }
    }

    /**
     * 发送请求并同步阻塞等待响应，自动转译为期望的目标类型（默认客户端，QoS 1）
     *
     * @param topic        目标请求主题
     * @param payload      请求载荷
     * @param responseType 期望返回的目标对象类型
     * @param timeout      超时阈值
     * @param <T>          泛型类型
     * @return 反序列化后的响应对象
     */
    public <T> T sendAndReceive(String topic, Object payload, Class<T> responseType, Duration timeout) {
        return sendAndReceive(null, topic, payload, 1, responseType, timeout);
    }

    /**
     * 发送请求并同步阻塞等待响应，自动转译为期望的目标类型（默认客户端）
     *
     * @param topic        目标请求主题
     * @param payload      请求载荷
     * @param qos          QoS 等级
     * @param responseType 期望返回的目标对象类型
     * @param timeout      超时阈值
     * @param <T>          泛型类型
     * @return 反序列化后的响应对象
     */
    public <T> T sendAndReceive(String topic, Object payload, int qos, Class<T> responseType, Duration timeout) {
        return sendAndReceive(null, topic, payload, qos, responseType, timeout);
    }

    /**
     * 指定客户端发送请求并同步阻塞等待响应，自动转译为期望的目标类型（QoS 1）
     *
     * @param clientName   客户端逻辑名称
     * @param topic        目标请求主题
     * @param payload      请求载荷
     * @param responseType 期望返回的目标对象类型
     * @param timeout      超时阈值
     * @param <T>          泛型类型
     * @return 反序列化后的响应对象
     */
    public <T> T sendAndReceive(String clientName, String topic, Object payload, Class<T> responseType, Duration timeout) {
        return sendAndReceive(clientName, topic, payload, 1, responseType, timeout);
    }

    /**
     * 指定客户端发送请求并同步阻塞等待响应，自动转译为期望的目标类型
     *
     * @param clientName   客户端逻辑名称
     * @param topic        目标请求主题
     * @param payload      请求载荷
     * @param qos          QoS 等级
     * @param responseType 期望返回的目标对象类型
     * @param timeout      超时阈值
     * @param <T>          泛型类型
     * @return 反序列化后的响应对象
     */
    public <T> T sendAndReceive(String clientName, String topic, Object payload, int qos, Class<T> responseType, Duration timeout) {
        MqttMessage message = sendAndReceive(clientName, topic, payload, qos, timeout);
        return convertResponse(message, responseType);
    }

    /**
     * 异步非阻塞发起请求并返回响应 Future（默认客户端，QoS 1）
     *
     * @param topic   目标请求主题
     * @param payload 请求载荷
     * @param timeout 超时阈值
     * @return CompletableFuture 响应报文
     */
    public CompletableFuture<MqttMessage> sendAndReceiveAsync(String topic, Object payload, Duration timeout) {
        return sendAndReceiveAsync(null, topic, payload, 1, timeout);
    }

    /**
     * 异步非阻塞发起请求并返回响应 Future（默认客户端）
     *
     * @param topic   目标请求主题
     * @param payload 请求载荷
     * @param qos     QoS 等级
     * @param timeout 超时阈值
     * @return CompletableFuture 响应报文
     */
    public CompletableFuture<MqttMessage> sendAndReceiveAsync(String topic, Object payload, int qos, Duration timeout) {
        return sendAndReceiveAsync(null, topic, payload, qos, timeout);
    }

    /**
     * 指定客户端异步非阻塞发起请求并返回响应 Future（QoS 1）
     *
     * @param clientName 客户端逻辑名称
     * @param topic      目标请求主题
     * @param payload    请求载荷
     * @param timeout    超时阈值
     * @return CompletableFuture 响应报文
     */
    public CompletableFuture<MqttMessage> sendAndReceiveAsync(String clientName, String topic, Object payload, Duration timeout) {
        return sendAndReceiveAsync(clientName, topic, payload, 1, timeout);
    }

    /**
     * 指定客户端异步非阻塞发起请求并返回响应 Future
     *
     * @param clientName 客户端逻辑名称
     * @param topic      目标请求主题
     * @param payload    请求载荷
     * @param qos        QoS 等级
     * @param timeout    超时阈值
     * @return CompletableFuture 响应报文
     */
    public CompletableFuture<MqttMessage> sendAndReceiveAsync(String clientName, String topic, Object payload, int qos, Duration timeout) {
        String effectiveClient = resolveEffectiveClientName(clientName);
        return getRequiredRequestReplyManager().sendAndReceiveAsync(effectiveClient, topic, payload, qos, timeout);
    }

    /**
     * 异步非阻塞发起请求，并自动转译为目标类型（默认客户端，QoS 1）
     *
     * @param topic        目标请求主题
     * @param payload      请求载荷
     * @param responseType 期望返回的目标对象类型
     * @param timeout      超时阈值
     * @param <T>          泛型类型
     * @return CompletableFuture 目标响应对象
     */
    public <T> CompletableFuture<T> sendAndReceiveAsync(String topic, Object payload, Class<T> responseType, Duration timeout) {
        return sendAndReceiveAsync(null, topic, payload, 1, responseType, timeout);
    }

    /**
     * 异步非阻塞发起请求，并自动转译为目标类型（默认客户端）
     *
     * @param topic        目标请求主题
     * @param payload      请求载荷
     * @param qos          QoS 等级
     * @param responseType 期望返回的目标对象类型
     * @param timeout      超时阈值
     * @param <T>          泛型类型
     * @return CompletableFuture 目标响应对象
     */
    public <T> CompletableFuture<T> sendAndReceiveAsync(String topic, Object payload, int qos, Class<T> responseType, Duration timeout) {
        return sendAndReceiveAsync(null, topic, payload, qos, responseType, timeout);
    }

    /**
     * 指定客户端异步非阻塞发起请求，并自动转译为目标类型（QoS 1）
     *
     * @param clientName   客户端逻辑名称
     * @param topic        目标请求主题
     * @param payload      请求载荷
     * @param responseType 期望返回的目标对象类型
     * @param timeout      超时阈值
     * @param <T>          泛型类型
     * @return CompletableFuture 目标响应对象
     */
    public <T> CompletableFuture<T> sendAndReceiveAsync(String clientName, String topic, Object payload, Class<T> responseType, Duration timeout) {
        return sendAndReceiveAsync(clientName, topic, payload, 1, responseType, timeout);
    }

    /**
     * 指定客户端异步非阻塞发起请求，并自动转译为目标类型
     *
     * @param clientName   客户端逻辑名称
     * @param topic        目标请求主题
     * @param payload      请求载荷
     * @param qos          QoS 等级
     * @param responseType 期望返回的目标对象类型
     * @param timeout      超时阈值
     * @param <T>          泛型类型
     * @return CompletableFuture 目标响应对象
     */
    public <T> CompletableFuture<T> sendAndReceiveAsync(String clientName, String topic, Object payload, int qos, Class<T> responseType, Duration timeout) {
        Objects.requireNonNull(responseType, "responseType must not be null");
        return sendAndReceiveAsync(clientName, topic, payload, qos, timeout)
                .thenApply(msg -> convertResponse(msg, responseType));
    }

    private MqttRequestReplyManager getRequiredRequestReplyManager() {
        if (requestReplyManager == null) {
            throw new BusinessException("MqttRequestReplyManager is not configured. Request-Response pattern requires MqttRequestReplyManager Bean.");
        }
        return requestReplyManager;
    }

    @SuppressWarnings("unchecked")
    private <T> T convertResponse(MqttMessage message, Class<T> responseType) {
        if (message == null || responseType == null) {
            return null;
        }
        if (responseType.isAssignableFrom(MqttMessage.class)) {
            return (T) message;
        }
        byte[] payload = message.getPayload();
        if (responseType == byte[].class) {
            return (T) (payload != null ? payload : new byte[0]);
        }
        if (responseType == String.class) {
            return (T) (payload != null ? new String(payload, StandardCharsets.UTF_8) : "");
        }
        if (payload == null || payload.length == 0) {
            return null;
        }
        return JsonUtil.parseObject(payload, responseType);
    }

    private Map<String, Object> extractHeaders(MqttMessage message) {
        if (message == null) {
            return Collections.emptyMap();
        }
        MqttProperties properties = message.getProperties();
        if (properties == null || properties.getUserProperties() == null) {
            return Collections.emptyMap();
        }
        List<UserProperty> userProperties = properties.getUserProperties();
        if (userProperties.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> headers = new HashMap<>(userProperties.size());
        for (UserProperty prop : userProperties) {
            headers.put(prop.getKey(), prop.getValue());
        }
        return headers;
    }
}
