package io.github.atengk.boot.sample.mqtt.service;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mqtt.properties.AtengMqttProperties;
import io.github.atengk.boot.mqtt.properties.ClientIdStrategy;
import io.github.atengk.boot.mqtt.properties.MqttClientProperties;
import io.github.atengk.boot.mqtt.registry.MqttClientHandle;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import io.github.atengk.boot.mqtt.template.MqttTemplate;
import io.github.atengk.boot.sample.mqtt.dto.MqttClientSummaryVO;
import io.github.atengk.boot.sample.mqtt.dto.RegisterClientRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 场景业务服务 8：多命名客户端动态生命周期治理与定向绑定视图发信
 *
 * <p>演示特性：
 * <ul>
 *   <li>运行期动态注册命名客户端（{@code NamedMqttClientRegistry.registerClient}）</li>
 *   <li>使用 {@code mqttTemplate.use(clientName)} 客户端绑定视图执行定向订阅与发布</li>
 *   <li>对外转换脱敏健康快照（{@code MqttClientSummaryVO}），严禁泄漏底层句柄</li>
 *   <li>注销前对称取消动态订阅并清理 Micrometer Gauge 度量</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Service
public class ClientGovernanceScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ClientGovernanceScenarioService.class);

    private final NamedMqttClientRegistry clientRegistry;
    private final MqttTemplate mqttTemplate;
    private final AtengMqttProperties mqttProperties;

    private final Map<String, String> dynamicSubscribedFilterByClient = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<String>> consumedByClient = new ConcurrentHashMap<>();

    public ClientGovernanceScenarioService(NamedMqttClientRegistry clientRegistry,
                                           MqttTemplate mqttTemplate,
                                           AtengMqttProperties mqttProperties) {
        this.clientRegistry = clientRegistry;
        this.mqttTemplate = mqttTemplate;
        this.mqttProperties = mqttProperties;
    }

    /**
     * 运行期动态注册命名客户端并绑定专属演示监听通道
     *
     * @param request 注册请求参数
     * @return 注册完成后的脱敏客户端快照
     */
    public MqttClientSummaryVO registerClient(RegisterClientRequest request) {
        if (request == null || !StringUtils.hasText(request.getClientName())) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "客户端逻辑名称 clientName 不能为空");
        }
        String clientName = request.getClientName().trim();

        MqttClientProperties baseProps = resolveDefaultClientProperties();
        MqttClientProperties targetProps = new MqttClientProperties();
        String serverUri = StringUtils.hasText(request.getServerUri())
                ? request.getServerUri().trim()
                : (baseProps != null ? baseProps.resolvePrimaryServerUri() : null);
        if (!StringUtils.hasText(serverUri)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "Broker serverUri 未配置且无法从主客户端继承");
        }
        targetProps.setServerUri(serverUri);
        targetProps.setClientId(StringUtils.hasText(request.getClientIdPrefix())
                ? request.getClientIdPrefix().trim()
                : "ateng-dynamic-" + clientName);
        targetProps.setClientIdStrategy(ClientIdStrategy.RANDOM_SUFFIX);
        targetProps.setCleanStart(!Boolean.FALSE.equals(request.getCleanStart()));
        int timeoutSeconds = request.getConnectionTimeoutSeconds() != null ? request.getConnectionTimeoutSeconds() : 5;
        targetProps.setConnectionTimeout(Duration.ofSeconds(timeoutSeconds));
        targetProps.setAutomaticReconnect(true);

        if (baseProps != null) {
            targetProps.setUsername(baseProps.getUsername());
            targetProps.setPassword(baseProps.getPassword());
            targetProps.setSsl(baseProps.getSsl());
            targetProps.setProtocolVersion(baseProps.getProtocolVersion());
            targetProps.setMaxPayloadSize(baseProps.getMaxPayloadSize());
            targetProps.setPublishTimeout(baseProps.getPublishTimeout());
        }

        MqttClientHandle handle = clientRegistry.registerClient(clientName, targetProps);

        // 通过 clients bound view 为该动态客户端建立演示订阅通道，验证定向收发闭环；若订阅失败则确定性回滚句柄防连接泄漏
        String filter = "iot/clients/" + clientName + "/+";
        try {
            mqttTemplate.use(clientName).subscribe(filter, 1, (topic, payloadBytes) -> {
                String payload = payloadBytes != null
                        ? new String(payloadBytes, StandardCharsets.UTF_8)
                        : "";
                consumedByClient.computeIfAbsent(clientName, k -> new CopyOnWriteArrayList<>()).add(payload);
                log.info("[SCENARIO-CLIENT-GOV] Dynamic client [{}] consumed message on [{}]: {}", clientName, topic, payload);
            });
            dynamicSubscribedFilterByClient.put(clientName, filter);
        } catch (RuntimeException ex) {
            dynamicSubscribedFilterByClient.remove(clientName);
            consumedByClient.remove(clientName);
            try {
                clientRegistry.unregisterClient(clientName, true);
            } catch (Exception cleanupEx) {
                ex.addSuppressed(cleanupEx);
            }
            throw ex;
        }

        return toSummaryVO(clientName, handle);
    }

    /**
     * 通过 MqttTemplate.use(clientName) 定向向命名客户端通道发布报文
     *
     * @param clientName 命名客户端名称
     * @param topic      目标主题
     * @param payload    载荷内容
     * @param qos        服务质量等级 (0, 1, 2)
     * @param retained   是否保留
     */
    public void publishToClient(String clientName, String topic, String payload, int qos, boolean retained) {
        if (!StringUtils.hasText(clientName)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "客户端逻辑名称 clientName 不能为空");
        }
        if (!StringUtils.hasText(topic)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "目标主题 topic 不能为空");
        }
        if (payload == null) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "报文载荷 payload 不能为空");
        }
        if (qos < 0 || qos > 2) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "QoS 等级超出允许范围 [0, 2]: " + qos);
        }
        String normalizedClient = clientName.trim();
        if (!clientRegistry.containsClient(normalizedClient)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "命名客户端 [" + normalizedClient + "] 未注册");
        }
        log.info("[SCENARIO-CLIENT-GOV] Publishing via mqttTemplate.use({}) to topic [{}], qos={}",
                normalizedClient, topic.trim(), qos);
        mqttTemplate.use(normalizedClient).publish(topic.trim(), payload, qos, retained);
    }

    /**
     * 查询当前所有受管命名客户端的脱敏状态快照
     *
     * @return 客户端快照列表
     */
    public List<MqttClientSummaryVO> listClientSummaries() {
        Map<String, MqttClientHandle> handles = clientRegistry.getClientHandles();
        if (handles.isEmpty()) {
            return Collections.emptyList();
        }
        List<MqttClientSummaryVO> result = new ArrayList<>(handles.size());
        for (Map.Entry<String, MqttClientHandle> entry : handles.entrySet()) {
            result.add(toSummaryVO(entry.getKey(), entry.getValue()));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 对称注销指定动态客户端（先清理动态订阅，再执行底层注册中心注销）
     *
     * @param clientName 命名客户端名称
     * @param force      是否强制注销（跳过活跃检查）
     */
    public void unregisterClient(String clientName, boolean force) {
        if (!StringUtils.hasText(clientName)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "客户端逻辑名称 clientName 不能为空");
        }
        String normalizedClient = clientName.trim();
        if (normalizedClient.equals(clientRegistry.getDefaultClientName())) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "禁止运行期注销默认主客户端: " + normalizedClient);
        }
        String subscribedFilter = dynamicSubscribedFilterByClient.remove(normalizedClient);
        if (!force && StringUtils.hasText(subscribedFilter) && clientRegistry.containsClient(normalizedClient)) {
            try {
                mqttTemplate.use(normalizedClient).unsubscribe(subscribedFilter);
            } catch (Exception ex) {
                log.warn("[SCENARIO-CLIENT-GOV] Error unsubscribing dynamic filter [{}] on client [{}]: {}",
                        subscribedFilter, normalizedClient, ex.getMessage());
            }
        }
        clientRegistry.unregisterClient(normalizedClient, force);
        consumedByClient.remove(normalizedClient);
        log.info("[SCENARIO-CLIENT-GOV] Unregistered dynamic client [{}] cleanly (force={})", normalizedClient, force);
    }

    /**
     * 获取指定动态客户端通过绑定视图消费到的历史报文列表
     *
     * @param clientName 客户端名称
     * @return 消费载荷列表
     */
    public List<String> getConsumedMessages(String clientName) {
        if (!StringUtils.hasText(clientName)) {
            return Collections.emptyList();
        }
        CopyOnWriteArrayList<String> list = consumedByClient.get(clientName.trim());
        return list != null ? Collections.unmodifiableList(new ArrayList<>(list)) : Collections.emptyList();
    }

    /**
     * 清理指定动态客户端的测试记录缓存
     *
     * @param clientName 客户端名称
     */
    public void clear(String clientName) {
        if (StringUtils.hasText(clientName)) {
            consumedByClient.remove(clientName.trim());
        }
    }

    private MqttClientProperties resolveDefaultClientProperties() {
        if (mqttProperties == null || mqttProperties.getClients() == null) {
            return null;
        }
        return mqttProperties.getClients().get(mqttProperties.getDefaultClient());
    }

    private MqttClientSummaryVO toSummaryVO(String clientName, MqttClientHandle handle) {
        MqttClientProperties props = handle.getClientProperties();
        Map<String, Object> healthDetails = handle.getHealthDetails();
        return MqttClientSummaryVO.builder()
                .clientName(clientName)
                .clientId(handle.getActualClientId())
                .serverUri(props != null ? props.resolvePrimaryServerUri() : null)
                .connected(handle.isConnected())
                .protocolVersion(props != null ? props.getProtocolVersion() : null)
                .healthDetails(healthDetails != null ? Collections.unmodifiableMap(healthDetails) : Collections.emptyMap())
                .build();
    }
}
