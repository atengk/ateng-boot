package io.github.atengk.boot.realtime.spi;

import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的实时通信离线消息提供者
 * <p>
 * 支持单用户最大消息数量配额（防 OOM FIFO 淘汰）与基于 TTL 的消息过期清理，适用于单机轻量场景与单元测试。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
public class InMemoryRealtimeOfflineMessageProvider implements RealtimeOfflineMessageProvider {

    private final int maxMessagesPerUser;
    private final Duration ttl;
    private final Map<String, Deque<RealtimeMessage<?>>> messageMap = new ConcurrentHashMap<>();

    public InMemoryRealtimeOfflineMessageProvider(RealtimeProperties properties) {
        this(
                properties != null && properties.getOffline() != null ? properties.getOffline().getMaxMessagesPerUser() : 100,
                properties != null && properties.getOffline() != null ? properties.getOffline().getTtl() : Duration.ofDays(7)
        );
    }

    public InMemoryRealtimeOfflineMessageProvider(int maxMessagesPerUser, Duration ttl) {
        this.maxMessagesPerUser = Math.max(1, maxMessagesPerUser);
        this.ttl = ttl != null ? ttl : Duration.ofDays(7);
    }

    @Override
    public void saveOfflineMessage(Object userId, RealtimeMessage<?> message) {
        if (userId == null || message == null) {
            return;
        }
        String key = String.valueOf(userId);
        messageMap.compute(key, (k, deque) -> {
            if (deque == null) {
                deque = new ArrayDeque<>();
            }
            long now = System.currentTimeMillis();
            long ttlMillis = ttl.toMillis();
            if (ttlMillis > 0) {
                deque.removeIf(msg -> msg.getTimestamp() != null && (now - msg.getTimestamp() > ttlMillis));
            }
            // 防 OOM 治理：超出单用户最大配额时按照 FIFO 淘汰最早的消息
            while (deque.size() >= maxMessagesPerUser) {
                deque.pollFirst();
            }
            deque.offerLast(message);
            return deque;
        });
    }

    @Override
    public List<RealtimeMessage<?>> pullAndClearOfflineMessages(Object userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        String key = String.valueOf(userId);
        Deque<RealtimeMessage<?>> deque = messageMap.remove(key);
        if (deque == null || deque.isEmpty()) {
            return Collections.emptyList();
        }
        long now = System.currentTimeMillis();
        long ttlMillis = ttl.toMillis();
        List<RealtimeMessage<?>> result = new ArrayList<>(deque.size());
        for (RealtimeMessage<?> msg : deque) {
            if (msg.getTimestamp() != null && ttlMillis > 0 && (now - msg.getTimestamp() > ttlMillis)) {
                // 已过 TTL 保留期，自动丢弃
                continue;
            }
            result.add(msg);
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<RealtimeMessage<?>> getOfflineMessagesSince(Object userId, long fromTimestamp) {
        if (userId == null) {
            return Collections.emptyList();
        }
        Deque<RealtimeMessage<?>> deque = messageMap.get(String.valueOf(userId));
        if (deque == null || deque.isEmpty()) {
            return Collections.emptyList();
        }
        long now = System.currentTimeMillis();
        long ttlMillis = ttl.toMillis();
        List<RealtimeMessage<?>> result = new ArrayList<>(deque.size());
        synchronized (deque) {
            for (RealtimeMessage<?> msg : deque) {
                if (msg.getTimestamp() != null) {
                    if (ttlMillis > 0 && (now - msg.getTimestamp() > ttlMillis)) {
                        continue;
                    }
                    if (msg.getTimestamp() > fromTimestamp) {
                        result.add(msg);
                    }
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<RealtimeMessage<?>> getOfflineMessages(Object userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        Deque<RealtimeMessage<?>> deque = messageMap.get(String.valueOf(userId));
        if (deque == null || deque.isEmpty()) {
            return Collections.emptyList();
        }
        long now = System.currentTimeMillis();
        long ttlMillis = ttl.toMillis();
        List<RealtimeMessage<?>> result = new ArrayList<>(deque.size());
        synchronized (deque) {
            for (RealtimeMessage<?> msg : deque) {
                if (msg.getTimestamp() != null && ttlMillis > 0 && (now - msg.getTimestamp() > ttlMillis)) {
                    continue;
                }
                result.add(msg);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public void clearOfflineMessages(Object userId) {
        if (userId != null) {
            messageMap.remove(String.valueOf(userId));
        }
    }

    @Override
    public int getOfflineMessageCount(Object userId) {
        if (userId == null) {
            return 0;
        }
        Deque<RealtimeMessage<?>> deque = messageMap.get(String.valueOf(userId));
        return deque != null ? deque.size() : 0;
    }
}
