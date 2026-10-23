package io.github.atengk.boot.mqtt.registry;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mqtt.properties.AtengMqttProperties;
import io.github.atengk.boot.mqtt.properties.ClientIdStrategy;
import io.github.atengk.boot.mqtt.properties.MqttClientProperties;
import io.github.atengk.boot.mqtt.properties.PersistenceType;
import org.eclipse.paho.mqttv5.client.DisconnectedBufferOptions;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttClientPersistence;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.client.persist.MqttDefaultFilePersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MQTT 命名客户端注册中心与生命周期管理器
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class NamedMqttClientRegistry implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NamedMqttClientRegistry.class);

    private final AtengMqttProperties properties;
    private final Map<String, MqttClientHandle> clientHandles = new ConcurrentHashMap<>();

    public NamedMqttClientRegistry(AtengMqttProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        initClients();
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
                log.info("[MQTT] Successfully initialized and connected client [{}] with actual clientId [{}] to [{}]",
                        clientName, handle.getActualClientId(), clientProps.getServerUri());
            } catch (Exception e) {
                log.error("[MQTT] Failed to initialize MQTT client [{}]: {}", clientName, e.getMessage(), e);
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
        MqttConnectionOptions connOpts = buildConnectionOptions(clientProps);

        // 6. 配置 DisconnectedBufferOptions
        DisconnectedBufferOptions bufferOpts = new DisconnectedBufferOptions();
        bufferOpts.setBufferEnabled(true);
        bufferOpts.setBufferSize(clientProps.getMaxBufferedMessages());
        bufferOpts.setDeleteOldestMessages(true);
        bufferOpts.setPersistBuffer(clientProps.getPersistenceType() == PersistenceType.FILE);
        asyncClient.setBufferOpts(bufferOpts);

        // 7. 发起异步连接并等待连接建立（受管连接超时）
        asyncClient.connect(connOpts).waitForCompletion(clientProps.getConnectionTimeout().toMillis());

        return new MqttClientHandle(clientName, actualClientId, asyncClient, connOpts, clientProps);
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

    private MqttConnectionOptions buildConnectionOptions(MqttClientProperties clientProps) {
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

        // LWT 遗嘱消息配置
        if (clientProps.getLwt() != null && clientProps.getLwt().isEnabled() && StringUtils.hasText(clientProps.getLwt().getTopic())) {
            MqttMessage willMsg = new MqttMessage(clientProps.getLwt().getPayloadBytes());
            willMsg.setQos(clientProps.getLwt().getQos());
            willMsg.setRetained(clientProps.getLwt().isRetain());
            connOpts.setWill(clientProps.getLwt().getTopic(), willMsg);
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
    public void destroy() {
        log.info("[MQTT] Shutting down NamedMqttClientRegistry, closing [{}] active clients", clientHandles.size());
        for (Map.Entry<String, MqttClientHandle> entry : clientHandles.entrySet()) {
            String name = entry.getKey();
            MqttClientHandle handle = entry.getValue();
            try {
                if (handle.getAsyncClient().isConnected()) {
                    handle.getAsyncClient().disconnect().waitForCompletion(handle.getClientProperties().getShutdownTimeout().toMillis());
                }
                handle.getAsyncClient().close();
                log.info("[MQTT] Client [{}] disconnected and closed cleanly", name);
            } catch (Exception e) {
                log.warn("[MQTT] Error closing MQTT client [{}]: {}", name, e.getMessage());
            }
        }
        clientHandles.clear();
    }
}
