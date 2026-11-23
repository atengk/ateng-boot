package io.github.atengk.boot.sms.guard;

import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsQuotaResetCycle;
import io.github.atengk.boot.sms.support.BoundedLruMap;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 基于 ReentrantLock（{@link BoundedLruMap}）与确定性 Clock 接缝的内存单渠道配额守卫
 * <p>
 * 支持 {@link SmsQuotaResetCycle#DAILY} 跨自然日自动复位与 {@link SmsQuotaResetCycle#TOTAL} 持久累计，
 * 全程采用 {@link java.util.concurrent.locks.ReentrantLock} 杜绝 JDK 21+ 虚拟线程 Carrier Pinning，并通过有界 LRU 防止 OOM。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class InMemorySmsQuotaGuard implements SmsQuotaGuard {

    private final Clock clock;
    private final BoundedLruMap<String, ChannelQuotaState> quotaMap;

    public InMemorySmsQuotaGuard() {
        this(BoundedLruMap.DEFAULT_MAX_ENTRIES, Clock.systemDefaultZone());
    }

    public InMemorySmsQuotaGuard(Clock clock) {
        this(BoundedLruMap.DEFAULT_MAX_ENTRIES, clock);
    }

    public InMemorySmsQuotaGuard(int maxEntries, Clock clock) {
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
        this.quotaMap = new BoundedLruMap<>(maxEntries);
    }

    @Override
    public boolean tryAcquire(SmsChannel channel, int count) {
        if (channel == null || channel.getName() == null || channel.getMaxQuota() <= 0 || count <= 0) {
            return true;
        }
        String name = channel.getName().trim();
        long maxQuota = channel.getMaxQuota();
        SmsQuotaResetCycle cycle = channel.getQuotaResetCycle() != null
                ? channel.getQuotaResetCycle()
                : SmsQuotaResetCycle.DAILY;
        LocalDate today = LocalDate.now(clock);

        return quotaMap.withLock(view -> {
            ChannelQuotaState state = view.get(name);
            if (state == null) {
                state = new ChannelQuotaState(today);
                view.put(name, state);
            }
            if (cycle == SmsQuotaResetCycle.DAILY) {
                state.rolloverIfNewDay(today);
                if (state.dailyCount + count > maxQuota) {
                    return false;
                }
                state.dailyCount += count;
                return true;
            }
            if (state.totalCount + count > maxQuota) {
                return false;
            }
            state.totalCount += count;
            return true;
        });
    }

    @Override
    public void release(SmsChannel channel, int count) {
        if (channel == null || channel.getName() == null || channel.getMaxQuota() <= 0 || count <= 0) {
            return;
        }
        String name = channel.getName().trim();
        SmsQuotaResetCycle cycle = channel.getQuotaResetCycle() != null
                ? channel.getQuotaResetCycle()
                : SmsQuotaResetCycle.DAILY;
        LocalDate today = LocalDate.now(clock);

        quotaMap.withLock(view -> {
            ChannelQuotaState state = view.get(name);
            if (state == null) {
                return null;
            }
            if (cycle == SmsQuotaResetCycle.DAILY) {
                state.rolloverIfNewDay(today);
                state.dailyCount = Math.max(0L, state.dailyCount - count);
            } else {
                state.totalCount = Math.max(0L, state.totalCount - count);
            }
            return null;
        });
    }

    @Override
    public boolean isQuotaAvailable(SmsChannel channel) {
        if (channel == null || channel.getName() == null || channel.getMaxQuota() <= 0) {
            return true;
        }
        return getUsedQuota(channel) < channel.getMaxQuota();
    }

    @Override
    public long getUsedQuota(String channelName, SmsQuotaResetCycle cycle) {
        if (channelName == null || channelName.isBlank()) {
            return 0L;
        }
        String name = channelName.trim();
        SmsQuotaResetCycle effectiveCycle = cycle != null ? cycle : SmsQuotaResetCycle.DAILY;
        LocalDate today = LocalDate.now(clock);

        return quotaMap.withLock(view -> {
            ChannelQuotaState state = view.get(name);
            if (state == null) {
                return 0L;
            }
            if (effectiveCycle == SmsQuotaResetCycle.DAILY) {
                state.rolloverIfNewDay(today);
                return state.dailyCount;
            }
            return state.totalCount;
        });
    }

    @Override
    public void resetQuota(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return;
        }
        quotaMap.remove(channelName.trim());
        log.info("[InMemorySmsQuotaGuard] 已重置渠道 [{}] 配额计数", channelName.trim());
    }

    @Override
    public void resetAll() {
        quotaMap.clear();
        log.info("[InMemorySmsQuotaGuard] 已重置所有渠道配额计数");
    }

    private static final class ChannelQuotaState {
        private LocalDate dailyDate;
        private long dailyCount;
        private long totalCount;

        private ChannelQuotaState(LocalDate initialDate) {
            this.dailyDate = initialDate;
        }

        private void rolloverIfNewDay(LocalDate today) {
            if (!today.equals(dailyDate)) {
                dailyDate = today;
                dailyCount = 0L;
            }
        }
    }
}
