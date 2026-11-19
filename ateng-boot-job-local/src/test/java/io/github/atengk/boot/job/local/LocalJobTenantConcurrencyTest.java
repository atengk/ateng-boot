package io.github.atengk.boot.job.local;

import io.github.atengk.boot.core.context.ContextCopyingTaskDecorator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.job.local.enums.JobTenantMode;
import io.github.atengk.boot.job.local.enums.TriggerType;
import io.github.atengk.boot.job.local.event.JobFailedEvent;
import io.github.atengk.boot.job.local.event.JobSuccessEvent;
import io.github.atengk.boot.job.local.model.JobDefinition;
import io.github.atengk.boot.job.local.model.JobExecutionRecord;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.properties.JobLocalProperties;
import io.github.atengk.boot.job.local.registry.LocalJobRegistry;
import io.github.atengk.boot.job.local.storage.InMemoryJobLogStorageProvider;
import io.github.atengk.boot.job.local.storage.InMemoryJobMetadataRepository;
import io.github.atengk.boot.job.local.tenant.TenantProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SaaS 多租户信号量受管并发遍历 (tenantConcurrency) 对抗测试
 *
 * @author Ateng
 * @since 2026-11-19
 */
@DisplayName("LocalJob SaaS 多租户信号量并发遍历测试")
class LocalJobTenantConcurrencyTest {

