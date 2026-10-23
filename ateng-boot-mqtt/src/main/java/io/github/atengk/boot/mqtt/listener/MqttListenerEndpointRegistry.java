package io.github.atengk.boot.mqtt.listener;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.core.helper.FatalExceptionClassifier;
import io.github.atengk.boot.mqtt.defense.PayloadGuard;
import io.github.atengk.boot.mqtt.registry.MqttClientHandle;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import io.github.atengk.boot.mqtt.routing.MqttTopicPattern;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.StringUtils;

import java.time.Duration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 声明式 MQTT 监听器端点注册中心与容器生命周期管理器
 * 负责解析多客户端订阅拓扑、共享订阅组转换、在受管线程池中异步调度业务监听方法并守护 Paho 网络循环
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class MqttListenerEndpointRegistry implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MqttListenerEndpointRegistry.class);

    private final NamedMqttClientRegistry clientRegistry;
    private final PayloadGuard payloadGuard;
    private final Executor executor;

    private final List<MqttListenerEndpoint> endpoints = new CopyOnWriteArrayList<>();
    private final Map<String, List<String>> subscribedTopicsByClient = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MqttListenerEndpointRegistry(NamedMqttClientRegistry clientRegistry,
                                        PayloadGuard payloadGuard,
                                        Executor executor) {
        this.clientRegistry = clientRegistry;
        this.payloadGuard = payloadGuard != null ? payloadGuard : new PayloadGuard();
        this.executor = executor;
    }

    /**
     * 注册声明式监听器端点
     *
     * @param endpoint 端点元数据
     */
    public void registerEndpoint(MqttListenerEndpoint endpoint) {
        if (endpoint == null) {
            return;
        }
        endpoints.add(endpoint);
        log.info("[MQTT] Registered listener endpoint [{}] for topics {}", endpoint.getId(), endpoint.getAnnotation().topics());

        if (isRunning()) {
            subscribeEndpoint(endpoint);
        }
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("[MQTT] Starting MqttListenerEndpointRegistry, activating [{}] endpoints", endpoints.size());
            // 为涉及的所有客户端绑定兜底回调，确保共享订阅（$share/...）等无本地 TopicFilter 命中的消息能被正确分发
            Set<String> targetClients = endpoints.stream()
                    .map(MqttListenerEndpoint::getClientName)
                    .map(name -> StringUtils.hasText(name) ? name : clientRegistry.getDefaultClientName())
                    .collect(Collectors.toSet());
            for (String clientName : targetClients) {
                if (clientRegistry.containsClient(clientName)) {
                    bindFallbackCallback(clientRegistry.getClientHandle(clientName));
                }
            }

            for (MqttListenerEndpoint endpoint : endpoints) {
                try {
                    subscribeEndpoint(endpoint);
                } catch (Exception e) {
                    log.error("[MQTT] Failed to subscribe endpoint [{}]: {}", endpoint.getId(), e.getMessage(), e);
                }
            }
        }
    }

    private void bindFallbackCallback(MqttClientHandle handle) {
        handle.getAsyncClient().setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {
                log.debug("[MQTT] Client [{}] disconnected", handle.getClientName());
            }

            @Override
            public void mqttErrorOccurred(MqttException exception) {
                log.warn("[MQTT] Client [{}] error occurred: {}", handle.getClientName(), exception.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                // 当消息未被特定 Topic 监听器命中（如共享订阅因 $share 前缀无法直接通过 Paho 内部 TopicFilter 匹配）时兜底分发
                for (MqttListenerEndpoint endpoint : endpoints) {
                    if (isEndpointAssignedToClient(endpoint, handle.getClientName())) {
                        for (MqttTopicPattern pattern : endpoint.getPatterns()) {
                            if (pattern.matchAndExtract(topic) != null) {
                                executor.execute(() -> dispatchMessage(endpoint, pattern, topic, message, handle));
                                break;
                            }
                        }
                    }
                }
            }

            @Override
            public void deliveryComplete(IMqttToken token) {}

            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                log.info("[MQTT] Client [{}] connect completed (reconnect={}) to {}", handle.getClientName(), reconnect, serverURI);
            }

            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        });
    }

    private boolean isEndpointAssignedToClient(MqttListenerEndpoint endpoint, String clientName) {
        if (StringUtils.hasText(endpoint.getClientName())) {
            return endpoint.getClientName().equals(clientName);
        }
        return clientName.equals(clientRegistry.getDefaultClientName());
    }

    private void subscribeEndpoint(MqttListenerEndpoint endpoint) {
        String clientName = endpoint.getClientName();
        MqttClientHandle handle = resolveClientHandle(clientName);
        String sharedGroup = endpoint.getSharedGroup();

        for (MqttTopicPattern pattern : endpoint.getPatterns()) {
            String filter = pattern.getSharedMqttFilter(sharedGroup);
            try {
                org.eclipse.paho.mqttv5.common.MqttSubscription subscription = new MqttSubscription(filter, endpoint.getQos());
                org.eclipse.paho.mqttv5.client.IMqttMessageListener listener = (topic, message) -> {
                    // Paho I/O 回调线程中秒级提交至受管线程池，彻底杜绝 KeepAlive 假死
                    executor.execute(() -> dispatchMessage(endpoint, pattern, topic, message, handle));
                };
                // 使用数组重载以规避 Paho 1.2.5 单参重载中的 subscriptionIdentifiers 空指针/越界 Bug
                org.eclipse.paho.mqttv5.client.IMqttToken token = handle.getAsyncClient().subscribe(
                        new org.eclipse.paho.mqttv5.common.MqttSubscription[]{subscription},
                        null,
                        null,
                        new org.eclipse.paho.mqttv5.client.IMqttMessageListener[]{listener},
                        null
                );
                long timeoutMs = handle.getClientProperties().getConnectionTimeout().toMillis();
                token.waitForCompletion(timeoutMs > 0 ? timeoutMs : 5000L);
                subscribedTopicsByClient.computeIfAbsent(handle.getClientName(), k -> new CopyOnWriteArrayList<>()).add(filter);
                log.info("[MQTT] Client [{}] successfully subscribed to [{}] for endpoint [{}]",
                        handle.getClientName(), filter, endpoint.getId());
            } catch (MqttException e) {
                log.error("[MQTT] Client [{}] failed to subscribe topic [{}]: {}", handle.getClientName(), filter, e.getMessage(), e);
                throw new BusinessException("Failed to subscribe MQTT topic [" + filter + "]: " + e.getMessage(), e);
            }
        }
    }

    private void dispatchMessage(MqttListenerEndpoint endpoint,
                                 MqttTopicPattern pattern,
                                 String actualTopic,
                                 MqttMessage message,
                                 MqttClientHandle handle) {
        try {
            Map<String, String> variables = pattern.matchAndExtract(actualTopic);
            if (variables == null) {
                return;
            }
            // 入站大报文守卫校验
            if (message != null && message.getPayload() != null) {
                payloadGuard.validate(message.getPayload(), handle.getClientProperties().getMaxPayloadSize(), actualTopic);
            }
            // 反射调用适配器
            endpoint.getAdapter().invoke(actualTopic, message, variables);
        } catch (Throwable e) {
            // 1. 消费端幂等静默确认 (Silent ACK Invariant)
            if (isIdempotentException(e)) {
                log.info("[MQTT] Idempotent duplicate message detected on topic [{}] for endpoint [{}], submitting Silent ACK to terminate redelivery loop",
                        actualTopic, endpoint.getId());
                return;
            }

            // 2. 毒丸死信转移 (DLT Invariant)
            if (FatalExceptionClassifier.isFatal(e)) {
                handleFatalPoisonPill(endpoint, actualTopic, message, handle, e);
                return;
            }

            // 3. 非致命普通异常日志记录
            log.error("[MQTT] Error during message dispatch for topic [{}] on endpoint [{}]: {}",
                    actualTopic, endpoint.getId(), e.getMessage(), e);
        }
    }

    private boolean isIdempotentException(Throwable t) {
        Throwable current = t;
        int depth = 0;
        while (current != null && depth < 30) {
            if (current instanceof IdempotentException) {
                return true;
            }
            if ("io.github.atengk.boot.core.exception.IdempotentException".equals(current.getClass().getName())) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    private Throwable findRootFatalCause(Throwable t) {
        Throwable current = t;
        Throwable candidate = t;
        int depth = 0;
        while (current != null && depth < 30) {
            if (FatalExceptionClassifier.isFatal(current)) {
                candidate = current;
            }
            current = current.getCause();
            depth++;
        }
        return candidate;
    }

    private void handleFatalPoisonPill(MqttListenerEndpoint endpoint,
                                       String actualTopic,
                                       MqttMessage message,
                                       MqttClientHandle handle,
                                       Throwable cause) {
        if (!endpoint.getAnnotation().enableDlt()) {
            log.warn("[MQTT] Fatal poison pill on topic [{}] for endpoint [{}], DLT is disabled via configuration, dropping message: {}",
                    actualTopic, endpoint.getId(), cause.getMessage());
            return;
        }

        String dltTopic = endpoint.getAnnotation().dltTopic();
        if (!StringUtils.hasText(dltTopic)) {
            dltTopic = actualTopic.endsWith("/DLT") ? actualTopic : actualTopic + "/DLT";
        }

        try {
            byte[] payload = message != null && message.getPayload() != null ? message.getPayload() : new byte[0];
            MqttMessage dltMessage = new MqttMessage(payload);
            dltMessage.setQos(endpoint.getQos());
            dltMessage.setRetained(false);

            // 构建死信出站 MqttProperties，附加原始上下文与异常堆栈标头
            Throwable fatalCause = findRootFatalCause(cause);
            MqttProperties dltProperties = new MqttProperties();
            List<UserProperty> userProps = new ArrayList<>();
            if (message != null && message.getProperties() != null && message.getProperties().getUserProperties() != null) {
                userProps.addAll(message.getProperties().getUserProperties());
            }
            userProps.add(new UserProperty("x-original-topic", actualTopic));
            userProps.add(new UserProperty("x-exception-class", fatalCause.getClass().getName()));
            String exMsg = fatalCause.getMessage();
            if (exMsg != null) {
                if (exMsg.length() > 512) {
                    exMsg = exMsg.substring(0, 512) + "...";
                }
                userProps.add(new UserProperty("x-exception-message", exMsg));
            }
            userProps.add(new UserProperty("x-dlt-timestamp", String.valueOf(System.currentTimeMillis())));
            dltProperties.setUserProperties(userProps);
            dltMessage.setProperties(dltProperties);

            handle.getAsyncClient().publish(dltTopic, dltMessage);
            log.warn("[MQTT] Poison pill message on topic [{}] successfully transferred to DLT [{}] for endpoint [{}], submitted ACK to break redelivery loop",
                    actualTopic, dltTopic, endpoint.getId());
        } catch (Exception e) {
            log.error("[MQTT] Failed to forward poison pill to DLT topic [{}] on client [{}]: {}",
                    dltTopic, handle.getClientName(), e.getMessage(), e);
        }
    }

    private MqttClientHandle resolveClientHandle(String clientName) {
        if (StringUtils.hasText(clientName)) {
            return clientRegistry.getClientHandle(clientName);
        }
        return clientRegistry.getDefaultClientHandle();
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("[MQTT] Stopping MqttListenerEndpointRegistry, unsubscribing [{}] active client channels", subscribedTopicsByClient.size());
            // 1. 第一阶段：注销所有入站订阅，阻断新报文涌入
            for (Map.Entry<String, List<String>> entry : subscribedTopicsByClient.entrySet()) {
                String clientName = entry.getKey();
                List<String> topics = entry.getValue();
                if (topics.isEmpty()) {
                    continue;
                }
                try {
                    if (clientRegistry.containsClient(clientName)) {
                        MqttClientHandle handle = clientRegistry.getClientHandle(clientName);
                        if (handle.isConnected()) {
                            handle.getAsyncClient().unsubscribe(topics.toArray(new String[0]));
                            log.info("[MQTT] Client [{}] unsubscribed {} topics cleanly", clientName, topics.size());
                        }
                    }
                } catch (Exception e) {
                    log.warn("[MQTT] Error unsubscribing topics for client [{}]: {}", clientName, e.getMessage());
                }
            }
            subscribedTopicsByClient.clear();

            // 2. 第二阶段：排空受管线程池中未决任务（最多等待 shutdownTimeout）
            drainExecutor();
        }
    }

    private void drainExecutor() {
        if (executor == null) {
            return;
        }
        Duration maxTimeout = Duration.ofSeconds(5);
        for (MqttClientHandle h : clientRegistry.getClientHandles().values()) {
            if (h.getClientProperties() != null && h.getClientProperties().getShutdownTimeout() != null) {
                if (h.getClientProperties().getShutdownTimeout().compareTo(maxTimeout) > 0) {
                    maxTimeout = h.getClientProperties().getShutdownTimeout();
                }
            }
        }
        long deadline = System.currentTimeMillis() + maxTimeout.toMillis();
        log.info("[MQTT] Awaiting executor task queue draining (max timeout: {}ms)", maxTimeout.toMillis());
        while (System.currentTimeMillis() < deadline) {
            if (getActiveCount() <= 0) {
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("[MQTT] Executor task queue drained successfully");
    }

    private int getActiveCount() {
        if (executor instanceof org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor springExec) {
            return springExec.getActiveCount();
        }
        if (executor instanceof java.util.concurrent.ThreadPoolExecutor jdkExec) {
            return jdkExec.getActiveCount();
        }
        return 0;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    public List<MqttListenerEndpoint> getEndpoints() {
        return Collections.unmodifiableList(endpoints);
    }
}
