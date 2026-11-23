package io.github.atengk.boot.sms.delay;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.sms.context.SmsContextHolder;
import io.github.atengk.boot.sms.event.SmsSendAuditEvent;
import io.github.atengk.boot.sms.exception.SmsException;
import io.github.atengk.boot.sms.guard.InMemoryDailyBudgetBreaker;
import io.github.atengk.boot.sms.guard.InMemorySmsBlacklistProvider;
import io.github.atengk.boot.sms.guard.InMemorySmsQuotaGuard;
import io.github.atengk.boot.sms.interceptor.SmsSendContext;
import io.github.atengk.boot.sms.interceptor.SmsSendInterceptor;
import io.github.atengk.boot.sms.model.BatchSmsRequest;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsParam;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsDelayProperties;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import io.github.atengk.boot.sms.template.SmsTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工单 #1149 双模可取消延迟与定时发信调度器单元测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("工单 #1149 双模延迟与定时短信调度器测试")
class SmsDelaySchedulerTest {

    @AfterEach
    void cleanUp() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
        SmsContextHolder.clear();
    }

    @Test
    @DisplayName("MEMORY 模式两阶段校验：提交期不扣预算配额，透传四维/渠道上下文并在到点执行实时安全链（含退订黑名单实时阻断）")
    void shouldPropagateContextsAndEnforceTwoPhaseSemanticsInMemoryMode() throws Exception {
        SmsProperties props = new SmsProperties();
        props.getGuard().setDailyMaxQuota(10L);
        props.getGuard().setBlacklistEnabled(true);

        SmsChannelRegistry registry = new SmsChannelRegistry(props);
        registry.register(SmsChannel.builder()
                .name("tenant-vip")
                .supplier(SmsSupplier.MOCK)
                .defaultTemplateId("TPL_DELAY")
                .maxQuota(5)
                .build());
        registry.register(SmsChannel.builder()
                .name("aliyun-only-tpl")
                .supplier(SmsSupplier.ALIYUN)
                .accessKeyId("AK_TEST")
                .accessKeySecret("SK_TEST")
                .signName("官方")
                .build());

        MockSmsProvider mockProvider = new MockSmsProvider(props.getGuard());
        InMemoryDailyBudgetBreaker budgetBreaker = new InMemoryDailyBudgetBreaker(props.getGuard(), null, Clock.systemDefaultZone());
        InMemorySmsQuotaGuard quotaGuard = new InMemorySmsQuotaGuard(Clock.systemDefaultZone());
        InMemorySmsBlacklistProvider blacklistProvider = new InMemorySmsBlacklistProvider(props.getGuard().getBlacklist());

        CountDownLatch deliveredLatch = new CountDownLatch(1);
        CountDownLatch blockedAuditLatch = new CountDownLatch(1);
        AtomicReference<String> capturedTrace = new AtomicReference<>();
        AtomicReference<String> capturedUser = new AtomicReference<>();
        AtomicReference<String> capturedTenant = new AtomicReference<>();
        AtomicReference<String> capturedChannel = new AtomicReference<>();
        List<SmsSendAuditEvent> auditEvents = Collections.synchronizedList(new ArrayList<>());

        ApplicationEventPublisher publisher = event -> {
            if (event instanceof SmsSendAuditEvent audit) {
                auditEvents.add(audit);
                if (audit.isSuccess()) {
                    deliveredLatch.countDown();
                } else if (audit.isBlocked()) {
                    blockedAuditLatch.countDown();
                }
            }
        };

        SmsSendInterceptor contextObserverInterceptor = new SmsSendInterceptor() {
            @Override
            public boolean preSend(SmsSendContext context) {
                if ("13800001002".equals(context.getRequest().getPhone())) {
                    capturedTrace.set(MDC.get("traceId"));
                    capturedUser.set(UserContextHolder.get());
                    capturedTenant.set(TenantContextHolder.getTenantId());
                    capturedChannel.set(SmsContextHolder.getChannel());
                }
                return true;
            }

            @Override
            public int getOrder() {
                return 0;
            }
        };

        MemorySmsDelayScheduler scheduler = new MemorySmsDelayScheduler(props.getDelay(), Clock.systemDefaultZone());
        try {
            SmsTemplate template = new SmsTemplate(
                    props,
                    List.of(mockProvider),
                    registry,
                    null, null,
                    blacklistProvider,
                    budgetBreaker,
                    null, null, null,
                    publisher,
                    null,
                    Clock.systemDefaultZone(),
                    List.of(contextObserverInterceptor),
                    null,
                    quotaGuard,
                    scheduler,
                    null
            );

            // 1. 提交期 Fail-Fast：渠道不存在 / 不支持纯文本直发
            assertThatThrownBy(() -> template.sendDelayed(SmsRequest.of("13800001001", "TPL_1").withChannel("non-exist"), Duration.ofMillis(50)))
                    .isInstanceOf(SmsException.class);
            assertThatThrownBy(() -> template.sendDelayed(
                    SmsRequest.builder().phone("13800001001").content("纯文本").channel("aliyun-only-tpl").build(),
                    Duration.ofMillis(50)))
                    .isInstanceOf(SmsException.class);
            assertThatThrownBy(() -> template.sendDelayed(SmsRequest.of("13800001001", "TPL_1"), Duration.ofSeconds(-1)))
                    .isInstanceOf(RuntimeException.class);

            // 2. 设置四维与渠道上下文提交延迟发信：确认提交期 0 扣减预算与配额
            MDC.put("traceId", "trace-delay-999");
            UserContextHolder.set("user-88");
            TenantContextHolder.setTenantId("tenant-vip");
            SmsContextHolder.setChannel("tenant-vip");

            SmsScheduledReceipt receiptDeliver = template.sendDelayed(
                    SmsRequest.of("13800001002", SmsParam.of("orderNo", "ORD-1")),
                    Duration.ofMillis(120)
            );
            SmsScheduledReceipt receiptToBlock = template.sendAt(
                    SmsRequest.of("13800001003", "TPL_DELAY"),
                    Instant.now().plusMillis(160)
            );
            SmsScheduledReceipt receiptCancelled = template.sendBatchDelayed(
                    BatchSmsRequest.builder().phones(List.of("13800001004", "13800001005")).templateId("TPL_DELAY").channel("tenant-vip").build(),
                    Duration.ofMillis(120)
            );

            // 主线程提交完成后立即清理上下文，检验异步工作线程是否依靠快照独立恢复
            MDC.clear();
            UserContextHolder.clear();
            TenantContextHolder.clear();
            SmsContextHolder.clear();

            // 关键：延迟提交期不扣减预算也不扣减单渠道配额
            assertThat(budgetBreaker.getCurrentDailyCount()).isZero();
            assertThat(template.getChannelQuotaUsed("tenant-vip")).isZero();
            assertThat(mockProvider.getSentCount()).isZero();

            // 3. 触发前取消任务3；将任务2手机号纳入黑名单（模拟延迟期间用户退订）
            assertThat(template.cancelScheduled(receiptCancelled.getTaskId())).isTrue();
            assertThat(receiptCancelled.isCancelled()).isTrue();
            assertThat(receiptCancelled.isDone()).isTrue();
            assertThat(receiptCancelled.cancel()).isFalse();

            blacklistProvider.add("13800001003", "用户已退订");

            // 4. 在拦截或执行时观测上下文（借助 SmsSendInterceptor + MockSmsProvider + AuditEvent）
            assertThat(deliveredLatch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(blockedAuditLatch.await(3, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);

            // 仅任务1真正物理外发（任务2被实时黑名单拦截，任务3已取消彻底不触发）
            assertThat(mockProvider.getSentCount()).isEqualTo(1);
            SmsRequest actualSent = mockProvider.getLastRequest();
            assertThat(actualSent.getPhone()).isEqualTo("13800001002");
            assertThat(actualSent.getChannel()).isEqualTo("tenant-vip");
            assertThat(capturedTrace.get()).isEqualTo("trace-delay-999");
            assertThat(capturedUser.get()).isEqualTo("user-88");
            assertThat(capturedTenant.get()).isEqualTo("tenant-vip");
            assertThat(capturedChannel.get()).isEqualTo("tenant-vip");

            // 任务1真正发出扣减了 1 次预算和配额，任务2被黑名单拦在预算之前未误扣，任务3被取消未执行
            assertThat(budgetBreaker.getCurrentDailyCount()).isEqualTo(1L);
            assertThat(template.getChannelQuotaUsed("tenant-vip")).isEqualTo(1L);
            assertThat(receiptDeliver.isDone()).isTrue();
            assertThat(auditEvents.stream().anyMatch(e -> e.isBlocked() && "13800001003".equals(e.getPhone()))).isTrue();
            assertThat(auditEvents.stream().noneMatch(e -> e.getPhones().contains("13800001004"))).isTrue();
        } finally {
            scheduler.destroy();
        }
    }

    @Test
    @DisplayName("REDIS 持久化调度模式：ZSet 入队、Lua 原子认领防重投、恢复四维渠道上下文、支持撤销取消与故障健壮性")
    @SuppressWarnings("unchecked")
    void shouldSupportRedisZSetClaimCancelAndContextRestoration() {
        AtomicReference<Instant> nowRef = new AtomicReference<>(Instant.parse("2026-11-21T10:00:00Z"));
        Clock mutableClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return nowRef.get();
            }
        };

        // 内存线程安全 Redis 仿真（ZSet + KV + Lua）
        ReentrantLock redisLock = new ReentrantLock();
        Map<String, String> kvStore = new ConcurrentHashMap<>();
        Map<String, Map<String, Double>> zsetStore = new ConcurrentHashMap<>();

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        ZSetOperations<String, String> zsetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForZSet()).thenReturn(zsetOps);

        org.mockito.Mockito.doAnswer(inv -> {
            kvStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString());

        when(zsetOps.add(anyString(), anyString(), anyDouble())).thenAnswer(inv -> {
            redisLock.lock();
            try {
                String zKey = inv.getArgument(0);
                String member = inv.getArgument(1);
                double score = inv.getArgument(2);
                Map<String, Double> map = zsetStore.computeIfAbsent(zKey, k -> new HashMap<>());
                return map.put(member, score) == null;
            } finally {
                redisLock.unlock();
            }
        });

        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(inv -> {
            List<String> keys = inv.getArgument(1);
            Object[] args = new Object[inv.getArguments().length - 2];
            for (int i = 2; i < inv.getArguments().length; i++) {
                args[i - 2] = inv.getArgument(i);
            }
            String zKey = keys.get(0);
            redisLock.lock();
            try {
                Map<String, Double> zset = zsetStore.computeIfAbsent(zKey, k -> new HashMap<>());
                if (args.length == 2) {
                    // CANCEL_LUA: ARGV[1]=taskId, ARGV[2]=taskKeyPrefix
                    String taskId = String.valueOf(args[0]);
                    String prefix = String.valueOf(args[1]);
                    boolean zRemoved = zset.remove(taskId) != null;
                    boolean kvRemoved = kvStore.remove(prefix + taskId) != null;
                    return (zRemoved || kvRemoved) ? 1L : 0L;
                }
                // CLAIM_DUE_LUA: ARGV[1]=nowMillis, ARGV[2]=batchSize, ARGV[3]=taskKeyPrefix
                long nowMillis = Long.parseLong(String.valueOf(args[0]));
                int limit = Integer.parseInt(String.valueOf(args[1]));
                String prefix = String.valueOf(args[2]);
                List<String> dueIds = zset.entrySet().stream()
                        .filter(e -> e.getValue() <= nowMillis)
                        .sorted(Map.Entry.comparingByValue())
                        .limit(limit)
                        .map(Map.Entry::getKey)
                        .toList();
                List<Object> payloads = new ArrayList<>();
                for (String id : dueIds) {
                    zset.remove(id);
                    String data = kvStore.remove(prefix + id);
                    if (data != null) {
                        payloads.add(data);
                    }
                }
                return payloads;
            } finally {
                redisLock.unlock();
            }
        });

        SmsProperties props = new SmsProperties();
        props.getDelay().setMode(SmsDelayProperties.DelayMode.REDIS);
        props.getDelay().setRedisKeyPrefix("test:sms:delay:");

        SmsChannelRegistry registry = new SmsChannelRegistry(props);
        registry.register(SmsChannel.builder()
                .name("redis-ctx-ch")
                .supplier(SmsSupplier.MOCK)
                .defaultTemplateId("TPL_REDIS")
                .build());
        MockSmsProvider mockProvider = new MockSmsProvider(props.getGuard());

        RedisSmsDelayScheduler redisScheduler = new RedisSmsDelayScheduler(redisTemplate, props.getDelay(), mutableClock, false);
        List<SmsSendAuditEvent> audits = new ArrayList<>();
        ApplicationEventPublisher publisher = e -> {
            if (e instanceof SmsSendAuditEvent a) {
                audits.add(a);
            }
        };
        AtomicReference<String> redisTrace = new AtomicReference<>();
        AtomicReference<String> redisUser = new AtomicReference<>();
        AtomicReference<String> redisTenant = new AtomicReference<>();
        AtomicReference<String> redisChannel = new AtomicReference<>();
        SmsSendInterceptor redisCtxObserver = new SmsSendInterceptor() {
            @Override
            public boolean preSend(SmsSendContext context) {
                if (context.getRequest() != null && "13800002001".equals(context.getRequest().getPhone())) {
                    redisTrace.set(MDC.get("traceId"));
                    redisUser.set(UserContextHolder.get());
                    redisTenant.set(TenantContextHolder.getTenantId());
                    redisChannel.set(SmsContextHolder.getChannel());
                }
                return true;
            }

            @Override
            public int getOrder() {
                return 0;
            }
        };

        SmsTemplate template = new SmsTemplate(
                props,
                List.of(mockProvider),
                registry,
                null, null, null, null, null, null, null,
                publisher,
                null,
                mutableClock,
                List.of(redisCtxObserver),
                null, null,
                redisScheduler,
                null
        );

        MDC.put("traceId", "redis-trace-777");
        UserContextHolder.set("redis-user-1");
        TenantContextHolder.setTenantId("tenant-r");
        SmsContextHolder.setChannel("redis-ctx-ch");

        SmsScheduledReceipt r1 = template.sendDelayed(SmsRequest.of("13800002001", SmsParam.of("v", "1")), Duration.ofSeconds(10));
        SmsScheduledReceipt r2 = template.sendDelayed(SmsRequest.of("13800002002", "TPL_REDIS"), Duration.ofSeconds(10));
        SmsScheduledReceipt r3 = template.sendBatchDelayed(
                BatchSmsRequest.builder().phones(List.of("13800002003", "13800002004")).templateId("TPL_REDIS").channel("redis-ctx-ch").build(),
                Duration.ofSeconds(30)
        );

        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        SmsContextHolder.clear();

        // 取消 r2，未到期时 poll 返回 0
        assertThat(template.cancelScheduled(r2.getTaskId())).isTrue();
        assertThat(r2.isCancelled()).isTrue();
        assertThat(redisScheduler.pollAndDispatchDueTasks()).isZero();
        assertThat(mockProvider.getSentCount()).isZero();

        // 推进 15 秒：仅 r1 到期被认领并还原 traceId 与渠道，再次 poll 防重投返回 0
        nowRef.set(Instant.parse("2026-11-21T10:00:15Z"));
        assertThat(redisScheduler.pollAndDispatchDueTasks()).isEqualTo(1);
        assertThat(redisScheduler.pollAndDispatchDueTasks()).isZero();
        assertThat(mockProvider.getSentCount()).isEqualTo(1);
        assertThat(mockProvider.getLastRequest().getChannel()).isEqualTo("redis-ctx-ch");
        assertThat(audits).hasSize(1);
        assertThat(redisTrace.get()).isEqualTo("redis-trace-777");
        assertThat(redisUser.get()).isEqualTo("redis-user-1");
        assertThat(redisTenant.get()).isEqualTo("tenant-r");
        assertThat(redisChannel.get()).isEqualTo("redis-ctx-ch");
        assertThat(r1.isDone()).isTrue();
        assertThat(r1.getStatus()).isEqualTo(SmsScheduledStatus.COMPLETED);

        // 验证 poll 结束后工作线程现场干净无残留
        assertThat(UserContextHolder.get()).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(SmsContextHolder.getChannel()).isNull();
        assertThat(MDC.get("traceId")).isNull();

        // 推进至 35 秒：批量任务 r3 到期执行
        nowRef.set(Instant.parse("2026-11-21T10:00:35Z"));
        assertThat(redisScheduler.pollAndDispatchDueTasks()).isEqualTo(1);
        assertThat(mockProvider.getSentCount()).isEqualTo(2);

        // Redis 异常健壮性：轮询遇到异常不抛崩溃
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("Redis connection timeout"));
        assertThat(redisScheduler.pollAndDispatchDueTasks()).isZero();
    }
}
