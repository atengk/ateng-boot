package io.github.atengk.boot.mqtt.properties;

/**
 * MQTT 客户端断线离线缓冲策略
 * <p>
 * 决定客户端处于断线自动重连退避窗口期间，普通出站发布请求的行为语义。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-18
 */
public enum OfflineBufferPolicy {

    /**
     * 当开启自动重连且 {@code maxBufferedMessages > 0} 时，允许出站普通/异步消息进入 Paho 离线缓冲并在重连后自动补发（默认）
     */
    BUFFER_WHEN_RECONNECTING,

    /**
     * 客户端未处于已连接状态时立即快速失败，拒绝写入离线缓冲区（适用于强一致性实时拒收场景）
     */
    FAIL_FAST
}
