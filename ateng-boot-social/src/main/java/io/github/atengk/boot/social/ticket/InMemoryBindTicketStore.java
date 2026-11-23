package io.github.atengk.boot.social.ticket;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.github.atengk.boot.social.model.SocialUser;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.index.qual.NonNegative;

import java.time.Duration;

/**
 * 基于 Caffeine 本地内存驱动的绑定票据缓存仓库
 * <p>
 * 提供纯内存保底支撑，在宿主未配置 Redis 环境时自动激活；
 * 采用高并发细粒度 Expiry 过期策略定期淘汰防内存泄漏，并依托 ConcurrentMap 原子 remove 操作实现阅后即焚。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
public class InMemoryBindTicketStore implements BindTicketStore {

    private final Cache<String, TicketHolder> cache;
    private final Duration defaultTimeout;

    public InMemoryBindTicketStore() {
        this(10_000, DEFAULT_TICKET_TIMEOUT);
    }

    public InMemoryBindTicketStore(long maximumSize) {
        this(maximumSize, DEFAULT_TICKET_TIMEOUT);
    }

    public InMemoryBindTicketStore(long maximumSize, Duration defaultTimeout) {
        this.defaultTimeout = defaultTimeout != null ? defaultTimeout : DEFAULT_TICKET_TIMEOUT;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfter(new Expiry<String, TicketHolder>() {
                    @Override
                    public long expireAfterCreate(String key, TicketHolder holder, long currentTime) {
                        return holder.durationNanos;
                    }

                    @Override
                    public long expireAfterUpdate(String key, TicketHolder holder, long currentTime, @NonNegative long currentDuration) {
                        return holder.durationNanos;
                    }

                    @Override
                    public long expireAfterRead(String key, TicketHolder holder, long currentTime, @NonNegative long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    @Override
    public String createTicket(SocialUser socialUser) {
        return createTicket(socialUser, this.defaultTimeout);
    }

    @Override
    public void save(String bindTicket, SocialUser socialUser, Duration timeout) {
        if (bindTicket == null || bindTicket.isBlank() || socialUser == null) {
            return;
        }
        Duration effectiveTimeout = timeout != null ? timeout : this.defaultTimeout;
        long nanos = effectiveTimeout.toNanos();
        cache.put(bindTicket, new TicketHolder(socialUser, nanos));
    }

    @Override
    public SocialUser remove(String bindTicket) {
        if (bindTicket == null || bindTicket.isBlank()) {
            return null;
        }
        TicketHolder holder = cache.asMap().remove(bindTicket);
        return holder != null ? holder.user : null;
    }

    @Override
    public SocialUser get(String bindTicket) {
        if (bindTicket == null || bindTicket.isBlank()) {
            return null;
        }
        TicketHolder holder = cache.getIfPresent(bindTicket);
        return holder != null ? holder.user : null;
    }

    @Override
    public boolean contains(String bindTicket) {
        if (bindTicket == null || bindTicket.isBlank()) {
            return false;
        }
        return cache.getIfPresent(bindTicket) != null;
    }

    /**
     * 内部包装载体
     */
    private record TicketHolder(SocialUser user, long durationNanos) {
    }
}
