package io.github.atengk.boot.mail.guard;

import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.properties.MailIdempotentProperties;
import io.github.atengk.boot.mail.properties.MailQuotaProperties;
import io.github.atengk.boot.mail.properties.MailRateLimitProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 邮件三阶双模出站防护（频控/自然日配额熔断/幂等防重）单测
 *
 * @author Ateng
 * @since 2026-11-16
 */
class MailOutboundGuardsTest {

    private InMemoryMailRateLimiter inMemoryRateLimiter;
    private InMemoryMailDailyQuotaBreaker inMemoryQuotaBreaker;
    private InMemoryMailIdempotentGuard inMemoryIdempotentGuard;

    @AfterEach
    void tearDown() {
        if (inMemoryRateLimiter != null) {
            inMemoryRateLimiter.reset();
        }
        if (inMemoryQuotaBreaker != null) {
            inMemoryQuotaBreaker.reset();
        }
        if (inMemoryIdempotentGuard != null) {
            inMemoryIdempotentGuard.clear();
        }
    }

    @Test
    @DisplayName("单机内存频控：账号令牌桶按去重收件人次扣减 + 单收件人滑动窗口防轰炸")
    void testInMemoryRateLimiterAccountAndRecipientLimits() {
        MailRateLimitProperties props = new MailRateLimitProperties();
        props.setEnabled(true);
        props.setAccountCapacity(4L);
        props.setAccountPermitsPerSecond(0.001d);
        props.setRecipientMaxCount(2L);
        props.setRecipientWindow(Duration.ofMinutes(1));
        inMemoryRateLimiter = new InMemoryMailRateLimiter(props);

        MailAccount account = MailAccount.builder().name("ops").build();
        MailMessage message = new MailMessage().to("user@example.com").subject("test");

        // 1. 首次 3 个去重收件人消耗 3 枚账号令牌，通过
        assertTrue(inMemoryRateLimiter.tryAcquire(account, message, List.of("u1@example.com", "u2@example.com", "u3@example.com")));
        // 2. 桶内剩余不足 2 枚令牌，再发 2 人被账号令牌桶拒绝
        assertFalse(inMemoryRateLimiter.tryAcquire(account, message, List.of("u4@example.com", "u5@example.com")));
        // 3. 剩 1 枚令牌且 u1 窗口仅 1 次（<=2），发给 u1（大小写混合规范化一致）通过
        assertTrue(inMemoryRateLimiter.tryAcquire(account, message, List.of("  U1@EXAMPLE.COM ")));

        // 4. 关闭账号桶仅验证单收件人滑动窗口（u1 已满 2 次），第 3 次命中 u1 应拒绝且不污染新收件人 u6
        inMemoryRateLimiter.reset();
        props.setAccountCapacity(100L);
        props.setRecipientMaxCount(2L);
        assertTrue(inMemoryRateLimiter.tryAcquire(account, message, List.of("u1@example.com")));
        assertTrue(inMemoryRateLimiter.tryAcquire(account, message, List.of("u1@example.com")));
        assertFalse(inMemoryRateLimiter.tryAcquire(account, message, List.of("u1@example.com", "u6@example.com")));
        // u6 因为预检拦截未被误记数，单独可连续发满 2 次
        assertTrue(inMemoryRateLimiter.tryAcquire(account, message, List.of("u6@example.com")));
        assertTrue(inMemoryRateLimiter.tryAcquire(account, message, List.of("u6@example.com")));
        assertFalse(inMemoryRateLimiter.tryAcquire(account, message, List.of("u6@example.com")));
    }

