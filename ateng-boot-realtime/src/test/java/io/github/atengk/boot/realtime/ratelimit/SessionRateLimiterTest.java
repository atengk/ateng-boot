package io.github.atengk.boot.realtime.ratelimit;

import io.github.atengk.boot.realtime.properties.RealtimeProperties.RateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 会话级无锁滑动窗口限流器单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class SessionRateLimiterTest {

    @Test
    @DisplayName("流量未达阈值时均返回 ALLOWED")
    void testAllowedTraffic() {
        AtomicLong currentTime = new AtomicLong(1000000L); // 1000s 0ms
        SessionRateLimiter limiter = new SessionRateLimiter(5, 3, currentTime::get);

        for (int i = 0; i < 5; i++) {
            assertEquals(RateLimitDecision.ALLOWED, limiter.tryAcquire(), "第 " + (i + 1) + " 次请求应放行");
        }
        assertEquals(0, limiter.getConsecutiveViolations());
    }

    @Test
    @DisplayName("流量超限时触发 WARNING 警告，持续超限升级为 CLOSE 熔断")
    void testWarningAndEscalationToClose() {
        AtomicLong currentTime = new AtomicLong(1000000L); // 1000s 0ms
        SessionRateLimiter limiter = new SessionRateLimiter(5, 3, currentTime::get);

        // 5 次正常放行
        for (int i = 0; i < 5; i++) {
            assertEquals(RateLimitDecision.ALLOWED, limiter.tryAcquire());
        }

        // 第 6 次超限 (第 1 次违规) -> WARNING
        assertEquals(RateLimitDecision.WARNING, limiter.tryAcquire());
        assertEquals(1, limiter.getConsecutiveViolations());

        // 第 7 次超限 (第 2 次违规) -> WARNING
        assertEquals(RateLimitDecision.WARNING, limiter.tryAcquire());
        assertEquals(2, limiter.getConsecutiveViolations());

        // 第 8 次超限 (第 3 次违规，达到阈值 3) -> CLOSE
        assertEquals(RateLimitDecision.CLOSE, limiter.tryAcquire());
        assertEquals(3, limiter.getConsecutiveViolations());
    }

    @Test
    @DisplayName("下一秒流量恢复正常后连续违规计数自动清零")
    void testRecoveryInNextWindow() {
        AtomicLong currentTime = new AtomicLong(1000500L); // 1000s 500ms
        SessionRateLimiter limiter = new SessionRateLimiter(5, 3, currentTime::get);

        // 打满并违规 2 次
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire();
        }
        assertEquals(RateLimitDecision.WARNING, limiter.tryAcquire());
        assertEquals(RateLimitDecision.WARNING, limiter.tryAcquire());
        assertEquals(2, limiter.getConsecutiveViolations());

        // 推进到两秒后 (1002s 0ms)，前序历史已完全衰减
        currentTime.set(1002000L);

        // 重新请求应放行并重置违规计数
        assertEquals(RateLimitDecision.ALLOWED, limiter.tryAcquire());
        assertEquals(0, limiter.getConsecutiveViolations());
    }

    @Test
    @DisplayName("阈值设为 0 时仅返回 WARNING 不触发 CLOSE 熔断")
    void testZeroConsecutiveViolationsMeansNoClose() {
        AtomicLong currentTime = new AtomicLong(1000000L);
        // consecutiveViolationsBeforeClose = 0
        SessionRateLimiter limiter = new SessionRateLimiter(2, 0, currentTime::get);

        assertEquals(RateLimitDecision.ALLOWED, limiter.tryAcquire());
        assertEquals(RateLimitDecision.ALLOWED, limiter.tryAcquire());

        // 后续多次违规均为 WARNING
        for (int i = 0; i < 10; i++) {
            assertEquals(RateLimitDecision.WARNING, limiter.tryAcquire());
        }
    }

    @Test
    @DisplayName("通过 RateLimitProperties 配置对象正确初始化")
    void testInitWithProperties() {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(true);
        props.setMaxRequestsPerSecond(30);
        props.setConsecutiveViolationsBeforeClose(5);

        SessionRateLimiter limiter = new SessionRateLimiter(props);
        assertEquals(30, limiter.getMaxRequestsPerSecond());
        assertEquals(5, limiter.getConsecutiveViolationsBeforeClose());
    }
}
