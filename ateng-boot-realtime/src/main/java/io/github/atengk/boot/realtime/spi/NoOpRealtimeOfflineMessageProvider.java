package io.github.atengk.boot.realtime.spi;

import io.github.atengk.boot.realtime.model.RealtimeMessage;

import java.util.Collections;
import java.util.List;

/**
 * 实时通信离线消息 No-Op 保底丢弃实现
 * <p>
 * 默认保底实现，不执行任何持久化操作，瞬态离线消息直接丢弃。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
public class NoOpRealtimeOfflineMessageProvider implements RealtimeOfflineMessageProvider {

    @Override
    public void saveOfflineMessage(Object userId, RealtimeMessage<?> message) {
        // No-Op: 默认丢弃瞬态离线消息
    }

    @Override
    public List<RealtimeMessage<?>> pullAndClearOfflineMessages(Object userId) {
        return Collections.emptyList();
    }

    @Override
    public int getOfflineMessageCount(Object userId) {
        return 0;
    }
}
