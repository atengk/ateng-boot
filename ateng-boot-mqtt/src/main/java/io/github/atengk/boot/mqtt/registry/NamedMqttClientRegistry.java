package io.github.atengk.boot.mqtt.registry;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mqtt.credentials.MqttCredentials;
import io.github.atengk.boot.mqtt.credentials.MqttCredentialsProvider;
import io.github.atengk.boot.mqtt.event.MqttConnectedEvent;
import io.github.atengk.boot.mqtt.event.MqttConnectionLostEvent;
import io.github.atengk.boot.mqtt.event.MqttDisconnectedEvent;
import io.github.atengk.boot.mqtt.properties.AtengMqttProperties;
import io.github.atengk.boot.mqtt.properties.ClientIdStrategy;
import io.github.atengk.boot.mqtt.properties.MqttClientProperties;
import io.github.atengk.boot.mqtt.properties.OfflineBufferPolicy;
import io.github.atengk.boot.mqtt.properties.PersistenceType;
import io.github.atengk.boot.mqtt.ssl.MqttSslSocketFactoryBuilder;
import io.github.atengk.boot.mqtt.will.MqttWill;
import io.github.atengk.boot.mqtt.will.MqttWillProvider;
import org.eclipse.paho.mqttv5.client.DisconnectedBufferOptions;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClientPersistence;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.client.persist.MqttDefaultFilePersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * MQTT 命名客户端注册中心与生命周期管理器
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class NamedMqttClientRegistry implements InitializingBean, DisposableBean, SmartLifecycle, ApplicationEventPublisherAware {

    private static final Logger log = LoggerFactory.getLogger(NamedMqttClientRegistry.class);

    private final AtengMqttProperties properties;
    private final MqttWillProvider willProvider;
    private final MqttCredentialsProvider credentialsProvider;
    private final Map<String, MqttClientHandle> clientHandles = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ReentrantLock registryLock = new ReentrantLock();
    private final List<Consumer<MqttClientHandle>> clientRegisteredListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> clientUnregisterListeners = new CopyOnWriteArrayList<>();
    private final List<Predicate<String>> activeBindingPredicates = new CopyOnWriteArrayList<>();
    private ApplicationEventPublisher applicationEventPublisher;

    public NamedMqttClientRegistry(AtengMqttProperties properties) {
        this(properties, null, null);
    }

    public NamedMqttClientRegistry(AtengMqttProperties properties, MqttWillProvider willProvider) {
        this(properties, willProvider, null);
    }

    public NamedMqttClientRegistry(AtengMqttProperties properties,
                                   MqttWillProvider willProvider,
                                   MqttCredentialsProvider credentialsProvider) {
        this.properties = properties;
        this.willProvider = willProvider;
        this.credentialsProvider = credentialsProvider;
    }

    public void addClientRegisteredListener(Consumer<MqttClientHandle> listener) {
        if (listener != null) {
            clientRegisteredListeners.add(listener);
        }
    }

    public void addClientUnregisterListener(Consumer<String> listener) {
        if (listener != null) {
            clientUnregisterListeners.add(listener);
        }
    }

    public void addActiveBindingPredicate(Predicate<String> predicate) {
        if (predicate != null) {
            activeBindingPredicates.add(predicate);
        }
    }

    @Override
    public void afterPropertiesSet() {
        initClients();
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            if (clientHandles.isEmpty()) {
                initClients();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return 10; // 在监听器与 RPC 管理器停止后断开物理连接
    }

    /**
     * 初始化并连接所有配置的 MQTT 客户端
     */
    public void initClients() {
        List<MqttClientHandle> initialized = new ArrayList<>();
        registryLock.lock();
        try {
            if (!properties.isEnabled()) {
                log.info("[MQTT] ateng-boot-mqtt is disabled via configuration");
                return;
            }
            Map<String, MqttClientProperties> clients = properties.getClients();
            if (clients == null || clients.isEmpty()) {
                log.warn("[MQTT] No MQTT clients configured under 'ateng.boot.mqtt.clients'");
                return;
            }

            for (Map.Entry<String, MqttClientProperties> entry : clients.entrySet()) {
                String clientName = entry.getKey();
                if (clientHandles.containsKey(clientName)) {
                    continue;
                }
                MqttClientHandle handle = registerConnectedHandle(clientName, entry.getValue(), false);
                initialized.add(handle);
            }
        } finally {
            registryLock.unlock();
        }
        for (MqttClientHandle handle : initialized) {
            notifyRegisteredAndPublishConnected(handle);
        }
    }

    /**
     * 运行期动态注册并连接命名 MQTT 客户端（重名立即快速失败）
     *
     * @param clientName  客户端逻辑名称
     * @param clientProps 客户端配置属性
     * @return 已连接的客户端句柄
     */
    public MqttClientHandle registerClient(String clientName, MqttClientProperties clientProps) {
        validateRegistrationArgs(clientName, clientProps);
        String trimmedName = clientName.trim();
        MqttClientHandle handle;
        registryLock.lock();
        try {
            if (clientHandles.containsKey(trimmedName)) {
                throw new BusinessException("MQTT client [" + trimmedName + "] already exists");
            }
            handle = registerConnectedHandle(trimmedName, clientProps, true);
        } finally {
            registryLock.unlock();
        }
        notifyRegisteredAndPublishConnected(handle);
        return handle;
    }

    /**
     * 运行期动态注册或热替换命名 MQTT 客户端（若已存在同名客户端，则先安全断开并清理旧资源后重建连接）
     *
     * @param clientName  客户端逻辑名称
     * @param clientProps 客户端配置属性
     * @return 新建连接的客户端句柄
     */
    public MqttClientHandle registerOrReplaceClient(String clientName, MqttClientProperties clientProps) {
        validateRegistrationArgs(clientName, clientProps);
        String trimmedName = clientName.trim();
        MqttClientHandle handle;
        registryLock.lock();
        try {
            if (clientHandles.containsKey(trimmedName)) {
                MqttClientHandle previous = detachHandleForRemoval(trimmedName, clientHandles.get(trimmedName));
                // 热替换同逻辑名客户端时需先断开旧连接，避免 STATIC clientId 与新建连接在 Broker 端互踢
                disconnectAndCloseHandle(trimmedName, previous);
            }
            handle = registerConnectedHandle(trimmedName, clientProps, true);
        } finally {
            registryLock.unlock();
        }
        notifyRegisteredAndPublishConnected(handle);
        return handle;
    }

    /**
     * 运行期安全注销并断开指定的命名 MQTT 客户端（默认禁止注销默认主客户端及存在活跃监听器的客户端）
     *
     * @param clientName 客户端逻辑名称
     */
    public void unregisterClient(String clientName) {
        unregisterClient(clientName, false);
    }

    /**
     * 运行期安全注销并断开指定的命名 MQTT 客户端
     *
     * @param clientName 客户端逻辑名称
     * @param force      是否强制注销（为 true 时跳过默认客户端与活跃监听器绑定守卫）
     */
    public void unregisterClient(String clientName, boolean force) {
        if (!StringUtils.hasText(clientName)) {
            throw new IllegalArgumentException("clientName must not be blank");
        }
        String trimmedName = clientName.trim();
        MqttClientHandle removed;
        registryLock.lock();
        try {
            MqttClientHandle handle = clientHandles.get(trimmedName);
            if (handle == null) {
                throw new BusinessException("No MQTT client found with name [" + trimmedName + "]");
            }
            if (!force && trimmedName.equals(properties.getDefaultClient())) {
                throw new BusinessException("Cannot unregister default MQTT client [" + trimmedName + "] without force=true");
            }
            if (!force && hasActiveBindings(trimmedName)) {
                throw new BusinessException("Cannot unregister MQTT client [" + trimmedName + "] while active listener endpoints are bound");
            }
            removed = detachHandleForRemoval(trimmedName, handle);
        } finally {
            registryLock.unlock();
        }
        disconnectAndCloseHandle(trimmedName, removed);
        log.info("[MQTT] Dynamically unregistered and disconnected client [{}]", trimmedName);
    }

    private MqttClientHandle registerConnectedHandle(String clientName, MqttClientProperties clientProps, boolean dynamic) {
        try {
            MqttClientHandle handle = createAndConnectClient(clientName, clientProps);
            clientHandles.put(clientName, handle);
            String primaryUri = clientProps.resolvePrimaryServerUri();
            log.info("[MQTT] Successfully {} client [{}] with actual clientId [{}] to [{}]",
                    dynamic ? "dynamically registered and connected" : "initialized and connected",
                    clientName, handle.getActualClientId(), primaryUri);
            return handle;
        } catch (Exception e) {
            if (e instanceof BusinessException be) {
                throw be;
            }
            String action = dynamic ? "register" : "initialize";
            throw new BusinessException("Failed to " + action + " MQTT client [" + clientName + "]: " + e.getMessage(), e);
        }
    }

    private void notifyRegisteredAndPublishConnected(MqttClientHandle handle) {
        if (handle == null) {
            return;
        }
        notifyClientRegistered(handle);
        MqttClientProperties clientProps = handle.getClientProperties();
        String primaryUri = clientProps != null ? clientProps.resolvePrimaryServerUri() : null;
        publishConnectedEvent(handle.getClientName(), primaryUri, handle.getActualClientId(), false);
    }

    private MqttClientHandle detachHandleForRemoval(String clientName, MqttClientHandle handle) {
        for (Consumer<String> listener : clientUnregisterListeners) {
            try {
                listener.accept(clientName);
            } catch (Exception e) {
                log.warn("[MQTT] Error in unregister listener for client [{}]: {}", clientName, e.getMessage(), e);
            }
        }
        clientHandles.remove(clientName, handle);
        return handle;
    }

    private void disconnectAndCloseHandle(String clientName, MqttClientHandle handle) {
        if (handle == null) {
            return;
        }
        handle.markClosing();
        MqttClientProperties clientProps = handle.getClientProperties();
        String primaryUri = clientProps != null ? clientProps.resolvePrimaryServerUri() : null;
        long shutdownMs = clientProps != null && clientProps.getShutdownTimeout() != null
                ? clientProps.getShutdownTimeout().toMillis() : 5000L;
        long effectiveTimeoutMs = shutdownMs > 0 ? shutdownMs : 5000L;
        try {
            if (handle.getAsyncClient() != null && handle.getAsyncClient().isConnected()) {
                // 显式发送 MQTT 规范 DISCONNECT 报文，告知 Broker 正常下线并依法销毁 LWT 遗嘱消息（防误报）
                handle.getAsyncClient().disconnect().waitForCompletion(effectiveTimeoutMs);
                publishDisconnectedEvent(clientName, primaryUri, handle.getActualClientId(), handle);
            }
            if (handle.getAsyncClient() != null) {
                handle.getAsyncClient().close();
            }
            log.info("[MQTT] Client [{}] disconnected and closed cleanly with DISCONNECT", clientName);
        } catch (Exception e) {
            log.warn("[MQTT] Error closing MQTT client [{}]: {}", clientName, e.getMessage());
        }
    }

    private void notifyClientRegistered(MqttClientHandle handle) {
        for (Consumer<MqttClientHandle> listener : clientRegisteredListeners) {
            try {
                listener.accept(handle);
            } catch (Exception e) {
                log.warn("[MQTT] Error in client registered listener for [{}]: {}", handle.getClientName(), e.getMessage(), e);
            }
        }
    }

    private boolean hasActiveBindings(String clientName) {
        for (Predicate<String> predicate : activeBindingPredicates) {
            if (predicate.test(clientName)) {
                return true;
            }
        }
        return false;
    }

    private void validateRegistrationArgs(String clientName, MqttClientProperties clientProps) {
        if (!StringUtils.hasText(clientName)) {
            throw new IllegalArgumentException("clientName must not be blank");
        }
        Objects.requireNonNull(clientProps, "clientProps must not be null");
    }

    /**
     * 创建并连接指定的单个 MQTT 客户端
     *
     * @param clientName  客户端逻辑名称
     * @param clientProps 客户端配置属性
     * @return 客户端句柄
     * @throws MqttException 底层连接异常
     */
    private MqttClientHandle createAndConnectClient(String clientName, MqttClientProperties clientProps) throws MqttException {
        // 0. 校验协议版本合法性防线 (Fail-Fast Invariant)
        String protocolVersion = StringUtils.hasText(clientProps.getProtocolVersion()) ? clientProps.getProtocolVersion().trim() : "5.0";
        if (!"5.0".equalsIgnoreCase(protocolVersion) && !"5".equalsIgnoreCase(protocolVersion)
                && !"3.1.1".equalsIgnoreCase(protocolVersion) && !"3.1".equalsIgnoreCase(protocolVersion)) {
            throw new IllegalArgumentException("Unsupported MQTT protocol version [" + protocolVersion + "] for client [" + clientName + "]. Supported versions: 5.0, 3.1.1");
        }

        // 1. 校验持久会话与随机 ClientId 策略防线 (Fail-Fast Invariant)
        if (!clientProps.isCleanStart() && clientProps.getClientIdStrategy() == ClientIdStrategy.RANDOM_SUFFIX) {
            log.warn("[MQTT] Client [{}] has cleanStart=false (Persistent Session) but uses RANDOM_SUFFIX clientIdStrategy. " +
                    "Reconnecting with random clientId will orphan previous offline session and queued QoS 1 messages! " +
                    "Consider setting clientIdStrategy=STATIC or IP_PID.", clientName);
        }

        // 2. 解析实际 ClientId 与可用 Broker 地址列表
        String baseClientId = StringUtils.hasText(clientProps.getClientId()) ? clientProps.getClientId() : "ateng-mqtt-" + clientName;
        String actualClientId = clientProps.getClientIdStrategy().resolve(baseClientId);
        String[] serverUris = clientProps.resolveServerUris();
        if (serverUris.length == 0) {
            throw new IllegalArgumentException("MQTT serverUri/serverUris must not be blank for client [" + clientName + "]");
        }
        String primaryUri = serverUris[0];

        // 3. 构建持久化介质
        MqttClientPersistence persistence = createPersistence(clientProps, actualClientId);

        // 4. 创建 Paho MqttAsyncClient 并启用受管手动确认 (manualAcks=true)，由底座统一调度确认出口
        MqttAsyncClient asyncClient = new MqttAsyncClient(primaryUri, actualClientId, persistence);
        asyncClient.setManualAcks(true);

        // 5. 构建连接选项 MqttConnectionOptions
        MqttConnectionOptions connOpts = buildConnectionOptions(clientName, actualClientId, clientProps, serverUris);

        // 6. 配置 DisconnectedBufferOptions（受 offlineBufferPolicy 与自动重连约束）
        DisconnectedBufferOptions bufferOpts = new DisconnectedBufferOptions();
        bufferOpts.setBufferEnabled(clientProps.isOfflineBufferingEnabled());
        bufferOpts.setBufferSize(Math.max(1, clientProps.getMaxBufferedMessages()));
        bufferOpts.setDeleteOldestMessages(true);
        bufferOpts.setPersistBuffer(clientProps.getPersistenceType() == PersistenceType.FILE);
        asyncClient.setBufferOpts(bufferOpts);

        MqttClientHandle handle = new MqttClientHandle(clientName, actualClientId, asyncClient, connOpts, clientProps);

        // 安装权威生命周期与消息转译回调
        asyncClient.setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {
                log.debug("[MQTT] Client [{}] disconnected callback received", clientName);
                boolean isUnexpectedDrop = !handle.isClosing() && clientHandles.get(clientName) == handle;
                if (isUnexpectedDrop && clientProps.isAutomaticReconnect() && credentialsProvider != null) {
                    refreshDynamicCredentials(clientName, clientProps, connOpts, true);
                }
                if (disconnectResponse != null && disconnectResponse.getException() != null) {
                    publishConnectionLostEvent(clientName, primaryUri, actualClientId, disconnectResponse.getException());
                } else {
                    publishDisconnectedEvent(clientName, primaryUri, actualClientId, handle);
                }
            }

            @Override
            public void mqttErrorOccurred(MqttException exception) {
                log.warn("[MQTT] Client [{}] error occurred: {}", clientName, exception.getMessage());
                if (!handle.isClosing() && !handle.isConnected() && clientProps.isAutomaticReconnect() && credentialsProvider != null) {
                    refreshDynamicCredentials(clientName, clientProps, connOpts, true);
                }
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                IMqttMessageListener fallback = handle.getFallbackMessageListener();
                if (fallback != null) {
                    fallback.messageArrived(topic, message);
                }
            }

            @Override
            public void deliveryComplete(IMqttToken token) {}

            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                log.info("[MQTT] Client [{}] connectComplete callback received (reconnect={}) to {}", clientName, reconnect, serverURI);
                if (reconnect) {
                    publishConnectedEvent(clientName, serverURI, actualClientId, true);
                }
                handle.notifyConnectComplete(reconnect, serverURI);
            }

            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        });

        // 7. 发起异步连接并等待连接建立（受管连接超时）
        asyncClient.connect(connOpts).waitForCompletion(clientProps.getConnectionTimeout().toMillis());

        return handle;
    }

    private MqttClientPersistence createPersistence(MqttClientProperties clientProps, String actualClientId) {
        if (clientProps.getPersistenceType() == PersistenceType.FILE) {
            String dir = clientProps.getPersistenceDir();
            if (!StringUtils.hasText(dir)) {
                dir = System.getProperty("java.io.tmpdir") + File.separator + "ateng-mqtt-persistence" + File.separator + actualClientId;
            }
            return new MqttDefaultFilePersistence(dir);
        }
        return new MemoryPersistence();
    }

    private MqttConnectionOptions buildConnectionOptions(String clientName,
                                                         String actualClientId,
                                                         MqttClientProperties clientProps,
                                                         String[] serverUris) {
        MqttConnectionOptions connOpts = new MqttConnectionOptions();
        connOpts.setServerURIs(serverUris);
        if (clientProps.getWebSocketHeaders() != null && !clientProps.getWebSocketHeaders().isEmpty()) {
            connOpts.setCustomWebSocketHeaders(new LinkedHashMap<>(clientProps.getWebSocketHeaders()));
        }
        connOpts.setCleanStart(clientProps.isCleanStart());
        if (clientProps.getSessionExpiryInterval() != null) {
            connOpts.setSessionExpiryInterval(clientProps.getSessionExpiryInterval().toSeconds());
        }
        connOpts.setConnectionTimeout((int) clientProps.getConnectionTimeout().toSeconds());
        connOpts.setKeepAliveInterval((int) clientProps.getKeepAliveInterval().toSeconds());
        connOpts.setAutomaticReconnect(clientProps.isAutomaticReconnect());
        if (clientProps.isAutomaticReconnect()) {
            connOpts.setMaxReconnectDelay((int) clientProps.getMaxReconnectDelay().toSeconds());
        }
        connOpts.setReceiveMaximum(clientProps.getMaxInflight());

        // 认证凭据配置：静态凭据打底，动态 SPI 优先覆盖
        if (StringUtils.hasText(clientProps.getUsername())) {
            connOpts.setUserName(clientProps.getUsername());
        }
        if (StringUtils.hasText(clientProps.getPassword())) {
            connOpts.setPassword(clientProps.getPassword().getBytes(StandardCharsets.UTF_8));
        }
        if (credentialsProvider != null) {
            refreshDynamicCredentials(clientName, clientProps, connOpts, false);
        }

        // 协议版本判定（用于约束 MQTT 5.0 扩展属性装配）
        String protocolVersion = StringUtils.hasText(clientProps.getProtocolVersion()) ? clientProps.getProtocolVersion().trim() : "5.0";
        boolean isMqtt311 = "3.1.1".equalsIgnoreCase(protocolVersion) || "3.1".equalsIgnoreCase(protocolVersion);

        // LWT 遗嘱消息配置（优先使用 MqttWillProvider 动态 SPI，无则回退静态配置）
        boolean hasWill = false;
        MqttWill dynamicWill = willProvider != null ? willProvider.getWill(clientName, actualClientId, clientProps) : null;
        if (dynamicWill != null && StringUtils.hasText(dynamicWill.getTopic())) {
            MqttMessage willMsg = new MqttMessage(dynamicWill.getPayload());
            willMsg.setQos(dynamicWill.getQos());
            willMsg.setRetained(dynamicWill.isRetained());
            connOpts.setWill(dynamicWill.getTopic(), willMsg);
            hasWill = true;
            log.info("[MQTT] Configured dynamic LWT will for client [{}] to topic [{}] via MqttWillProvider", clientName, dynamicWill.getTopic());
        } else if (clientProps.getLwt() != null && clientProps.getLwt().isEnabled() && StringUtils.hasText(clientProps.getLwt().getTopic())) {
            MqttMessage willMsg = new MqttMessage(clientProps.getLwt().getPayloadBytes());
            willMsg.setQos(clientProps.getLwt().getQos());
            willMsg.setRetained(clientProps.getLwt().isRetain());
            connOpts.setWill(clientProps.getLwt().getTopic(), willMsg);
            hasWill = true;
        }

        if (hasWill && !isMqtt311 && clientProps.getLwt() != null && clientProps.getLwt().getWillDelayInterval() != null) {
            long delaySeconds = clientProps.getLwt().getWillDelayInterval().toSeconds();
            if (delaySeconds > 0) {
                MqttProperties willProps = new MqttProperties();
                willProps.setWillDelayInterval(delaySeconds);
                connOpts.setWillMessageProperties(willProps);
                log.info("[MQTT] Configured LWT willDelayInterval [{}s] for client [{}]", delaySeconds, clientName);
            }
        }

        // 协议版本适配（MQTT 3.1.1 存量降级模式防御 5.0 特有参数引发 Broker 异常）
        if (isMqtt311) {
            log.info("[MQTT] Client [{}] configured for MQTT 3.1.1 legacy compatibility mode", clientName);
            connOpts.setSessionExpiryInterval(0L);
            connOpts.setTopicAliasMaximum(0);
        } else if (clientProps.getTopicAliasMaximum() > 0) {
            connOpts.setTopicAliasMaximum(clientProps.getTopicAliasMaximum());
            log.info("[MQTT] Configured topicAliasMaximum [{}] for client [{}]", clientProps.getTopicAliasMaximum(), clientName);
        }

        // TLS / SSL 安全套接字配置与主机名校验开关
        if (clientProps.getSsl() != null) {
            connOpts.setHttpsHostnameVerificationEnabled(clientProps.getSsl().isHostnameVerificationEnabled());
            if (clientProps.getSsl().isEnabled()) {
                javax.net.ssl.SSLSocketFactory socketFactory = MqttSslSocketFactoryBuilder.build(clientProps.getSsl());
                if (socketFactory != null) {
                    connOpts.setSocketFactory(socketFactory);
                    log.info("[MQTT] Configured custom SSLSocketFactory for client [{}] with protocol [{}], hostnameVerification={}",
                            clientName, clientProps.getSsl().getProtocol(), clientProps.getSsl().isHostnameVerificationEnabled());
                }
            }
        }

        return connOpts;
    }

    private void refreshDynamicCredentials(String clientName,
                                           MqttClientProperties clientProps,
                                           MqttConnectionOptions connOpts,
                                           boolean reconnect) {
        try {
            MqttCredentials credentials = credentialsProvider.resolveCredentials(clientName, clientProps, reconnect);
            if (credentials == null) {
                return;
            }
            if (credentials.getUsername() != null) {
                connOpts.setUserName(credentials.getUsername());
            } else {
                connOpts.setUserName(StringUtils.hasText(clientProps.getUsername()) ? clientProps.getUsername() : null);
            }
            if (credentials.getPassword() != null) {
                connOpts.setPassword(credentials.getPassword().getBytes(StandardCharsets.UTF_8));
            } else {
                connOpts.setPassword(StringUtils.hasText(clientProps.getPassword())
                        ? clientProps.getPassword().getBytes(StandardCharsets.UTF_8) : null);
            }
            connOpts.setAuthMethod(StringUtils.hasText(credentials.getAuthMethod()) ? credentials.getAuthMethod() : null);
            connOpts.setAuthData(credentials.getAuthData());
            log.debug("[MQTT] Resolved dynamic credentials for client [{}] (reconnect={})", clientName, reconnect);
        } catch (Exception e) {
            if (!reconnect) {
                throw e;
            }
            log.warn("[MQTT] Failed to refresh dynamic credentials during reconnect for client [{}]: {}",
                    clientName, e.getMessage(), e);
        }
    }

    /**
     * 获取默认配置的 MQTT 客户端逻辑名称
     *
     * @return 默认客户端逻辑名称
     */
    public String getDefaultClientName() {
        return properties.getDefaultClient();
    }

    /**
     * 获取默认配置的 MQTT 客户端句柄
     *
     * @return 默认客户端句柄
     */
    public MqttClientHandle getDefaultClientHandle() {
        return getClientHandle(properties.getDefaultClient());
    }

    /**
     * 根据客户端名称获取指定的 MQTT 客户端句柄
     *
     * @param clientName 客户端逻辑名称
     * @return 客户端句柄
     */
    public MqttClientHandle getClientHandle(String clientName) {
        if (!StringUtils.hasText(clientName)) {
            clientName = properties.getDefaultClient();
        }
        MqttClientHandle handle = clientHandles.get(clientName);
        if (handle == null) {
            throw new BusinessException("No MQTT client found with name [" + clientName + "]");
        }
        return handle;
    }

    /**
     * 获取指定名称且处于已连接状态的客户端句柄（断线时立即快速失败）
     *
     * @param clientName 客户端逻辑名称
     * @return 已连接的客户端句柄
     */
    public MqttClientHandle getConnectedClientHandle(String clientName) {
        MqttClientHandle handle = getClientHandle(clientName);
        if (!handle.isConnected()) {
            throw new BusinessException("MQTT client [" + handle.getClientName() + "] is not connected to broker");
        }
        return handle;
    }

    /**
     * 判断是否包含指定名称的客户端
     *
     * @param clientName 客户端逻辑名称
     * @return true 若存在
     */
    public boolean containsClient(String clientName) {
        return clientHandles.containsKey(clientName);
    }

    /**
     * 获取所有受管客户端句柄只读映射
     *
     * @return 客户端句柄映射
     */
    public Map<String, MqttClientHandle> getClientHandles() {
        return Collections.unmodifiableMap(clientHandles);
    }

    @Override
    public void stop() {
        Map<String, MqttClientHandle> toClose = Collections.emptyMap();
        registryLock.lock();
        try {
            if (running.compareAndSet(true, false) || !clientHandles.isEmpty()) {
                log.info("[MQTT] Shutting down NamedMqttClientRegistry, cleanly disconnecting [{}] active clients", clientHandles.size());
                toClose = new LinkedHashMap<>(clientHandles);
                clientHandles.clear();
            }
        } finally {
            registryLock.unlock();
        }
        for (Map.Entry<String, MqttClientHandle> entry : toClose.entrySet()) {
            disconnectAndCloseHandle(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void destroy() {
        stop();
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    public void publishConnectedEvent(String clientName, String serverUri, String clientId, boolean reconnect) {
        MqttClientHandle handle = clientHandles.get(clientName);
        if (handle != null) {
            handle.resetDisconnected();
        }
        if (applicationEventPublisher != null) {
            applicationEventPublisher.publishEvent(new MqttConnectedEvent(this, clientName, serverUri, clientId, reconnect));
        }
    }

    public void publishDisconnectedEvent(String clientName, String serverUri, String clientId) {
        publishDisconnectedEvent(clientName, serverUri, clientId, clientHandles.get(clientName));
    }

    public void publishDisconnectedEvent(String clientName, String serverUri, String clientId, MqttClientHandle targetHandle) {
        if (targetHandle != null && !targetHandle.markDisconnected()) {
            return;
        }
        if (applicationEventPublisher != null) {
            applicationEventPublisher.publishEvent(new MqttDisconnectedEvent(this, clientName, serverUri, clientId));
        }
    }

    public void publishConnectionLostEvent(String clientName, String serverUri, String clientId, Throwable cause) {
        if (applicationEventPublisher != null) {
            applicationEventPublisher.publishEvent(new MqttConnectionLostEvent(this, clientName, serverUri, clientId, cause));
        }
    }
}