    @Test
    @DisplayName("单机内存频控与日配额：多线程并发竞争精确限流且绝不超发")
    void testConcurrentQuotaAndRateLimiterAccuracy() throws Exception {
        MailQuotaProperties quotaProps = new MailQuotaProperties();
        quotaProps.setEnabled(true);
        quotaProps.setDailyMaxCount(25L);
        inMemoryQuotaBreaker = new InMemoryMailDailyQuotaBreaker(quotaProps);

        MailRateLimitProperties rateProps = new MailRateLimitProperties();
        rateProps.setEnabled(true);
        rateProps.setAccountCapacity(20L);
        rateProps.setAccountPermitsPerSecond(0.001d);
        rateProps.setRecipientEnabled(false);
        inMemoryRateLimiter = new InMemoryMailRateLimiter(rateProps);

        MailAccount account = MailAccount.builder().name("concurrent").build();
        MailMessage message = new MailMessage().to("target@example.com").subject("concurrent");

        int threads = 12;
        int iterationsPerThread = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger quotaAllowedCount = new AtomicInteger();
        AtomicInteger rateAllowedCount = new AtomicInteger();

        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < iterationsPerThread; i++) {
                            if (inMemoryQuotaBreaker.tryConsume(account, message, 1).allowed()) {
                                quotaAllowedCount.incrementAndGet();
                            }
                            if (inMemoryRateLimiter.tryAcquire(account, message, List.of("target@example.com"))) {
                                rateAllowedCount.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            startLatch.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(25, quotaAllowedCount.get());
        assertEquals(25L, inMemoryQuotaBreaker.getCurrentCount(null));
        assertTrue(rateAllowedCount.get() >= 20 && rateAllowedCount.get() <= 21);
    }

    @Test
    @DisplayName("单机内存日配额熔断：严格控制跨日 00:00 自动归零重置与非法参数快速失败")
    void testInMemoryDailyQuotaCrossDayReset() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        MutableClock mutableClock = new MutableClock(zone, Instant.parse("2026-11-16T15:59:50Z")); // 23:59:50+08:00

        MailQuotaProperties props = new MailQuotaProperties();
        props.setEnabled(true);
        props.setDailyMaxCount(5L);
        inMemoryQuotaBreaker = new InMemoryMailDailyQuotaBreaker(props, mutableClock);

        MailAccount account = MailAccount.builder().name("default").build();
        MailMessage msg = new MailMessage().to("a@example.com").subject("s");

        // 非法 recipientCount 快速失败
        assertThrows(IllegalArgumentException.class, () -> inMemoryQuotaBreaker.tryConsume(account, msg, 0));
        assertThrows(IllegalArgumentException.class, () -> inMemoryQuotaBreaker.tryConsume(account, msg, -2));

        // 1. 当天消费 3 + 2 = 5，达到上限
        assertTrue(inMemoryQuotaBreaker.tryConsume(account, msg, 3).allowed());
        assertTrue(inMemoryQuotaBreaker.tryConsume(account, msg, 2).allowed());
        assertEquals(5L, inMemoryQuotaBreaker.getCurrentCount(LocalDate.of(2026, 11, 16)));

        // 2. 第 6 次尝试应拒绝并保持已用数为 5
        MailDailyQuotaBreaker.QuotaCheckResult rejected = inMemoryQuotaBreaker.tryConsume(account, msg, 1);
        assertFalse(rejected.allowed());
        assertEquals(5L, rejected.currentCount());
        assertEquals(5L, rejected.dailyLimit());
        assertEquals(1, rejected.requestedCount());
        assertEquals(LocalDate.of(2026, 11, 16), rejected.quotaDate());

        // 3. 跨越午夜进入 2026-11-17 00:00:05+08:00，配额自动归零重放
        mutableClock.setInstant(Instant.parse("2026-11-16T16:00:05Z"));
        assertEquals(0L, inMemoryQuotaBreaker.getCurrentCount(LocalDate.of(2026, 11, 17)));
        MailDailyQuotaBreaker.QuotaCheckResult nextDay = inMemoryQuotaBreaker.tryConsume(account, msg, 4);
        assertTrue(nextDay.allowed());
        assertEquals(4L, nextDay.currentCount());
        assertEquals(LocalDate.of(2026, 11, 17), nextDay.quotaDate());
    }

