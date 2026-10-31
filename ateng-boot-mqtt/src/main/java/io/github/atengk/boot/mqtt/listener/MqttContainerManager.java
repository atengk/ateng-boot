package io.github.atengk.boot.mqtt.listener;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MQTT 声明式监听器容器动态运维门面
 * 支持在应用运行期对指定监听端点实施动态流控（暂停/恢复）、查询各客户端活跃订阅主题及全量端点运行状态巡检
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class MqttContainerManager {

    private final MqttListenerEndpointRegistry registry;

    public MqttContainerManager(MqttListenerEndpointRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "MqttListenerEndpointRegistry must not be null");
    }

    /**
     * 暂停指定端点的消息消费调度
     *
     * @param endpointId 端点唯一标识 (如 "telemetryListener#onMessage")
     */
    public void pause(String endpointId) {
        registry.pause(endpointId);
    }

    /**
     * 恢复指定端点的消息消费调度
     *
     * @param endpointId 端点唯一标识
     */
    public void resume(String endpointId) {
        registry.resume(endpointId);
    }

    /**
     * 判断指定端点是否已被暂停
     *
     * @param endpointId 端点唯一标识
     * @return true 若处于暂停状态
     */
    public boolean isPaused(String endpointId) {
        return registry.isPaused(endpointId);
    }

    /**
     * 全局暂停所有已注册端点的消费调度
     */
    public void pauseAll() {
        registry.pauseAll();
    }

    /**
     * 全局恢复所有已注册端点的消费调度
     */
    public void resumeAll() {
        registry.resumeAll();
    }

    /**
     * 查询指定客户端当前活跃订阅的主题列表
     *
     * @param clientName 客户端逻辑名称
     * @return 活跃订阅主题列表
     */
    public List<String> getSubscribedTopics(String clientName) {
        return registry.getSubscribedTopics(clientName);
    }

    /**
     * 查询所有客户端当前活跃订阅的主题拓扑映射
     *
     * @return Map<客户端逻辑名称, 订阅主题列表>
     */
    public Map<String, List<String>> getAllSubscribedTopics() {
        return registry.getAllSubscribedTopics();
    }

    /**
     * 获取所有已注册端点的运行状态快照列表
     *
     * @return 端点状态快照列表
     */
    public List<MqttEndpointStatus> getEndpointStatuses() {
        return registry.getEndpointStatuses();
    }

    /**
     * 获取指定端点的运行状态快照
     *
     * @param endpointId 端点唯一标识
     * @return 端点状态快照，若不存在则返回 null
     */
    public MqttEndpointStatus getEndpointStatus(String endpointId) {
        return registry.getEndpointStatus(endpointId);
    }

    /**
     * 判断是否存在指定标识的端点
     *
     * @param endpointId 端点唯一标识
     * @return true 若端点已注册
     */
    public boolean containsEndpoint(String endpointId) {
        return registry.containsEndpoint(endpointId);
    }
}
