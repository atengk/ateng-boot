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
import io.github.atengk.boot.mqtt.context.MqttContextHolder;
import io.github.atengk.boot.mqtt.defense.PayloadGuard;
import io.github.atengk.boot.mqtt.handler.DefaultMqttSendFailureHandler;
import io.github.atengk.boot.mqtt.handler.MqttSendFailureHandler;
import io.github.atengk.boot.mqtt.registry.MqttClientHandle;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttActionListener;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

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

    public MqttTemplate(NamedMqttClientRegistry clientRegistry, PayloadGuard payloadGuard) {
        this(clientRegistry, payloadGuard, new DefaultMqttSendFailureHandler());
    }

    public MqttTemplate(NamedMqttClientRegistry clientRegistry,
                        PayloadGuard payloadGuard,
                        MqttSendFailureHandler failureHandler) {
        this.clientRegistry = Objects.requireNonNull(clientRegistry, "clientRegistry must not be null");
        this.payloadGuard = payloadGuard != null ? payloadGuard : new PayloadGuard();
        this.failureHandler = failureHandler != null ? failureHandler : new DefaultMqttSendFailureHandler();
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
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliveryTask.run();
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        log.warn("[MQTT] Transaction uncommitted or rolled back (status: {}), canceled delivery to topic [{}] on client [{}]",
                                status, topic, effectiveClient);
                    }
                }
            });
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
        return SPRING_TX_PRESENT && TransactionSynchronizationManager.isSynchronizationActive();
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
}