    @Test
    @DisplayName("单机内存幂等防重守卫：抢占拦截、失败放锁重试与过期自动释放")
    void testInMemoryIdempotentGuardLifecycle() throws Exception {
        MailIdempotentProperties props = new MailIdempotentProperties();
        props.setEnabled(true);
        props.setTtl(Duration.ofMillis(80));
        inMemoryIdempotentGuard = new InMemoryMailIdempotentGuard(props);

        MailAccount account = MailAccount.builder().name("default").build();
        MailMessage msg = new MailMessage().bizId("ORDER-99").to("a@example.com");

        assertTrue(inMemoryIdempotentGuard.tryAcquire(account, msg, "biz:ORDER-99"));
        assertTrue(inMemoryIdempotentGuard.isLocked("biz:ORDER-99"));
        assertFalse(inMemoryIdempotentGuard.tryAcquire(account, msg, "biz:ORDER-99"));

        // 失败主动释放锁允许立即重试
        inMemoryIdempotentGuard.releaseLock(account, msg, "biz:ORDER-99");
        assertFalse(inMemoryIdempotentGuard.isLocked("biz:ORDER-99"));
        assertTrue(inMemoryIdempotentGuard.tryAcquire(account, msg, "biz:ORDER-99"));

        // 等待 TTL 过期自动释放
        Thread.sleep(110L);
        assertFalse(inMemoryIdempotentGuard.isLocked("biz:ORDER-99"));
        assertTrue(inMemoryIdempotentGuard.tryAcquire(account, msg, "biz:ORDER-99"));
    }

