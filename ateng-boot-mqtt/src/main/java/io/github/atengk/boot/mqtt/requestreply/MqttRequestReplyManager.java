package io.github.atengk.boot.mqtt.requestreply;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.mqtt.defense.PayloadGuard;
import io.github.atengk.boot.mqtt.exception.MqttTimeoutException;
import io.github.atengk.boot.mqtt.metrics.MqttMetrics;
import io.github.atengk.boot.mqtt.registry.MqttClientHandle;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import io.github.atengk.boot.mqtt.util.MqttHeaderUtils;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MQTT 请求-响应模式核心管理引擎
 * 实现单客户端私有聚合响应主题预订阅、协议原生关联标识管理、无锁并发唤醒与内存防泄露安全扫描
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class MqttRequestReplyManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MqttRequestReplyManager.class);

    private final NamedMqttClientRegistry clientRegistry;
    private final PayloadGuard payloadGuard;
    private final Executor executor;
    private final MqttMetrics metrics;

    private final Map<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private final Set<String> subscribedClientNames = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final boolean externalScanner;
    private ScheduledExecutorService timeoutScanner;

    public MqttRequestReplyManager(NamedMqttClientRegistry clientRegistry,
                                  PayloadGuard payloadGuard,
                                  Executor executor,
                                  ScheduledExecutorService timeoutScanner,
                                  MqttMetrics metrics) {
        this.clientRegistry = Objects.requireNonNull(clientRegistry, "clientRegistry must not be null");
        this.payloadGuard = payloadGuard != null ? payloadGuard : new PayloadGuard();
        this.executor = executor;
        this.externalScanner = timeoutScanner != null;
        this.timeoutScanner = timeoutScanner;
        this.metrics = metrics;
    }

    public MqttRequestReplyManager(NamedMqttClientRegistry clientRegistry,
                                  PayloadGuard payloadGuard,
                                  Executor executor,
                                  ScheduledExecutorService timeoutScanner) {
        this(clientRegistry, payloadGuard, executor, timeoutScanner, null);
    }

    public MqttRequestReplyManager(NamedMqttClientRegistry clientRegistry,
                                  PayloadGuard payloadGuard,
                                  Executor executor) {
        this(clientRegistry, payloadGuard, executor, null, null);
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("[MQTT] Starting MqttRequestReplyManager, initializing aggregated response subscriptions");
            if (this.timeoutScanner == null) {
                CustomizableThreadFactory factory = new CustomizableThreadFactory("ateng-mqtt-request-reply-cleaner-");
                factory.setDaemon(true);
                this.timeoutScanner = Executors.newSingleThreadScheduledExecutor(factory);
            }
            // 启动定时超时安全扫描器（每 500ms 扫描一次），防范无界内存挂起
            this.timeoutScanner.scheduleWithFixedDelay(this::scanAndCleanExpiredRequests, 500, 500, TimeUnit.MILLISECONDS);

            // 预订阅所有已配置客户端的私有聚合响应主题
            for (MqttClientHandle handle : clientRegistry.getClientHandles().values()) {
                try {
                    ensureSubscribed(handle);
                } catch (Exception e) {
                    log.error("[MQTT] Failed to pre-subscribe aggregated response topic for client [{}]: {}",
                            handle.getClientName(), e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("[MQTT] Stopping MqttRequestReplyManager, cleaning [{}] pending requests", pendingRequests.size());
            if (!externalScanner && timeoutScanner != null) {
                timeoutScanner.shutdownNow();
            }
            // 终止所有未决的请求 Future
            for (PendingRequest pending : pendingRequests.values()) {
                pending.future.completeExceptionally(new BusinessException("MqttRequestReplyManager is shutting down"));
            }
            pendingRequests.clear();
            subscribedClientNames.clear();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return 50; // 在客户端连接建立后启动
    }

    /**
     * 确保指定客户端已订阅私有聚合响应主题
     *
     * @param handle 客户端句柄
     */
    public synchronized void ensureSubscribed(MqttClientHandle handle) {
        if (handle == null || subscribedClientNames.contains(handle.getClientName())) {
            return;
        }
        String responseTopic = handle.getResponseTopic();
        try {
            IMqttMessageListener listener = (topic, message) -> handleInboundResponse(handle, topic, message);
            handle.subscribe(responseTopic, 1, listener);
            subscribedClientNames.add(handle.getClientName());
            log.info("[MQTT] Client [{}] successfully pre-subscribed to private response topic [{}]",
                    handle.getClientName(), responseTopic);
        } catch (MqttException e) {
            throw new BusinessException("Failed to subscribe response topic [" + responseTopic + "] on client [" + handle.getClientName() + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 发起请求并等待响应（异步非阻塞）
     *
     * @param clientName 客户端逻辑名称（为空则使用默认客户端）
     * @param topic      目标请求主题
     * @param payload    请求载荷
     * @param qos        QoS 等级
     * @param timeout    等待超时阈值
     * @return 响应报文 CompletableFuture
     */
    public CompletableFuture<MqttMessage> sendAndReceiveAsync(String clientName,
                                                             String topic,
                                                             Object payload,
                                                             int qos,
                                                             Duration timeout) {
        validateTopic(topic);
        validateTimeout(timeout);

        MqttClientHandle handle = clientRegistry.getClientHandle(clientName);
        ensureSubscribed(handle);

        // 1. 生成基于 UUID 的关联标识 Correlation Data
        String correlationId = IdUtil.fastSimpleUUID();
        byte[] correlationData = correlationId.getBytes(StandardCharsets.UTF_8);

        // 2. 构造协议属性：注入 Response Topic、Correlation Data 与四维上下文 User Properties
        MqttProperties props = new MqttProperties();
        props.setCorrelationData(correlationData);
        props.setResponseTopic(handle.getResponseTopic());
        injectContextUserProperties(props);

        // 3. 转译载荷并执行大报文防卫校验
        byte[] payloadBytes = convertPayload(payload);
        payloadGuard.validate(payloadBytes, handle.getClientProperties().getMaxPayloadSize(), topic);

        // 4. 构建出站报文
        MqttMessage message = new MqttMessage(payloadBytes);
        message.setQos(qos);
        message.setRetained(false);
        message.setProperties(props);

        // 5. 登记挂起请求并配置防泄漏自清理
        CompletableFuture<MqttMessage> future = new CompletableFuture<>();
        long expireTimeMillis = System.currentTimeMillis() + timeout.toMillis();
        PendingRequest pendingRequest = new PendingRequest(correlationId, topic, handle.getClientName(), expireTimeMillis, timeout, future);
        pendingRequests.put(correlationId, pendingRequest);

        // 无论何种原因完成均确定性移除，防止堆内存残留
        future.whenComplete((res, ex) -> pendingRequests.remove(correlationId));

        // 6. 出站发信
        try {
            handle.getAsyncClient().publish(topic, message);
            log.debug("[MQTT] Sent request to topic [{}] with correlationId [{}], expecting reply on [{}]",
                    topic, correlationId, handle.getResponseTopic());
        } catch (Exception e) {
            pendingRequests.remove(correlationId);
            String traceId = MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY);
            MessageDeliveryException deliveryException = new MessageDeliveryException(
                    "mqtt", topic, traceId,
                    "Failed to publish request-response message: " + e.getMessage(), e
            );
            future.completeExceptionally(deliveryException);
        }

        return future;
    }

    /**
     * 处理响应主题接收到的入站报文
     */
    private void handleInboundResponse(MqttClientHandle handle, String actualTopic, MqttMessage message) {
        byte[] correlationBytes = null;
        if (message.getProperties() != null) {
            correlationBytes = message.getProperties().getCorrelationData();
        }
        if (correlationBytes == null || correlationBytes.length == 0) {
            log.warn("[MQTT] Inbound response received on topic [{}] without correlationData, discarded", actualTopic);
            return;
        }

        String correlationId = new String(correlationBytes, StandardCharsets.UTF_8);
        PendingRequest pending = pendingRequests.remove(correlationId);
        if (pending == null) {
            log.debug("[MQTT] Response arrived for correlationId [{}] on topic [{}] but no active pending request found (expired or already handled)",
                    correlationId, actualTopic);
            return;
        }

        Runnable completeTask = () -> {
            Map<String, Object> headers = extractHeaders(message);
            MessageContextPropagator.runInContext(headers, () -> {
                pending.future.complete(message);
                log.debug("[MQTT] Successfully completed request-response for correlationId [{}] on topic [{}]",
                        correlationId, pending.topic);
            });
        };

        if (executor != null) {
            executor.execute(completeTask);
        } else {
            completeTask.run();
        }
    }

    /**
     * 定时扫描并清理超时的挂起请求
     */
    private void scanAndCleanExpiredRequests() {
        if (pendingRequests.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (PendingRequest pending : pendingRequests.values()) {
            if (now > pending.expireTimeMillis) {
                if (pendingRequests.remove(pending.correlationId, pending)) {
                    if (metrics != null) {
                        metrics.recordRequestTimeout(pending.clientName, pending.topic);
                    }
                    String msg = String.format("Request-Response timed out after waiting %sms for topic [%s] on client [%s], correlationId: [%s]",
                            pending.timeout.toMillis(), pending.topic, pending.clientName, pending.correlationId);
                    log.warn("[MQTT] {}", msg);
                    pending.future.completeExceptionally(new MqttTimeoutException(msg, pending.topic, pending.correlationId, pending.timeout));
                }
            }
        }
    }

    private void injectContextUserProperties(MqttProperties props) {
        List<UserProperty> userProperties = new ArrayList<>();

        String traceId = MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY);
        if (!StringUtils.hasText(traceId)) {
            traceId = IdUtil.fastSimpleUUID();
        }
        userProperties.add(new UserProperty(MessageHeaderConstants.TRACE_ID, traceId));

        String userId = UserContextHolder.get();
        if (StringUtils.hasText(userId)) {
            userProperties.add(new UserProperty(MessageHeaderConstants.USER_ID, userId));
        }

        String tenantId = TenantContextHolder.getTenantId();
        if (StringUtils.hasText(tenantId)) {
            userProperties.add(new UserProperty(MessageHeaderConstants.TENANT_ID, tenantId));
        }

        if (TenantContextHolder.isIgnoreTenant()) {
            userProperties.add(new UserProperty(MessageHeaderConstants.IGNORE_TENANT, "true"));
        }

        props.setUserProperties(userProperties);
    }

    private Map<String, Object> extractHeaders(MqttMessage message) {
        return new HashMap<>(MqttHeaderUtils.extractHeaders(message));
    }

    private byte[] convertPayload(Object payload) {
        if (payload == null) {
            return new byte[0];
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
            throw new IllegalArgumentException("MQTT topic must not be empty");
        }
        if (topic.contains("+") || topic.contains("#")) {
            throw new IllegalArgumentException("Publish topic must not contain wildcards (+ or #): " + topic);
        }
    }

    private void validateTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Timeout must be a positive duration");
        }
    }

    /**
     * 挂起请求结构体
     */
    private static class PendingRequest {
        private final String correlationId;
        private final String topic;
        private final String clientName;
        private final long expireTimeMillis;
        private final Duration timeout;
        private final CompletableFuture<MqttMessage> future;

        public PendingRequest(String correlationId,
                              String topic,
                              String clientName,
                              long expireTimeMillis,
                              Duration timeout,
                              CompletableFuture<MqttMessage> future) {
            this.correlationId = correlationId;
            this.topic = topic;
            this.clientName = clientName;
            this.expireTimeMillis = expireTimeMillis;
            this.timeout = timeout;
            this.future = future;
        }
    }
}
