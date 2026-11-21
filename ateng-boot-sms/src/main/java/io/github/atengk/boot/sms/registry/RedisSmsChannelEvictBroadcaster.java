package io.github.atengk.boot.sms.registry;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * 基于 Redis Pub/Sub 的跨节点短信渠道热驱逐集群广播器
 * <p>
 * 附带实例唯一 {@code nodeId} 防回环过滤，收到远端节点驱逐指令时发布带 {@code fromCluster=true} 标记的本地
 * {@link io.github.atengk.boot.sms.event.SmsChannelEvictEvent}，杜绝广播风暴。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class RedisSmsChannelEvictBroadcaster implements SmsChannelEvictBroadcaster, MessageListener {

    @Getter
    private final String nodeId = IdUtil.fastSimpleUUID();
    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    @Getter
    private final String topic;

    public RedisSmsChannelEvictBroadcaster(StringRedisTemplate redisTemplate,
                                           ApplicationEventPublisher eventPublisher,
                                           String topic) {
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
        this.topic = StringUtils.hasText(topic) ? topic.trim() : "ateng:sms:channel:evict";
    }

    /**
     * 向集群其他节点广播渠道驱逐消息
     *
     * @param channelName 待驱逐渠道名称，null 表示全量清空
     */
    @Override
    public void publishEvict(String channelName) {
        if (redisTemplate == null) {
            return;
        }
        try {
            String normalized = StringUtils.hasText(channelName) ? channelName.trim() : null;
            EvictPayload payload = new EvictPayload(nodeId, normalized);
            redisTemplate.convertAndSend(topic, JsonUtil.toJsonString(payload));
        } catch (Exception ex) {
            log.warn("[RedisSmsChannelEvictBroadcaster] 广播渠道驱逐消息失败 -> topic: {}, error: {}", topic, ex.getMessage());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (message == null || message.getBody() == null || eventPublisher == null) {
            return;
        }
        try {
            String rawJson = new String(message.getBody(), StandardCharsets.UTF_8);
            EvictPayload payload = JsonUtil.parseObject(rawJson, EvictPayload.class);
            if (payload == null) {
                return;
            }
            if (payload.nodeId() != null && nodeId.equals(payload.nodeId())) {
                // 忽略本节点自发广播回环
                return;
            }
            eventPublisher.publishEvent(new io.github.atengk.boot.sms.event.SmsChannelEvictEvent(this, payload.channelName(), true));
        } catch (Exception ex) {
            log.warn("[RedisSmsChannelEvictBroadcaster] 处理远端渠道驱逐广播消息异常: {}", ex.getMessage());
        }
    }

    private record EvictPayload(String nodeId, String channelName) {
    }
}
