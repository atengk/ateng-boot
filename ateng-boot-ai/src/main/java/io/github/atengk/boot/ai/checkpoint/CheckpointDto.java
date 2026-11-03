package io.github.atengk.boot.ai.checkpoint;

import org.bsc.langgraph4j.checkpoint.Checkpoint;

import java.io.Serializable;
import java.util.Map;

/**
 * 认知图检查点序列化传输对象
 * <p>
 * 专用于纯净 JSON 格式在 Redis 或存储介质中落盘，彻底取缔 Java 原生反序列化与 {@code @class} 污染。
 * </p>
 *
 * @param id 检查点唯一标识
 * @param nodeId 当前已执行节点标识
 * @param nextNodeId 下一待执行节点标识（中断或分支）
 * @param state 纯净状态字典数据
 * @param timestamp 快照时间戳（毫秒）
 * @author Ateng
 * @since 2026-11-03
 */
public record CheckpointDto(
        String id,
        String nodeId,
        String nextNodeId,
        Map<String, Object> state,
        long timestamp
) implements Serializable {

    /**
     * 从 LangGraph4j Checkpoint 模型转换为 DTO
     *
     * @param checkpoint 原始检查点
     * @return 序列化 DTO
     */
    public static CheckpointDto from(Checkpoint checkpoint) {
        if (checkpoint == null) {
            return null;
        }
        return new CheckpointDto(
                checkpoint.getId(),
                checkpoint.getNodeId() != null ? checkpoint.getNodeId() : "",
                checkpoint.getNextNodeId() != null ? checkpoint.getNextNodeId() : "",
                checkpoint.getState(),
                System.currentTimeMillis()
        );
    }

    /**
     * 将 DTO 还原为 LangGraph4j Checkpoint 原生模型
     *
     * @return 原生 Checkpoint
     */
    public Checkpoint toCheckpoint() {
        return Checkpoint.builder()
                .id(id != null ? id : "")
                .nodeId(nodeId != null ? nodeId : "")
                .nextNodeId(nextNodeId != null ? nextNodeId : "")
                .state(state != null ? state : Map.of())
                .build();
    }
}
