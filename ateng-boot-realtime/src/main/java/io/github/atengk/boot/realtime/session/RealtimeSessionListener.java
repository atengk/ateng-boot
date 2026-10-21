package io.github.atengk.boot.realtime.session;

/**
 * 实时通信受管会话生命周期监听器
 * <p>
 * 用于监听实时会话的建立注册与关闭销毁事件，支持业务侧解耦扩展（如用户上线离线通知、资源锁定/释放等）。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
public interface RealtimeSessionListener {

    /**
     * 会话注册成功后触发
     *
     * @param session 受管会话
     */
    default void onSessionRegistered(RealtimeSession session) {
    }

    /**
     * 会话关闭或注销后触发
     *
     * @param session 受管会话
     */
    default void onSessionClosed(RealtimeSession session) {
    }
}
