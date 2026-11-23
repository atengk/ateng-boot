package io.github.atengk.boot.notification;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.exception.NotificationRateLimitException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.limiter.InMemoryNotificationRateLimiter;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.properties.NotificationRateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 协同通知单机内存滑动窗口频控器单元测试与并发对抗性守护
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("协同通知单机内存滑动窗口频控器单元测试")
class InMemoryNotificationRateLimiterTest {

    private NotificationChannel createChannel(String name, Platform platform, Integer permitsPerMinute) {
        return NotificationChannel.builder()
                .name(name)
                .platform(platform)
                .webhook("https://example.com/webhook/" + name)
                .permitsPerMinute(permitsPerMinute)
                .build();
    }

    @Test
    @DisplayName("验证平台基准配额解析与通道自定义配额优先覆盖")
    void testResolvePermitsPerMinute() {
        InMemoryNotificationRateLimiter limiter = new InMemoryNotificationRateLimiter(new NotificationRateLimitProperties());

        // 默认平台基准配额
        NotificationChannel dingTalk = createChannel("dingtalk-bot", Platform.DINGTALK, null);
        NotificationChannel weCom = createChannel("wecom-bot", Platform.WECOM, null);
        NotificationChannel feishu = createChannel("feishu-bot", Platform.FEISHU, null);
        NotificationChannel generic = createChannel("generic-bot", Platform.GENERIC, null);

        assertEquals(20, limiter.resolvePermitsPerMinute(dingTalk));
        assertEquals(20, limiter.resolvePermitsPerMinute(weCom));
        assertEquals(100, limiter.resolvePermitsPerMinute(feishu));
        assertEquals(60, limiter.resolvePermitsPerMinute(generic));

        // 验证单秒突发防卫配额解析
        assertEquals(0, limiter.resolveBurstPermitsPerSecond(dingTalk));
        assertEquals(0, limiter.resolveBurstPermitsPerSecond(weCom));
        assertEquals(5, limiter.resolveBurstPermitsPerSecond(feishu));
        assertEquals(0, limiter.resolveBurstPermitsPerSecond(generic));

        // 通道专属配额优先覆盖
        NotificationChannel customDingTalk = createChannel("custom-dingtalk", Platform.DINGTALK, 5);
        assertEquals(5, limiter.resolvePermitsPerMinute(customDingTalk));

        NotificationChannel customFeishu = createChannel("custom-feishu", Platform.FEISHU, 300);
        assertEquals(300, limiter.resolvePermitsPerMinute(customFeishu));

        // 通道专属单秒突发配额覆盖
        NotificationChannel customBurstChannel = NotificationChannel.builder()
                .name("custom-burst")
                .platform(Platform.DINGTALK)
                .webhook("https://example.com/burst")
                .burstPermitsPerSecond(15)
                .build();
        assertEquals(15, limiter.resolveBurstPermitsPerSecond(customBurstChannel));
    }

    @Test
    @DisplayName("验证配额内顺畅获取许可且配额耗尽后立即失败")
    void testImmediateAcquireWithinPermits() {
        // 创建窗口为 1 秒、配额为 3 的通道频控器
        InMemoryNotificationRateLimiter limiter = new InMemoryNotificationRateLimiter(
                new NotificationRateLimitProperties(), Duration.ofSeconds(1));
        NotificationChannel channel = createChannel("test-channel", Platform.DINGTALK, 3);

        assertEquals(3, limiter.getAvailablePermits(channel));

        // 前 3 次立即获取成功
        assertTrue(limiter.tryAcquire(channel, Duration.ZERO));
        assertEquals(2, limiter.getAvailablePermits(channel));

        assertTrue(limiter.tryAcquire(channel, Duration.ZERO));
        assertEquals(1, limiter.getAvailablePermits(channel));

        assertTrue(limiter.tryAcquire(channel, Duration.ZERO));
        assertEquals(0, limiter.getAvailablePermits(channel));

        // 第 4 次立即返回 false（非阻塞超时）
        assertFalse(limiter.tryAcquire(channel, Duration.ZERO));
        assertEquals(0, limiter.getAvailablePermits(channel));
    }

