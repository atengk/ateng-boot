package io.github.atengk.boot.sample.redis.service;

import io.github.atengk.boot.redis.pubsub.RedisMessagePublisher;
import io.github.atengk.boot.redis.pubsub.annotation.ChannelTopicListener;
import io.github.atengk.boot.sample.redis.model.NoticeBroadcastPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 广播发布订阅与声明式主题监听协同服务
 * 演示 Redis Pub/Sub：RedisMessagePublisher 广播发布与 @ChannelTopicListener 声明式消费
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoticeBroadcastService {

    public static final String TOPIC_NOTICE_BROADCAST = "sample:notice:broadcast";
    public static final String PATTERN_SYSTEM_EVENT = "sample:system:*";

    private final RedisMessagePublisher redisMessagePublisher;

    private final List<NoticeBroadcastPayload> receivedNotices = new CopyOnWriteArrayList<>();
    private final List<String> receivedSystemEvents = new CopyOnWriteArrayList<>();

    /**
     * 向指定主题广播发布系统通知
     *
     * @param notice 通知载荷
     * @return 接收订阅客户端数
     */
    public Long publishNotice(NoticeBroadcastPayload notice) {
        log.info("广播发布系统通知: topic={}, noticeId={}, title={}", TOPIC_NOTICE_BROADCAST, notice.getNoticeId(), notice.getTitle());
        return redisMessagePublisher.publish(TOPIC_NOTICE_BROADCAST, notice);
    }

    /**
     * 向通配模式主题广播发布系统事件
     *
     * @param eventType 事件类型（如 config_refresh, user_logout）
     * @param eventData 事件详情字符串
     * @return 接收订阅客户端数
     */
    public Long publishSystemEvent(String eventType, String eventData) {
        String channel = "sample:system:" + eventType;
        log.info("广播发布系统事件: channel={}, data={}", channel, eventData);
        return redisMessagePublisher.publish(channel, eventData);
    }

    /**
     * 声明式精确主题监听：自动反序列化为强类型 POJO
     *
     * @param notice 通知载荷
     */
    @ChannelTopicListener(topics = TOPIC_NOTICE_BROADCAST, concurrency = 1)
    public void onNoticeBroadcast(NoticeBroadcastPayload notice) {
        log.info("声明式接收系统公告广播: noticeId={}, title={}", notice.getNoticeId(), notice.getTitle());
        receivedNotices.add(notice);
    }

    /**
     * 声明式通配模式监听：多参数接收 payload 与当前匹配频道
     *
     * @param eventData 文本数据
     * @param channel   触发该事件的具体频道
     */
    @ChannelTopicListener(patterns = PATTERN_SYSTEM_EVENT, concurrency = 1)
    public void onSystemEvent(String eventData, String channel) {
        log.info("声明式通配监听到系统事件: channel={}, data={}", channel, eventData);
        receivedSystemEvents.add(channel + "=" + eventData);
    }

    /**
     * 获取已接收的系统通知列表
     *
     * @return 通知列表
     */
    public List<NoticeBroadcastPayload> getReceivedNotices() {
        return receivedNotices;
    }

    /**
     * 获取已接收的系统事件列表
     *
     * @return 事件列表
     */
    public List<String> getReceivedSystemEvents() {
        return receivedSystemEvents;
    }
}
