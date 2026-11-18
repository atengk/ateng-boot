package io.github.atengk.boot.mqtt.listener;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.core.helper.FatalExceptionClassifier;
import io.github.atengk.boot.mqtt.annotation.MqttReply;
import io.github.atengk.boot.mqtt.defense.PayloadGuard;
import io.github.atengk.boot.mqtt.dlt.MqttDeadLetterStore;
import io.github.atengk.boot.mqtt.exception.SilentAckException;
import io.github.atengk.boot.mqtt.interceptor.MqttMessageInterceptor;
import io.github.atengk.boot.mqtt.metrics.MqttMetrics;
import io.github.atengk.boot.mqtt.model.MqttDeadLetterRecord;
import io.github.atengk.boot.mqtt.properties.AckMode;
import io.github.atengk.boot.mqtt.properties.MqttConsumerProperties;
import io.github.atengk.boot.mqtt.properties.MqttDltProperties;
import io.github.atengk.boot.mqtt.registry.MqttClientHandle;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import io.github.atengk.boot.mqtt.requestreply.MqttRequestReplyManager;
import io.github.atengk.boot.mqtt.routing.MqttTopicPattern;
import io.github.atengk.boot.mqtt.util.MqttHeaderUtils;
import io.github.atengk.boot.mqtt.util.MqttSchedulerSupport;
import org.eclipse.paho.mqttv5.client.MqttActionListener;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.StringUtils;