    @Test
    @DisplayName("验证微退避排队机制：在等待预算充足时成功获取许可")
    void testMicroBackoffQueueingSuccess() {
        // 窗口为 100ms，配额为 2
        InMemoryNotificationRateLimiter limiter = new InMemoryNotificationRateLimiter(
                new NotificationRateLimitProperties(), Duration.ofMillis(100));
        NotificationChannel channel = createChannel("backoff-channel", Platform.DINGTALK, 2);

        // 先耗尽当前 2 个配额
        assertTrue(limiter.tryAcquire(channel, Duration.ZERO));
        assertTrue(limiter.tryAcquire(channel, Duration.ZERO));

        // 第 3 个请求设置 250ms 等待预算（充足的微退避窗口）
        long start = System.currentTimeMillis();
        boolean acquired = limiter.tryAcquire(channel, Duration.ofMillis(250));
        long cost = System.currentTimeMillis() - start;

        assertTrue(acquired);
        // 应当等待了至少 70ms 直至窗口滑出，但绝不会超过 250ms
        assertThat(cost).isGreaterThanOrEqualTo(70L).isLessThan(250L);
    }

    @Test
    @DisplayName("验证微退避等待超时快速失败并抛出受控业务异常")
    void testMicroBackoffTimeoutFastFail() {
        // 窗口为 500ms，配额为 2
        InMemoryNotificationRateLimiter limiter = new InMemoryNotificationRateLimiter(
                new NotificationRateLimitProperties(), Duration.ofMillis(500));
        NotificationChannel channel = createChannel("fastfail-channel", Platform.DINGTALK, 2);

        assertTrue(limiter.tryAcquire(channel, Duration.ZERO));
        assertTrue(limiter.tryAcquire(channel, Duration.ZERO));

        // 第 3 个请求设置 40ms 超时（远小于槽位释放所需的 ~500ms），算法立即快速失败
        long start = System.currentTimeMillis();
        boolean acquired = limiter.tryAcquire(channel, Duration.ofMillis(40));
        long cost = System.currentTimeMillis() - start;

        assertFalse(acquired);
        // 快速失败断言：无需傻等，应在几十毫秒内迅速返回
        assertThat(cost).isLessThan(100L);

        // 验证 acquire 阻塞契约超时抛出 NotificationRateLimitException
        assertThatThrownBy(() -> limiter.acquire(channel, Duration.ofMillis(40)))
                .isInstanceOf(NotificationRateLimitException.class)
                .satisfies(ex -> {
                    NotificationRateLimitException rateLimitEx = (NotificationRateLimitException) ex;
                    assertEquals("fastfail-channel", rateLimitEx.getChannelName());
                    assertEquals(2, rateLimitEx.getPermitsPerMinute());
                    assertEquals(Duration.ofMillis(40), rateLimitEx.getWaitDuration());
                    assertEquals(NotificationResultCode.NOTIFICATION_RATE_LIMIT_EXCEEDED, rateLimitEx.getResultCode());
                    assertThat(rateLimitEx.getMessage()).contains("触发客户端发信频控超限");
                });
    }

    @Test
    @DisplayName("验证多通道频控状态互不干扰与隔离性")
    void testMultiChannelIsolation() {
        InMemoryNotificationRateLimiter limiter = new InMemoryNotificationRateLimiter(
                new NotificationRateLimitProperties(), Duration.ofSeconds(1));

        NotificationChannel channelA = createChannel("channel-a", Platform.DINGTALK, 2);
        NotificationChannel channelB = createChannel("channel-b", Platform.FEISHU, 2);

        // 耗尽 channelA 配额
        assertTrue(limiter.tryAcquire(channelA, Duration.ZERO));
        assertTrue(limiter.tryAcquire(channelA, Duration.ZERO));
        assertFalse(limiter.tryAcquire(channelA, Duration.ZERO));

        // channelB 依然具备完整配额并顺畅获取
        assertEquals(2, limiter.getAvailablePermits(channelB));
        assertTrue(limiter.tryAcquire(channelB, Duration.ZERO));
        assertTrue(limiter.tryAcquire(channelB, Duration.ZERO));
        assertFalse(limiter.tryAcquire(channelB, Duration.ZERO));

        assertEquals(2, limiter.getActiveChannelsCount());
    }

