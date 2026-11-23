package io.github.atengk.boot.sms;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.atengk.boot.sms.event.SmsSendAuditEvent;
import io.github.atengk.boot.sms.exception.SmsIdempotentException;
import io.github.atengk.boot.sms.exception.SmsRateLimitException;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.exception.SmsVerifyCodeException;
import io.github.atengk.boot.sms.guard.InMemorySmsBlacklistProvider;
import io.github.atengk.boot.sms.guard.InMemorySmsIdempotentGuard;
import io.github.atengk.boot.sms.guard.SmsChannelCircuitBreaker;
import io.github.atengk.boot.sms.limiter.InMemorySmsRateLimiter;
import io.github.atengk.boot.sms.model.BatchSmsRequest;
import io.github.atengk.boot.sms.model.BatchSmsResult;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsDeliveryStatus;
import io.github.atengk.boot.sms.model.SmsParam;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsChannelProperties;
import io.github.atengk.boot.sms.properties.SmsGuardProperties;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.properties.SmsRateLimitProperties;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import io.github.atengk.boot.sms.provider.SmsProvider;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import io.github.atengk.boot.sms.repository.InMemorySmsVerifyCodeRepository;
import io.github.atengk.boot.sms.repository.RedisSmsVerifyCodeRepository;
import io.github.atengk.boot.sms.template.SmsTemplate;
import io.github.atengk.boot.sms.template.SmsVerifyCodeTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工单 #1145 存量九道防线缺陷修复与加固契约验证测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("工单 #1145 九道防线缺陷修复与底座演进验证")
class SmsTicket1145HardeningTest {

    @Test
    @DisplayName("修复验证：InMemory 与 Redis 验证码核销和冻结日志不得泄露原始手机号明文")
    void shouldMaskPhoneNumberInVerifyCodeLogs() {
        String rawPhone = "13812345678";
        Logger memLogger = (Logger) LoggerFactory.getLogger(InMemorySmsVerifyCodeRepository.class);
        ListAppender<ILoggingEvent> memAppender = new ListAppender<>();
        memAppender.start();
        memLogger.addAppender(memAppender);

        Logger redisLogger = (Logger) LoggerFactory.getLogger(RedisSmsVerifyCodeRepository.class);
        ListAppender<ILoggingEvent> redisAppender = new ListAppender<>();
        redisAppender.start();
        redisLogger.addAppender(redisAppender);

        try {
            InMemorySmsVerifyCodeRepository memRepo = new InMemorySmsVerifyCodeRepository();
            memRepo.save(rawPhone, "login", "123456", Duration.ofMinutes(5), Duration.ZERO, 2);
            assertThatThrownBy(() -> memRepo.verify(rawPhone, "000000", "login"))
                    .isInstanceOf(SmsVerifyCodeException.class);
            assertThatThrownBy(() -> memRepo.verify(rawPhone, "000000", "login"))
                    .isInstanceOf(SmsVerifyCodeException.class);

            memRepo.remove(rawPhone, "login");
            memRepo.save(rawPhone, "register", "654321", Duration.ofMinutes(5), Duration.ZERO, 5);
            assertThat(memRepo.verify(rawPhone, "654321", "register")).isTrue();

            List<String> memLogs = memAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(memLogs).isNotEmpty();
            assertThat(memLogs).noneMatch(msg -> msg.contains(rawPhone));
            assertThat(memLogs).anyMatch(msg -> msg.contains("138****5678"));

            // Redis 核销成功日志脱敏
            StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
            when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(List.of(1L, 0L));
            RedisSmsVerifyCodeRepository redisRepo = new RedisSmsVerifyCodeRepository(redisTemplate);
            assertThat(redisRepo.verify(rawPhone, "654321", "login")).isTrue();

            List<String> redisLogs = redisAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(redisLogs).isNotEmpty();
            assertThat(redisLogs).noneMatch(msg -> msg.contains(rawPhone));
            assertThat(redisLogs).anyMatch(msg -> msg.contains("138****5678"));
        } finally {
            memLogger.detachAppender(memAppender);
            redisLogger.detachAppender(redisAppender);
        }
    }

