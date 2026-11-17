package io.github.atengk.boot.notification;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationRateLimitException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.limiter.RedisNotificationRateLimiter;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.properties.NotificationRateLimitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 基于 StringRedisTemplate 与原子 Lua 双滑动窗口的分布式频控器单元测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisNotificationRateLimiter 分布式双滑动窗口频控器单元测试")
class RedisNotificationRateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private NotificationRateLimitProperties properties;
    private RedisNotificationRateLimiter limiter;

    @BeforeEach
    void setUp() {
        properties = new NotificationRateLimitProperties();
        properties.setDistributed(true);
        properties.setRedisKeyPrefix("ateng:test:ratelimit");
        properties.setFailOpenOnRedisError(true);
        limiter = new RedisNotificationRateLimiter(redisTemplate, properties);
    }

    private NotificationChannel createChannel(String name, Platform platform, Integer permitsPerMinute, Integer burstPermits) {
        return NotificationChannel.builder()
                .name(name)
                .platform(platform)
                .webhook("https://example.com/webhook/" + name)
                .permitsPerMinute(permitsPerMinute)
                .burstPermitsPerSecond(burstPermits)
                .build();
    }

    @Test
    @DisplayName("构造期与入口 Fail-Fast 防卫及平台双配额解析验证")
    void testGuardAndQuotaResolution() {
        assertThatThrownBy(() -> new RedisNotificationRateLimiter(null, properties))
                .isInstanceOf(BusinessException.class);

        NotificationChannel blankChannel = mock(NotificationChannel.class);
        when(blankChannel.getName()).thenReturn("  ");
        assertThatThrownBy(() -> limiter.tryAcquire(null, Duration.ZERO)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> limiter.tryAcquire(blankChannel, Duration.ZERO)).isInstanceOf(BusinessException.class);

        NotificationChannel dingTalk = createChannel("ding", Platform.DINGTALK, null, null);
        NotificationChannel feishu = createChannel("feishu", Platform.FEISHU, null, null);
        NotificationChannel customFeishu = createChannel("feishu-vip", Platform.FEISHU, 200, 10);

        assertEquals(20, limiter.resolvePermitsPerMinute(dingTalk));
        assertEquals(0, limiter.resolveBurstPermitsPerSecond(dingTalk));
        assertEquals(100, limiter.resolvePermitsPerMinute(feishu));
        assertEquals(5, limiter.resolveBurstPermitsPerSecond(feishu));
        assertEquals(200, limiter.resolvePermitsPerMinute(customFeishu));
        assertEquals(10, limiter.resolveBurstPermitsPerSecond(customFeishu));

        // prefix 自动补全末尾冒号并使用 Cluster Hash Tag，保证双键落入同槽位
        assertEquals("ateng:test:ratelimit:{feishu}:minute", limiter.buildMinuteKey("feishu"));
        assertEquals("ateng:test:ratelimit:{feishu}:second", limiter.buildSecondKey("feishu"));
    }

    @Test
    @DisplayName("频控总开关关闭时直接放行且零 Redis 交互")
    void testDisabledRateLimiterSkipsRedis() {
        properties.setEnabled(false);
        NotificationChannel channel = createChannel("feishu-disabled", Platform.FEISHU, 100, 5);

        assertTrue(limiter.tryAcquire(channel, Duration.ofSeconds(1)));
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("验证单次原子 Lua 脚本协同传递分钟与秒级突发双窗口 Key 及 ARGV 参数")
    @SuppressWarnings("unchecked")
    void testSingleAtomicDualWindowLuaInvocation() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        NotificationChannel feishu = createChannel("feishu-ops", Platform.FEISHU, 100, 5);
        assertTrue(limiter.tryAcquire(feishu, Duration.ZERO));

        ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object> minuteLimitCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> minuteWindowCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> burstLimitCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> burstWindowCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> nowCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> memberCaptor = ArgumentCaptor.forClass(Object.class);

        verify(redisTemplate, times(1)).execute(
                scriptCaptor.capture(),
                keysCaptor.capture(),
                minuteLimitCaptor.capture(),
                minuteWindowCaptor.capture(),
                burstLimitCaptor.capture(),
                burstWindowCaptor.capture(),
                nowCaptor.capture(),
                memberCaptor.capture()
        );

        String lua = scriptCaptor.getValue().getScriptAsString();
        assertThat(lua).contains("ZREMRANGEBYSCORE").contains("ZCARD").contains("ZADD").contains("PEXPIRE");
        assertThat(keysCaptor.getValue()).containsExactly(
                "ateng:test:ratelimit:{feishu-ops}:minute",
                "ateng:test:ratelimit:{feishu-ops}:second"
        );
        assertEquals("100", minuteLimitCaptor.getValue());
        assertEquals("60000", minuteWindowCaptor.getValue());
        assertEquals("5", burstLimitCaptor.getValue());
        assertEquals("1000", burstWindowCaptor.getValue());
        assertThat(memberCaptor.getValue().toString()).isNotBlank();
    }

    @Test
    @DisplayName("验证微退避轮询等待后成功获取配额，以及超限等待超时触发 NotificationRateLimitException")
    @SuppressWarnings("unchecked")
    void testMicroBackoffAndAcquireTimeoutException() {
        NotificationChannel dingTalk = createChannel("ding-backoff", Platform.DINGTALK, 20, null);

        // 首次被突发/分钟窗口拒绝（返回估算等待 -30ms），微退避后第二次抢占成功
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(-30L)
                .thenReturn(1L);

        assertTrue(limiter.tryAcquire(dingTalk, Duration.ofMillis(300)));
        verify(redisTemplate, times(2)).execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        // 持续超限直到 maxWaitDuration 耗尽，acquire() 必须抛出 NotificationRateLimitException
        reset(redisTemplate);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(-200L);

        assertFalse(limiter.tryAcquire(dingTalk, Duration.ofMillis(60)));
        assertThatThrownBy(() -> limiter.acquire(dingTalk, Duration.ofMillis(40)))
                .isInstanceOf(NotificationRateLimitException.class)
                .satisfies(ex -> {
                    NotificationRateLimitException rlEx = (NotificationRateLimitException) ex;
                    assertEquals(NotificationResultCode.NOTIFICATION_RATE_LIMIT_EXCEEDED, rlEx.getResultCode());
                    assertEquals("ding-backoff", rlEx.getChannelName());
                    assertEquals(20, rlEx.getPermitsPerMinute());
                });
    }

    @Test
    @DisplayName("验证线程中断时立即中止微退避并保留中断标记")
    @SuppressWarnings("unchecked")
    void testInterruptedDuringBackoffRestoresFlag() throws InterruptedException {
        NotificationChannel channel = createChannel("interrupt-ch", Platform.WECOM, 20, null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(-500L);

        AtomicBoolean acquired = new AtomicBoolean(true);
        AtomicBoolean interruptedFlagAfterReturn = new AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            acquired.set(limiter.tryAcquire(channel, Duration.ofSeconds(1)));
            interruptedFlagAfterReturn.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        worker.join(3000);

        assertFalse(acquired.get());
        assertTrue(interruptedFlagAfterReturn.get());
    }

    @Test
    @DisplayName("验证 reset 与 resetAll（基于 SCAN 流式游标与 try-with-resources）对称清理语义")
    @SuppressWarnings("unchecked")
    void testResetAndResetAllSymmetricCleanup() {
        limiter.reset("  ");
        limiter.reset(null);
        verifyNoInteractions(redisTemplate);

        limiter.reset("ops-alert");
        verify(redisTemplate, times(1)).delete(List.of(
                "ateng:test:ratelimit:{ops-alert}:minute",
                "ateng:test:ratelimit:{ops-alert}:second"
        ));

        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(
                "ateng:test:ratelimit:{scan-alert}:minute",
                "ateng:test:ratelimit:{scan-alert}:second"
        );
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

        limiter.resetAll();
        verify(redisTemplate, times(1)).delete(List.of(
                "ateng:test:ratelimit:{scan-alert}:minute",
                "ateng:test:ratelimit:{scan-alert}:second"
        ));
        verify(cursor, times(1)).close();
    }

    @Test
    @DisplayName("验证 Redis 故障时 fail-open 降级放行与 fail-closed 保留 cause 链快速失败")
    @SuppressWarnings("unchecked")
    void testFailOpenAndFailClosedStrategies() {
        NotificationChannel channel = createChannel("redis-err-ch", Platform.FEISHU, 100, 5);
        RuntimeException redisDown = new RuntimeException("READONLY You can't write against a read only replica");
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(redisDown);

        // fail-open (默认 true): 记录警告并放行，避免阻塞告警推送
        properties.setFailOpenOnRedisError(true);
        assertTrue(limiter.tryAcquire(channel, Duration.ofMillis(200)));

        doThrow(redisDown).when(redisTemplate).delete(anyCollection());
        assertDoesNotThrow(() -> limiter.reset("redis-err-ch"));

        // fail-closed (false): 严禁吞咽异常，必须保留原始 cause 链抛出受控异常
        properties.setFailOpenOnRedisError(false);
        assertThatThrownBy(() -> limiter.tryAcquire(channel, Duration.ofMillis(200)))
                .isInstanceOf(NotificationRateLimitException.class)
                .hasCause(redisDown);

        assertThatThrownBy(() -> limiter.reset("redis-err-ch"))
                .isInstanceOf(NotificationException.class)
                .hasCause(redisDown);

        // resetAll() 在扫描异常时的 fail-open / fail-closed 及 Cursor 确定性关闭校验
        Cursor<String> failingCursor = mock(Cursor.class);
        when(failingCursor.hasNext()).thenReturn(true);
        when(failingCursor.next()).thenThrow(redisDown);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(failingCursor);

        properties.setFailOpenOnRedisError(true);
        assertDoesNotThrow(() -> limiter.resetAll());
        verify(failingCursor, times(1)).close();

        Cursor<String> failingCursor2 = mock(Cursor.class);
        when(failingCursor2.hasNext()).thenReturn(true);
        when(failingCursor2.next()).thenThrow(redisDown);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(failingCursor2);

        properties.setFailOpenOnRedisError(false);
        assertThatThrownBy(() -> limiter.resetAll())
                .isInstanceOf(NotificationException.class)
                .hasCause(redisDown);
        verify(failingCursor2, times(1)).close();
    }

    @Test
    @DisplayName("覆盖边界防卫：null Lua 结果、空白 Prefix 兜底、超过 128 键分批 SCAN 删除、null 游标及多线程无状态并发")
    @SuppressWarnings("unchecked")
    void testAdversarialBoundariesAndConcurrency() throws InterruptedException {
        // 1. 空白 redisKeyPrefix 自动兜底默认值，且 buildMinuteKey/buildSecondKey 防卫空白输入并 trim
        NotificationRateLimitProperties blankPrefixProps = new NotificationRateLimitProperties();
        blankPrefixProps.setRedisKeyPrefix("   ");
        RedisNotificationRateLimiter defaultPrefixLimiter =
                new RedisNotificationRateLimiter(redisTemplate, blankPrefixProps, Duration.ZERO);
        assertEquals("ateng:notification:ratelimit:{feishu-trim}:minute",
                defaultPrefixLimiter.buildMinuteKey("  feishu-trim "));
        assertEquals("ateng:notification:ratelimit:{feishu-trim}:second",
                defaultPrefixLimiter.buildSecondKey("  feishu-trim "));
        assertThatThrownBy(() -> defaultPrefixLimiter.buildMinuteKey("  "))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> defaultPrefixLimiter.buildSecondKey(null))
                .isInstanceOf(BusinessException.class);

        // 2. redisTemplate.execute 返回 null 时安全降级 false，绝不 NPE；minutePermits <= 0 直接拒绝
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);
        NotificationChannel ding = createChannel("null-res-ch", Platform.DINGTALK, 10, null);
        assertFalse(defaultPrefixLimiter.tryAcquire(ding, Duration.ZERO));

        NotificationRateLimitProperties zeroQuotaProps = new NotificationRateLimitProperties();
        zeroQuotaProps.setDefaultDingTalkPermits(0);
        RedisNotificationRateLimiter zeroLimiter = new RedisNotificationRateLimiter(redisTemplate, zeroQuotaProps);
        NotificationChannel defaultDing = createChannel("zero-quota-ch", Platform.DINGTALK, null, null);
        assertFalse(zeroLimiter.tryAcquire(defaultDing, Duration.ZERO));

        // 3. scan 返回 null 游标安全无异常；超过 128 键分批删除验证
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(null);
        assertDoesNotThrow(() -> limiter.resetAll());

        Cursor<String> largeCursor = mock(Cursor.class);
        Boolean[] hasNextSequence = new Boolean[131];
        for (int i = 0; i < 130; i++) {
            hasNextSequence[i] = true;
        }
        hasNextSequence[130] = false;
        when(largeCursor.hasNext()).thenReturn(hasNextSequence[0], java.util.Arrays.copyOfRange(hasNextSequence, 1, hasNextSequence.length));
        java.util.concurrent.atomic.AtomicInteger keySeq = new java.util.concurrent.atomic.AtomicInteger(0);
        when(largeCursor.next()).thenAnswer(inv -> "ateng:test:ratelimit:{batch}:k-" + keySeq.getAndIncrement());
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(largeCursor);

        limiter.resetAll();
        verify(redisTemplate, times(2)).delete(anyList());
        verify(largeCursor, times(1)).close();

        // 4. 多线程并发无状态回归测试
        reset(redisTemplate);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
        int threads = 8;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.List<Thread> workers = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    if (limiter.tryAcquire(ding, Duration.ZERO)) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
            workers.add(t);
            t.start();
        }
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(threads, successCount.get());
    }
}
