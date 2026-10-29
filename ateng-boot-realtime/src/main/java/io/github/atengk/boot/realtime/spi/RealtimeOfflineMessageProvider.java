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
     * 根据起始时间戳（不含）只读获取指定用户的增量离线消息（支持 W3C SSE Last-Event-ID 断点重放与 CQS 幂等读取）
     *
     * @param userId        目标用户唯一标识
     * @param fromTimestamp 起始时间戳（毫秒，仅获取严格大于该时间戳的消息）
     * @return 增量离线消息列表（保底返回空列表，绝不返回 null）
     */
    default List<RealtimeMessage<?>> getOfflineMessagesSince(Object userId, long fromTimestamp) {
        return java.util.Collections.emptyList();
    }

    /**
     * 根据客户端上报的 Last-Event-ID 只读获取指定用户的增量离线消息（支持 W3C SSE 断点重放与 CQS 幂等读取）
     * <p>
     * 优先将 lastEventId 尝试解析为毫秒级纪元时间戳；若非纯数字则遍历检索匹配该 ID 的基准消息并提取其时间戳作为游标。
     * </p>
     *
     * @param userId      目标用户唯一标识
     * @param lastEventId 客户端上报的最后事件标识（支持时间戳或消息 UUID）
     * @return 增量离线消息列表（保底返回空列表，绝不返回 null）
     */
    default List<RealtimeMessage<?>> getOfflineMessagesSince(Object userId, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return getOfflineMessages(userId);
        }
        String trimmedId = lastEventId.trim();
        List<RealtimeMessage<?>> all = getOfflineMessages(userId);
        if (all != null && !all.isEmpty()) {
            for (int i = 0; i < all.size(); i++) {
                RealtimeMessage<?> msg = all.get(i);
                if (trimmedId.equals(msg.getId())) {
                    if (i + 1 < all.size()) {
                        return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(all.subList(i + 1, all.size())));
                    }
                    return java.util.Collections.emptyList();
                }
            }
        }
        try {
            long timestamp = Long.parseLong(trimmedId);
            return getOfflineMessagesSince(userId, timestamp);
        } catch (NumberFormatException ignored) {
            return java.util.Collections.emptyList();
        }
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
