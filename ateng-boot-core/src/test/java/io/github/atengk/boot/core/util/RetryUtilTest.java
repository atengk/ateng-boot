package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RetryUtil 单元测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("RetryUtil 重试执行器测试")
class RetryUtilTest {

    @Test
    @DisplayName("首次执行成功无需重试")
    void shouldReturnDirectlyWhenFirstAttemptSuccess() {
        AtomicInteger count = new AtomicInteger();
        String result = RetryUtil.execute(() -> {
            count.incrementAndGet();
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(1, count.get());
    }

    @Test
    @DisplayName("前置失败后在第 2 次重试成功")
    void shouldSucceedOnSecondAttempt() {
        AtomicInteger count = new AtomicInteger();
        RetryUtil.RetryConfig config = RetryUtil.RetryConfig.builder()
                .maxAttempts(3)
                .delay(Duration.ofMillis(10))
                .multiplier(1.5)
                .build();

        String result = RetryUtil.execute(() -> {
            if (count.incrementAndGet() < 2) {
                throw new IllegalStateException("模拟偶发超时");
            }
            return "recovered";
        }, config);

        assertEquals("recovered", result);
        assertEquals(2, count.get());
    }

    @Test
    @DisplayName("重试耗尽时抛出最后的根异常")
    void shouldThrowLastExceptionWhenAttemptsExhausted() {
        AtomicInteger count = new AtomicInteger();
        RetryUtil.RetryConfig config = RetryUtil.RetryConfig.builder()
                .maxAttempts(3)
                .delay(Duration.ofMillis(10))
                .build();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                RetryUtil.execute(() -> {
                    count.incrementAndGet();
                    throw new IllegalStateException("持续故障");
                }, config)
        );

        assertEquals("持续故障", ex.getMessage());
        assertEquals(3, count.get());
    }

    @Test
    @DisplayName("谓词过滤：遇到不符合重试条件的异常立即熔断")
    void shouldFastFailWhenExceptionNotMatchingPredicate() {
        AtomicInteger count = new AtomicInteger();
        RetryUtil.RetryConfig config = RetryUtil.RetryConfig.builder()
                .maxAttempts(5)
                .delay(Duration.ofMillis(10))
                .retryPredicate(ex -> ex instanceof IOException) // 仅重试 IO 异常
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                RetryUtil.execute(() -> {
                    count.incrementAndGet();
                    throw new IllegalArgumentException("参数校验错误不可重试");
                }, config)
        );

        assertEquals("参数校验错误不可重试", ex.getMessage());
        assertEquals(1, count.get(), "非重试异常应立即熔断，仅执行 1 次");
    }

    @Test
    @DisplayName("Runnable 重试执行")
    void shouldSupportRunnableTask() {
        AtomicInteger count = new AtomicInteger();
        RetryUtil.RetryConfig config = RetryUtil.RetryConfig.builder()
                .maxAttempts(3)
                .delay(Duration.ofMillis(10))
                .build();

        RetryUtil.execute(() -> {
            if (count.incrementAndGet() < 2) {
                throw new RuntimeException("fail");
            }
        }, config);

        assertEquals(2, count.get());
    }

    @Test
    @DisplayName("RetryConfig 紧凑构造对非法入参实施强类型防卫拦截")
    void shouldDefendAgainstInvalidParametersInRecordConstructor() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryUtil.RetryConfig(0, Duration.ofMillis(100), 2.0, Duration.ofMillis(1000), ex -> true)
        );
        assertThrows(IllegalArgumentException.class, () ->
                new RetryUtil.RetryConfig(3, Duration.ofMillis(-10), 2.0, Duration.ofMillis(1000), ex -> true)
        );
        assertThrows(IllegalArgumentException.class, () ->
                new RetryUtil.RetryConfig(3, Duration.ofMillis(100), 0.8, Duration.ofMillis(1000), ex -> true)
        );
        assertThrows(IllegalArgumentException.class, () ->
                new RetryUtil.RetryConfig(3, Duration.ofMillis(100), 2.0, Duration.ofMillis(-50), ex -> true)
        );
        assertThrows(NullPointerException.class, () ->
                new RetryUtil.RetryConfig(3, null, 2.0, Duration.ofMillis(1000), ex -> true)
        );
    }
}
