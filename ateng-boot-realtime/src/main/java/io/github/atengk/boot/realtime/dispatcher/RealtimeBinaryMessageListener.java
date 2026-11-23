package io.github.atengk.boot.realtime.dispatcher;

import io.github.atengk.boot.realtime.session.RealtimeSession;

/**
 * 客户端上行原生二进制帧消费监听 SPI 扩展点
 * <p>
 * 专门用于高效处理 WebSocket 原始二进制帧（如实时音频流 ASR/TTS、Protobuf 序列化、二进制分片传输等），
 * 绕过 JSON 序列化与反序列化开销，与基于事件名的 &#64;RealtimeAction 路由正交解耦。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@FunctionalInterface
public interface RealtimeBinaryMessageListener {

    /**
     * 接收并处理客户端上行的原始二进制载荷
     *
     * @param session 上行发起端受管会话
     * @param payload 二进制字节数组载荷
     */
    void onBinaryMessage(RealtimeSession session, byte[] payload);
}