    @Test
    @DisplayName("验证当全局配置 enabled=false 时直接放行")
    void testDisabledRateLimiter() {
        NotificationRateLimitProperties properties = new NotificationRateLimitProperties();
        properties.setEnabled(false);

        InMemoryNotificationRateLimiter limiter = new InMemoryNotificationRateLimiter(properties);
        NotificationChannel channel = createChannel("disabled-channel", Platform.DINGTALK, 1);

        // 配额为 1，但关闭限流时连续调用 10 次均应直接放行
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire(channel, Duration.ZERO));
            assertDoesNotThrow(() -> limiter.acquire(channel));
        }
    }

    @Test
    @DisplayName("验证 reset 与 resetAll 能够清除滑动窗口并恢复初始配额")
    void testReset() {
        InMemoryNotificationRateLimiter limiter = new InMemoryNotificationRateLimiter(
                new NotificationRateLimitProperties(), Duration.ofSeconds(10));
        NotificationChannel channelA = createChannel("channel-reset-a", Platform.DINGTALK, 2);
        NotificationChannel channelB = createChannel("channel-reset-b", Platform.DINGTALK, 2);

        limiter.tryAcquire(channelA, Duration.ZERO);
        limiter.tryAcquire(channelA, Duration.ZERO);
        assertEquals(0, limiter.getAvailablePermits(channelA));

        limiter.tryAcquire(channelB, Duration.ZERO);
        limiter.tryAcquire(channelB, Duration.ZERO);
        assertEquals(0, limiter.getAvailablePermits(channelB));

        // 单通道重置
        limiter.reset("channel-reset-a");
        assertEquals(2, limiter.getAvailablePermits(channelA));
        assertEquals(0, limiter.getAvailablePermits(channelB));

        // 全量重置
        limiter.resetAll();
        assertEquals(2, limiter.getAvailablePermits(channelA));
        assertEquals(2, limiter.getAvailablePermits(channelB));
    }

    @Test
    @DisplayName("验证入参防御性卫语句拦截非法通道与空名称")
    void testPreconditions() {
        InMemoryNotificationRateLimiter limiter = new InMemoryNotificationRateLimiter(new NotificationRateLimitProperties());

        assertThatThrownBy(() -> limiter.tryAcquire(null, Duration.ZERO))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("通知渠道不能为空");

        assertThatThrownBy(() -> limiter.acquire(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("通知渠道不能为空");
    }

    @Test
    @DisplayName("高并发对抗性测试：多线程瞬时并发竞争非阻塞获取配额严格无超发")
    void testHighConcurrencyInstantAcquire() throws InterruptedException {
        int permits = 10;
        int threadCount = 30;
        InMemoryNotificationRateLimiter limiter = new InMemoryNotificationRateLimiter(
                new NotificationRateLimitProperties(), Duration.ofSeconds(5));
        NotificationChannel channel = createChannel("concurrent-channel", Platform.DINGTALK, permits);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    boolean acquired = limiter.tryAcquire(channel, Duration.ZERO);
                    if (acquired) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(endGate.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // 强断言：多线程瞬时竞争下，成功数必须严格等于配额 10，失败数严格为 20，绝不超发且绝无丢锁
        assertEquals(permits, successCount.get());
        assertEquals(threadCount - permits, failCount.get());
        assertEquals(0, limiter.getAvailablePermits(channel));
    }

    @Test
    @DisplayName("高并发对抗性测试：多线程微退避排队平滑全量发放")
    void testHighConcurrencyWithMicroBackoffQueueing() throws InterruptedException {
        int permits = 5;
        int totalRequests = 10;
        // 窗口 80ms，10 个请求排队并发，最大允许等待 400ms
        InMemoryNotificationRateLimiter limiter = new InMemoryNotificationRateLimiter(
                new NotificationRateLimitProperties(), Duration.ofMillis(80));
        NotificationChannel channel = createChannel("smooth-channel", Platform.DINGTALK, permits);

        ExecutorService executor = Executors.newFixedThreadPool(totalRequests);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(totalRequests);

        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    limiter.acquire(channel, Duration.ofMillis(400));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // ignore
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(endGate.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // 所有 10 个请求在微退避窗口内依次顺畅排队发放，全部成功
        assertEquals(totalRequests, successCount.get());
    }

    @Test
    @DisplayName("验证飞书渠道双阶频控：每秒最高 5 次突发防卫与毫秒级微退避排队")
    void testFeishuDualTierBurstRateLimiting() {
        NotificationRateLimitProperties props = new NotificationRateLimitProperties();
        InMemoryNotificationRateLimiter limiter = new InMemoryNotificationRateLimiter(props);
        NotificationChannel feishu = createChannel("feishu-burst-bot", Platform.FEISHU, 100);

        // 飞书渠道前 5 次请求立即成功（单秒突发上限为 5 次）
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(feishu, Duration.ZERO), "第 " + (i + 1) + " 次应立即成功");
        }

        // 第 6 次在同一秒内非阻塞获取应被突发防卫拦截
        assertFalse(limiter.tryAcquire(feishu, Duration.ZERO), "第 6 次突发请求应被单秒 5 次上限拦截");

        // 带有充裕等待时长的第 6 次请求，应通过毫秒级微退避排队等待后成功放行
        assertTrue(limiter.tryAcquire(feishu, Duration.ofMillis(1500)), "等待单秒窗口滑动后应成功获取许可");
    }

    @Test
    @DisplayName("高并发对抗性测试：飞书双阶突发限流多线程瞬时并发竞争与排队")
    void testFeishuDualTierBurstRateLimitingConcurrent() throws InterruptedException {
        int burstPermits = 5;
        int threadCount = 20;
        NotificationRateLimitProperties props = new NotificationRateLimitProperties();
        InMemoryNotificationRateLimiter limiter = new InMemoryNotificationRateLimiter(props);
        NotificationChannel feishu = createChannel("feishu-concurrent-bot", Platform.FEISHU, 100);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);

        AtomicInteger instantSuccess = new AtomicInteger();
        AtomicInteger instantFail = new AtomicInteger();

        // 1. 20 个线程瞬时非阻塞竞争飞书渠道的单秒 5 次突发配额
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    boolean acquired = limiter.tryAcquire(feishu, Duration.ZERO);
                    if (acquired) {
                        instantSuccess.incrementAndGet();
                    } else {
                        instantFail.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(endGate.await(5, TimeUnit.SECONDS));

        // 强断言：瞬时并发下，严格仅有 5 个线程成功抢占单秒突发许可，其余 15 个线程被突发限流拦截
        assertEquals(burstPermits, instantSuccess.get());
        assertEquals(threadCount - burstPermits, instantFail.get());

        // 2. 验证多线程微退避排队：新发起 3 个线程，给予 1500ms 超时，等待单秒突发窗口滑动后应平滑放行
        CountDownLatch queueGate = new CountDownLatch(3);
        AtomicInteger queueSuccess = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            executor.submit(() -> {
                try {
                    if (limiter.tryAcquire(feishu, Duration.ofMillis(1500))) {
                        queueSuccess.incrementAndGet();
                    }
                } finally {
                    queueGate.countDown();
                }
            });
        }

        assertTrue(queueGate.await(5, TimeUnit.SECONDS));
        assertEquals(3, queueSuccess.get());
        executor.shutdown();
    }
}
