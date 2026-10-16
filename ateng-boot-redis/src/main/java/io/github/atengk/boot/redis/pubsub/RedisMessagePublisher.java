package io.github.atengk.boot.redis.pubsub;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 企业级 Redis Pub/Sub 主题消息发布与动态监听门面客户端
 * 基于 Spring Data Redis 原生生态封装，满足跨实例本地缓存刷新、轻量通知与分布式事件广播诉求
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Slf4j
public class RedisMessagePublisher {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;

    public RedisMessagePublisher(RedisTemplate<String, Object> redisTemplate,
                                 RedisMessageListenerContainer listenerContainer) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.listenerContainer = listenerContainer;
    }

    /**
     * 向指定频道广播发布消息
     *
     * @param channel 频道主题名称
     * @param message 消息体对象（自动执行序列化）
     * @return 收到该消息的客户端数量
     */
    public Long publish(String channel, Object message) {
        Assert.hasText(channel, "channel must not be blank");
        Assert.notNull(message, "message must not be null");
        log.debug("向 Redis 频道 [{}] 发布广播消息", channel);
        return redisTemplate.convertAndSend(channel, message);
    }

    /**
     * 向指定 Topic 广播发布消息
     *
     * @param topic 频道主题
     * @param message 消息体对象
     * @return 收到该消息的客户端数量
     */
    public Long publish(Topic topic, Object message) {
        Assert.notNull(topic, "topic must not be null");
        Assert.notNull(message, "message must not be null");
        return publish(topic.getTopic(), message);
    }

    /**
     * 动态注册频道监听器
     *
     * @param channel 频道名称
     * @param listener 消息监听器
     */
    public void subscribe(String channel, MessageListener listener) {
        Assert.hasText(channel, "channel must not be blank");
        Assert.notNull(listener, "listener must not be null");
        ensureContainerAvailable();
        listenerContainer.addMessageListener(listener, new ChannelTopic(channel));
        log.info("已动态订阅 Redis 广播频道 [{}]", channel);
    }

    /**
     * 动态注册 Topic 主题监听器
     *
     * @param topic 广播主题
     * @param listener 消息监听器
     */
    public void subscribe(Topic topic, MessageListener listener) {
        Assert.notNull(topic, "topic must not be null");
        Assert.notNull(listener, "listener must not be null");
        ensureContainerAvailable();
        listenerContainer.addMessageListener(listener, topic);
        log.info("已动态订阅 Redis 广播主题 [{}]", topic.getTopic());
    }

    /**
     * 动态注册频道监听器（便捷函数式 Consumer 回调，接收消息内容）
     *
     * @param channel 频道名称
     * @param messageConsumer 接收消息内容的回调
     */
    public void subscribe(String channel, Consumer<String> messageConsumer) {
        Assert.hasText(channel, "channel must not be blank");
        Assert.notNull(messageConsumer, "messageConsumer must not be null");
        MessageListener listener = (message, pattern) -> {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            messageConsumer.accept(body);
        };
        subscribe(channel, listener);
    }

    /**
     * 动态注册频道监听器（便捷函数式 BiConsumer 回调，接收频道与消息内容）
     *
     * @param channel 频道名称
     * @param consumer 接收 (channel, message) 的双参数回调
     */
    public void subscribeChannel(String channel, BiConsumer<String, String> consumer) {
        Assert.hasText(channel, "channel must not be blank");
        Assert.notNull(consumer, "consumer must not be null");
        MessageListener listener = (message, pattern) -> {
            String ch = new String(message.getChannel(), StandardCharsets.UTF_8);
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            consumer.accept(ch, body);
        };
        subscribe(channel, listener);
    }

    /**
     * 动态注销指定频道的监听器
     *
     * @param channel 频道名称
     * @param listener 消息监听器
     */
    public void unsubscribe(String channel, MessageListener listener) {
        Assert.hasText(channel, "channel must not be blank");
        if (listenerContainer != null) {
            listenerContainer.removeMessageListener(listener, new ChannelTopic(channel));
            log.info("已取消订阅 Redis 广播频道 [{}]", channel);
        }
    }

    /**
     * 获取底层 RedisMessageListenerContainer 容器实例
     *
     * @return 监听容器实例
     */
    public RedisMessageListenerContainer getListenerContainer() {
        return listenerContainer;
    }

    private void ensureContainerAvailable() {
        if (listenerContainer == null) {
            throw new IllegalStateException("RedisMessageListenerContainer 未注入，无法动态添加订阅监听器");
        }
    }
}
