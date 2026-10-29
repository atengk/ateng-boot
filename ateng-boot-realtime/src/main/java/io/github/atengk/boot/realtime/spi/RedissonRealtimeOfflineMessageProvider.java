package io.github.atengk.boot.realtime.spi;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.*;

/**
 * 基于 Redisson ScoredSortedSet (ZSet) 的分布式有界离线消息持久化提供者
 * <p>
 * 面向多端（PC/Mobile/Multi-Tab）长连接高并发场景，提供强类型有界离线消息存储：
 * <ul>
 *     <li>有序时序存储：以 Redis ZSet {@code ateng:realtime:offline:{userId}} 为载体，消息时间戳作为 score 严格单调递增；</li>
 *     <li>双重物理有界：单次写入原子执行 FIFO 截断（保留最新不超过 {@code max-messages-per-user} 条），配合 Redis 键级 TTL 自动淘汰，从物理根源杜绝爆仓；</li>
 *     <li>只读窥探重放：提供 {@link #getOfflineMessagesSince}，支持 W3C SSE {@code Last-Event-ID} 断点无损续传，多端并发连接互不污染。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
public class RedissonRealtimeOfflineMessageProvider implements RealtimeOfflineMessageProvider {

    private final RedissonClient redissonClient;
    private final RealtimeProperties properties;

    public RedissonRealtimeOfflineMessageProvider(RedissonClient redissonClient, RealtimeProperties properties) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        log.info("[Realtime] Redisson 分布式有界离线消息提供者已就绪 (Distributed Offline Message Provider Enabled)");
    }

    @Override
    public void saveOfflineMessage(Object userId, RealtimeMessage<?> message) {
        if (userId == null || message == null) {
            return;
        }
        long timestamp = (message.getTimestamp() != null && message.getTimestamp() > 0)
                ? message.getTimestamp()
                : System.currentTimeMillis();
        if (message.getTimestamp() == null) {
            message.setTimestamp(timestamp);
        }
        if (message.getId() == null || message.getId().isBlank()) {
            message.setId(IdUtil.fastSimpleUUID());
        }

        String json = JsonUtil.toJsonString(message);
        String key = getOfflineKey(userId);
        try {
            RScoredSortedSet<String> scoredSet = redissonClient.getScoredSortedSet(key, StringCodec.INSTANCE);
            scoredSet.add(timestamp, json);

            // 1. 设置键级 TTL 过期时间
            Duration ttl = (properties.getOffline() != null && properties.getOffline().getTtl() != null)
                    ? properties.getOffline().getTtl()
                    : Duration.ofDays(7);
            if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                scoredSet.expire(ttl);
            }

            // 2. 有界防爆仓原子 FIFO 截断：超出 maxMessagesPerUser 时通过负向索引原子截断
            int maxMessages = (properties.getOffline() != null)
                    ? properties.getOffline().getMaxMessagesPerUser()
                    : 100;
            if (maxMessages > 0) {
                scoredSet.removeRangeByRank(0, -(maxMessages + 1));
            }
            log.debug("[Realtime-Offline] 成功暂存用户离线消息: userId={}, messageId={}, timestamp={}",
                    userId, message.getId(), timestamp);
        } catch (Exception e) {
            log.warn("[Realtime-Offline] 暂存离线消息异常: userId={}, messageId={}, error={}",
                    userId, message.getId(), e.getMessage());
        }
    }

    @Override
    public List<RealtimeMessage<?>> pullAndClearOfflineMessages(Object userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        String key = getOfflineKey(userId);
        try {
            RScoredSortedSet<String> scoredSet = redissonClient.getScoredSortedSet(key, StringCodec.INSTANCE);
            Collection<String> jsonList = scoredSet.readAll();
            if (jsonList == null || jsonList.isEmpty()) {
                return Collections.emptyList();
            }
            scoredSet.delete();
            List<RealtimeMessage<?>> messages = deserializeMessages(jsonList);
            log.info("[Realtime-Offline] 用户上线，一次性拉取并清空离线消息: userId={}, count={}", userId, messages.size());
            return messages;
        } catch (Exception e) {
            log.warn("[Realtime-Offline] 拉取并清空离线消息异常: userId={}, error={}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<RealtimeMessage<?>> getOfflineMessages(Object userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        String key = getOfflineKey(userId);
        try {
            RScoredSortedSet<String> scoredSet = redissonClient.getScoredSortedSet(key, StringCodec.INSTANCE);
            Collection<String> jsonList = scoredSet.readAll();
            if (jsonList == null || jsonList.isEmpty()) {
                return Collections.emptyList();
            }
            return deserializeMessages(jsonList);
        } catch (Exception e) {
            log.warn("[Realtime-Offline] 只读查询离线消息异常: userId={}, error={}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<RealtimeMessage<?>> getOfflineMessagesSince(Object userId, long fromTimestamp) {
        if (userId == null) {
            return Collections.emptyList();
        }
        String key = getOfflineKey(userId);
        try {
            RScoredSortedSet<String> scoredSet = redissonClient.getScoredSortedSet(key, StringCodec.INSTANCE);
            // ZRANGEBYSCORE (fromTimestamp, +inf)
            Collection<String> jsonList = scoredSet.valueRange(fromTimestamp, false, Double.POSITIVE_INFINITY, false);
            if (jsonList == null || jsonList.isEmpty()) {
                return Collections.emptyList();
            }
            List<RealtimeMessage<?>> messages = deserializeMessages(jsonList);
            log.debug("[Realtime-Offline] 增量范围只读重放离线消息: userId={}, fromTimestamp={}, count={}",
                    userId, fromTimestamp, messages.size());
            return messages;
        } catch (Exception e) {
            log.warn("[Realtime-Offline] 增量范围查询离线消息异常: userId={}, fromTimestamp={}, error={}",
                    userId, fromTimestamp, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void clearOfflineMessages(Object userId) {
        if (userId == null) {
            return;
        }
        try {
            redissonClient.getScoredSortedSet(getOfflineKey(userId), StringCodec.INSTANCE).delete();
        } catch (Exception e) {
            log.warn("[Realtime-Offline] 清空离线消息异常: userId={}, error={}", userId, e.getMessage());
        }
    }

    @Override
    public int getOfflineMessageCount(Object userId) {
        if (userId == null) {
            return 0;
        }
        try {
            return redissonClient.getScoredSortedSet(getOfflineKey(userId), StringCodec.INSTANCE).size();
        } catch (Exception e) {
            log.warn("[Realtime-Offline] 获取离线消息数量异常: userId={}, error={}", userId, e.getMessage());
            return 0;
        }
    }

    /**
     * 获取指定用户的 Redis ZSet 离线消息键名
     *
     * @param userId 用户标识
     * @return Redis 键名
     */
    public String getOfflineKey(Object userId) {
        String prefix = (properties != null && properties.getOffline() != null && properties.getOffline().getKeyPrefix() != null)
                ? properties.getOffline().getKeyPrefix()
                : "ateng:realtime:offline:";
        return prefix + userId;
    }

    private List<RealtimeMessage<?>> deserializeMessages(Collection<String> jsonList) {
        List<RealtimeMessage<?>> result = new ArrayList<>(jsonList.size());
        for (String json : jsonList) {
            try {
                RealtimeMessage<?> msg = JsonUtil.parseObject(json, RealtimeMessage.class);
                if (msg != null) {
                    result.add(msg);
                }
            } catch (Exception ex) {
                log.warn("[Realtime-Offline] 反序列化离线消息失败: json={}, error={}", json, ex.getMessage());
            }
        }
        return Collections.unmodifiableList(result);
    }
}
