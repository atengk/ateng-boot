package io.github.atengk.boot.mqtt.registry;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mqtt.event.MqttConnectedEvent;
import io.github.atengk.boot.mqtt.event.MqttConnectionLostEvent;
import io.github.atengk.boot.mqtt.event.MqttDisconnectedEvent;
import io.github.atengk.boot.mqtt.properties.AtengMqttProperties;
import io.github.atengk.boot.mqtt.properties.ClientIdStrategy;
import io.github.atengk.boot.mqtt.properties.MqttClientProperties;
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
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final Map<String, MqttClientHandle> clientHandles = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ApplicationEventPublisher applicationEventPublisher;

    public NamedMqttClientRegistry(AtengMqttProperties properties) {
        this(properties, null);
    }

    public NamedMqttClientRegistry(AtengMqttProperties properties, MqttWillProvider willProvider) {
        this.properties = properties;
        this.willProvider = willProvider;
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
    public synchronized void initClients() {
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
            MqttClientProperties clientProps = entry.getValue();
            try {
                MqttClientHandle handle = createAndConnectClient(clientName, clientProps);
                clientHandles.put(clientName, handle);
                publishConnectedEvent(clientName, clientProps.getServerUri(), handle.getActualClientId(), false);
                log.info("[MQTT] Successfully initialized and connected client [{}] with actual clientId [{}] to [{}]",
                        clientName, handle.getActualClientId(), clientProps.getServerUri());
            } catch (Exception e) {
                throw new BusinessException("Failed to initialize MQTT client [" + clientName + "]: " + e.getMessage(), e);
            }
        }
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

        // 2. 解析实际 ClientId
        String baseClientId = StringUtils.hasText(clientProps.getClientId()) ? clientProps.getClientId() : "ateng-mqtt-" + clientName;
        String actualClientId = clientProps.getClientIdStrategy().resolve(baseClientId);

        // 3. 构建持久化介质
        MqttClientPersistence persistence = createPersistence(clientProps, actualClientId);

        // 4. 创建 Paho MqttAsyncClient
        MqttAsyncClient asyncClient = new MqttAsyncClient(clientProps.getServerUri(), actualClientId, persistence);

        // 5. 构建连接选项 MqttConnectionOptions
        MqttConnectionOptions connOpts = buildConnectionOptions(clientName, actualClientId, clientProps);

        // 6. 配置 DisconnectedBufferOptions
        DisconnectedBufferOptions bufferOpts = new DisconnectedBufferOptions();
        bufferOpts.setBufferEnabled(true);
        bufferOpts.setBufferSize(clientProps.getMaxBufferedMessages());
        bufferOpts.setDeleteOldestMessages(true);
        bufferOpts.setPersistBuffer(clientProps.getPersistenceType() == PersistenceType.FILE);
        asyncClient.setBufferOpts(bufferOpts);

        MqttClientHandle handle = new MqttClientHandle(clientName, actualClientId, asyncClient, connOpts, clientProps);

        // 安装权威生命周期与消息转译回调
        asyncClient.setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {
                log.debug("[MQTT] Client [{}] disconnected callback received", clientName);
                if (disconnectResponse != null && disconnectResponse.getException() != null) {
                    publishConnectionLostEvent(clientName, clientProps.getServerUri(), actualClientId, disconnectResponse.getException());
                } else {
                    publishDisconnectedEvent(clientName, clientProps.getServerUri(), actualClientId);
                }
            }

            @Override
            public void mqttErrorOccurred(MqttException exception) {
                log.warn("[MQTT] Client [{}] error occurred: {}", clientName, exception.getMessage());
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

    private MqttConnectionOptions buildConnectionOptions(String clientName, String actualClientId, MqttClientProperties clientProps) {
        MqttConnectionOptions connOpts = new MqttConnectionOptions();
        connOpts.setServerURIs(new String[]{clientProps.getServerUri()});
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

        if (StringUtils.hasText(clientProps.getUsername())) {
            connOpts.setUserName(clientProps.getUsername());
        }
        if (StringUtils.hasText(clientProps.getPassword())) {
            connOpts.setPassword(clientProps.getPassword().getBytes(StandardCharsets.UTF_8));
        }

        // LWT 遗嘱消息配置（优先使用 MqttWillProvider 动态 SPI，无则回退静态配置）
        MqttWill dynamicWill = willProvider != null ? willProvider.getWill(clientName, actualClientId, clientProps) : null;
        if (dynamicWill != null && StringUtils.hasText(dynamicWill.getTopic())) {
            MqttMessage willMsg = new MqttMessage(dynamicWill.getPayload());
            willMsg.setQos(dynamicWill.getQos());
            willMsg.setRetained(dynamicWill.isRetained());
            connOpts.setWill(dynamicWill.getTopic(), willMsg);
            log.info("[MQTT] Configured dynamic LWT will for client [{}] to topic [{}] via MqttWillProvider", clientName, dynamicWill.getTopic());
        } else if (clientProps.getLwt() != null && clientProps.getLwt().isEnabled() && StringUtils.hasText(clientProps.getLwt().getTopic())) {
            MqttMessage willMsg = new MqttMessage(clientProps.getLwt().getPayloadBytes());
            willMsg.setQos(clientProps.getLwt().getQos());
            willMsg.setRetained(clientProps.getLwt().isRetain());
            connOpts.setWill(clientProps.getLwt().getTopic(), willMsg);
        }

        // 协议版本适配（MQTT 3.1.1 存量降级模式防御 5.0 特有参数引发 Broker 异常）
        String protocolVersion = StringUtils.hasText(clientProps.getProtocolVersion()) ? clientProps.getProtocolVersion().trim() : "5.0";
        if ("3.1.1".equalsIgnoreCase(protocolVersion) || "3.1".equalsIgnoreCase(protocolVersion)) {
            log.info("[MQTT] Client [{}] configured for MQTT 3.1.1 legacy compatibility mode", clientName);
            connOpts.setSessionExpiryInterval(0L);
            connOpts.setTopicAliasMaximum(0);
        } else if (clientProps.getTopicAliasMaximum() > 0) {
            connOpts.setTopicAliasMaximum(clientProps.getTopicAliasMaximum());
            log.info("[MQTT] Configured topicAliasMaximum [{}] for client [{}]", clientProps.getTopicAliasMaximum(), clientName);
        }

        // TLS / SSL 安全套接字配置
        if (clientProps.getSsl() != null && clientProps.getSsl().isEnabled()) {
            javax.net.ssl.SSLSocketFactory socketFactory = MqttSslSocketFactoryBuilder.build(clientProps.getSsl());
            if (socketFactory != null) {
                connOpts.setSocketFactory(socketFactory);
                log.info("[MQTT] Configured custom SSLSocketFactory for client [{}] with protocol [{}]",
                        clientName, clientProps.getSsl().getProtocol());
            }
        }

        return connOpts;
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
        if (running.compareAndSet(true, false) || !clientHandles.isEmpty()) {
            log.info("[MQTT] Shutting down NamedMqttClientRegistry, cleanly disconnecting [{}] active clients", clientHandles.size());
            for (Map.Entry<String, MqttClientHandle> entry : clientHandles.entrySet()) {
                String name = entry.getKey();
                MqttClientHandle handle = entry.getValue();
                try {
                    if (handle.getAsyncClient().isConnected()) {
                        long shutdownMs = handle.getClientProperties().getShutdownTimeout().toMillis();
                        // 显式发送 MQTT 规范 DISCONNECT 报文，告知 Broker 正常下线并依法销毁 LWT 遗嘱消息（防误报）
                        handle.getAsyncClient().disconnect().waitForCompletion(shutdownMs > 0 ? shutdownMs : 5000L);
                        publishDisconnectedEvent(name, handle.getClientProperties().getServerUri(), handle.getActualClientId());
                    }
                    handle.getAsyncClient().close();
                    log.info("[MQTT] Client [{}] disconnected and closed cleanly with DISCONNECT", name);
                } catch (Exception e) {
                    log.warn("[MQTT] Error closing MQTT client [{}]: {}", name, e.getMessage());
                }
            }
            clientHandles.clear();
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
        MqttClientHandle handle = clientHandles.get(clientName);
        if (handle != null && !handle.markDisconnected()) {
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