    @Test
    @DisplayName("Redis 分布式三阶实现：脚本/预检无污染、跨日键轮转与 Redis 异常双策略降级（保留因果链）")
    @SuppressWarnings("unchecked")
    void testRedisGuardsScriptsAndErrorFallbackPolicies() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // 1. RedisMailRateLimiter 正常放行、预检拦截零污染与 fail-open / fail-closed（抛出因果异常）
        MailRateLimitProperties rateProps = new MailRateLimitProperties();
        rateProps.setEnabled(true);
        rateProps.setAccountCapacity(10);
        rateProps.setFailOpenOnRedisError(true);
        RedisMailRateLimiter redisRateLimiter = new RedisMailRateLimiter(redisTemplate, rateProps);
        MailAccount account = MailAccount.builder().name("vip").build();
        MailMessage msg = new MailMessage().to("a@example.com", "b@example.com");

        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(1L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(1L);
        assertTrue(redisRateLimiter.tryAcquire(account, msg, List.of("a@example.com", "b@example.com")));

        // 模拟第 2 个收件人在只读预检阶段失败，则绝不执行账号桶扣减（仅发生预检调用，不写入任何 ZADD/HMSET）
        reset(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("ateng:mail:rate:rcpt:a@example.com")), any(), any(), any(), eq("")))
                .thenReturn(1L);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("ateng:mail:rate:rcpt:b@example.com")), any(), any(), any(), eq("")))
                .thenReturn(0L);
        assertFalse(redisRateLimiter.tryAcquire(account, msg, List.of("a@example.com", "b@example.com")));
        verify(redisTemplate, never()).execute(any(RedisScript.class), eq(List.of("ateng:mail:rate:acct:vip")), any(), any(), any(), any(), any());

        // 模拟 Redis 连接故障：failOpen=true 放行
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("Redis down"));
        assertTrue(redisRateLimiter.tryAcquire(account, msg, List.of("a@example.com")));

        // failOpen=false 抛出保留原因的 MAIL_SEND_ERROR，绝不吞掉 Redis cause 链
        rateProps.setFailOpenOnRedisError(false);
        MailSendException rateRedisEx = assertThrows(MailSendException.class,
                () -> redisRateLimiter.tryAcquire(account, msg, List.of("a@example.com")));
        assertEquals(MailResultCode.MAIL_SEND_ERROR, rateRedisEx.getResultCode());
        assertInstanceOf(RedisConnectionFailureException.class, rateRedisEx.getCause());

        // 2. RedisMailDailyQuotaBreaker 跨日键格式、非法数量拦截与异常降级
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        MutableClock mutableClock = new MutableClock(zone, Instant.parse("2026-11-16T08:00:00Z"));
        MailQuotaProperties quotaProps = new MailQuotaProperties();
        quotaProps.setEnabled(true);
        quotaProps.setDailyMaxCount(50L);
        RedisMailDailyQuotaBreaker redisQuota = new RedisMailDailyQuotaBreaker(redisTemplate, quotaProps, mutableClock);

        assertThrows(IllegalArgumentException.class, () -> redisQuota.tryConsume(account, msg, 0));

        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("ateng:mail:quota:20261116")), eq("50"), eq("2"), anyString()))
                .thenReturn(List.of(1L, 2L));
        MailDailyQuotaBreaker.QuotaCheckResult ok = redisQuota.tryConsume(account, msg, 2);
        assertTrue(ok.allowed());
        assertEquals(2L, ok.currentCount());

        // 切换次日验证 key 自动变为 20261117
        mutableClock.setInstant(Instant.parse("2026-11-17T08:00:00Z"));
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("ateng:mail:quota:20261117")), eq("50"), eq("3"), anyString()))
                .thenReturn(List.of(0L, 50L));
        MailDailyQuotaBreaker.QuotaCheckResult blocked = redisQuota.tryConsume(account, msg, 3);
        assertFalse(blocked.allowed());
        assertEquals(50L, blocked.currentCount());

        // Redis 异常 fail-open=true vs fail-closed 抛保留因果的 MAIL_SEND_ERROR（避免误发配额告警）
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("Redis timeout"));
        assertTrue(redisQuota.tryConsume(account, msg, 1).allowed());
        quotaProps.setFailOpenOnRedisError(false);
        MailSendException quotaRedisEx = assertThrows(MailSendException.class,
                () -> redisQuota.tryConsume(account, msg, 1));
        assertEquals(MailResultCode.MAIL_SEND_ERROR, quotaRedisEx.getResultCode());
        assertInstanceOf(RedisConnectionFailureException.class, quotaRedisEx.getCause());

        // 3. RedisMailIdempotentGuard SETNX / releaseLock / 异常降级
        MailIdempotentProperties idemProps = new MailIdempotentProperties();
        idemProps.setEnabled(true);
        RedisMailIdempotentGuard redisIdem = new RedisMailIdempotentGuard(redisTemplate, idemProps);

        when(valueOps.setIfAbsent("ateng:mail:idempotent:biz:B1", "1", idemProps.getTtl())).thenReturn(Boolean.TRUE);
        assertTrue(redisIdem.tryAcquire(account, msg, "biz:B1"));

        when(valueOps.setIfAbsent("ateng:mail:idempotent:biz:B1", "1", idemProps.getTtl())).thenReturn(Boolean.FALSE);
        assertFalse(redisIdem.tryAcquire(account, msg, "biz:B1"));

        redisIdem.releaseLock(account, msg, "biz:B1");
        verify(redisTemplate).delete("ateng:mail:idempotent:biz:B1");

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("Redis error"));
        assertTrue(redisIdem.tryAcquire(account, msg, "biz:B2"));
        idemProps.setFailOpenOnRedisError(false);
        MailSendException idemRedisEx = assertThrows(MailSendException.class,
                () -> redisIdem.tryAcquire(account, msg, "biz:B2"));
        assertEquals(MailResultCode.MAIL_SEND_ERROR, idemRedisEx.getResultCode());
        assertInstanceOf(RedisConnectionFailureException.class, idemRedisEx.getCause());
    }

    private static final class MutableClock extends Clock {
        private final ZoneId zone;
        private final AtomicReference<Instant> current;

        private MutableClock(ZoneId zone, Instant initial) {
            this.zone = zone;
            this.current = new AtomicReference<>(initial);
        }

        void setInstant(Instant next) {
            current.set(next);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return new MutableClock(z, current.get());
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
