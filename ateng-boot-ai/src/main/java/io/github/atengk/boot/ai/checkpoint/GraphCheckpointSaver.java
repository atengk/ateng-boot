package io.github.atengk.boot.ai.checkpoint;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;

import java.util.Optional;

/**
 * 认知图检查点持久化存储适配器顶层接口
 * <p>
 * 继承 LangGraph4j {@link BaseCheckpointSaver}，补充完备对称生命周期治理接口（探查、按线程查询最新快照、安全清空），
 * 支撑单机测试与分布式生产集群的统一抽象。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
public interface GraphCheckpointSaver extends BaseCheckpointSaver {

    /**
     * 查询指定线程会话的最新检查点快照
     *
     * @param threadId 线程会话标识
     * @return 最新检查点快照
     */
    default Optional<Checkpoint> getLatest(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        return get(RunnableConfig.builder().threadId(threadId).build());
    }

    /**
     * 探查指定线程会话是否存在检查点
     *
     * @param threadId 线程会话标识
     * @return 是否存在
     */
    boolean exists(String threadId);

    /**
     * 物理清理指定线程会话的全量检查点记录
     *
     * @param threadId 线程会话标识
     */
    void clear(String threadId);
}