    @Test
    @DisplayName("修复验证：RedisSmsVerifyCodeRepository 亚秒级 TTL 保底至少 1 秒有效存活")
    @SuppressWarnings("unchecked")
    void shouldClampSubSecondTtlToAtLeastOneSecondInRedis() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        RedisSmsVerifyCodeRepository repo = new RedisSmsVerifyCodeRepository(redisTemplate);
        repo.save("13800138000", "login", "123456", Duration.ofMillis(400), Duration.ofSeconds(60), 5);

        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redisTemplate).expire(eq("ateng:sms:code:13800138000:login"), durationCaptor.capture());
        assertThat(durationCaptor.getValue().getSeconds()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("修复验证：InMemorySmsVerifyCodeRepository 并发 save 的冷却检查与写入原子互斥")
    void shouldEnforceAtomicCooldownOnConcurrentSave() throws Exception {
        InMemorySmsVerifyCodeRepository repo = new InMemorySmsVerifyCodeRepository();
        int threads = 16;
        ExecutorService pool = new ThreadPoolExecutor(
                threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), new NamedThreadFactory("test-verify-code-", false));
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rateLimitedCount = new AtomicInteger();

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final String code = String.format("%06d", i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        repo.save("13800138099", "concurrent", code, Duration.ofMinutes(5), Duration.ofSeconds(60), 5);
                        successCount.incrementAndGet();
                    } catch (SmsRateLimitException ex) {
                        rateLimitedCount.incrementAndGet();
                    }
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
            assertThat(successCount.get()).isEqualTo(1);
            assertThat(rateLimitedCount.get()).isEqualTo(threads - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("修复验证：BatchSmsRequest.withTemplateSignAndChannel 保留 urgent 标志")
    void shouldPreserveUrgentFlagOnBatchRequestCopy() {
        BatchSmsRequest req = BatchSmsRequest.builder()
                .phones(List.of("13800138000", "13800138001"))
                .templateId("LOGICAL_TPL")
                .params(SmsParam.of("code", "1234"))
                .bizId("BIZ_URGENT")
                .urgent(true)
                .build();

        BatchSmsRequest copied = req.withTemplateSignAndChannel("PHYSICAL_TPL", "签名", "mock");
        assertThat(copied.isUrgent()).isTrue();
        assertThat(copied.getTemplateId()).isEqualTo("PHYSICAL_TPL");
        assertThat(copied.getSignName()).isEqualTo("签名");
        assertThat(copied.getChannel()).isEqualTo("mock");
        assertThat(copied.getBizId()).isEqualTo("BIZ_URGENT");
    }

    @Test
    @DisplayName("修复验证：sendBatch 全黑名单拦截与异常失败均广播含非空 batchResult 的审计事件，并校验 bizId 幂等")
    void shouldPublishBatchAuditWithResultAcrossBranchesAndEnforceBatchIdempotency() {
        SmsProperties props = new SmsProperties();
        props.getGuard().setBlacklistEnabled(true);
        props.getGuard().setIdempotentEnabled(true);
        props.getGuard().setIdempotentWindow(Duration.ofMinutes(3));

        SmsChannelProperties mockCh = new SmsChannelProperties();
        mockCh.setSupplier(SmsSupplier.MOCK);
        mockCh.setDefaultTemplateId("TPL_BATCH");
        props.getChannels().put("mock", mockCh);

        List<SmsSendAuditEvent> capturedEvents = Collections.synchronizedList(new ArrayList<>());
        ApplicationEventPublisher publisher = evt -> {
            if (evt instanceof SmsSendAuditEvent audit) {
                capturedEvents.add(audit);
            }
        };

        InMemorySmsBlacklistProvider blacklist = new InMemorySmsBlacklistProvider(List.of("13899990001"));
        SmsTemplate template = new SmsTemplate(
                props,
                List.of(new MockSmsProvider()),
                new SmsChannelRegistry(props),
                null, null, blacklist, null, null, null,
                new InMemorySmsIdempotentGuard(),
                publisher,
                null
        );

        // 1. 全黑名单拦截 -> batchResult 非空且状态 BLOCKED
        BatchSmsRequest allBlockedReq = BatchSmsRequest.builder()
                .phones(List.of("13899990001"))
                .templateId("TPL_BATCH")
                .build();
        template.sendBatch(allBlockedReq);

        assertThat(capturedEvents).hasSize(1);
        SmsSendAuditEvent blockedAudit = capturedEvents.get(0);
        assertThat(blockedAudit.getBatchResult()).isNotNull();
        assertThat(blockedAudit.getBatchResult().getBlacklistedCount()).isEqualTo(1);
        assertThat(blockedAudit.isBlocked()).isTrue();

        // 2. 渠道不存在抛出异常 -> 同样广播非空 batchResult
        capturedEvents.clear();
        BatchSmsRequest badChannelReq = BatchSmsRequest.builder()
                .phones(List.of("13800138001"))
                .templateId("TPL_BATCH")
                .channel("non-existing")
                .build();
        assertThatThrownBy(() -> template.sendBatch(badChannelReq)).isInstanceOf(SmsSendException.class);
        assertThat(capturedEvents).hasSize(1);
        assertThat(capturedEvents.get(0).getBatchResult()).isNotNull();
        assertThat(capturedEvents.get(0).isFailed()).isTrue();
        assertThat(capturedEvents.get(0).getBatchResult().getFailedCount()).isEqualTo(1);

        // 3. 批量请求相同 bizId 开启幂等时第二次被拦截
        BatchSmsRequest idemBatch = BatchSmsRequest.builder()
                .phones(List.of("13800138002", "13800138003"))
                .templateId("TPL_BATCH")
                .bizId("BATCH_BIZ_888")
                .build();
        assertThat(template.sendBatch(idemBatch).isSuccess()).isTrue();
        assertThatThrownBy(() -> template.sendBatch(idemBatch)).isInstanceOf(SmsIdempotentException.class);

        // 4. 原生个性化批量分支同样携带非空 batchResult 广播审计事件
        SmsProvider nativeBatchProvider = new SmsProvider() {
            @Override
            public SmsSupplier getSupplier() {
                return SmsSupplier.MOCK;
            }

            @Override
            public SmsResult send(io.github.atengk.boot.sms.model.SmsRequest request, SmsChannel channel) {
                return SmsResult.success(channel.getName(), SmsSupplier.MOCK, "FALLBACK", "REQ_FALLBACK", 1);
            }

            @Override
            public BatchSmsResult sendPersonalizedBatch(BatchSmsRequest request, SmsChannel channel) {
                return BatchSmsResult.of(request.getPhones().size(), request.getPhones().size(),
                        Collections.emptyList(), Collections.emptyList(),
                        List.of(SmsResult.success(channel.getName(), SmsSupplier.MOCK, "NATIVE_P1", "REQ_NATIVE", 2)));
            }
        };
        capturedEvents.clear();
        SmsTemplate nativeTemplate = new SmsTemplate(
                props, List.of(nativeBatchProvider), new SmsChannelRegistry(props),
                null, null, null, null, null, null, null, publisher, null
        );
        BatchSmsRequest personalizedReq = BatchSmsRequest.builder()
                .phones(List.of("13800138004", "13800138005"))
                .templateId("TPL_BATCH")
                .phoneParams(java.util.Map.of(
                        "13800138004", SmsParam.of("name", "Alice"),
                        "13800138005", SmsParam.of("name", "Bob")
                ))
                .build();
        assertThat(nativeTemplate.sendBatch(personalizedReq).getSuccessCount()).isEqualTo(2);
        assertThat(capturedEvents).hasSize(1);
        assertThat(capturedEvents.get(0).getBatchResult()).isNotNull();
        assertThat(capturedEvents.get(0).getBatchResult().getSuccessCount()).isEqualTo(2);

        // 5. BatchSmsRequest.withTemplateSignAndChannel 同时保留 content 字段
        BatchSmsRequest plainCopy = BatchSmsRequest.builder()
                .phones(List.of("13800138006", "13800138007"))
                .content("纯文本测试")
                .urgent(true)
                .build()
                .withTemplateSignAndChannel(null, "新签名", "mock");
        assertThat(plainCopy.getContent()).isEqualTo("纯文本测试");
        assertThat(plainCopy.isUrgent()).isTrue();
    }

    @Test
    @DisplayName("Clock 接缝验证：零 Thread.sleep 确定性验证熔断半开自愈、滑窗过期、验证码 TTL 及跨午夜免打扰")
    void shouldVerifyTimeSensitiveComponentsDeterministicallyWithMutableClock() {
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        // 2026-11-21T23:30:00+08:00 == 2026-11-21T15:30:00Z
        MutableClock clock = new MutableClock(shanghai, Instant.parse("2026-11-21T15:30:00Z"));

        // 1. 熔断器 OPEN -> HALF_OPEN -> CLOSED (零 sleep)
        SmsGuardProperties guardProps = new SmsGuardProperties();
        guardProps.setCircuitBreakerFailureThreshold(2);
        guardProps.setCircuitBreakerOpenDuration(Duration.ofSeconds(30));
        SmsChannelCircuitBreaker breaker = new SmsChannelCircuitBreaker(guardProps, clock);
        breaker.recordFailure("ch-a");
        breaker.recordFailure("ch-a");
        assertThat(breaker.getState("ch-a")).isEqualTo(SmsChannelCircuitBreaker.State.OPEN);
        assertThat(breaker.allowRequest("ch-a")).isFalse();

        clock.advance(Duration.ofSeconds(30));
        assertThat(breaker.getState("ch-a")).isEqualTo(SmsChannelCircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.allowRequest("ch-a")).isTrue();
        breaker.recordSuccess("ch-a");
        assertThat(breaker.getState("ch-a")).isEqualTo(SmsChannelCircuitBreaker.State.CLOSED);

        // 2. 滑动窗口限流器分钟过期恢复 (零 sleep)
        SmsRateLimitProperties rateProps = new SmsRateLimitProperties();
        rateProps.setPerMinute(1);
        rateProps.setPerHour(10);
        rateProps.setPerDay(10);
        InMemorySmsRateLimiter limiter = new InMemorySmsRateLimiter(rateProps, 100, clock);
        assertThat(limiter.tryAcquire("13800001111")).isTrue();
        assertThat(limiter.tryAcquire("13800001111")).isFalse();
        clock.advance(Duration.ofSeconds(61));
        assertThat(limiter.tryAcquire("13800001111")).isTrue();

        // 3. 验证码冷却与 TTL 过期确定性推进 (零 sleep)
        InMemorySmsVerifyCodeRepository repo = new InMemorySmsVerifyCodeRepository(100, clock);
        repo.save("13800002222", "login", "654321", Duration.ofMinutes(5), Duration.ofSeconds(60), 5);
        assertThatThrownBy(() -> repo.save("13800002222", "login", "999999", Duration.ofMinutes(5), Duration.ofSeconds(60), 5))
                .isInstanceOf(SmsRateLimitException.class);
        clock.advance(Duration.ofSeconds(61));
        repo.save("13800002222", "login", "112233", Duration.ofMinutes(5), Duration.ofSeconds(60), 5);
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertThatThrownBy(() -> repo.verify("13800002222", "112233", "login"))
                .isInstanceOf(SmsVerifyCodeException.class);

        // 4. 跨午夜免打扰时段确定性推进 (23:30 拦截 -> 次日 08:01 放行)
        SmsProperties smsProps = new SmsProperties();
        smsProps.getGuard().setQuietHoursEnabled(true);
        smsProps.getGuard().setQuietHoursStart("22:00");
        smsProps.getGuard().setQuietHoursEnd("08:00");
        SmsChannelProperties mockCh = new SmsChannelProperties();
        mockCh.setSupplier(SmsSupplier.MOCK);
        mockCh.setDefaultTemplateId("TPL_QUIET");
        smsProps.getChannels().put("mock", mockCh);

        MutableClock midnightClock = new MutableClock(shanghai, Instant.parse("2026-11-21T15:30:00Z")); // 23:30+08:00
        MockSmsProvider mockProvider = new MockSmsProvider();
        SmsTemplate template = new SmsTemplate(
                smsProps, List.of(mockProvider), new SmsChannelRegistry(smsProps),
                null, null, null, null, null, null, null, null, null, midnightClock
        );
        assertThat(template.send(io.github.atengk.boot.sms.model.SmsRequest.of("13800003333", "TPL_QUIET")).getDeliveryStatus())
                .isEqualTo(SmsDeliveryStatus.BLOCKED);
        assertThat(mockProvider.getSentCount()).isZero();

        midnightClock.advance(Duration.ofHours(8).plusMinutes(31)); // 08:01+08:00
        assertThat(template.send(io.github.atengk.boot.sms.model.SmsRequest.of("13800003333", "TPL_QUIET")).isSuccess())
                .isTrue();
        assertThat(mockProvider.getSentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("有界容量防 OOM 验证：纯内存限流、验证码仓储与幂等卫士均严格受限于容量上限")
    void shouldEvictEntriesWhenInMemoryCapacityReached() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        MutableClock clock = new MutableClock(zone, Instant.parse("2026-11-21T10:00:00Z"));

        SmsRateLimitProperties rateProps = new SmsRateLimitProperties();
        rateProps.setPerMinute(5);
        rateProps.setPerHour(10);
        rateProps.setPerDay(20);
        InMemorySmsRateLimiter limiter = new InMemorySmsRateLimiter(rateProps, 3, clock);
        for (int i = 0; i < 10; i++) {
            limiter.acquire("1380000100" + i);
        }
        assertThat(limiter.size()).isLessThanOrEqualTo(3);

        InMemorySmsVerifyCodeRepository verifyRepo = new InMemorySmsVerifyCodeRepository(3, clock);
        for (int i = 0; i < 10; i++) {
            verifyRepo.save("1380000200" + i, "login", "123456", Duration.ofMinutes(5), Duration.ofSeconds(60), 5);
        }
        assertThat(verifyRepo.size()).isLessThanOrEqualTo(3);

        InMemorySmsIdempotentGuard idemGuard = new InMemorySmsIdempotentGuard(3, clock);
        for (int i = 0; i < 10; i++) {
            idemGuard.acquireLock("idem-key-" + i, Duration.ofMinutes(3));
        }
        assertThat(idemGuard.size()).isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("修复验证：SmsVerifyCodeTemplate 按入参 -> verifyCode.defaultTemplateId -> 渠道 defaultTemplateId -> 兜底常量级联解析")
    void shouldResolveVerifyCodeDefaultTemplateIdInCascadeOrder() {
        SmsProperties props = new SmsProperties();
        props.getVerifyCode().setResendInterval(Duration.ZERO);
        props.getRateLimit().setPerMinute(100);
        props.getRateLimit().setPerHour(100);
        props.getRateLimit().setPerDay(100);

        SmsChannelProperties chProps = new SmsChannelProperties();
        chProps.setSupplier(SmsSupplier.MOCK);
        chProps.setDefaultTemplateId("CH_DEFAULT_TPL");
        props.getChannels().put("mock", chProps);

        MockSmsProvider mockProvider = new MockSmsProvider();
        SmsTemplate smsTemplate = new SmsTemplate(props, List.of(mockProvider));
        SmsVerifyCodeTemplate verifyTemplate = new SmsVerifyCodeTemplate(
                smsTemplate, props, new InMemorySmsRateLimiter(props.getRateLimit()), new InMemorySmsVerifyCodeRepository()
        );

        // 1. 无入参且 verifyCode.defaultTemplateId 未配 -> 回退渠道 defaultTemplateId
        verifyTemplate.generateAndSend("13800110001", "login");
        assertThat(mockProvider.getLastRequest().getTemplateId()).isEqualTo("CH_DEFAULT_TPL");

        // 2. verifyCode.defaultTemplateId 优先于渠道 defaultTemplateId
        props.getVerifyCode().setDefaultTemplateId("VERIFY_PROP_TPL");
        verifyTemplate.generateAndSend("13800110002", "login");
        assertThat(mockProvider.getLastRequest().getTemplateId()).isEqualTo("VERIFY_PROP_TPL");

        // 3. 显式入参最高优先级
        verifyTemplate.generateAndSend("13800110003", "login", "EXPLICIT_TPL");
        assertThat(mockProvider.getLastRequest().getTemplateId()).isEqualTo("EXPLICIT_TPL");
    }

    private static final class MutableClock extends Clock {
        private final ZoneId zone;
        private final AtomicReference<Instant> current;

        private MutableClock(ZoneId zone, Instant initial) {
            this.zone = zone;
            this.current = new AtomicReference<>(initial);
        }

        void advance(Duration delta) {
            current.updateAndGet(instant -> instant.plus(delta));
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
