package io.github.atengk.boot.satoken.cluster.broadcaster;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.satoken.cluster.enums.SaTokenClusterEventType;
import io.github.atengk.boot.satoken.cluster.model.SaTokenClusterMessage;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

/**
 * Sa-Token 分布式集群广播器门面
 * 基于 Redis Pub/Sub 实现全网轻量状态同步；内嵌本机节点唯一标识 (instanceId) 防自重入风暴；
 * 自适应支持无 Redis 环境静默降级为单机无害模式
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Slf4j
public class SaTokenClusterBroadcaster {

    /**
     * 当前节点实例不可变唯一标识
     */
    @Getter
    private final String instanceId;

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final SaTokenProperties properties;

    public SaTokenClusterBroadcaster(ObjectProvider<StringRedisTemplate> redisTemplateProvider, SaTokenProperties properties) {
        this(IdUtil.fastSimpleUUID(), redisTemplateProvider, properties);
    }

    /**
     * 测试用构造函数，允许注入指定的 instanceId
     */
    public SaTokenClusterBroadcaster(String instanceId, ObjectProvider<StringRedisTemplate> redisTemplateProvider, SaTokenProperties properties) {
        this.instanceId = instanceId != null && !instanceId.isBlank() ? instanceId : IdUtil.fastSimpleUUID();
        this.redisTemplateProvider = redisTemplateProvider;
        this.properties = properties;
        log.info("Sa-Token 集群同步广播器初始化完成: instanceId={}", this.instanceId);
    }

    /**
     * 广播集群同步事件（极简模式）
     *
     * @param eventType 事件类型
     * @param targetKey 目标业务键
     */
    public void broadcast(SaTokenClusterEventType eventType, String targetKey) {
        broadcast(eventType, targetKey, null);
    }

    /**
     * 广播集群同步事件（带扩展参数）
     *
     * @param eventType 事件类型
     * @param targetKey 目标业务键
     * @param extra     扩展参数字典
     */
    public void broadcast(SaTokenClusterEventType eventType, String targetKey, Map<String, Object> extra) {
        if (eventType == null) {
            return;
        }
        if (properties == null || properties.getClusterSync() == null || !properties.getClusterSync().isEnabled()) {
            return;
        }
        StringRedisTemplate redisTemplate = redisTemplateProvider != null ? redisTemplateProvider.getIfAvailable() : null;
        if (redisTemplate == null) {
            // 无 Redis 环境下自动静默降级为单机模式
            log.trace("未检测到有效 Redis 基础设施，集群广播自动静默降级: eventType={}, targetKey={}", eventType, targetKey);
            return;
        }

        try {
            SaTokenClusterMessage message = SaTokenClusterMessage.of(this.instanceId, eventType, targetKey);
            if (extra != null && !extra.isEmpty()) {
                message.getExtra().putAll(extra);
            }
            String channel = properties.getClusterSync().getChannel();
            String payload = JsonUtil.toJsonString(message);
            redisTemplate.convertAndSend(channel, payload);
            log.debug("成功发送 Sa-Token 集群同步广播: channel={}, eventType={}, targetKey={}", channel, eventType, targetKey);
        } catch (Throwable ex) {
            // 异常隔离防卫，严禁广播异常阻断核心业务主链路
            log.warn("Sa-Token 集群广播消息发送异常（降级忽略）: eventType={}, targetKey={}, error={}",
                    eventType, targetKey, ex.getMessage());
        }
    }
}
