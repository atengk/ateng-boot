package io.github.atengk.boot.job.snail.retry.context;

import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 重试最近失败异常因果跟踪器
 *
 * <p>由于 SnailJob {@code RetryCompleteCallback#doMaxRetryCallback} 原生回调签名不包含底层异常对象，
 * 本跟踪器基于 {@link ReentrantLock} 与有界 LRU + 惰性 TTL 缓存记录最近一次失败的真实 {@link Throwable}，
 * 以便死信回调与手工模板完整透传故障因果链路并支持重试成功后立即清理。</p>
 *
 * @author Ateng
 * @since 2026-11-20
 */
public final class SnailRetryFailureTracker {

    private static final int DEFAULT_MAX_ENTRIES = 4096;
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final Map<String, FailureEntry> STORE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, FailureEntry> eldest) {
            return size() > DEFAULT_MAX_ENTRIES;
        }
    };

    private SnailRetryFailureTracker() {
    }

    /**
     * 记录一次重试异常因果至多个关联索引键
     *
     * @param throwable 原始异常对象
     * @param keys      关联索引键列表
     */
    public static void record(Throwable throwable, String... keys) {
        if (throwable == null || keys == null || keys.length == 0) {
            return;
        }
        Instant expiresAt = Instant.now().plus(DEFAULT_TTL);
        FailureEntry entry = new FailureEntry(throwable, expiresAt);
        LOCK.lock();
        try {
            pruneExpiredLocked(Instant.now());
            for (String key : keys) {
                if (StringUtils.hasText(key)) {
                    STORE.put(key, entry);
                }
            }
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * 按优先级顺序查找最近记录的未过期异常因果
     *
     * @param keys 候选索引键列表（由细粒度到粗粒度）
     * @return 命中的异常对象，未命中或已过期返回 {@code null}
     */
    public static Throwable find(String... keys) {
        if (keys == null || keys.length == 0) {
            return null;
        }
        Instant now = Instant.now();
        LOCK.lock();
        try {
            for (String key : keys) {
                if (!StringUtils.hasText(key)) {
                    continue;
                }
                FailureEntry entry = STORE.get(key);
                if (entry == null) {
                    continue;
                }
                if (!now.isBefore(entry.expiresAt())) {
                    STORE.remove(key);
                    continue;
                }
                return entry.throwable();
            }
            return null;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * 清理指定索引键关联的失败异常记录
     *
     * @param keys 待清理索引键列表
     */
    public static void remove(String... keys) {
        if (keys == null || keys.length == 0) {
            return;
        }
        LOCK.lock();
        try {
            for (String key : keys) {
                if (StringUtils.hasText(key)) {
                    STORE.remove(key);
                }
            }
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * 清空全部失败异常记录（主要用于单元测试清理）
     */
    public static void clear() {
        LOCK.lock();
        try {
            STORE.clear();
        } finally {
            LOCK.unlock();
        }
    }

    private static void pruneExpiredLocked(Instant now) {
        if (STORE.size() < 256) {
            return;
        }
        Iterator<Map.Entry<String, FailureEntry>> iterator = STORE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, FailureEntry> next = iterator.next();
            if (!now.isBefore(next.getValue().expiresAt())) {
                iterator.remove();
            }
        }
    }

    private record FailureEntry(Throwable throwable, Instant expiresAt) {
    }
}
