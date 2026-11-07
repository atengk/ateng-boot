package io.github.atengk.boot.sms.guard;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.sms.properties.SmsGuardProperties;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 短信渠道健康度短时熔断器
 * 针对各短信服务商物理渠道维护连续失败计数与三态熔断自愈生命周期（CLOSED、OPEN、HALF_OPEN）
 * 依托无锁原子状态与 ConcurrentHashMap 保障高并发场景下的极速判断与线程安全
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Slf4j
public class SmsChannelCircuitBreaker {

    /**
     * 熔断器生命周期三态
     */
    public enum State {
        /**
         * 闭路状态：渠道健康，正常通行
         */
        CLOSED,

        /**
         * 开路状态：渠道熔断，短路阻断网络请求
         */
        OPEN,

        /**
         * 半开探测状态：冷却时间已过，放行试探请求以探测服务可用性
         */
        HALF_OPEN
    }

    private final SmsGuardProperties properties;
    private final ConcurrentHashMap<String, ChannelHealth> channelHealthMap = new ConcurrentHashMap<>();

    public SmsChannelCircuitBreaker(SmsGuardProperties properties) {
        this.properties = properties != null ? properties : new SmsGuardProperties();
    }

    public SmsChannelCircuitBreaker() {
        this(new SmsGuardProperties());
    }

    /**
     * 判断指定渠道当前是否允许发起网络发信请求
     *
     * @param channelName 渠道名称
     * @return true 允许请求，false 处于开路熔断状态应阻断或短路切换
     */
    public boolean allowRequest(String channelName) {
        if (!properties.isCircuitBreakerEnabled()) {
            return true;
        }
        BusinessAssert.hasText(channelName, "渠道名称不能为空");
        ChannelHealth health = channelHealthMap.computeIfAbsent(channelName, k -> new ChannelHealth());
        long openDurationMillis = getOpenDurationMillis();
        return health.allowRequest(true, openDurationMillis);
    }

    /**
     * 记录指定渠道调用物理发信成功
     *
     * @param channelName 渠道名称
     */
    public void recordSuccess(String channelName) {
        if (!properties.isCircuitBreakerEnabled()) {
            return;
        }
        BusinessAssert.hasText(channelName, "渠道名称不能为空");
        ChannelHealth health = channelHealthMap.get(channelName);
        if (health != null) {
            State previousState = health.getState(getOpenDurationMillis());
            health.recordSuccess();
            if (previousState == State.HALF_OPEN || previousState == State.OPEN) {
                log.info("[SmsChannelCircuitBreaker] 渠道 [{}] 探测发信成功，熔断状态自愈复位: {} -> CLOSED",
                        channelName, previousState);
            }
        }
    }

    /**
     * 记录指定渠道调用物理发信失败
     *
     * @param channelName 渠道名称
     */
    public void recordFailure(String channelName) {
        if (!properties.isCircuitBreakerEnabled()) {
            return;
        }
        BusinessAssert.hasText(channelName, "渠道名称不能为空");
        ChannelHealth health = channelHealthMap.computeIfAbsent(channelName, k -> new ChannelHealth());
        int threshold = Math.max(1, properties.getCircuitBreakerFailureThreshold());
        long openDurationMillis = getOpenDurationMillis();

        State previousState = health.getState(openDurationMillis);
        health.recordFailure(threshold);
        State currentState = health.getState(openDurationMillis);

        if (previousState != State.OPEN && currentState == State.OPEN) {
            log.warn("[SmsChannelCircuitBreaker] 渠道 [{}] 连续发信失败达阈值 ({}/{})，触发短时开路熔断 (OPEN)，冷却时长: {} 秒",
                    channelName, health.getConsecutiveFailures(), threshold, openDurationMillis / 1000);
        }
    }

