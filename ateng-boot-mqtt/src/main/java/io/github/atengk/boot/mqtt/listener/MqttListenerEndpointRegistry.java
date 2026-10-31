package io.github.atengk.boot.mqtt.listener;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.core.helper.FatalExceptionClassifier;
import io.github.atengk.boot.mqtt.defense.PayloadGuard;
import io.github.atengk.boot.mqtt.exception.SilentAckException;
import io.github.atengk.boot.mqtt.interceptor.MqttMessageInterceptor;
import io.github.atengk.boot.mqtt.metrics.MqttMetrics;
import io.github.atengk.boot.mqtt.model.MqttDeadLetterRecord;
import io.github.atengk.boot.mqtt.registry.MqttClientHandle;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import io.github.atengk.boot.mqtt.requestreply.MqttRequestReplyManager;
import io.github.atengk.boot.mqtt.routing.MqttTopicPattern;
import io.github.atengk.boot.mqtt.util.MqttHeaderUtils;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
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
    private final MqttMetrics metrics;
    private final List<MqttMessageInterceptor> interceptors;

    private final List<MqttListenerEndpoint> endpoints = new CopyOnWriteArrayList<>();
    private final Map<String, List<String>> subscribedTopicsByClient = new ConcurrentHashMap<>();
    private final Set<String> pausedEndpoints = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile MqttRequestReplyManager requestReplyManager;
    private volatile int maxReplayCount = 1;

    public MqttListenerEndpointRegistry(NamedMqttClientRegistry clientRegistry,
                                        PayloadGuard payloadGuard,
                                        Executor executor) {
        this(clientRegistry, payloadGuard, executor, null, null, Collections.emptyList());
    }

    public MqttListenerEndpointRegistry(NamedMqttClientRegistry clientRegistry,
                                        PayloadGuard payloadGuard,
                                        Executor executor,
                                        MqttMetrics metrics) {
        this(clientRegistry, payloadGuard, executor, metrics, null, Collections.emptyList());
    }

    public MqttListenerEndpointRegistry(NamedMqttClientRegistry clientRegistry,
                                        PayloadGuard payloadGuard,
                                        Executor executor,
                                        MqttMetrics metrics,
                                        MqttRequestReplyManager requestReplyManager) {
        this(clientRegistry, payloadGuard, executor, metrics, requestReplyManager, Collections.emptyList());
    }

    public MqttListenerEndpointRegistry(NamedMqttClientRegistry clientRegistry,
                                        PayloadGuard payloadGuard,
                                        Executor executor,
                                        MqttMetrics metrics,
                                        MqttRequestReplyManager requestReplyManager,
                                        List<MqttMessageInterceptor> interceptors) {
        this.clientRegistry = clientRegistry;
        this.payloadGuard = payloadGuard != null ? payloadGuard : new PayloadGuard();
        this.executor = executor;
        this.metrics = metrics;
        this.requestReplyManager = requestReplyManager;
        this.interceptors = interceptors != null ? new ArrayList<>(interceptors) : Collections.emptyList();
    }

    public void setRequestReplyManager(MqttRequestReplyManager requestReplyManager) {
        this.requestReplyManager = requestReplyManager;
    }

    public int getMaxReplayCount() {
        return maxReplayCount;
    }

    public void setMaxReplayCount(int maxReplayCount) {
        this.maxReplayCount = Math.max(1, maxReplayCount);
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
            if (metrics != null) {
                metrics.bindClientRegistry(clientRegistry);
            }
            // 为所有已配置客户端绑定兜底回调与连接完成监听，确保共享订阅与断线重连自愈无遗漏
            for (MqttClientHandle handle : clientRegistry.getClientHandles().values()) {
                bindFallbackCallback(handle);
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
        handle.setFallbackMessageListener((topic, message) -> {
            // 仅针对共享订阅端点（因 $share 前缀导致 Paho 内部 TopicFilter 无法直接匹配 listener）执行兜底分发，彻底防御普通端点重复分发
            for (MqttListenerEndpoint endpoint : endpoints) {
                if (isPaused(endpoint.getId())) {
                    continue;
                }
                if (StringUtils.hasText(endpoint.getSharedGroup()) && isEndpointAssignedToClient(endpoint, handle.getClientName())) {
                    for (MqttTopicPattern pattern : endpoint.getPatterns()) {
                        if (pattern.matchAndExtract(topic) != null) {
                            executor.execute(() -> dispatchMessage(endpoint, pattern, topic, message, handle));
                            break;
                        }
                    }
                }
            }
        });
        handle.addConnectCompleteListener((reconnect, serverURI) -> {
            handleConnectComplete(handle, reconnect, serverURI);
        });
    }

    /**
     * 处理客户端连接完成或重连事件，触发端点订阅自愈与 RPC 响应通道重建
     *
     * @param handle    客户端句柄
     * @param reconnect 是否为断线重连
     * @param serverURI Broker 服务地址
     */
    public void handleConnectComplete(MqttClientHandle handle, boolean reconnect, String serverURI) {
        log.info("[MQTT] Client [{}] connect completed (reconnect={}) to {}", handle.getClientName(), reconnect, serverURI);
        if (reconnect) {
            resubscribeEndpointsForClient(handle);
            if (requestReplyManager != null) {
                requestReplyManager.handleReconnect(handle.getClientName());
            }
        }
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
                org.eclipse.paho.mqttv5.client.IMqttMessageListener listener = (topic, message) -> {
                    // Paho I/O 回调线程中秒级提交至受管线程池，彻底杜绝 KeepAlive 假死
                    executor.execute(() -> dispatchMessage(endpoint, pattern, topic, message, handle));
                };
                handle.subscribe(filter, endpoint.getQos(), listener);
                List<String> clientTopics = subscribedTopicsByClient.computeIfAbsent(handle.getClientName(), k -> new CopyOnWriteArrayList<>());
                if (!clientTopics.contains(filter)) {
                    clientTopics.add(filter);
                }
                log.info("[MQTT] Client [{}] successfully subscribed to [{}] for endpoint [{}]",
                        handle.getClientName(), filter, endpoint.getId());
            } catch (MqttException e) {
                throw new BusinessException("Failed to subscribe MQTT topic [" + filter + "]: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 断线重连后全量重新订阅指定客户端的所有端点主题
     */
    private void resubscribeEndpointsForClient(MqttClientHandle handle) {
        String clientName = handle.getClientName();
        log.info("[MQTT] Triggering automatic subscription self-healing for client [{}] on reconnect", clientName);
        for (MqttListenerEndpoint endpoint : endpoints) {
            if (isEndpointAssignedToClient(endpoint, clientName)) {
                try {
                    subscribeEndpoint(endpoint);
                } catch (Exception e) {
                    log.error("[MQTT] Error during resubscription for endpoint [{}] on client [{}]: {}",
                            endpoint.getId(), clientName, e.getMessage(), e);
                }
            }
        }
    }

    private void dispatchMessage(MqttListenerEndpoint endpoint,
                                 MqttTopicPattern pattern,
                                 String actualTopic,
                                 MqttMessage message,
                                 MqttClientHandle handle) {
        if (isPaused(endpoint.getId())) {
            log.debug("[MQTT] Endpoint [{}] is paused, skipping message dispatch for topic [{}]", endpoint.getId(), actualTopic);
            return;
        }
        if (metrics != null) {
            metrics.recordReceived(handle.getClientName(), actualTopic);
        }
        Semaphore semaphore = endpoint.getConcurrencySemaphore();
        if (semaphore != null) {
            try {
                semaphore.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        long startNanos = System.nanoTime();
        Map<String, String> headers = MqttHeaderUtils.extractHeaders(message);

        try {
            // 消息消费前置拦截切面
            for (MqttMessageInterceptor interceptor : interceptors) {
                try {
                    if (!interceptor.preConsume(handle.getClientName(), endpoint.getId(), actualTopic, message, headers)) {
                        log.debug("[MQTT] Message on topic [{}] for endpoint [{}] was intercepted and dropped by [{}]",
                                actualTopic, endpoint.getId(), interceptor.getClass().getSimpleName());
                        return;
                    }
                } catch (Throwable t) {
                    log.error("[MQTT] Error in interceptor [{}] preConsume: {}", interceptor.getClass().getSimpleName(), t.getMessage(), t);
                    throw t;
                }
            }

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

            // 消息消费后置拦截切面 (逆序执行)
            triggerPostConsume(handle.getClientName(), endpoint.getId(), actualTopic, message, headers, null);

            if (metrics != null) {
                metrics.recordConsume(endpoint.getId(), actualTopic, true, System.nanoTime() - startNanos);
            }
        } catch (Throwable e) {
            // 消息消费后置拦截切面 (异常逆序执行)
            triggerPostConsume(handle.getClientName(), endpoint.getId(), actualTopic, message, headers, e);

            if (metrics != null) {
                metrics.recordConsume(endpoint.getId(), actualTopic, false, System.nanoTime() - startNanos);
            }
            // 1. 消费端静默确认与幂等防重拦截 (Silent ACK Invariant)
            if (isSilentAckOrIdempotent(e)) {
                log.info("[MQTT] Silent ACK requested or idempotent duplicate detected on topic [{}] for endpoint [{}], submitting ACK to terminate redelivery loop: {}",
                        actualTopic, endpoint.getId(), e.getMessage());
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
        } finally {
            if (semaphore != null) {
                semaphore.release();
            }
        }
    }

    private boolean isSilentAckOrIdempotent(Throwable t) {
        Throwable current = t;
        int depth = 0;
        while (current != null && depth < 30) {
            if (current instanceof SilentAckException) {
                return true;
            }
            if (current instanceof IdempotentException) {
                return true;
            }
            if ("io.github.atengk.boot.core.exception.IdempotentException".equals(current.getClass().getName())
                    || "io.github.atengk.boot.mqtt.exception.SilentAckException".equals(current.getClass().getName())) {
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

        // 防御死信主题自循环投递隐患：若当前报文已来自 DLT 主题，严禁再次转发自身
        if (actualTopic.endsWith("/DLT") || actualTopic.startsWith("sys/dlt")) {
            log.error("[MQTT] Poison pill occurred on dead-letter topic [{}] itself for endpoint [{}], dropping message to break infinite DLT self-loop: {}",
                    actualTopic, endpoint.getId(), cause.getMessage());
            return;
        }

        // 防二次雪崩熔断防线：若报文已达到重放上限，丢弃并告警，彻底熔断业务主题与死信主题之间的死循环
        int replayCount = MqttDeadLetterRecord.extractReplayCount(message);
        if (replayCount >= maxReplayCount) {
            log.error("[MQTT] Poison pill message on topic [{}] has already been replayed [{}] time(s), reaching maxReplayCount threshold [{}], dropping message to break secondary avalanche infinite loop: {}",
                    actualTopic, replayCount, maxReplayCount, cause.getMessage());
            return;
        }

        String dltTopic = endpoint.getAnnotation().dltTopic();
        if (!StringUtils.hasText(dltTopic)) {
            dltTopic = actualTopic + "/DLT";
        }

        try {
            byte[] payload = message != null && message.getPayload() != null ? message.getPayload() : new byte[0];
            MqttMessage dltMessage = new MqttMessage(payload);
            dltMessage.setQos(endpoint.getQos());
            dltMessage.setRetained(true);

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
            if (metrics != null) {
                metrics.recordDlt(endpoint.getId(), actualTopic);
            }
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
                            handle.unsubscribe(topics.toArray(new String[0]));
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

    public List<MqttMessageInterceptor> getInterceptors() {
        return Collections.unmodifiableList(interceptors);
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    public List<MqttListenerEndpoint> getEndpoints() {
        return Collections.unmodifiableList(endpoints);
    }

    private void triggerPostConsume(String clientName, String endpointId, String actualTopic,
                                    MqttMessage message, Map<String, String> headers, Throwable ex) {
        if (interceptors.isEmpty()) {
            return;
        }
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            try {
                interceptors.get(i).postConsume(clientName, endpointId, actualTopic, message, headers, ex);
            } catch (Throwable t) {
                log.warn("[MQTT] Error in interceptor [{}] postConsume: {}", interceptors.get(i).getClass().getSimpleName(), t.getMessage(), t);
            }
        }
    }

    // ==================== 容器动态运维接口 (MqttContainerManager 支撑) ====================

    /**
     * 暂停指定端点的消息消费调度
     *
     * @param endpointId 端点唯一标识
     */
    public void pause(String endpointId) {
        if (!StringUtils.hasText(endpointId)) {
            throw new IllegalArgumentException("endpointId must not be blank");
        }
        if (!containsEndpoint(endpointId)) {
            throw new IllegalArgumentException("MQTT listener endpoint not found: " + endpointId);
        }
        pausedEndpoints.add(endpointId);
        log.info("[MQTT] Paused message dispatch for listener endpoint [{}]", endpointId);
    }

    /**
     * 恢复指定端点的消息消费调度
     *
     * @param endpointId 端点唯一标识
     */
    public void resume(String endpointId) {
        if (!StringUtils.hasText(endpointId)) {
            throw new IllegalArgumentException("endpointId must not be blank");
        }
        if (!containsEndpoint(endpointId)) {
            throw new IllegalArgumentException("MQTT listener endpoint not found: " + endpointId);
        }
        pausedEndpoints.remove(endpointId);
        log.info("[MQTT] Resumed message dispatch for listener endpoint [{}]", endpointId);
    }

    /**
     * 判断指定端点是否已被暂停
     *
     * @param endpointId 端点唯一标识
     * @return true 若处于暂停状态
     */
    public boolean isPaused(String endpointId) {
        if (!StringUtils.hasText(endpointId)) {
            return false;
        }
        return pausedEndpoints.contains(endpointId);
    }

    /**
     * 全局暂停所有已注册端点的消费调度
     */
    public void pauseAll() {
        for (MqttListenerEndpoint endpoint : endpoints) {
            pausedEndpoints.add(endpoint.getId());
        }
        log.info("[MQTT] Paused message dispatch for all [{}] listener endpoints", endpoints.size());
    }

    /**
     * 全局恢复所有已注册端点的消费调度
     */
    public void resumeAll() {
        pausedEndpoints.clear();
        log.info("[MQTT] Resumed message dispatch for all [{}] listener endpoints", endpoints.size());
    }

    /**
     * 判断是否存在指定标识的端点
     *
     * @param endpointId 端点唯一标识
     * @return true 若已注册
     */
    public boolean containsEndpoint(String endpointId) {
        if (!StringUtils.hasText(endpointId)) {
            return false;
        }
        for (MqttListenerEndpoint endpoint : endpoints) {
            if (endpoint.getId().equals(endpointId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 查询指定客户端当前活跃订阅的主题列表
     *
     * @param clientName 客户端逻辑名称
     * @return 活跃订阅主题列表
     */
    public List<String> getSubscribedTopics(String clientName) {
        if (!StringUtils.hasText(clientName)) {
            clientName = clientRegistry.getDefaultClientName();
        }
        List<String> topics = subscribedTopicsByClient.get(clientName);
        return topics != null ? Collections.unmodifiableList(new ArrayList<>(topics)) : Collections.emptyList();
    }

    /**
     * 查询所有客户端当前活跃订阅的主题拓扑映射
     *
     * @return Map<客户端逻辑名称, 订阅主题列表>
     */
    public Map<String, List<String>> getAllSubscribedTopics() {
        Map<String, List<String>> result = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : subscribedTopicsByClient.entrySet()) {
            result.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 获取所有已注册端点的运行状态快照列表
     *
     * @return 端点状态快照列表
     */
    public List<MqttEndpointStatus> getEndpointStatuses() {
        List<MqttEndpointStatus> list = new ArrayList<>();
        for (MqttListenerEndpoint endpoint : endpoints) {
            list.add(buildEndpointStatus(endpoint));
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * 获取指定端点的运行状态快照
     *
     * @param endpointId 端点唯一标识
     * @return 端点状态快照，若不存在则返回 null
     */
    public MqttEndpointStatus getEndpointStatus(String endpointId) {
        if (!StringUtils.hasText(endpointId)) {
            return null;
        }
        for (MqttListenerEndpoint endpoint : endpoints) {
            if (endpoint.getId().equals(endpointId)) {
                return buildEndpointStatus(endpoint);
            }
        }
        return null;
    }

    private MqttEndpointStatus buildEndpointStatus(MqttListenerEndpoint endpoint) {
        String clientName = StringUtils.hasText(endpoint.getClientName())
                ? endpoint.getClientName()
                : clientRegistry.getDefaultClientName();
        List<String> topics = List.of(endpoint.getAnnotation().topics());
        String targetMethod = endpoint.getBean().getClass().getSimpleName() + "#" + endpoint.getMethod().getName();
        return new MqttEndpointStatus(
                endpoint.getId(),
                clientName,
                topics,
                endpoint.getSharedGroup(),
                endpoint.getQos(),
                endpoint.getConcurrency(),
                isPaused(endpoint.getId()),
                isRunning(),
                targetMethod
        );
    }
}
