package io.github.atengk.boot.ai.ratelimit;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.properties.AtengAiRateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单机并发无锁滑动窗口限流器单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
class DefaultAiRateLimiterTest {

    @Test
    @DisplayName("RPS 滑动窗口精准拦截超出请求并支持时间滚动平滑恢复")
    void testRpsSlidingWindowLimitingAndRollover() throws InterruptedException {
        AtengAiRateLimitProperties props = new AtengAiRateLimitProperties();
        props.setDefaultRps(3.0); // 3 req/sec

        DefaultAiRateLimiter limiter = new DefaultAiRateLimiter(props);

        // 第 1~3 次请求成功
        assertThat(limiter.tryAcquire("tenant-a", "gpt-4o", 10)).isTrue();
        assertThat(limiter.tryAcquire("tenant-a", "gpt-4o", 10)).isTrue();
        assertThat(limiter.tryAcquire("tenant-a", "gpt-4o", 10)).isTrue();

        // 第 4 次请求因超出 RPS 触发限流
        assertThat(limiter.tryAcquire("tenant-a", "gpt-4o", 10)).isFalse();

        // 验证 acquire() 抛出语义化业务异常 AI_RATE_LIMIT_EXCEEDED
        assertThatThrownBy(() -> limiter.acquire("tenant-a", "gpt-4o", 10))
                .isInstanceOf(AiException.class)
                .satisfies(ex -> assertThat(((AiException) ex).getCode()).isEqualTo(AiResultCode.AI_RATE_LIMIT_EXCEEDED.getCode()));

        // 等待滑动窗口滚动过期 (超过 1000ms)
        Thread.sleep(1100);

        // 窗口滚动后容量恢复
        assertThat(limiter.tryAcquire("tenant-a", "gpt-4o", 10)).isTrue();
    }

    @Test
    @DisplayName("TPM 滑动窗口精准累计预估 Token 预算并触发拦截")
    void testTpmTokenBudgetTracking() {
        AtengAiRateLimitProperties props = new AtengAiRateLimitProperties();
        props.setDefaultTpm(1000L); // 1000 tokens/min

        DefaultAiRateLimiter limiter = new DefaultAiRateLimiter(props);

        // 消耗 600 tokens 成功
        assertThat(limiter.tryAcquire("tenant-b", "qwen-plus", 600)).isTrue();
        // 消耗 350 tokens 成功 (累计 950)
        assertThat(limiter.tryAcquire("tenant-b", "qwen-plus", 350)).isTrue();
        // 再次尝试消耗 100 tokens 失败 (950 + 100 = 1050 > 1000)
        assertThat(limiter.tryAcquire("tenant-b", "qwen-plus", 100)).isFalse();
        // 但消耗 50 tokens 成功 (950 + 50 = 1000 <= 1000)
        assertThat(limiter.tryAcquire("tenant-b", "qwen-plus", 50)).isTrue();
        // 耗尽后再次尝试消耗 1 token 失败
        assertThat(limiter.tryAcquire("tenant-b", "qwen-plus", 1)).isFalse();
    }

    @Test
    @DisplayName("高并发无锁 CAS 竞争安全性校验：并发 50 线程竞争 RPS 限制绝对防超发")
    void testConcurrentSafetyWithoutOverAllocation() throws InterruptedException {
        AtengAiRateLimitProperties props = new AtengAiRateLimitProperties();
        props.setDefaultRps(10.0); // 严格限制 10 次/秒

        DefaultAiRateLimiter limiter = new DefaultAiRateLimiter(props);

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    if (limiter.tryAcquire("tenant-concurrent", "deepseek-v3", 10)) {
                        successCount.incrementAndGet();
                    } else {
                        rejectedCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // 成功获取的次数必须恰好为 10，严禁发生并发超发
        assertThat(successCount.get()).isEqualTo(10);
        assertThat(rejectedCount.get()).isEqualTo(40);
        assertThat(limiter.isDistributed()).isFalse();
    }
}
