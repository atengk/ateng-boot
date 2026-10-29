package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RetryUtil 单元测试
 * 覆盖重试成功、耗尽异常抛出、异常谓词过滤、Equal Jitter 抖动打散、onRetry 监听器及 Fallback 降级兜底
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
    @DisplayName("Equal Jitter 抖动打散配置与执行")
    void shouldSupportEqualJitter() {
        AtomicInteger count = new AtomicInteger();
        RetryUtil.RetryConfig config = RetryUtil.RetryConfig.builder()
                .maxAttempts(3)
                .delay(Duration.ofMillis(20))
                .multiplier(2.0)
                .jitter(true)
                .build();

        assertTrue(config.jitter(), "jitter 开关应处于激活状态");

        String result = RetryUtil.execute(() -> {
            if (count.incrementAndGet() < 3) {
                throw new IllegalStateException("抖动重试测试");
            }
            return "jitter-success";
        }, config);

        assertEquals("jitter-success", result);
        assertEquals(3, count.get());
    }

    @Test
    @DisplayName("onRetry 监听器回调：在休眠前触发并准确接收序号与异常")
    void shouldTriggerOnRetryListenerBeforeSleep() {
        AtomicInteger count = new AtomicInteger();
        List<Integer> attemptHistory = new ArrayList<>();
        List<String> exceptionHistory = new ArrayList<>();

        RetryUtil.RetryConfig config = RetryUtil.RetryConfig.builder()
                .maxAttempts(3)
                .delay(Duration.ofMillis(10))
                .onRetry((attempt, error) -> {
                    attemptHistory.add(attempt);
                    exceptionHistory.add(error.getMessage());
                })
                .build();

        String result = RetryUtil.execute(() -> {
            if (count.incrementAndGet() < 3) {
                throw new RuntimeException("err-" + count.get());
            }
            return "success-with-listener";
        }, config);

        assertEquals("success-with-listener", result);
        assertEquals(3, count.get());
        assertEquals(List.of(1, 2), attemptHistory, "监听器应分别在第 1 次和第 2 次失败后休眠前触发");
        assertEquals(List.of("err-1", "err-2"), exceptionHistory);
    }

    @Test
    @DisplayName("onRetry 监听器异常受控隔离：监听器抛出异常不中断主重试循环")
    void shouldIsolateExceptionThrownFromOnRetryListener() {
        AtomicInteger count = new AtomicInteger();
        RetryUtil.RetryConfig config = RetryUtil.RetryConfig.builder()
                .maxAttempts(3)
                .delay(Duration.ofMillis(10))
                .onRetry((attempt, error) -> {
                    throw new RuntimeException("监听器内部度量上报偶发错误");
                })
                .build();

        String result = RetryUtil.execute(() -> {
            if (count.incrementAndGet() < 2) {
                throw new IllegalStateException("业务超时");
            }
            return "ok-listener-err-isolated";
        }, config);

        assertEquals("ok-listener-err-isolated", result);
        assertEquals(2, count.get());
    }

    @Test
    @DisplayName("Fallback 降级：重试耗尽时返回指定兜底值")
    void shouldReturnFallbackWhenAttemptsExhausted() {
        AtomicInteger count = new AtomicInteger();
        RetryUtil.RetryConfig config = RetryUtil.RetryConfig.builder()
                .maxAttempts(3)
                .delay(Duration.ofMillis(10))
                .build();

        String result = RetryUtil.execute(
                () -> {
                    count.incrementAndGet();
                    throw new RuntimeException("下游服务熔断");
                },
                config,
                ex -> "fallback-" + ex.getMessage()
        );

        assertEquals("fallback-下游服务熔断", result, "重试耗尽后应执行 Fallback 回调并返回兜底结果");
        assertEquals(3, count.get());
    }

    @Test
    @DisplayName("Fallback 降级：默认配置下的快捷重载")
    void shouldSupportFallbackWithDefaultConfig() {
        AtomicInteger count = new AtomicInteger();
        String result = RetryUtil.execute(
                () -> {
                    count.incrementAndGet();
                    throw new RuntimeException("网络波动");
                },
                ex -> "default-fallback"
        );

        assertEquals("default-fallback", result);
        assertEquals(3, count.get());
    }

    @Test
    @DisplayName("Fallback 降级：不满足重试谓词时亦可触发降级返回")
    void shouldReturnFallbackWhenExceptionNotMatchingPredicate() {
        AtomicInteger count = new AtomicInteger();
        RetryUtil.RetryConfig config = RetryUtil.RetryConfig.builder()
                .maxAttempts(5)
                .delay(Duration.ofMillis(10))
                .retryPredicate(ex -> ex instanceof IOException)
                .build();

        String result = RetryUtil.execute(
                () -> {
                    count.incrementAndGet();
                    throw new IllegalArgumentException("参数错误不可重试");
                },
                config,
                ex -> "fallback-on-illegal-argument"
        );

        assertEquals("fallback-on-illegal-argument", result);
        assertEquals(1, count.get(), "非重试异常直接触发降级，仅尝试 1 次");
    }

    @Test
    @DisplayName("Runnable Fallback 降级：重试耗尽时触发兜底 Consumer")
    void shouldTriggerRunnableFallbackWhenExhausted() {
        AtomicInteger count = new AtomicInteger();
        AtomicBoolean fallbackTriggered = new AtomicBoolean(false);

        RetryUtil.RetryConfig config = RetryUtil.RetryConfig.builder()
                .maxAttempts(3)
                .delay(Duration.ofMillis(10))
                .build();

        RetryUtil.executeAction(
                () -> {
                    count.incrementAndGet();
                    throw new RuntimeException("任务失败");
                },
                config,
                ex -> fallbackTriggered.set(true)
        );

        assertEquals(3, count.get());
        assertTrue(fallbackTriggered.get(), "Runnable 重试耗尽应触发 Fallback Consumer");

        // 默认配置重载测试
        AtomicBoolean defaultFallbackTriggered = new AtomicBoolean(false);
        RetryUtil.executeAction(
                () -> {
                    throw new RuntimeException("默认配置任务失败");
                },
                ex -> defaultFallbackTriggered.set(true)
        );
        assertTrue(defaultFallbackTriggered.get());
    }

    @Test
    @DisplayName("向后兼容：既有 5 参数构造器与向后兼容性验证")
    void shouldPreserveBackwardCompatibility() {
        // 验证既有 5 参数构造函数可正常初始化
        RetryUtil.RetryConfig config = new RetryUtil.RetryConfig(
                3,
                Duration.ofMillis(100),
                2.0,
                Duration.ofMillis(1000),
                ex -> true
        );

        assertEquals(3, config.maxAttempts());
        assertEquals(Duration.ofMillis(100), config.delay());
        assertEquals(2.0, config.multiplier());
        assertEquals(Duration.ofMillis(1000), config.maxDelay());
        assertFalse(config.jitter(), "默认 jitter 应为 false");
        assertNull(config.onRetry(), "默认 onRetry 监听器应为 null");
        assertNull(config.delayFunction(), "默认 delayFunction 应为 null");

        // 验证 7 参数构造函数兼容
        RetryUtil.RetryConfig config7 = new RetryUtil.RetryConfig(
                3,
                Duration.ofMillis(100),
                2.0,
                Duration.ofMillis(1000),
                ex -> true,
                true,
                null
        );
        assertTrue(config7.jitter());
        assertNull(config7.delayFunction());
    }

    @Test
    @DisplayName("delayFunction：动态异常延迟计算并覆盖休眠等待")
    void shouldApplyDynamicDelayFunctionWhenProvided() {
        AtomicInteger count = new AtomicInteger();
        long start = System.currentTimeMillis();

        class CustomDynamicDelayException extends RuntimeException {
            final Duration retryAfter;
            CustomDynamicDelayException(Duration retryAfter) {
                this.retryAfter = retryAfter;
            }
        }

        RetryUtil.RetryConfig config = RetryUtil.RetryConfig.builder()
                .maxAttempts(2)
                .delay(Duration.ofSeconds(10)) // 默认非常大
                .delayFunction(ex -> {
                    if (ex instanceof CustomDynamicDelayException cde) {
                        return cde.retryAfter;
                    }
                    return null;
                })
                .build();

        String result = RetryUtil.execute(() -> {
            if (count.incrementAndGet() < 2) {
                throw new CustomDynamicDelayException(Duration.ofMillis(30));
            }
            return "dynamic-delay-ok";
        }, config);

        long elapsed = System.currentTimeMillis() - start;
        assertEquals("dynamic-delay-ok", result);
        assertEquals(2, count.get());
        assertTrue(elapsed >= 25 && elapsed < 3000, "实际等待耗时应基于 delayFunction 动态计算（~30ms），而非默认的 10s");
    }

    @Test
    @DisplayName("delayFunction 异常隔离：当 delayFunction 内部抛出异常时回退默认退避")
    void shouldFallbackToDefaultDelayWhenDelayFunctionThrows() {
        AtomicInteger count = new AtomicInteger();
        RetryUtil.RetryConfig config = RetryUtil.RetryConfig.builder()
                .maxAttempts(2)
                .delay(Duration.ofMillis(10))
                .delayFunction(ex -> {
                    throw new RuntimeException("动态计算函数异常");
                })
                .build();

        String result = RetryUtil.execute(() -> {
            if (count.incrementAndGet() < 2) {
                throw new RuntimeException("业务错误");
            }
            return "fallback-delay-ok";
        }, config);

        assertEquals("fallback-delay-ok", result);
        assertEquals(2, count.get());
    }

    @Test
    @DisplayName("delayFunction 防御性上限拦截：动态延迟超限时强制受 maxDelay 约束截断")
    void shouldClampDynamicDelayWithMaxDelay() {
        AtomicInteger count = new AtomicInteger();
        long startTime = System.currentTimeMillis();
        RetryUtil.RetryConfig config = RetryUtil.RetryConfig.builder()
                .maxAttempts(2)
                .delay(Duration.ofMillis(10))
                .maxDelay(Duration.ofMillis(30))
                .delayFunction(ex -> Duration.ofHours(1)) // 远端返回 1 小时超大 Retry-After
                .build();

        String result = RetryUtil.execute(() -> {
            if (count.incrementAndGet() < 2) {
                throw new RuntimeException("429 Too Many Requests");
            }
            return "clamped-ok";
        }, config);

        long elapsed = System.currentTimeMillis() - startTime;
        assertEquals("clamped-ok", result);
        assertEquals(2, count.get());
        assertTrue(elapsed < 2000, "实际休眠时长必须被 maxDelay 强行截断，绝不能休眠 1 小时");
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
