package io.github.atengk.boot.datasource.readwrite;

import io.github.atengk.boot.core.context.UserContextHolder;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 默认内存级时间窗口写后读主感知解析器
 * <p>
 * 基于 Core 模块 {@link UserContextHolder} 获取当前用户标识，在本地内存中维护滑动过期窗口。
 * 下游模块或应用可接入 Redis 等分布式实现平滑无缝替换。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
public class DefaultWriteOccurredResolver implements WriteOccurredResolver {

    private final ConcurrentMap<String, Long> writeWindowCache = new ConcurrentHashMap<>();

    @Override
    public void markWrite(Duration windowTtl) {
        if (writeWindowCache.size() > 2000) {
            pruneExpiredEntries();
        }
        String key = resolveCurrentKey();
        long ttlMillis = (windowTtl != null && !windowTtl.isNegative()) ? windowTtl.toMillis() : 3000L;
        writeWindowCache.put(key, System.currentTimeMillis() + ttlMillis);
    }

    private void pruneExpiredEntries() {
        long now = System.currentTimeMillis();
        writeWindowCache.entrySet().removeIf(entry -> entry.getValue() == null || now >= entry.getValue());
    }

    @Override
    public boolean isWriteOccurred(Duration windowTtl) {
        String key = resolveCurrentKey();
        Long expireTime = writeWindowCache.get(key);
        if (expireTime == null) {
            return false;
        }
        if (System.currentTimeMillis() < expireTime) {
            return true;
        }
        // 已过期，被动清理
        writeWindowCache.remove(key, expireTime);
        return false;
    }

    @Override
    public void clear() {
        String key = resolveCurrentKey();
        writeWindowCache.remove(key);
    }

    /**
     * 清理所有缓存项（供测试或管理运维使用）
     */
    public void clearAll() {
        writeWindowCache.clear();
    }

    /**
     * 解析当前客户端的标识 Key
     * 优先提取 UserContextHolder 用户 ID，若未登录则回退至当前线程标识
     */
    protected String resolveCurrentKey() {
        String userId = UserContextHolder.getUserId();
        if (StringUtils.hasText(userId)) {
            return "USER:" + userId;
        }
        return "THREAD:" + Thread.currentThread().threadId();
    }
}