    /**
     * 查询指定渠道当前是否处于开路熔断态 (OPEN)
     *
     * @param channelName 渠道名称
     * @return true 处于开路熔断态
     */
    public boolean isOpen(String channelName) {
        if (!properties.isCircuitBreakerEnabled()) {
            return false;
        }
        BusinessAssert.hasText(channelName, "渠道名称不能为空");
        ChannelHealth health = channelHealthMap.get(channelName);
        if (health == null) {
            return false;
        }
        return health.isOpen(true, getOpenDurationMillis());
    }

    /**
     * 获取指定渠道当前熔断状态
     *
     * @param channelName 渠道名称
     * @return 当前状态 (CLOSED / OPEN / HALF_OPEN)
     */
    public State getState(String channelName) {
        if (!properties.isCircuitBreakerEnabled()) {
            return State.CLOSED;
        }
        BusinessAssert.hasText(channelName, "渠道名称不能为空");
        ChannelHealth health = channelHealthMap.get(channelName);
        if (health == null) {
            return State.CLOSED;
        }
        return health.getState(getOpenDurationMillis());
    }

    /**
     * 手动重置指定渠道熔断状态为 CLOSED
     *
     * @param channelName 渠道名称
     */
    public void reset(String channelName) {
        BusinessAssert.hasText(channelName, "渠道名称不能为空");
        ChannelHealth health = channelHealthMap.get(channelName);
        if (health != null) {
            health.reset();
            log.info("[SmsChannelCircuitBreaker] 渠道 [{}] 熔断状态已被手动重置为 CLOSED", channelName);
        }
    }

    /**
     * 手动重置所有渠道熔断状态
     */
    public void resetAll() {
        for (Map.Entry<String, ChannelHealth> entry : channelHealthMap.entrySet()) {
            entry.getValue().reset();
        }
        log.info("[SmsChannelCircuitBreaker] 所有短信渠道熔断状态已被全部手动重置为 CLOSED");
    }

    /**
     * 获取指定渠道当前连续失败次数
     *
     * @param channelName 渠道名称
     * @return 连续失败次数
     */
    public int getConsecutiveFailures(String channelName) {
        ChannelHealth health = channelHealthMap.get(channelName);
        return health != null ? health.getConsecutiveFailures() : 0;
    }

    private long getOpenDurationMillis() {
        Duration duration = properties.getCircuitBreakerOpenDuration();
        return duration != null ? duration.toMillis() : 60_000L;
    }

    /**
     * 单渠道健康状态模型（无锁并发）
     */
    private static class ChannelHealth {
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicLong lastOpenTimestamp = new AtomicLong(0L);
        private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

        State getState(long openDurationMillis) {
            State current = state.get();
            if (current == State.OPEN) {
                long elapsed = System.currentTimeMillis() - lastOpenTimestamp.get();
                if (elapsed >= openDurationMillis) {
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        return State.HALF_OPEN;
                    }
                    return state.get();
                }
            }
            return current;
        }

        boolean allowRequest(boolean enabled, long openDurationMillis) {
            if (!enabled) {
                return true;
            }
            State current = getState(openDurationMillis);
            return current != State.OPEN;
        }

        void recordSuccess() {
            consecutiveFailures.set(0);
            lastOpenTimestamp.set(0L);
            state.set(State.CLOSED);
        }

        void recordFailure(int threshold) {
            State current = state.get();
            if (current == State.HALF_OPEN) {
                // 半开探测失败，重新进入开路熔断并刷新开路时间戳
                lastOpenTimestamp.set(System.currentTimeMillis());
                state.set(State.OPEN);
                return;
            }

            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= threshold) {
                lastOpenTimestamp.set(System.currentTimeMillis());
                state.set(State.OPEN);
            }
        }

        boolean isOpen(boolean enabled, long openDurationMillis) {
            if (!enabled) {
                return false;
            }
            return getState(openDurationMillis) == State.OPEN;
        }

        void reset() {
            consecutiveFailures.set(0);
            lastOpenTimestamp.set(0L);
            state.set(State.CLOSED);
        }

        int getConsecutiveFailures() {
            return consecutiveFailures.get();
        }
    }
}
