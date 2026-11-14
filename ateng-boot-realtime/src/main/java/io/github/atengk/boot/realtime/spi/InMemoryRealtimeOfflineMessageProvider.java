package io.github.atengk.boot.realtime.spi;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

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
    private final Map<String, UserMailbox> messageMap = new ConcurrentHashMap<>();

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
        if (message.getTimestamp() == null || message.getTimestamp() <= 0) {
            message.setTimestamp(System.currentTimeMillis());
        }
        if (message.getId() == null || message.getId().isBlank()) {
            message.setId(IdUtil.fastSimpleUUID());
        }
        String key = String.valueOf(userId);
        while (true) {
            UserMailbox mailbox = messageMap.computeIfAbsent(key, k -> new UserMailbox());
            mailbox.lock.lock();
            try {
                if (mailbox.retired) {
                    continue;
                }
                long now = System.currentTimeMillis();
                long ttlMillis = ttl.toMillis();
                if (ttlMillis > 0) {
                    mailbox.deque.removeIf(msg -> isExpired(msg, now, ttlMillis));
                }
                // 防 OOM 治理：超出单用户最大配额时按照 FIFO 淘汰最早的消息
                while (mailbox.deque.size() >= maxMessagesPerUser) {
                    mailbox.deque.pollFirst();
                }
                mailbox.deque.offerLast(message);
                return;
            } finally {
                if (mailbox.lock.isHeldByCurrentThread()) {
                    mailbox.lock.unlock();
                }
            }
        }
    }

    @Override
    public List<RealtimeMessage<?>> pullAndClearOfflineMessages(Object userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        String key = String.valueOf(userId);
        UserMailbox mailbox = messageMap.get(key);
        if (mailbox == null) {
            return Collections.emptyList();
        }
        mailbox.lock.lock();
        try {
            if (mailbox.retired || mailbox.deque.isEmpty()) {
                mailbox.retired = true;
                messageMap.remove(key, mailbox);
                return Collections.emptyList();
            }
            long now = System.currentTimeMillis();
            long ttlMillis = ttl.toMillis();
            List<RealtimeMessage<?>> result = new ArrayList<>(mailbox.deque.size());
            for (RealtimeMessage<?> msg : mailbox.deque) {
                if (isExpired(msg, now, ttlMillis)) {
                    // 已过 TTL 保留期，自动丢弃
                    continue;
                }
                result.add(msg);
            }
            mailbox.deque.clear();
            mailbox.retired = true;
            messageMap.remove(key, mailbox);
            return Collections.unmodifiableList(result);
        } finally {
            if (mailbox.lock.isHeldByCurrentThread()) {
                mailbox.lock.unlock();
            }
        }
    }

    @Override
    public List<RealtimeMessage<?>> getOfflineMessagesSince(Object userId, long fromTimestamp) {
        if (userId == null) {
            return Collections.emptyList();
        }
        UserMailbox mailbox = messageMap.get(String.valueOf(userId));
        if (mailbox == null) {
            return Collections.emptyList();
        }
        mailbox.lock.lock();
        try {
            if (mailbox.retired || mailbox.deque.isEmpty()) {
                return Collections.emptyList();
            }
            long now = System.currentTimeMillis();
            long ttlMillis = ttl.toMillis();
            List<RealtimeMessage<?>> result = new ArrayList<>(mailbox.deque.size());
            for (RealtimeMessage<?> msg : mailbox.deque) {
                if (msg.getTimestamp() != null) {
                    if (isExpired(msg, now, ttlMillis)) {
                        continue;
                    }
                    if (msg.getTimestamp() > fromTimestamp) {
                        result.add(msg);
                    }
                }
            }
            return Collections.unmodifiableList(result);
        } finally {
            if (mailbox.lock.isHeldByCurrentThread()) {
                mailbox.lock.unlock();
            }
        }
    }

    @Override
    public List<RealtimeMessage<?>> getOfflineMessages(Object userId) {
        return getOfflineMessagesSince(userId, 0L);
    }

    @Override
    public void clearOfflineMessages(Object userId) {
        if (userId == null) {
            return;
        }
        String key = String.valueOf(userId);
        UserMailbox mailbox = messageMap.get(key);
        if (mailbox == null) {
            return;
        }
        mailbox.lock.lock();
        try {
            mailbox.deque.clear();
            mailbox.retired = true;
            messageMap.remove(key, mailbox);
        } finally {
            if (mailbox.lock.isHeldByCurrentThread()) {
                mailbox.lock.unlock();
            }
        }
    }

    @Override
    public int getOfflineMessageCount(Object userId) {
        if (userId == null) {
            return 0;
        }
        UserMailbox mailbox = messageMap.get(String.valueOf(userId));
        if (mailbox == null) {
            return 0;
        }
        mailbox.lock.lock();
        try {
            return mailbox.retired ? 0 : mailbox.deque.size();
        } finally {
            if (mailbox.lock.isHeldByCurrentThread()) {
                mailbox.lock.unlock();
            }
        }
    }

    private boolean isExpired(RealtimeMessage<?> msg, long now, long ttlMillis) {
        return msg.getTimestamp() != null && ttlMillis > 0 && (now - msg.getTimestamp() > ttlMillis);
    }

    /**
     * 单用户离线消息邮箱与独立重入锁，避免虚拟线程环境下内置管程锁造成载体线程 Pinning
     */
    private static final class UserMailbox {
        private final ReentrantLock lock = new ReentrantLock();
        private final Deque<RealtimeMessage<?>> deque = new ArrayDeque<>();
        private boolean retired = false;
    }
}
