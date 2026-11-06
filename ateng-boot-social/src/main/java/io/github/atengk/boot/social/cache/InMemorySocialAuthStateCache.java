package io.github.atengk.boot.social.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.github.atengk.boot.social.model.StateContext;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.index.qual.NonNegative;

import java.time.Duration;

/**
 * 基于 Caffeine 本地内存驱动的防伪 State 状态缓存实现
 * <p>
 * 提供纯内存保底支撑，在宿主未配置 Redis 环境时自动激活；
 * 采用高并发细粒度 Expiry 过期策略，严格按每个 State 的生命周期自动定期淘汰防爆内存，
 * 并依托 ConcurrentMap 原子 remove 操作实现“阅后即焚”防重放。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
public class InMemorySocialAuthStateCache implements SocialAuthStateCache {

    private final Cache<String, StateContextHolder> cache;
    private final Duration defaultTimeout;

    public InMemorySocialAuthStateCache() {
        this(10_000, DEFAULT_STATE_TIMEOUT);
    }

    public InMemorySocialAuthStateCache(long maximumSize) {
        this(maximumSize, DEFAULT_STATE_TIMEOUT);
    }

    public InMemorySocialAuthStateCache(long maximumSize, Duration defaultTimeout) {
        this.defaultTimeout = defaultTimeout != null ? defaultTimeout : DEFAULT_STATE_TIMEOUT;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfter(new Expiry<String, StateContextHolder>() {
                    @Override
                    public long expireAfterCreate(String key, StateContextHolder holder, long currentTime) {
                        return holder.durationNanos;
                    }

                    @Override
                    public long expireAfterUpdate(String key, StateContextHolder holder, long currentTime, @NonNegative long currentDuration) {
                        return holder.durationNanos;
                    }

                    @Override
                    public long expireAfterRead(String key, StateContextHolder holder, long currentTime, @NonNegative long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    @Override
    public String generateState(StateContext context) {
        return generateState(context, this.defaultTimeout);
    }

    @Override
    public void set(String state, StateContext context, Duration timeout) {
        if (state == null || state.isBlank() || context == null) {
            return;
        }
        Duration effectiveTimeout = timeout != null ? timeout : this.defaultTimeout;
        long nanos = effectiveTimeout.toNanos();
        cache.put(state, new StateContextHolder(context, nanos));
    }

    @Override
    public StateContext getContext(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        StateContextHolder holder = cache.getIfPresent(state);
        return holder != null ? holder.context : null;
    }

    @Override
    public StateContext removeContext(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        StateContextHolder removed = cache.asMap().remove(state);
        return removed != null ? removed.context : null;
    }

    @Override
    public boolean contains(String state) {
        if (state == null || state.isBlank()) {
            return false;
        }
        return cache.getIfPresent(state) != null;
    }

    /**
     * 内部包装载体，暂存上下文与独立的有效纳秒数
     */
    private record StateContextHolder(StateContext context, long durationNanos) {
    }
}
