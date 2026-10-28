package io.github.atengk.boot.mqtt.properties;

import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * 单个 MQTT 客户端连接配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class MqttClientProperties {

    /**
     * Broker 连接地址（支持 tcp://, ssl://, ws://, wss://）
     */
    private String serverUri = "tcp://127.0.0.1:1883";

    /**
     * 客户端逻辑标识前缀
     */
    private String clientId = "ateng-mqtt-client";

    /**
     * 客户端防互踢标识生成策略 (STATIC | RANDOM_SUFFIX | IP_PID)
     */
    private ClientIdStrategy clientIdStrategy = ClientIdStrategy.RANDOM_SUFFIX;

    /**
     * 认证用户名
     */
    private String username;

    /**
     * 认证密码
     */
    private String password;

    /**
     * 协议版本 (默认 5.0，支持 5.0 与 3.1.1)
     */
    private String protocolVersion = "5.0";

    /**
     * 是否清除会话 (Clean Start)
     */
    private boolean cleanStart = true;

    /**
     * 会话过期时间间隔 (Duration，默认 0 表示连接断开即销毁)
     */
    private Duration sessionExpiryInterval = Duration.ZERO;

    /**
     * 连接建立超时阈值
     */
    private Duration connectionTimeout = Duration.ofSeconds(10);

    /**
     * 同步消息发布超时阈值
     */
    private Duration publishTimeout = Duration.ofSeconds(10);

    /**
     * 心跳保活间隔
     */
    private Duration keepAliveInterval = Duration.ofSeconds(60);

    /**
     * 自动断线重连最大退避等待时间
     */
    private Duration maxReconnectDelay = Duration.ofSeconds(128);

    /**
     * 是否开启断线自动重连
     */
    private boolean automaticReconnect = true;

    /**
     * 生产级最大未完成在途报文数窗口（默认 1000，彻底消除 Paho 默认 10 引发的 32202 吞吐雪崩）
     */
    private int maxInflight = 1000;

    /**
     * 断线期间内存最大缓冲离线消息条数
     */
    private int maxBufferedMessages = 5000;

    /**
     * 上下行最大报文尺寸（防 JVM 堆内存 OOM 守卫）
     */
    private DataSize maxPayloadSize = DataSize.ofMegabytes(1);

    /**
     * 持久化类型 (MEMORY / FILE)
     */
    private PersistenceType persistenceType = PersistenceType.MEMORY;

    /**
     * 文件持久化目录（当 persistenceType 为 FILE 时生效）
     */
    private String persistenceDir;

    /**
     * 优雅停机排空最大超时阈值
     */
    private Duration shutdownTimeout = Duration.ofSeconds(10);

    /**
     * 请求-响应模式专用的聚合响应主题前缀（默认 "sys/response/"，实际订阅为 prefix + actualClientId）
     */
    private String responseTopicPrefix = "sys/response/";

    /**
     * 遗嘱消息配置
     */
    @NestedConfigurationProperty
    private MqttLwtProperties lwt = new MqttLwtProperties();

    /**
     * TLS/SSL 加密配置
     */
    @NestedConfigurationProperty
    private MqttSslProperties ssl = new MqttSslProperties();

    public String getServerUri() {
        return serverUri;
    }

    public void setServerUri(String serverUri) {
        this.serverUri = serverUri;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public ClientIdStrategy getClientIdStrategy() {
        return clientIdStrategy;
    }

    public void setClientIdStrategy(ClientIdStrategy clientIdStrategy) {
        this.clientIdStrategy = clientIdStrategy;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public boolean isCleanStart() {
        return cleanStart;
    }

    public void setCleanStart(boolean cleanStart) {
        this.cleanStart = cleanStart;
    }

    public Duration getSessionExpiryInterval() {
        return sessionExpiryInterval;
    }

    public void setSessionExpiryInterval(Duration sessionExpiryInterval) {
        this.sessionExpiryInterval = sessionExpiryInterval;
    }

    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public Duration getPublishTimeout() {
        return publishTimeout;
    }

    public void setPublishTimeout(Duration publishTimeout) {
        this.publishTimeout = publishTimeout;
    }

    public Duration getKeepAliveInterval() {
        return keepAliveInterval;
    }

    public void setKeepAliveInterval(Duration keepAliveInterval) {
        this.keepAliveInterval = keepAliveInterval;
    }

    public Duration getMaxReconnectDelay() {
        return maxReconnectDelay;
    }

    public void setMaxReconnectDelay(Duration maxReconnectDelay) {
        this.maxReconnectDelay = maxReconnectDelay;
    }

    public boolean isAutomaticReconnect() {
        return automaticReconnect;
    }

    public void setAutomaticReconnect(boolean automaticReconnect) {
        this.automaticReconnect = automaticReconnect;
    }

    public int getMaxInflight() {
        return maxInflight;
    }

    public void setMaxInflight(int maxInflight) {
        this.maxInflight = maxInflight;
    }

    public int getMaxBufferedMessages() {
        return maxBufferedMessages;
    }

    public void setMaxBufferedMessages(int maxBufferedMessages) {
        this.maxBufferedMessages = maxBufferedMessages;
    }

    public DataSize getMaxPayloadSize() {
        return maxPayloadSize;
    }

    public void setMaxPayloadSize(DataSize maxPayloadSize) {
        this.maxPayloadSize = maxPayloadSize;
    }

    public PersistenceType getPersistenceType() {
        return persistenceType;
    }

    public void setPersistenceType(PersistenceType persistenceType) {
        this.persistenceType = persistenceType;
    }

    public String getPersistenceDir() {
        return persistenceDir;
    }

    public void setPersistenceDir(String persistenceDir) {
        this.persistenceDir = persistenceDir;
    }

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    public void setShutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
    }

    public MqttLwtProperties getLwt() {
        return lwt;
    }

    public void setLwt(MqttLwtProperties lwt) {
        this.lwt = lwt;
    }

    public MqttSslProperties getSsl() {
        return ssl;
    }

    public void setSsl(MqttSslProperties ssl) {
        this.ssl = ssl;
    }

    public String getResponseTopicPrefix() {
        return responseTopicPrefix;
    }

    public void setResponseTopicPrefix(String responseTopicPrefix) {
        this.responseTopicPrefix = responseTopicPrefix;
    }

    /**
     * 解析当前客户端实际生效的私有聚合响应主题
     *
     * @param actualClientId 实际客户端唯一标识
     * @return 聚合响应主题
     */
    public String resolveResponseTopic(String actualClientId) {
        if (org.springframework.util.StringUtils.hasText(responseTopicPrefix)) {
            if (responseTopicPrefix.contains("{clientId}")) {
                return responseTopicPrefix.replace("{clientId}", actualClientId);
            }
            return responseTopicPrefix.endsWith("/") ? responseTopicPrefix + actualClientId : responseTopicPrefix + "/" + actualClientId;
        }
        return "sys/response/" + actualClientId;
    }
}
