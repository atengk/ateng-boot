package io.github.atengk.boot.satoken.cluster.model;

import io.github.atengk.boot.satoken.cluster.enums.SaTokenClusterEventType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sa-Token 分布式集群即时同步广播强类型消息载荷
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaTokenClusterMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 消息发送方节点实例唯一标识（防自消费风暴与自重入）
     */
    private String instanceId;

    /**
     * 广播事件类型
     */
    private SaTokenClusterEventType eventType;

    /**
     * 目标业务键（如用户 ID、角色编码等）
     */
    private String targetKey;

    /**
     * 消息产生的时间戳（毫秒）
     */
    private Long timestamp;

    /**
     * 扩展字典参数
     */
    @Builder.Default
    private Map<String, Object> extra = new LinkedHashMap<>();

    /**
     * 快速构建集群广播消息
     *
     * @param instanceId 节点实例标识
     * @param eventType  事件类型
     * @param targetKey  目标业务键
     * @return 集群消息实例
     */
    public static SaTokenClusterMessage of(String instanceId, SaTokenClusterEventType eventType, String targetKey) {
        return SaTokenClusterMessage.builder()
                .instanceId(instanceId)
                .eventType(eventType)
                .targetKey(targetKey)
                .timestamp(System.currentTimeMillis())
                .extra(new LinkedHashMap<>())
                .build();
    }
}
