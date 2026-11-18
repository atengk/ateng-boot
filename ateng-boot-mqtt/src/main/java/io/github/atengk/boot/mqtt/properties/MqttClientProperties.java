package io.github.atengk.boot.mqtt.properties;

import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * 多 Broker 集群高可用故障转移地址列表（优先使用该列表，未配置时回退至 serverUri）
     */
    private List<String> serverUris = new ArrayList<>();

    /**
     * WebSocket 握手自定义 HTTP 请求头（适用于 ws:// 或 wss:// 协议携带鉴权或租户头）
     */
    private Map<String, String> webSocketHeaders = new LinkedHashMap<>();

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

    /**
     * MQTT 5.0 主题别名最大数量上限（0 表示禁用，建议蜂窝物联场景按需配置，如 100）
     */
    private int topicAliasMaximum = 0;

    /**
     * 断线离线缓冲策略（默认 BUFFER_WHEN_RECONNECTING，支持 FAIL_FAST）
     */
    private OfflineBufferPolicy offlineBufferPolicy = OfflineBufferPolicy.BUFFER_WHEN_RECONNECTING;

    public String getServerUri() {
        return serverUri;
    }

    public void setServerUri(String serverUri) {
        this.serverUri = serverUri;
    }

    public List<String> getServerUris() {
        return serverUris;
    }

    public void setServerUris(List<String> serverUris) {
        this.serverUris = serverUris != null ? serverUris : new ArrayList<>();
    }

    public Map<String, String> getWebSocketHeaders() {
        return webSocketHeaders;
    }

    public void setWebSocketHeaders(Map<String, String> webSocketHeaders) {
        this.webSocketHeaders = webSocketHeaders != null ? webSocketHeaders : new LinkedHashMap<>();
    }

    /**
     * 解析有效的 Broker 地址有序列表（优先使用 serverUris，为空时回退至 serverUri）
     *
     * @return 非空 Broker 地址数组
     */
    public String[] resolveServerUris() {
        List<String> resolved = new ArrayList<>();
        if (serverUris != null) {
            for (String uri : serverUris) {
                if (StringUtils.hasText(uri)) {
                    String trimmed = uri.trim();
                    if (!resolved.contains(trimmed)) {
                        resolved.add(trimmed);
                    }
                }
            }
        }
        if (resolved.isEmpty() && StringUtils.hasText(serverUri)) {
            resolved.add(serverUri.trim());
        }
        return resolved.toArray(new String[0]);
    }

    /**
     * 解析首要 Broker 连接地址
     *
     * @return 首要连接地址
     */
    public String resolvePrimaryServerUri() {
        String[] uris = resolveServerUris();
        return uris.length > 0 ? uris[0] : serverUri;
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

    public int getTopicAliasMaximum() {
        return topicAliasMaximum;
    }

    public void setTopicAliasMaximum(int topicAliasMaximum) {
        this.topicAliasMaximum = topicAliasMaximum;
    }

    public OfflineBufferPolicy getOfflineBufferPolicy() {
        return offlineBufferPolicy;
    }

    public void setOfflineBufferPolicy(OfflineBufferPolicy offlineBufferPolicy) {
        this.offlineBufferPolicy = offlineBufferPolicy != null ? offlineBufferPolicy : OfflineBufferPolicy.BUFFER_WHEN_RECONNECTING;
    }

    /**
     * 判断当前客户端是否满足断线离线缓冲前提（策略为 BUFFER_WHEN_RECONNECTING、开启自动重连且缓冲容量大于 0）
     *
     * @return true 若允许离线缓冲
     */
    public boolean isOfflineBufferingEnabled() {
        return offlineBufferPolicy == OfflineBufferPolicy.BUFFER_WHEN_RECONNECTING
                && automaticReconnect
                && maxBufferedMessages > 0;
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