import java.time.Duration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
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
    private final Map<FilterKey, Set<String>> activeFilterEndpoints = new ConcurrentHashMap<>();
    private final Map<FilterKey, Integer> subscribedQosByFilter = new ConcurrentHashMap<>();
    private final Set<String> pausedEndpoints = ConcurrentHashMap.newKeySet();
    private final Set<PendingRetry> pendingRetries = ConcurrentHashMap.newKeySet();
    private final Map<String, SerialEndpointQueue> serialQueues = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean registryHooksRegistered = new AtomicBoolean(false);
    private final Map<String, MqttClientHandle> boundFallbackHandles = new ConcurrentHashMap<>();
    private final AtomicInteger inFlightTasks = new AtomicInteger(0);
    private final ReentrantLock subscriptionLock = new ReentrantLock();
    private final ReentrantLock schedulerLock = new ReentrantLock();
    private final ReentrantLock drainLock = new ReentrantLock();
    private final Condition allTasksDrained = drainLock.newCondition();
    private volatile MqttRequestReplyManager requestReplyManager;
    private volatile int maxReplayCount = 1;
    private volatile MqttConsumerProperties consumerProperties = new MqttConsumerProperties();
    private volatile MqttDltProperties dltProperties = new MqttDltProperties();
    private volatile MqttDeadLetterStore deadLetterStore;
    private volatile ScheduledExecutorService externalScheduler;
    private volatile ThreadPoolTaskScheduler ownedScheduler;

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
        this(clientRegistry, payloadGuard, executor, metrics, requestReplyManager, interceptors, new MqttConsumerProperties(), null);
    }

    /**
     * 构建完整的 declarative MQTT 监听器端点注册与运行期调度容器
     *
     * @param clientRegistry      命名客户端注册表
     * @param payloadGuard        出入站报文载荷安全守卫
     * @param executor            受管消费工作线程池
     * @param metrics             Micrometer 度量采集器（可选）
     * @param requestReplyManager 请求-响应关联器（可选）
     * @param interceptors        消息拦截器链（可选）
     * @param consumerProperties  消费确认与退避重试配置（可选）
     * @param taskScheduler       延迟重试调度执行器（可选，缺省按需创建内部受管调度器）
     */
    public MqttListenerEndpointRegistry(NamedMqttClientRegistry clientRegistry,
                                        PayloadGuard payloadGuard,
                                        Executor executor,
                                        MqttMetrics metrics,
                                        MqttRequestReplyManager requestReplyManager,
                                        List<MqttMessageInterceptor> interceptors,
                                        MqttConsumerProperties consumerProperties,
                                        ScheduledExecutorService taskScheduler) {
        this.clientRegistry = clientRegistry;
        this.payloadGuard = payloadGuard != null ? payloadGuard : new PayloadGuard();
        this.executor = executor;
        this.metrics = metrics;
        this.requestReplyManager = requestReplyManager;
        this.interceptors = interceptors != null ? new ArrayList<>(interceptors) : Collections.emptyList();
        this.consumerProperties = consumerProperties != null ? consumerProperties : new MqttConsumerProperties();
        this.externalScheduler = taskScheduler;
    }

    /**
     * 设置消费确认与重试配置属性
     *
     * @param consumerProperties 消费端配置
     */
    public void setConsumerProperties(MqttConsumerProperties consumerProperties) {
        this.consumerProperties = consumerProperties != null ? consumerProperties : new MqttConsumerProperties();
    }

    /**
     * 获取当前消费确认与重试配置属性
     *
     * @return 消费端配置
     */
    public MqttConsumerProperties getConsumerProperties() {
        return consumerProperties;
    }

    /**
     * 注入外部延迟重试调度执行器
     *
     * @param taskScheduler 外部调度执行器
     */
    public void setTaskScheduler(ScheduledExecutorService taskScheduler) {
        this.externalScheduler = taskScheduler;
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
     * 获取死信存储与投递策略配置属性
     *
     * @return 死信策略配置
     */
    public MqttDltProperties getDltProperties() {
        return dltProperties;
    }

    /**
     * 设置死信存储与投递策略配置属性
     *
     * @param dltProperties 死信策略配置
     */
    public void setDltProperties(MqttDltProperties dltProperties) {
        this.dltProperties = dltProperties != null ? dltProperties : new MqttDltProperties();
    }

    /**
     * 获取当前生效的本地死信存储 SPI 实现
     *
     * @return 死信存储实例，未启用时返回 null
     */
    public MqttDeadLetterStore getDeadLetterStore() {
        return deadLetterStore;
    }

    /**
     * 注入本地死信存储 SPI 实现
     *
     * @param deadLetterStore 死信存储实例
     */
    public void setDeadLetterStore(MqttDeadLetterStore deadLetterStore) {
        this.deadLetterStore = deadLetterStore;
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
        if (resolveAckMode(endpoint) == AckMode.AUTO_AFTER_CONSUME
                && endpoint.getAdapter().hasAcknowledgmentParameter()) {
            throw new IllegalStateException(
                    "Endpoint [" + endpoint.getId() + "] uses AUTO_AFTER_CONSUME mode and must not declare MqttAcknowledgment parameter; configure ackMode = AckMode.MANUAL instead");
        }
        endpoints.add(endpoint);
        if (!endpoint.getAnnotation().autoStartup()) {
            pausedEndpoints.add(endpoint.getId());
            log.info("[MQTT] Registered listener endpoint [{}] (autoStartup=false, paused) for topics {}",
                    endpoint.getId(), endpoint.getAnnotation().topics());
            return;
        }
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
            // 为所有已配置及运行期热注册客户端绑定兜底回调、连接完成监听与热注销守卫（幂等防重）
            for (MqttClientHandle handle : clientRegistry.getClientHandles().values()) {
                bindFallbackCallback(handle);
            }
            if (registryHooksRegistered.compareAndSet(false, true)) {
                clientRegistry.addClientRegisteredListener(this::bindFallbackCallback);
                clientRegistry.addClientUnregisterListener(this::handleClientUnregistered);
                clientRegistry.addActiveBindingPredicate(this::hasActiveEndpointsForClient);
            }

            for (MqttListenerEndpoint endpoint : endpoints) {
                if (isPaused(endpoint.getId())) {
                    log.info("[MQTT] Skipping initial Broker subscription for paused endpoint [{}] (autoStartup=false)",
                            endpoint.getId());
                    continue;
                }
                try {
                    subscribeEndpoint(endpoint);
                } catch (Exception e) {
                    log.error("[MQTT] Failed to subscribe endpoint [{}]: {}", endpoint.getId(), e.getMessage(), e);
                }
            }
        }
    }

    private boolean hasActiveEndpointsForClient(String clientName) {
        for (MqttListenerEndpoint endpoint : endpoints) {
            if (isEndpointAssignedToClient(endpoint, clientName) && !isPaused(endpoint.getId())) {
                return true;
            }
        }
        return false;
    }

    private void handleClientUnregistered(String clientName) {
        if (!StringUtils.hasText(clientName)) {
            return;
        }
        boundFallbackHandles.remove(clientName);
        subscriptionLock.lock();
        try {
            subscribedTopicsByClient.remove(clientName);
            activeFilterEndpoints.keySet().removeIf(key -> key.clientName().equals(clientName));
            subscribedQosByFilter.keySet().removeIf(key -> key.clientName().equals(clientName));
        } finally {
            subscriptionLock.unlock();
        }
    }

    private void bindFallbackCallback(MqttClientHandle handle) {
        if (handle == null) {
            return;
        }
        handle.setFallbackMessageListener((topic, message) -> {
            // 仅针对共享订阅端点（因 $share 前缀导致 Paho 内部 TopicFilter 无法直接匹配 listener）执行兜底分发，彻底防御普通端点重复分发
            for (MqttListenerEndpoint endpoint : endpoints) {
                if (isPaused(endpoint.getId())) {
                    continue;
                }
                if (StringUtils.hasText(endpoint.getSharedGroup()) && isEndpointAssignedToClient(endpoint, handle.getClientName())) {
                    for (MqttTopicPattern pattern : endpoint.getPatterns()) {
                        if (pattern.matchAndExtract(topic) != null) {
                            submitTrackedTask(endpoint, doneCallback -> dispatchMessage(endpoint, pattern, topic, message, handle, doneCallback));
                            break;
                        }
                    }
                }
            }
        });
        boundFallbackHandles.compute(handle.getClientName(), (name, existing) -> {
            if (existing != handle) {
                handle.addConnectCompleteListener((reconnect, serverURI) -> {
                    handleConnectComplete(handle, reconnect, serverURI);
                });
            }
            return handle;
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
        if (reconnect && isRunning()) {
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
        subscriptionLock.lock();
        try {
            subscribeEndpointLocked(endpoint);
        } finally {
            subscriptionLock.unlock();
        }
    }

    private void subscribeEndpointLocked(MqttListenerEndpoint endpoint) {
        MqttClientHandle handle = resolveClientHandle(endpoint.getClientName());
        String effectiveClient = handle.getClientName();
        String sharedGroup = endpoint.getSharedGroup();

        for (MqttTopicPattern pattern : endpoint.getPatterns()) {
            String filter = pattern.getSharedMqttFilter(sharedGroup);
            FilterKey key = new FilterKey(effectiveClient, filter);
            Set<String> activeIds = activeFilterEndpoints.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
            boolean addedNow = activeIds.add(endpoint.getId());
            List<String> clientTopics = subscribedTopicsByClient.computeIfAbsent(effectiveClient, k -> new CopyOnWriteArrayList<>());
            int effectiveQos = resolveMaxActiveQosLocked(key, endpoint.getQos());
            boolean needsBrokerSubscribe = activeIds.size() == 1
                    || !clientTopics.contains(filter)
                    || endpoint.getQos() >= effectiveQos && !subscribedQosMatches(key, effectiveQos);
            if (needsBrokerSubscribe) {
                try {
                    subscribeFilterToBrokerLocked(handle, filter, endpoint, effectiveQos, clientTopics);
                    subscribedQosByFilter.put(key, effectiveQos);
                } catch (RuntimeException ex) {
                    if (addedNow) {
                        activeIds.remove(endpoint.getId());
                        if (activeIds.isEmpty()) {
                            activeFilterEndpoints.remove(key);
                        }
                    }
                    throw ex;
                }
            } else {
                log.debug("[MQTT] Reusing existing Broker subscription [{}] on client [{}] for endpoint [{}] (refCount={})",
                        filter, effectiveClient, endpoint.getId(), activeIds.size());
            }
        }
    }

    private int resolveMaxActiveQosLocked(FilterKey key, int candidateQos) {
        Set<String> activeIds = activeFilterEndpoints.get(key);
        if (activeIds == null || activeIds.isEmpty()) {
            return candidateQos;
        }
        int maxQos = candidateQos;
        for (String id : activeIds) {
            MqttListenerEndpoint ep = findEndpoint(id);
            if (ep != null && ep.getQos() > maxQos) {
                maxQos = ep.getQos();
            }
        }
        return maxQos;
    }

    private boolean subscribedQosMatches(FilterKey key, int effectiveQos) {
        Integer current = subscribedQosByFilter.get(key);
        return current != null && current >= effectiveQos;
    }

    private void subscribeFilterToBrokerLocked(MqttClientHandle handle,
                                               String filter,
                                               MqttListenerEndpoint endpoint,
                                               int effectiveQos,
                                               List<String> clientTopics) {
        try {
            org.eclipse.paho.mqttv5.client.IMqttMessageListener listener = (topic, message) -> {
                // Paho I/O 回调线程中秒级提交至受管线程池，彻底杜绝 KeepAlive 假死
                dispatchFilterMessage(handle, filter, topic, message);
            };
            handle.subscribe(
                    filter,
                    effectiveQos,
                    endpoint.getAnnotation().noLocal(),
                    endpoint.getAnnotation().retainAsPublished(),
                    endpoint.getAnnotation().retainHandling(),
                    listener
            );
            if (!clientTopics.contains(filter)) {
                clientTopics.add(filter);
            }
            log.info("[MQTT] Client [{}] successfully subscribed to [{}] (qos={}) for endpoint [{}]",
                    handle.getClientName(), filter, effectiveQos, endpoint.getId());
        } catch (MqttException e) {
            throw new BusinessException("Failed to subscribe MQTT topic [" + filter + "]: " + e.getMessage(), e);
        }
    }

    private void dispatchFilterMessage(MqttClientHandle handle,
                                       String filter,
                                       String topic,
                                       MqttMessage message) {
        for (MqttListenerEndpoint endpoint : endpoints) {
            if (isPaused(endpoint.getId())
                    || StringUtils.hasText(endpoint.getSharedGroup())
                    || !isEndpointAssignedToClient(endpoint, handle.getClientName())) {
                continue;
            }
            for (MqttTopicPattern pattern : endpoint.getPatterns()) {
                if (pattern.getSharedMqttFilter(null).equals(filter) && pattern.matchAndExtract(topic) != null) {
                    submitTrackedTask(endpoint, doneCallback -> dispatchMessage(endpoint, pattern, topic, message, handle, doneCallback));
                    break;
                }
            }
        }
    }

    /**
     * 断线重连后重新订阅指定客户端的所有活跃（未暂停）端点主题
     */
    private void resubscribeEndpointsForClient(MqttClientHandle handle) {
        String clientName = handle.getClientName();
        log.info("[MQTT] Triggering automatic subscription self-healing for client [{}] on reconnect", clientName);
        subscriptionLock.lock();
        try {
            Set<String> resubscribedFilters = new HashSet<>();
            List<String> clientTopics = subscribedTopicsByClient.computeIfAbsent(clientName, k -> new CopyOnWriteArrayList<>());
            for (MqttListenerEndpoint endpoint : endpoints) {
                if (!isEndpointAssignedToClient(endpoint, clientName) || isPaused(endpoint.getId())) {
                    continue;
                }
                for (MqttTopicPattern pattern : endpoint.getPatterns()) {
                    String filter = pattern.getSharedMqttFilter(endpoint.getSharedGroup());
                    FilterKey key = new FilterKey(clientName, filter);
                    activeFilterEndpoints.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(endpoint.getId());
                    if (resubscribedFilters.add(filter)) {
                        int effectiveQos = resolveMaxActiveQosLocked(key, endpoint.getQos());
                        try {
                            subscribeFilterToBrokerLocked(handle, filter, endpoint, effectiveQos, clientTopics);
                            subscribedQosByFilter.put(key, effectiveQos);
                        } catch (Exception e) {
                            log.error("[MQTT] Error during resubscription for endpoint [{}] on client [{}]: {}",
                                    endpoint.getId(), clientName, e.getMessage(), e);
                        }
                    }
                }
            }
        } finally {
            subscriptionLock.unlock();
        }
    }

    private void dispatchMessage(MqttListenerEndpoint endpoint,
                                 MqttTopicPattern pattern,
                                 String actualTopic,
                                 MqttMessage message,
                                 MqttClientHandle handle,
                                 Runnable doneCallback) {
        if (isPaused(endpoint.getId())) {
            log.debug("[MQTT] Endpoint [{}] is paused, skipping message dispatch for topic [{}]", endpoint.getId(), actualTopic);
            doneCallback.run();
            return;
        }
        if (metrics != null) {
            metrics.recordReceived(handle.getClientName(), actualTopic);
        }

        ConsumeExecutionContext ctx = new ConsumeExecutionContext(endpoint, pattern, actualTopic, message, handle, doneCallback);
        if (!ctx.acquireConcurrencyPermit()) {
            ctx.finishWithoutAck();
            return;
        }

        try (MessageContextPropagator.ContextScope ignored = MessageContextPropagator.activate(ctx.headers)) {
            if (!ctx.prepareBeforeExecution()) {
                return;
            }
            ctx.executeAttempt(0);
        }
    }

    private AckMode resolveAckMode(MqttListenerEndpoint endpoint) {
        AckMode[] overrides = endpoint.getAnnotation().ackMode();
        if (overrides != null && overrides.length > 0 && overrides[0] != null) {
            return overrides[0];
        }
        return consumerProperties != null && consumerProperties.getAckMode() != null
                ? consumerProperties.getAckMode()
                : AckMode.AUTO_AFTER_CONSUME;
    }

    private int resolveMaxRetries(MqttListenerEndpoint endpoint) {
        int override = endpoint.getAnnotation().maxRetries();
        if (override >= 0) {
            return override;
        }
        return consumerProperties != null ? Math.max(0, consumerProperties.getMaxRetries()) : 2;
    }

    private long computeBackoffMillis(int failedAttemptIndex) {
        MqttConsumerProperties props = consumerProperties != null ? consumerProperties : new MqttConsumerProperties();
        long initialMs = props.getInitialBackoff() != null ? Math.max(0L, props.getInitialBackoff().toMillis()) : 200L;
        long maxMs = props.getMaxBackoff() != null ? Math.max(initialMs, props.getMaxBackoff().toMillis()) : 5000L;
        double multiplier = Math.max(1.0, props.getBackoffMultiplier());
        double computed = initialMs * Math.pow(multiplier, failedAttemptIndex);
        if (Double.isNaN(computed) || Double.isInfinite(computed)) {
            return maxMs;
        }
        return Math.min(Math.round(computed), maxMs);
    }

    private ScheduledExecutorService getOrCreateRetryScheduler() {
        ScheduledExecutorService ext = this.externalScheduler;
        if (ext != null) {
            return ext;
        }
        schedulerLock.lock();
        try {
            if (this.ownedScheduler == null) {
                this.ownedScheduler = MqttSchedulerSupport.createDaemonScheduler(2, "ateng-mqtt-retry-");
            }
            return this.ownedScheduler.getScheduledExecutor();
        } finally {
            schedulerLock.unlock();
        }
    }

    private final class ConsumeExecutionContext {
        private final MqttListenerEndpoint endpoint;
        private final MqttTopicPattern pattern;
        private final String actualTopic;
        private final MqttMessage message;
        private final MqttClientHandle handle;
        private final Runnable doneCallback;
        private final AckMode ackMode;
        private final int maxRetries;
        private final Map<String, String> headers;
        private final Semaphore semaphore;
        private final AtomicBoolean semaphoreAcquired = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final DefaultMqttAcknowledgment manualAck;
        private final long startNanos = System.nanoTime();
        private Map<String, String> pathVariables;

        private ConsumeExecutionContext(MqttListenerEndpoint endpoint,
                                        MqttTopicPattern pattern,
                                        String actualTopic,
                                        MqttMessage message,
                                        MqttClientHandle handle,
                                        Runnable doneCallback) {
            this.endpoint = endpoint;
            this.pattern = pattern;
            this.actualTopic = actualTopic;
            this.message = message;
            this.handle = handle;
            this.doneCallback = doneCallback;
            this.ackMode = resolveAckMode(endpoint);
            this.maxRetries = resolveMaxRetries(endpoint);
            this.headers = MqttHeaderUtils.extractHeaders(message);
            this.semaphore = endpoint.getConcurrencySemaphore();
            this.manualAck = ackMode == AckMode.MANUAL
                    ? new DefaultMqttAcknowledgment(() -> handleFatalPoisonPill(endpoint, pattern.getRawPattern(), actualTopic, message, handle,
                            new BusinessException("Explicitly rejected to dead-letter topic via MqttAcknowledgment.nackToDlt()")),
                            () -> handle.acknowledgeMessage(message))
                    : null;
        }

        boolean acquireConcurrencyPermit() {
            if (semaphore == null) {
                return true;
            }
            try {
                semaphore.acquire();
                semaphoreAcquired.set(true);
                return true;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        boolean prepareBeforeExecution() {
            this.pathVariables = pattern.matchAndExtract(actualTopic);
            if (this.pathVariables == null) {
                finishWithForcedAck();
                return false;
            }
            // 入站大报文守卫校验（超限属于结构性毒丸，0 次重试直接转入死信/丢弃）
            try {
                if (message != null && message.getPayload() != null && handle.getClientProperties() != null) {
                    payloadGuard.validate(message.getPayload(), handle.getClientProperties().getMaxPayloadSize(), actualTopic);
                }
            } catch (Throwable guardEx) {
                triggerPostConsume(handle.getClientName(), endpoint.getId(), actualTopic, message, headers, guardEx);
                recordConsumeMetric(false);
                handleFatalPoisonPill(endpoint, pattern.getRawPattern(), actualTopic, message, handle, guardEx);
                finishWithForcedAck();
                return false;
            }

            // 消息消费前置拦截切面
            for (MqttMessageInterceptor interceptor : interceptors) {
                try {
                    if (!interceptor.preConsume(handle.getClientName(), endpoint.getId(), actualTopic, message, headers)) {
                        log.debug("[MQTT] Message on topic [{}] for endpoint [{}] was intercepted and dropped by [{}]",
                                actualTopic, endpoint.getId(), interceptor.getClass().getSimpleName());
                        finishWithForcedAck();
                        return false;
                    }
                } catch (Throwable t) {
                    log.error("[MQTT] Error in interceptor [{}] preConsume: {}", interceptor.getClass().getSimpleName(), t.getMessage(), t);
                    handleExecutionFailure(0, t);
                    return false;
                }
            }
            return true;
        }

        void executeAttempt(int attempt) {
            try {
                Object result = endpoint.getAdapter().invoke(actualTopic, message, pathVariables, manualAck);
                if (result instanceof CompletableFuture<?> replyFuture) {
                    replyFuture.whenComplete((replyValue, replyEx) -> {
                        try (MessageContextPropagator.ContextScope ignored = MessageContextPropagator.activate(headers)) {
                            if (replyEx != null) {
                                Throwable unwrapped = replyEx instanceof CompletionException ce && ce.getCause() != null
                                        ? ce.getCause() : replyEx;
                                handleExecutionFailure(attempt, unwrapped);
                            } else {
                                completeSuccessfullyWithReply(replyValue);
                            }
                        }
                    });
                } else {
                    completeSuccessfullyWithReply(result);
                }
            } catch (Throwable e) {
                handleExecutionFailure(attempt, e);
            }
        }

        private void completeSuccessfullyWithReply(Object replyValue) {
            // 业务监听方法已完成执行，@MqttReply 回发故障不再重入业务逻辑以防止非幂等重复执行
            if (replyValue != null) {
                try {
                    publishDeclarativeReply(replyValue);
                } catch (Throwable replySendEx) {
                    log.error("[MQTT] Listener endpoint [{}] succeeded on topic [{}], but declarative @MqttReply publish failed: {}",
                            endpoint.getId(), actualTopic, replySendEx.getMessage(), replySendEx);
                }
            }
            triggerPostConsume(handle.getClientName(), endpoint.getId(), actualTopic, message, headers, null);
            recordConsumeMetric(true);
            finishWithAutoAck();
        }

        private void publishDeclarativeReply(Object replyValue) throws MqttException {
            MqttProperties inboundProps = message != null ? message.getProperties() : null;
            MqttReply replyAnnotation = endpoint.getReplyAnnotation();

            String replyTopic = inboundProps != null ? inboundProps.getResponseTopic() : null;
            if (!StringUtils.hasText(replyTopic) && replyAnnotation != null) {
                replyTopic = replyAnnotation.topic();
            }
            if (!StringUtils.hasText(replyTopic)) {
                log.warn("[MQTT] Listener endpoint [{}] returned non-null reply for topic [{}], but neither inbound ResponseTopic nor @MqttReply(topic=...) is present; skipping reply",
                        endpoint.getId(), actualTopic);
                return;
            }
            if (!handle.isConnected()) {
                throw new BusinessException("Cannot publish @MqttReply to [" + replyTopic + "]: client [" + handle.getClientName() + "] is disconnected");
            }

            int replyQos = replyAnnotation != null ? replyAnnotation.qos() : 1;
            boolean replyRetained = replyAnnotation != null && replyAnnotation.retained();
            byte[] replyBytes = endpoint.getAdapter().serializePayload(replyValue);
            payloadGuard.validate(
                    replyBytes,
                    handle.getClientProperties() != null ? handle.getClientProperties().getMaxPayloadSize() : null,
                    replyTopic
            );

            MqttMessage replyMessage = new MqttMessage(replyBytes);
            replyMessage.setQos(replyQos);
            replyMessage.setRetained(replyRetained);

            if (handle.isMqttV5()) {
                MqttProperties outboundProps = new MqttProperties();
                if (inboundProps != null && inboundProps.getCorrelationData() != null) {
                    outboundProps.setCorrelationData(inboundProps.getCorrelationData());
                }
                List<UserProperty> userProps = new ArrayList<>();
                MqttHeaderUtils.appendContextUserProperties(
                        userProps,
                        headers.get(MessageHeaderConstants.TRACE_ID),
                        headers.get(MessageHeaderConstants.TENANT_ID),
                        headers.get(MessageHeaderConstants.USER_ID),
                        headers.get(MessageHeaderConstants.IGNORE_TENANT)
                );
                outboundProps.setUserProperties(userProps);
                replyMessage.setProperties(outboundProps);
            }

            final String targetReplyTopic = replyTopic;
            handle.getAsyncClient().publish(targetReplyTopic, replyMessage, null, new MqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    log.debug("[MQTT] Declarative reply published from endpoint [{}] to topic [{}]", endpoint.getId(), targetReplyTopic);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    log.error("[MQTT] Async failure publishing declarative reply from endpoint [{}] to topic [{}]: {}",
                            endpoint.getId(), targetReplyTopic, exception != null ? exception.getMessage() : "unknown", exception);
                }
            });
        }

        void handleExecutionFailure(int attempt, Throwable e) {
            // 1. 消费端静默确认与幂等防重拦截 (Silent ACK Invariant)
            if (isSilentAckOrIdempotent(e)) {
                triggerPostConsume(handle.getClientName(), endpoint.getId(), actualTopic, message, headers, e);
                recordConsumeMetric(false);
                log.info("[MQTT] Silent ACK requested or idempotent duplicate detected on topic [{}] for endpoint [{}], submitting ACK to terminate redelivery loop: {}",
                        actualTopic, endpoint.getId(), e.getMessage());
                finishWithForcedAck();
                return;
            }

            // 2. MANUAL 模式下业务代码已显式完成确认或放弃确认的幂等闭环
            if (manualAck != null && manualAck.isSettled()) {
                triggerPostConsume(handle.getClientName(), endpoint.getId(), actualTopic, message, headers, e);
                recordConsumeMetric(false);
                finishWithoutAck();
                return;
            }

            // 3. @Valid 校验失败或 FatalExceptionClassifier 判定的不可自愈毒丸：0 次重试直接转入死信并强制确认
            if (FatalExceptionClassifier.isFatal(e)) {
                triggerPostConsume(handle.getClientName(), endpoint.getId(), actualTopic, message, headers, e);
                recordConsumeMetric(false);
                handleFatalPoisonPill(endpoint, pattern.getRawPattern(), actualTopic, message, handle, e);
                finishWithForcedAck();
                return;
            }

            // 4. 可恢复非致命异常：按受管指数退避调度重试，期间保持信号量与在途计数不释放
            if (attempt < maxRetries && running.get() && !isPaused(endpoint.getId())) {
                long delayMs = computeBackoffMillis(attempt);
                log.warn("[MQTT] Recoverable consumer error on topic [{}] for endpoint [{}] (attempt {}/{}), scheduling retry in {}ms: {}",
                        actualTopic, endpoint.getId(), attempt + 1, maxRetries + 1, delayMs, e.getMessage());
                if (scheduleRetryAttempt(attempt + 1, delayMs)) {
                    return;
                }
            }

            // 5. 重试耗尽或容器停机/暂停：停机或暂停中留待 Broker 重投；否则转入 DLT 并强制提交确认解除飞行窗口阻塞
            triggerPostConsume(handle.getClientName(), endpoint.getId(), actualTopic, message, headers, e);
            recordConsumeMetric(false);
            if (!running.get() || isPaused(endpoint.getId())) {
                finishWithoutAck();
                return;
            }
            if (endpoint.getAnnotation().enableDlt()) {
                handleFatalPoisonPill(endpoint, pattern.getRawPattern(), actualTopic, message, handle, e);
            } else {
                log.error("[MQTT] Consumer retries exhausted for topic [{}] on endpoint [{}] (DLT disabled), acknowledging to unblock flow: {}",
                        actualTopic, endpoint.getId(), e.getMessage(), e);
            }
            finishWithForcedAck();
        }

        private boolean scheduleRetryAttempt(int nextAttempt, long delayMs) {
            PendingRetry pendingRetry = new PendingRetry(this);
            pendingRetries.add(pendingRetry);
            try {
                ScheduledFuture<?> future = getOrCreateRetryScheduler().schedule(() -> {
                    if (!pendingRetries.remove(pendingRetry)) {
                        return;
                    }
                    if (!running.get() || isPaused(endpoint.getId())) {
                        finishWithoutAck();
                        return;
                    }
                    Runnable retryTask = () -> {
                        try (MessageContextPropagator.ContextScope ignored = MessageContextPropagator.activate(headers)) {
                            executeAttempt(nextAttempt);
                        }
                    };
                    if (executor != null) {
                        try {
                            executor.execute(retryTask);
                        } catch (Throwable submitEx) {
                            log.warn("[MQTT] Retry executor rejected attempt for endpoint [{}]: {}",
                                    endpoint.getId(), submitEx.getMessage());
                            finishWithoutAck();
                        }
                    } else {
                        retryTask.run();
                    }
                }, delayMs, TimeUnit.MILLISECONDS);
                pendingRetry.setFuture(future);
                if (!pendingRetries.contains(pendingRetry)) {
                    future.cancel(false);
                }
                return true;
            } catch (Throwable scheduleEx) {
                pendingRetries.remove(pendingRetry);
                log.warn("[MQTT] Failed to schedule retry for endpoint [{}]: {}", endpoint.getId(), scheduleEx.getMessage());
                return false;
            }
        }

        private void recordConsumeMetric(boolean success) {
            if (metrics != null) {
                metrics.recordConsume(endpoint.getId(), pattern.getRawPattern(), success, System.nanoTime() - startNanos);
            }
        }

        void finishWithAutoAck() {
            finish(ackMode == AckMode.AUTO_AFTER_CONSUME);
        }

        void finishWithForcedAck() {
            finish(true);
        }

        void finishWithoutAck() {
            finish(false);
        }

        private void finish(boolean shouldAck) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            try {
                if (shouldAck) {
                    if (manualAck != null) {
                        manualAck.ack();
                    } else {
                        handle.acknowledgeMessage(message);
                    }
                }
            } finally {
                if (semaphore != null && semaphoreAcquired.compareAndSet(true, false)) {
                    semaphore.release();
                }
                doneCallback.run();
            }
        }
    }

    private static final class PendingRetry {
        private final ConsumeExecutionContext context;
        private volatile ScheduledFuture<?> future;

        private PendingRetry(ConsumeExecutionContext context) {
            this.context = context;
        }

        ConsumeExecutionContext context() {
            return context;
        }

        ScheduledFuture<?> getFuture() {
            return future;
        }

        void setFuture(ScheduledFuture<?> future) {
            this.future = future;
        }
    }

    private static final class DefaultMqttAcknowledgment implements MqttAcknowledgment {

        private final Runnable dltAction;
        private final Runnable ackAction;
        private final AtomicBoolean settled = new AtomicBoolean(false);
        private volatile boolean acknowledged = false;

        private DefaultMqttAcknowledgment(Runnable dltAction, Runnable ackAction) {
            this.dltAction = dltAction;
            this.ackAction = ackAction;
        }

        @Override
        public void ack() {
            if (settled.compareAndSet(false, true)) {
                ackAction.run();
                this.acknowledged = true;
            }
        }

        @Override
        public void nackToDlt() {
            if (settled.compareAndSet(false, true)) {
                dltAction.run();
                ackAction.run();
                this.acknowledged = true;
            }
        }

        @Override
        public void abandonWithoutAck() {
            settled.compareAndSet(false, true);
        }

        @Override
        public boolean isAcknowledged() {
            return acknowledged;
        }

        boolean isSettled() {
            return settled.get();
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
                                       String topicPattern,
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
        if (MqttDeadLetterRecord.isDeadLetterTopic(actualTopic)) {
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
            dltMessage.setRetained(dltProperties != null && dltProperties.isRetained());

            // 构建死信出站 MqttProperties 与本地死信存储记录
            Throwable fatalCause = findRootFatalCause(cause);
            String exceptionClass = fatalCause != null ? fatalCause.getClass().getName() : null;
            String exMsg = fatalCause != null ? fatalCause.getMessage() : null;
            if (exMsg != null && exMsg.length() > 512) {
                exMsg = exMsg.substring(0, 512) + "...";
            }
            long dltTimestamp = System.currentTimeMillis();

            MqttProperties dltMqttProperties = new MqttProperties();
            List<UserProperty> userProps = new ArrayList<>();
            Map<String, String> userPropMap = new LinkedHashMap<>();
            if (message != null && message.getProperties() != null && message.getProperties().getUserProperties() != null) {
                for (UserProperty existingProp : message.getProperties().getUserProperties()) {
                    userProps.add(existingProp);
                    userPropMap.put(existingProp.getKey(), existingProp.getValue());
                }
            }
            appendDltHeader(userProps, userPropMap, MqttDeadLetterRecord.HEADER_ORIGINAL_TOPIC, actualTopic);
            if (exceptionClass != null) {
                appendDltHeader(userProps, userPropMap, MqttDeadLetterRecord.HEADER_EXCEPTION_CLASS, exceptionClass);
            }
            if (exMsg != null) {
                appendDltHeader(userProps, userPropMap, MqttDeadLetterRecord.HEADER_EXCEPTION_MESSAGE, exMsg);
            }
            appendDltHeader(userProps, userPropMap, MqttDeadLetterRecord.HEADER_DLT_TIMESTAMP, String.valueOf(dltTimestamp));
            dltMqttProperties.setUserProperties(userProps);
            dltMessage.setProperties(dltMqttProperties);

            if (deadLetterStore != null) {
                deadLetterStore.save(handle.getClientName(), new MqttDeadLetterRecord(
                        dltTopic,
                        actualTopic,
                        payload,
                        endpoint.getQos(),
                        exceptionClass,
                        exMsg,
                        dltTimestamp,
                        replayCount,
                        userPropMap
                ));
            }

            handle.getAsyncClient().publish(dltTopic, dltMessage);
            if (metrics != null) {
                metrics.recordDlt(endpoint.getId(), topicPattern);
            }
            log.warn("[MQTT] Poison pill message on topic [{}] successfully transferred to DLT [{}] for endpoint [{}], submitted ACK to break redelivery loop",
                    actualTopic, dltTopic, endpoint.getId());
        } catch (Exception e) {
            log.error("[MQTT] Failed to forward poison pill to DLT topic [{}] on client [{}]: {}",
                    dltTopic, handle.getClientName(), e.getMessage(), e);
        }
    }

    private void appendDltHeader(List<UserProperty> userProps, Map<String, String> userPropMap, String key, String value) {
        userProps.add(new UserProperty(key, value));
        userPropMap.put(key, value);
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
            subscriptionLock.lock();
            try {
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
                activeFilterEndpoints.clear();
                subscribedQosByFilter.clear();
            } finally {
                subscriptionLock.unlock();
            }

            // 2. 第二阶段：主动取消退避等待中的延迟重试任务、清空单端点串行积压队列并排空线程池
            cancelPendingRetries();
            drainSerialQueues();
            drainExecutor();

            schedulerLock.lock();
            try {
                if (ownedScheduler != null) {
                    ownedScheduler.shutdown();
                    ownedScheduler = null;
                }
            } finally {
                schedulerLock.unlock();
            }
        }
    }

    private void cancelPendingRetries() {
        if (pendingRetries.isEmpty()) {
            return;
        }
        for (PendingRetry pending : new ArrayList<>(pendingRetries)) {
            if (pendingRetries.remove(pending)) {
                ScheduledFuture<?> future = pending.getFuture();
                if (future != null) {
                    future.cancel(false);
                }
                pending.context().finishWithoutAck();
            }
        }
    }

    private void submitTrackedTask(MqttListenerEndpoint endpoint, Consumer<Runnable> taskConsumer) {
        inFlightTasks.incrementAndGet();
        AtomicBoolean completed = new AtomicBoolean(false);
        Runnable doneCallback = () -> {
            if (completed.compareAndSet(false, true)) {
                onTaskCompleted();
            }
        };
        if (endpoint != null && endpoint.getConcurrency() == 1 && executor != null) {
            SerialEndpointQueue serialQueue = serialQueues.computeIfAbsent(endpoint.getId(), k -> new SerialEndpointQueue());
            serialQueue.enqueue(new QueuedDispatchTask(taskConsumer, doneCallback));
            return;
        }
        dispatchToExecutor(taskConsumer, doneCallback);
    }

    private boolean dispatchToExecutor(Consumer<Runnable> taskConsumer, Runnable doneCallback) {
        Runnable trackedTask = () -> {
            try {
                taskConsumer.accept(doneCallback);
            } catch (Throwable ex) {
                doneCallback.run();
                throw ex;
            }
        };
        if (executor != null) {
            try {
                executor.execute(trackedTask);
                return true;
            } catch (Throwable submitEx) {
                log.warn("[MQTT] Consumer executor rejected inbound task: {}", submitEx.getMessage());
                doneCallback.run();
                return false;
            }
        } else {
            trackedTask.run();
            return true;
        }
    }

    private void drainSerialQueues() {
        for (SerialEndpointQueue queue : serialQueues.values()) {
            for (QueuedDispatchTask pending : queue.drainPending()) {
                pending.doneCallback().run();
            }
        }
    }

    private record QueuedDispatchTask(Consumer<Runnable> taskConsumer, Runnable doneCallback) {
    }

    private final class SerialEndpointQueue {
        private final ReentrantLock queueLock = new ReentrantLock();
        private final Deque<QueuedDispatchTask> pendingQueue = new ArrayDeque<>();
        private boolean active = false;

        void enqueue(QueuedDispatchTask task) {
            if (!running.get()) {
                task.doneCallback().run();
                return;
            }
            boolean startNow = false;
            queueLock.lock();
            try {
                if (!running.get()) {
                    startNow = false;
                } else if (!active) {
                    active = true;
                    startNow = true;
                } else {
                    pendingQueue.addLast(task);
                    return;
                }
            } finally {
                queueLock.unlock();
            }
            if (startNow) {
                runSequentially(task);
            } else {
                task.doneCallback().run();
            }
        }

        private void runSequentially(QueuedDispatchTask initialTask) {
            QueuedDispatchTask current = initialTask;
            while (current != null) {
                final QueuedDispatchTask taskToRun = current;
                final AtomicBoolean advanced = new AtomicBoolean(false);
                final boolean[] syncFailed = new boolean[]{false};
                Runnable chainedDone = () -> {
                    if (advanced.compareAndSet(false, true)) {
                        try {
                            taskToRun.doneCallback().run();
                        } finally {
                            // 若当前处于同步拒绝路径，交由外层循环迭代推进避免递归栈深化
                            if (!syncFailed[0]) {
                                QueuedDispatchTask next = pollNextLocked();
                                if (next != null) {
                                    runSequentially(next);
                                }
                            }
                        }
                    }
                };
                // 若 executor 拒绝提交（返回 false），标记 syncFailed 让当前栈帧直接迭代推进下一条
                if (executor != null) {
                    Runnable trackedTask = () -> {
                        try {
                            taskToRun.taskConsumer().accept(chainedDone);
                        } catch (Throwable ex) {
                            chainedDone.run();
                            throw ex;
                        }
                    };
                    try {
                        executor.execute(trackedTask);
                        return;
                    } catch (Throwable submitEx) {
                        log.warn("[MQTT] Consumer executor rejected serial endpoint task: {}", submitEx.getMessage());
                        syncFailed[0] = true;
                        chainedDone.run();
                        current = pollNextLocked();
                    }
                } else {
                    dispatchToExecutor(taskToRun.taskConsumer(), chainedDone);
                    return;
                }
            }
        }

        private QueuedDispatchTask pollNextLocked() {
            queueLock.lock();
            try {
                QueuedDispatchTask next = running.get() ? pendingQueue.pollFirst() : null;
                if (next == null) {
                    active = false;
                }
                return next;
            } finally {
                queueLock.unlock();
            }
        }

        List<QueuedDispatchTask> drainPending() {
            queueLock.lock();
            try {
                if (pendingQueue.isEmpty()) {
                    active = false;
                    return Collections.emptyList();
                }
                List<QueuedDispatchTask> drained = new ArrayList<>(pendingQueue);
                pendingQueue.clear();
                active = false;
                return drained;
            } finally {
                queueLock.unlock();
            }
        }
    }

    private void onTaskCompleted() {
        if (inFlightTasks.decrementAndGet() <= 0) {
            drainLock.lock();
            try {
                allTasksDrained.signalAll();
            } finally {
                drainLock.unlock();
            }
        }
    }

    private void drainExecutor() {
        Duration maxTimeout = Duration.ofSeconds(5);
        for (MqttClientHandle h : clientRegistry.getClientHandles().values()) {
            if (h.getClientProperties() != null && h.getClientProperties().getShutdownTimeout() != null) {
                if (h.getClientProperties().getShutdownTimeout().compareTo(maxTimeout) > 0) {
                    maxTimeout = h.getClientProperties().getShutdownTimeout();
                }
            }
        }
        long timeoutMs = maxTimeout.toMillis();
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        log.info("[MQTT] Awaiting in-flight consumer tasks draining (active: {}, max timeout: {}ms)",
                inFlightTasks.get(), timeoutMs);
        drainLock.lock();
        try {
            while (inFlightTasks.get() > 0) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    log.warn("[MQTT] Timed out waiting for [{}] in-flight consumer tasks to drain", inFlightTasks.get());
                    break;
                }
                try {
                    allTasksDrained.await(remainingNanos, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            drainLock.unlock();
        }
        log.info("[MQTT] Executor task queue drained successfully (remaining in-flight: {})", inFlightTasks.get());
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
        MqttMessageInterceptor.invokePostConsume(interceptors, clientName, endpointId, actualTopic, message, headers, ex);
    }

    // ==================== 容器动态运维接口 (MqttContainerManager 支撑) ====================

    /**
     * 暂停指定端点的消息消费调度（基于引用计数在活跃端点归零时向 Broker 真实退订）
     *
     * @param endpointId 端点唯一标识
     */
    public void pause(String endpointId) {
        if (!StringUtils.hasText(endpointId)) {
            throw new IllegalArgumentException("endpointId must not be blank");
        }
        subscriptionLock.lock();
        try {
            MqttListenerEndpoint endpoint = findEndpoint(endpointId);
            if (endpoint == null) {
                throw new IllegalArgumentException("MQTT listener endpoint not found: " + endpointId);
            }
            pausedEndpoints.add(endpointId);
            releaseEndpointSubscriptionsLocked(endpoint);
            log.info("[MQTT] Paused message dispatch for listener endpoint [{}]", endpointId);
        } finally {
            subscriptionLock.unlock();
        }
    }

    /**
     * 恢复指定端点的消息消费调度（按需重建 Broker 底层订阅）
     *
     * @param endpointId 端点唯一标识
     */
    public void resume(String endpointId) {
        if (!StringUtils.hasText(endpointId)) {
            throw new IllegalArgumentException("endpointId must not be blank");
        }
        subscriptionLock.lock();
        try {
            MqttListenerEndpoint endpoint = findEndpoint(endpointId);
            if (endpoint == null) {
                throw new IllegalArgumentException("MQTT listener endpoint not found: " + endpointId);
            }
            pausedEndpoints.remove(endpointId);
            if (isRunning()) {
                subscribeEndpointLocked(endpoint);
            }
            log.info("[MQTT] Resumed message dispatch for listener endpoint [{}]", endpointId);
        } finally {
            subscriptionLock.unlock();
        }
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
     * 全局暂停所有已注册端点的消费调度（并向 Broker 真实退订对应主题过滤器）
     */
    public void pauseAll() {
        subscriptionLock.lock();
        try {
            RuntimeException firstError = null;
            for (MqttListenerEndpoint endpoint : endpoints) {
                pausedEndpoints.add(endpoint.getId());
                try {
                    releaseEndpointSubscriptionsLocked(endpoint);
                } catch (RuntimeException ex) {
                    if (firstError == null) {
                        firstError = ex;
                    } else {
                        firstError.addSuppressed(ex);
                    }
                }
            }
            if (firstError != null) {
                throw firstError;
            }
            log.info("[MQTT] Paused message dispatch for all [{}] listener endpoints", endpoints.size());
        } finally {
            subscriptionLock.unlock();
        }
    }

    /**
     * 全局恢复所有已注册端点的消费调度（按引用计数重建 Broker 订阅）
     */
    public void resumeAll() {
        subscriptionLock.lock();
        try {
            pausedEndpoints.clear();
            if (isRunning()) {
                for (MqttListenerEndpoint endpoint : endpoints) {
                    subscribeEndpointLocked(endpoint);
                }
            }
            log.info("[MQTT] Resumed message dispatch for all [{}] listener endpoints", endpoints.size());
        } finally {
            subscriptionLock.unlock();
        }
    }

    private void releaseEndpointSubscriptionsLocked(MqttListenerEndpoint endpoint) {
        MqttClientHandle handle = resolveExistingClientHandle(endpoint.getClientName());
        String effectiveClient = handle != null
                ? handle.getClientName()
                : (StringUtils.hasText(endpoint.getClientName()) ? endpoint.getClientName() : clientRegistry.getDefaultClientName());
        if (!StringUtils.hasText(effectiveClient)) {
            return;
        }
        for (MqttTopicPattern pattern : endpoint.getPatterns()) {
            String filter = pattern.getSharedMqttFilter(endpoint.getSharedGroup());
            FilterKey key = new FilterKey(effectiveClient, filter);
            Set<String> activeIds = activeFilterEndpoints.get(key);
            if (activeIds == null || !activeIds.contains(endpoint.getId())) {
                continue;
            }
            boolean lastActiveEndpoint = activeIds.size() == 1;
            if (lastActiveEndpoint && handle != null && handle.isConnected()) {
                try {
                    handle.unsubscribe(filter);
                    log.info("[MQTT] Client [{}] unsubscribed [{}] because active endpoint refCount reached 0",
                            effectiveClient, filter);
                } catch (MqttException e) {
                    throw new BusinessException("Failed to unsubscribe MQTT topic [" + filter + "]: " + e.getMessage(), e);
                }
            }
            activeIds.remove(endpoint.getId());
            if (activeIds.isEmpty()) {
                activeFilterEndpoints.remove(key);
                subscribedQosByFilter.remove(key);
                List<String> clientTopics = subscribedTopicsByClient.get(effectiveClient);
                if (clientTopics != null) {
                    clientTopics.remove(filter);
                }
            }
        }
    }

    private MqttListenerEndpoint findEndpoint(String endpointId) {
        for (MqttListenerEndpoint endpoint : endpoints) {
            if (endpoint.getId().equals(endpointId)) {
                return endpoint;
            }
        }
        return null;
    }

    private MqttClientHandle resolveExistingClientHandle(String clientName) {
        String effectiveName = StringUtils.hasText(clientName) ? clientName : clientRegistry.getDefaultClientName();
        if (!StringUtils.hasText(effectiveName) || !clientRegistry.containsClient(effectiveName)) {
            return null;
        }
        return clientRegistry.getClientHandle(effectiveName);
    }

    private record FilterKey(String clientName, String mqttFilter) {
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
