package io.github.atengk.boot.sample.realtime.handler;

import io.github.atengk.boot.realtime.dispatcher.RealtimeBinaryMessageListener;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 实时原生二进制音频流监听组件
 * <p>
 * 演示使用 RealtimeBinaryMessageListener SPI 直接接收客户端上行的原始音视频字节流，
 * 绕过 JSON 反序列化损耗，与 JSON @RealtimeAction 路由完全解耦。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
@Component
@Getter
public class AudioBinaryStreamListener implements RealtimeBinaryMessageListener {

    private final BlockingQueue<byte[]> receivedChunks = new LinkedBlockingQueue<>();

    @Override
    public void onBinaryMessage(RealtimeSession session, byte[] payload) {
        log.info("[Sample] 收到客户端原生二进制音频流分片: sessionKey={}, byteCount={}",
                session.getSessionKey(), payload != null ? payload.length : 0);
        if (payload != null) {
            receivedChunks.offer(payload);
        }
    }

    /**
     * 阻塞拉取接收到的二进制载荷（用于测试断言）
     */
    public byte[] pollChunk(long timeout, TimeUnit unit) throws InterruptedException {
        return receivedChunks.poll(timeout, unit);
    }

    /**
     * 清空队列
     */
    public void clear() {
        receivedChunks.clear();
    }
}
