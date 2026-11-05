package io.github.atengk.boot.ai.ratelimit;

import io.github.atengk.boot.ai.properties.AtengAiRateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单机并发无锁滑动窗口限流器 (In-Memory Concurrent Lock-Free Sliding Window Rate Limiter)
 * <p>
 * 基于 {@link AtomicReference} 与 CAS 乐观无锁自旋机制，精细化切片滑动窗口：
 * <ul>
 *   <li><b>RPS 维度</b>：1 秒窗口细分为 10 个 100ms 纳秒级切片，滑动平滑消除毛刺突发；</li>
 *   <li><b>TPM 维度</b>：1 分钟窗口细分为 60 个 1s 滚动切片，精准追踪 Token 预算消耗；</li>
 *   <li><b>零锁高吞吐</b>：全程无 synchronized、无排他锁阻塞，毫秒级自旋 CAS 吞吐无损。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class DefaultAiRateLimiter implements AiRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(DefaultAiRateLimiter.class);

    private final AtengAiRateLimitProperties properties;
    private final Map<String, LockFreeSlidingWindowTracker> trackers = new ConcurrentHashMap<>();

    public DefaultAiRateLimiter(AtengAiRateLimitProperties properties) {
        this.properties = properties != null ? properties : new AtengAiRateLimitProperties();
    }

    @Override
    public boolean tryAcquire(String tenantId, String modelName, int estimatedTokens) {
        if (!properties.isEnabled()) {
            return true;
        }

        RateLimitRuleResolver.ResolvedRule rule = RateLimitRuleResolver.resolve(properties, tenantId, modelName);
        if (!rule.hasLimit()) {
            return true;
        }

        String trackerKey = buildTrackerKey(tenantId, modelName);
        long now = System.currentTimeMillis();

        LockFreeSlidingWindowTracker tracker = trackers.computeIfAbsent(trackerKey,
                k -> new LockFreeSlidingWindowTracker(now));

        boolean acquired = tracker.tryAcquire(now, estimatedTokens, rule.rps(), rule.tpm());
        if (!acquired) {
            log.warn("[AI-RATE-LIMIT] 触发微观突发流量限流 [target={}, rpsLimit={}, tpmLimit={}, currentRps={}, currentTpm={}]",
                    trackerKey, rule.rps(), rule.tpm(), tracker.getCurrentRps(now), tracker.getCurrentTpm(now));
        }
        return acquired;
    }

    @Override
    public boolean isDistributed() {
        return false;
    }

    /**
     * 获取指定维度的当前滑动窗口 RPS 计数 (用于可观测性与测试)
     */
    public long getCurrentRps(String tenantId, String modelName) {
        String key = buildTrackerKey(tenantId, modelName);
        LockFreeSlidingWindowTracker tracker = trackers.get(key);
        return tracker != null ? tracker.getCurrentRps(System.currentTimeMillis()) : 0;
    }

    /**
     * 获取指定维度的当前滑动窗口 TPM Token 消耗计数 (用于可观测性与测试)
     */
    public long getCurrentTpm(String tenantId, String modelName) {
        String key = buildTrackerKey(tenantId, modelName);
        LockFreeSlidingWindowTracker tracker = trackers.get(key);
        return tracker != null ? tracker.getCurrentTpm(System.currentTimeMillis()) : 0;
    }

    /**
     * 重置所有限流追踪器
     */
    public void reset() {
        trackers.clear();
    }

    private String buildTrackerKey(String tenantId, String modelName) {
        String cleanTenant = StringUtils.hasText(tenantId) ? tenantId.trim() : "global";
        String cleanModel = StringUtils.hasText(modelName) ? modelName.trim() : "default";
        return cleanTenant + ":" + cleanModel;
    }

    /**
     * 无锁并发滑动窗口计数器实现
     */
    static class LockFreeSlidingWindowTracker {

        private static final int RPS_SLICES = 10;
        private static final long RPS_SLICE_MS = 100L;

        private static final int TPM_SLICES = 60;
        private static final long TPM_SLICE_MS = 1000L;

        private final AtomicReference<WindowSnapshot> snapshotRef;

        public LockFreeSlidingWindowTracker(long initialTimeMs) {
            long rpsSlot = initialTimeMs / RPS_SLICE_MS;
            long tpmSlot = initialTimeMs / TPM_SLICE_MS;
            this.snapshotRef = new AtomicReference<>(new WindowSnapshot(rpsSlot, new long[RPS_SLICES], tpmSlot, new long[TPM_SLICES]));
        }

        public boolean tryAcquire(long nowMs, int tokens, double rpsLimit, long tpmLimit) {
            int tokenAcquire = Math.max(1, tokens);

            while (true) {
                WindowSnapshot current = snapshotRef.get();
                WindowSnapshot rolled = current.advanceTo(nowMs);

                long currentRps = rolled.sumRps();
                long currentTpm = rolled.sumTpm();

                // 1. 判定限流阈值
                if (rpsLimit > 0 && currentRps + 1 > rpsLimit) {
                    snapshotRef.compareAndSet(current, rolled);
                    return false;
                }
                if (tpmLimit > 0 && currentTpm + tokenAcquire > tpmLimit) {
                    snapshotRef.compareAndSet(current, rolled);
                    return false;
                }

                // 2. CAS 提交新计数
                WindowSnapshot next = rolled.increment(nowMs, 1, tokenAcquire);
                if (snapshotRef.compareAndSet(current, next)) {
                    return true;
                }
                // CAS 发生并发冲突，自旋重试
            }
        }

        public long getCurrentRps(long nowMs) {
            WindowSnapshot current = snapshotRef.get();
            return current.advanceTo(nowMs).sumRps();
        }

        public long getCurrentTpm(long nowMs) {
            WindowSnapshot current = snapshotRef.get();
            return current.advanceTo(nowMs).sumTpm();
        }

        /**
         * 不可变窗口快照
         */
        static class WindowSnapshot {
            final long rpsBaseSlot;
            final long[] rpsCounts;
            final long tpmBaseSlot;
            final long[] tpmCounts;

            WindowSnapshot(long rpsBaseSlot, long[] rpsCounts, long tpmBaseSlot, long[] tpmCounts) {
                this.rpsBaseSlot = rpsBaseSlot;
                this.rpsCounts = rpsCounts;
                this.tpmBaseSlot = tpmBaseSlot;
                this.tpmCounts = tpmCounts;
            }

            WindowSnapshot advanceTo(long nowMs) {
                long currentRpsSlot = nowMs / RPS_SLICE_MS;
                long currentTpmSlot = nowMs / TPM_SLICE_MS;

                long[] newRps = rpsCounts;
                long rpsDiff = currentRpsSlot - rpsBaseSlot;
                if (rpsDiff > 0) {
                    newRps = new long[RPS_SLICES];
                    if (rpsDiff < RPS_SLICES) {
                        System.arraycopy(rpsCounts, (int) rpsDiff, newRps, 0, (int) (RPS_SLICES - rpsDiff));
                    }
                } else {
                    currentRpsSlot = rpsBaseSlot;
                }

                long[] newTpm = tpmCounts;
                long tpmDiff = currentTpmSlot - tpmBaseSlot;
                if (tpmDiff > 0) {
                    newTpm = new long[TPM_SLICES];
                    if (tpmDiff < TPM_SLICES) {
                        System.arraycopy(tpmCounts, (int) tpmDiff, newTpm, 0, (int) (TPM_SLICES - tpmDiff));
                    }
                } else {
                    currentTpmSlot = tpmBaseSlot;
                }

                if (newRps == rpsCounts && newTpm == tpmCounts) {
                    return this;
                }

                return new WindowSnapshot(currentRpsSlot, newRps, currentTpmSlot, newTpm);
            }

            long sumRps() {
                long sum = 0;
                for (long c : rpsCounts) {
                    sum += c;
                }
                return sum;
            }

            long sumTpm() {
                long sum = 0;
                for (long c : tpmCounts) {
                    sum += c;
                }
                return sum;
            }

            WindowSnapshot increment(long nowMs, long rpsIncr, long tpmIncr) {
                WindowSnapshot ready = advanceTo(nowMs);
                long[] nextRps = ready.rpsCounts.clone();
                long[] nextTpm = ready.tpmCounts.clone();

                nextRps[RPS_SLICES - 1] += rpsIncr;
                nextTpm[TPM_SLICES - 1] += tpmIncr;

                return new WindowSnapshot(ready.rpsBaseSlot, nextRps, ready.tpmBaseSlot, nextTpm);
            }
        }
    }
}