    private ThreadPoolTaskExecutor workerExecutor;
    private ThreadPoolTaskScheduler scheduler;
    private LocalJobRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.destroy();
        }
        if (workerExecutor != null) {
            workerExecutor.shutdown();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
    }

    private void initRuntime(List<String> tenants, List<ApplicationEvent> events, InMemoryJobLogStorageProvider logStorage) {
        initRuntime(tenants, events, logStorage, 6);
    }

    private void initRuntime(List<String> tenants, List<ApplicationEvent> events, InMemoryJobLogStorageProvider logStorage, int corePoolSize) {
        workerExecutor = new ThreadPoolTaskExecutor();
        workerExecutor.setCorePoolSize(corePoolSize);
        workerExecutor.setMaxPoolSize(corePoolSize * 2);
        workerExecutor.setQueueCapacity(100);
        workerExecutor.setThreadNamePrefix("ateng-job-worker-");
        workerExecutor.setTaskDecorator(new ContextCopyingTaskDecorator());
        workerExecutor.initialize();

        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ateng-job-sched-");
        scheduler.initialize();

        TenantProvider tenantProvider = () -> tenants;
        registry = new LocalJobRegistry(
                scheduler, workerExecutor, new InMemoryJobMetadataRepository(),
                logStorage, null, new JobLocalProperties(), tenantProvider);
        if (events != null) {
            registry.setApplicationEventPublisher((ApplicationEventPublisher) event -> {
                if (event instanceof ApplicationEvent ae) {
                    events.add(ae);
                }
            });
        }
    }

    @Test
    @DisplayName("tenantConcurrency = 3 时应在受管线程池受 Semaphore 背压并发执行且租户与上下文严格隔离")
    void shouldExecuteTenantsConcurrentlyWithSemaphoreBackpressureAndContextIsolation() {
        List<String> tenants = List.of("T1", "T2", "T3", "T4", "T5", "T6");
        List<ApplicationEvent> events = new CopyOnWriteArrayList<>();
        InMemoryJobLogStorageProvider logStorage = new InMemoryJobLogStorageProvider(50);
        initRuntime(tenants, events, logStorage);

        AtomicInteger activeWorkers = new AtomicInteger(0);
        AtomicInteger maxObservedConcurrency = new AtomicInteger(0);
        Map<String, String> observedThreadTenants = new ConcurrentHashMap<>();
        Map<String, String> observedThreadNames = new ConcurrentHashMap<>();
        Map<String, String> observedTraceIds = new ConcurrentHashMap<>();
        Map<String, String> observedUsers = new ConcurrentHashMap<>();

        registry.register(JobDefinition.builder()
                        .jobName("concurrentIterateJob")
                        .triggerType(TriggerType.MANUAL)
                        .tenantMode(JobTenantMode.ITERATE)
                        .tenantConcurrency(3)
                        .build(),
                ctx -> {
                    int current = activeWorkers.incrementAndGet();
                    maxObservedConcurrency.accumulateAndGet(current, Math::max);
                    try {
                        observedThreadTenants.put(ctx.getTenantId(), TenantContextHolder.getTenantId());
                        observedThreadNames.put(ctx.getTenantId(), Thread.currentThread().getName());
                        observedTraceIds.put(ctx.getTenantId(), MDC.get("traceId"));
                        observedUsers.put(ctx.getTenantId(), UserContextHolder.getExplicit());
                        ctx.log("tenant=" + ctx.getTenantId() + ",holder=" + TenantContextHolder.getTenantId());
                        Thread.sleep(60);
                        return JobResult.success("done:" + ctx.getTenantId());
                    } finally {
                        activeWorkers.decrementAndGet();
                    }
                });

        TenantContextHolder.setTenantId("OUTER_CALLER_TENANT");
        UserContextHolder.set("admin-user");

        JobResult result = registry.trigger("concurrentIterateJob", Map.of("traceId", "trace-concurrent-001", "userId", "admin-user"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("完成 6/6 租户调度", "成功 6", "失败 0");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("OUTER_CALLER_TENANT");

        // 证明确实发生了多租户并发（>=2），且严格受信号量背压控制（<=3）
        assertThat(maxObservedConcurrency.get()).isBetween(2, 3);
        assertThat(observedThreadTenants).hasSize(6);
        for (String tenant : tenants) {
            assertThat(observedThreadTenants.get(tenant)).isEqualTo(tenant);
            assertThat(observedThreadNames.get(tenant)).startsWith("ateng-job-worker-");
            assertThat(observedTraceIds.get(tenant)).isEqualTo("trace-concurrent-001");
            assertThat(observedUsers.get(tenant)).isEqualTo("admin-user");
        }

        JobInfo info = registry.getJobInfo("concurrentIterateJob");
        assertThat(info.getLastStatus()).isEqualTo("SUCCESS");
        assertThat(info.getTenantConcurrency()).isEqualTo(3);

        List<JobExecutionRecord> records = logStorage.listLogs("concurrentIterateJob", 10);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getLogs()).hasSize(12);
        assertThat(events).anySatisfy(e -> assertThat(e).isInstanceOf(JobSuccessEvent.class));
    }

    @Test
    @DisplayName("并发遍历中单个租户抛错应 Fail-Safe 隔离，不阻断其余租户并输出 PARTIAL_SUCCESS")
    void shouldIsolateSingleTenantFailureInParallelIterateAndReportPartialSuccess() {
        List<String> tenants = List.of("T1", "T2", "T3", "T4", "T5", "T6");
        List<ApplicationEvent> events = new CopyOnWriteArrayList<>();
        InMemoryJobLogStorageProvider logStorage = new InMemoryJobLogStorageProvider(50);
        initRuntime(tenants, events, logStorage);

        List<String> completedTenants = new CopyOnWriteArrayList<>();
        registry.register(JobDefinition.builder()
                        .jobName("partialFailIterateJob")
                        .triggerType(TriggerType.MANUAL)
                        .tenantMode(JobTenantMode.ITERATE)
                        .tenantConcurrency(3)
                        .build(),
                ctx -> {
                    Thread.sleep(30);
                    if ("T3".equals(TenantContextHolder.getTenantId())) {
                        throw new IllegalStateException("租户 T3 数据源连接异常");
                    }
                    completedTenants.add(TenantContextHolder.getTenantId());
                    return JobResult.success("ok");
                });

        JobResult result = registry.trigger("partialFailIterateJob", null);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("完成 6/6 租户调度", "成功 5，失败 1", "租户 T3 数据源连接异常");
        assertThat(completedTenants).containsExactlyInAnyOrder("T1", "T2", "T4", "T5", "T6");

        JobInfo info = registry.getJobInfo("partialFailIterateJob");
        assertThat(info.getLastStatus()).isEqualTo("PARTIAL_SUCCESS");

        List<JobExecutionRecord> records = logStorage.listLogs("partialFailIterateJob", 10);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getStatus()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(events).anySatisfy(e -> assertThat(e).isInstanceOf(JobFailedEvent.class));
    }

    @Test
    @DisplayName("tenantConcurrency <= 0 时应自动归一化为 1 并串行安全遍历")
    void shouldNormalizeNonPositiveTenantConcurrencyToOne() {
        List<String> tenants = List.of("T1", "T2", "T3");
        InMemoryJobLogStorageProvider logStorage = new InMemoryJobLogStorageProvider(50);
        initRuntime(tenants, new ArrayList<>(), logStorage);

        List<String> executedOrder = new ArrayList<>();
        registry.register(JobDefinition.builder()
                        .jobName("normalizedConcurrencyJob")
                        .triggerType(TriggerType.MANUAL)
                        .tenantMode(JobTenantMode.ITERATE)
                        .tenantConcurrency(-2)
                        .build(),
                ctx -> {
                    executedOrder.add(TenantContextHolder.getTenantId());
                    return JobResult.success("ok");
                });

        JobResult result = registry.trigger("normalizedConcurrencyJob", null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(executedOrder).containsExactly("T1", "T2", "T3");
        assertThat(registry.getJobInfo("normalizedConcurrencyJob").getTenantConcurrency()).isEqualTo(1);
    }

    @Test
    @DisplayName("tenantConcurrency >= corePoolSize 且配置 timeout 时应避免同池嵌套死锁并精准超时隔离单租户")
    void shouldAvoidPoolStarvationDeadlockWhenConcurrentIterateCombinesWithTimeout() {
        List<String> tenants = List.of("T1", "T2", "T3", "T4", "T5", "T6");
        InMemoryJobLogStorageProvider logStorage = new InMemoryJobLogStorageProvider(50);
        // 故意设置 corePoolSize = 2 < tenantConcurrency = 4 且 queueCapacity = 100，若同池二次嵌套必然 100% 饥饿死锁
        initRuntime(tenants, new CopyOnWriteArrayList<>(), logStorage, 2);

        List<String> succeededTenants = new CopyOnWriteArrayList<>();
        registry.register(JobDefinition.builder()
                        .jobName("concurrentTimeoutIterateJob")
                        .triggerType(TriggerType.MANUAL)
                        .tenantMode(JobTenantMode.ITERATE)
                        .tenantConcurrency(4)
                        .timeout(Duration.ofMillis(120))
                        .build(),
                ctx -> {
                    String tid = TenantContextHolder.getTenantId();
                    if ("T3".equals(tid)) {
                        Thread.sleep(600);
                    } else {
                        Thread.sleep(25);
                    }
                    succeededTenants.add(tid);
                    return JobResult.success("ok:" + tid);
                });

        JobResult result = registry.trigger("concurrentTimeoutIterateJob", null);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("完成 6/6 租户调度", "成功 5，失败 1");
        assertThat(succeededTenants).containsExactlyInAnyOrder("T1", "T2", "T4", "T5", "T6");
        assertThat(registry.getJobInfo("concurrentTimeoutIterateJob").getLastStatus()).isEqualTo("PARTIAL_SUCCESS");
    }
}
