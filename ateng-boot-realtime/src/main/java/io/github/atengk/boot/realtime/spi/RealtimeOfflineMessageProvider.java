package io.github.atengk.boot.realtime.spi;

import io.github.atengk.boot.realtime.model.RealtimeMessage;

import java.util.List;

/**
 * 实时通信离线消息持久化暂存与补发 SPI 扩展点
 * <p>
 * 当向指定用户推送消息且目标用户无活跃物理会话时，若消息标记为需持久化暂存，
 * 则触发本 SPI 扩展点进行消息暂存；下游客户端重新上线或业务按需拉取时，
 * 可通过拉取并清空接口完成离线消息补发闭环。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
public interface RealtimeOfflineMessageProvider {

    /**
     * 暂存目标用户的离线消息
     *
     * @param userId  目标用户唯一标识
     * @param message 实时业务消息
     */
    void saveOfflineMessage(Object userId, RealtimeMessage<?> message);

    /**
     * 一次性拉取并清空目标用户的离线暂存消息
     *
     * @param userId 目标用户唯一标识
     * @return 离线消息列表（保底返回空列表，绝不返回 null）
     */
    List<RealtimeMessage<?>> pullAndClearOfflineMessages(Object userId);

    /**
     * 只读获取目标用户的离线暂存消息（不执行清空，支持 CQS 幂等查询）
     *
     * @param userId 目标用户唯一标识
     * @return 离线消息列表（保底返回空列表，绝不返回 null）
     */
    default List<RealtimeMessage<?>> getOfflineMessages(Object userId) {
        return java.util.Collections.emptyList();
    }

    /**
     * 清空目标用户的离线暂存消息
     *
     * @param userId 目标用户唯一标识
     */
    default void clearOfflineMessages(Object userId) {
    }

    /**
     * 获取指定用户当前暂存的离线消息总数
     *
     * @param userId 目标用户唯一标识
     * @return 暂存消息数量（非负整数）
     */
    int getOfflineMessageCount(Object userId);
}
