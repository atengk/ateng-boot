package io.github.atengk.boot.satoken.cluster.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

import java.nio.charset.StandardCharsets;

/**
 * Sa-Token Redis Pub/Sub 广播消息订阅适配器
 * 负责接收底层 Redis 物理消息并转译桥接给 SaTokenClusterSyncListener，实现核心监听器与 Redis 类型的类加载完全解耦
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Slf4j
@RequiredArgsConstructor
public class SaTokenClusterRedisMessageSubscriber implements MessageListener {

    private final SaTokenClusterSyncListener listener;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (message == null || message.getBody() == null) {
            return;
        }
        try {
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);
            if (!payload.isBlank()) {
                listener.onClusterMessage(payload);
            }
        } catch (Throwable ex) {
            log.warn("反序列化 Sa-Token 集群广播消息异常: error={}", ex.getMessage(), ex);
        }
    }
}
