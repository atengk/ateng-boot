package io.github.atengk.boot.job.local;

import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.config.AtengJobLocalAutoConfiguration;
import io.github.atengk.boot.job.local.enums.DiscardReason;
import io.github.atengk.boot.job.local.enums.OverlappingStrategy;
import io.github.atengk.boot.job.local.enums.TriggerType;
import io.github.atengk.boot.job.local.event.JobDiscardedEvent;
import io.github.atengk.boot.job.local.lock.JobClusterLockProvider;
import io.github.atengk.boot.job.local.lock.JobLockHandle;
import io.github.atengk.boot.job.local.lock.LocalJobClusterLockProvider;
import io.github.atengk.boot.job.local.lock.LocalJobClusterLockProvider.LockEntry;
import io.github.atengk.boot.job.local.model.JobDefinition;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.registry.LocalJobRegistry;
import io.github.atengk.boot.job.local.registry.ScheduledJobTask;
import io.github.atengk.boot.job.local.storage.InMemoryJobLogStorageProvider;
import io.github.atengk.boot.job.local.storage.InMemoryJobMetadataRepository;
import io.github.atengk.boot.job.local.template.LocalJobTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 轻量集群互斥锁 SPI 与防死锁租约机制对抗性测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
@SpringBootTest(classes = LocalJobClusterLockTest.TestAppConfig.class)
@DisplayName("LocalJob 轻量集群互斥锁 SPI 与防死锁租约机制测试")
class LocalJobClusterLockTest {

    @Autowired
    private LocalJobTemplate jobTemplate;

    @Autowired
    private JobClusterLockProvider clusterLockProvider;

    @Autowired
    private DeclarativeClusterTasks tasks;

    @AfterEach
    void tearDown() {
        if (clusterLockProvider instanceof LocalJobClusterLockProvider localProvider) {
            localProvider.clear();
        }
    }

    @Test
    @DisplayName("多节点并发竞争互斥：2 个虚拟节点同时触发同一集群任务，仅有 1 个节点成功抢锁执行，另 1 个节点安全跳过")
    void testMultiNodeConcurrencyMutualExclusion() throws Exception {
        ConcurrentMap<String, LockEntry> sharedLockStore = new ConcurrentHashMap<>();

        LocalJobClusterLockProvider nodeAProvider = new LocalJobClusterLockProvider("node-A", sharedLockStore);
        LocalJobClusterLockProvider nodeBProvider = new LocalJobClusterLockProvider("node-B", sharedLockStore);

        LocalJobRegistry nodeARegistry = new LocalJobRegistry(null, null, new InMemoryJobMetadataRepository(),
                new InMemoryJobLogStorageProvider(), nodeAProvider, null);
        LocalJobRegistry nodeBRegistry = new LocalJobRegistry(null, null, new InMemoryJobMetadataRepository(),
                new InMemoryJobLogStorageProvider(), nodeBProvider, null);

        CountDownLatch taskRunningLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        AtomicInteger totalExecuted = new AtomicInteger(0);

        JobDefinition def = JobDefinition.builder()
                .jobName("clusterJob1")
                .triggerType(TriggerType.MANUAL)
                .strategy(OverlappingStrategy.DISCARD)
                .clusterLock(true)
                .clusterLockLeaseTime(Duration.ofSeconds(10))
                .build();

        nodeARegistry.register(def, context -> {
            totalExecuted.incrementAndGet();
            taskRunningLatch.countDown();
            try {
                releaseLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return JobResult.success("Node A executed");
        });

        nodeBRegistry.register(def, context -> {
            totalExecuted.incrementAndGet();
            taskRunningLatch.countDown();
            try {
                releaseLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return JobResult.success("Node B executed");
        });

        // 1. 节点 A 先发起执行并持有锁挂起
        CompletableFuture<JobResult> nodeAFuture = CompletableFuture.supplyAsync(() -> nodeARegistry.trigger("clusterJob1", Collections.emptyMap()));

        boolean running = taskRunningLatch.await(3, TimeUnit.SECONDS);
        assertThat(running).isTrue();

        // 验证集群锁正被持有
        assertThat(nodeAProvider.isLocked("clusterJob1")).isTrue();
        assertThat(nodeBProvider.isLocked("clusterJob1")).isTrue();

        // 2. 节点 B 在节点 A 执行中并发触发，预期抢锁失败并安全跳过
        JobResult nodeBResult = nodeBRegistry.trigger("clusterJob1", Collections.emptyMap());
        assertThat(nodeBResult.isSuccess()).isTrue();
        assertThat(nodeBResult.getMessage()).contains("cluster lock held by another node");

        JobInfo nodeBInfo = nodeBRegistry.getJobInfo("clusterJob1");
        assertThat(nodeBInfo.getLastStatus()).isEqualTo("SKIPPED_CLUSTER_LOCKED");

        // 3. 释放节点 A 任务
        releaseLatch.countDown();
        JobResult nodeAResult = nodeAFuture.get(3, TimeUnit.SECONDS);
        assertThat(nodeAResult.isSuccess()).isTrue();

        // 4. 节点 A 执行完成后，锁应被干净释放
        assertThat(nodeAProvider.isLocked("clusterJob1")).isFalse();
        assertThat(nodeBProvider.isLocked("clusterJob1")).isFalse();

        // 仅有 1 个节点执行了业务逻辑
        assertThat(totalExecuted.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("防死锁租约到期自动失效：节点 A 抢锁后崩溃未释放，租约超时后节点 B 能够安全接管锁")
    void testLeaseExpirationAndTakeover() throws Exception {
        ConcurrentMap<String, LockEntry> sharedLockStore = new ConcurrentHashMap<>();

        LocalJobClusterLockProvider nodeA = new LocalJobClusterLockProvider("node-A", sharedLockStore);
        LocalJobClusterLockProvider nodeB = new LocalJobClusterLockProvider("node-B", sharedLockStore);

        String jobName = "leaseExpireJob";

        // 节点 A 抢锁，设置极短租约 400ms，模拟节点 A 崩溃不释放锁
        JobLockHandle handleA = nodeA.tryLock(jobName, Duration.ofMillis(400));
        assertThat(handleA.isAcquired()).isTrue();
        assertThat(handleA.isExpired()).isFalse();

        // 节点 B 立即尝试抢锁，预期失败
        JobLockHandle handleBFirst = nodeB.tryLock(jobName, Duration.ofSeconds(5));
        assertThat(handleBFirst.isAcquired()).isFalse();

        // 等待租约超时（500ms > 400ms）
        Thread.sleep(500);
        assertThat(handleA.isExpired()).isTrue();

        // 租约超时后，节点 B 再次尝试抢锁，预期成功接管！
        JobLockHandle handleBSecond = nodeB.tryLock(jobName, Duration.ofSeconds(5));
        assertThat(handleBSecond.isAcquired()).isTrue();
        assertThat(handleBSecond.getLockOwner()).startsWith("node-B@");

        // 节点 B 释放锁
        boolean unlockedB = nodeB.unlock(handleBSecond);
        assertThat(unlockedB).isTrue();
        assertThat(nodeB.isLocked(jobName)).isFalse();
    }

    @Test
    @DisplayName("属主签名比对防误删：租约失效并被节点 B 接管后，节点 A 迟到的释放请求无法误解节点 B 的锁")
    void testOwnerSignaturePreventsMisrelease() throws Exception {
        ConcurrentMap<String, LockEntry> sharedLockStore = new ConcurrentHashMap<>();

        LocalJobClusterLockProvider nodeA = new LocalJobClusterLockProvider("node-A", sharedLockStore);
        LocalJobClusterLockProvider nodeB = new LocalJobClusterLockProvider("node-B", sharedLockStore);

        String jobName = "antiMisreleaseJob";

        // 1. 节点 A 获取租约 300ms
        JobLockHandle handleA = nodeA.tryLock(jobName, Duration.ofMillis(300));
        assertThat(handleA.isAcquired()).isTrue();

        // 2. 模拟节点 A 发生慢请求，睡眠 400ms 导致租约失效
        Thread.sleep(400);

        // 3. 节点 B 成功接管锁，租约 10s
        JobLockHandle handleB = nodeB.tryLock(jobName, Duration.ofSeconds(10));
        assertThat(handleB.isAcquired()).isTrue();
        assertThat(handleB.getLockOwner()).startsWith("node-B@");

        // 4. 节点 A 醒来后尝试释放，由于 handleA 的 owner 签名与当前锁持有者 (node-B) 不匹配，预期释放失败
        boolean unlockedByA = nodeA.unlock(handleA);
        assertThat(unlockedByA).isFalse();

        // 5. 验证节点 B 的锁依然完好无损地被持有
        assertThat(nodeB.isLocked(jobName)).isTrue();
        assertThat(nodeB.getLockOwner(jobName)).isEqualTo(handleB.getLockOwner());

        // 6. 节点 B 执行正确释放，成功释放锁
        boolean unlockedByB = nodeB.unlock(handleB);
        assertThat(unlockedByB).isTrue();
        assertThat(nodeB.isLocked(jobName)).isFalse();
    }

    @Test
    @DisplayName("声明式 @LocalJob(clusterLock=true) 开箱即用：正常触发自动加锁并在 finally 中释放")
    void testDeclarativeClusterJobExecution() {
        String jobName = "declarativeClusterJob";

        // 执行前未锁定
        LocalJobClusterLockProvider localProvider = (LocalJobClusterLockProvider) clusterLockProvider;
        assertThat(localProvider.isLocked(jobName)).isFalse();

        // 触发执行
        JobResult result = jobTemplate.trigger(jobName);
        assertThat(result.isSuccess()).isTrue();
        assertThat(tasks.clusterJobCount.get()).isEqualTo(1);

        // 执行后锁在 finally 中被干净释放
        assertThat(localProvider.isLocked(jobName)).isFalse();

        // 验证执行元数据
        JobInfo info = jobTemplate.getJob(jobName);
        assertThat(info.getLastStatus()).isEqualTo("SUCCESS");
        assertThat(info.getLastResultMsg()).isEqualTo("Cluster job executed successfully");
    }

    @Test
    @DisplayName("边界保护与空安全：非法入参安全降级，杜绝崩溃与空指针")
    void testEdgeCasesAndNullSafety() {
        LocalJobClusterLockProvider provider = new LocalJobClusterLockProvider("edge-node");

        // 空入参加锁
        assertThat(provider.tryLock(null, Duration.ofSeconds(5)).isAcquired()).isFalse();
        assertThat(provider.tryLock(" ", Duration.ofSeconds(5)).isAcquired()).isFalse();

        // 负数与 0 时长安全回退为默认 5 分钟
        JobLockHandle handleZero = provider.tryLock("edgeJob1", Duration.ZERO);
        assertThat(handleZero.isAcquired()).isTrue();
        assertThat(handleZero.getLeaseExpireAt()).isAfter(java.time.Instant.now().plusSeconds(250));
        provider.unlock(handleZero);

        JobLockHandle handleNeg = provider.tryLock("edgeJob2", Duration.ofSeconds(-10));
        assertThat(handleNeg.isAcquired()).isTrue();
        assertThat(handleNeg.getLeaseExpireAt()).isAfter(java.time.Instant.now().plusSeconds(250));
        provider.unlock(handleNeg);

        // 空入参释放
        assertThat(provider.unlock(null)).isFalse();
        assertThat(provider.unlock(JobLockHandle.failed("edgeJob1"))).isFalse();
        assertThat(provider.unlock(new JobLockHandle("edgeJob1", "otherOwner", java.time.Instant.now(), true))).isFalse();

        // 状态查询空安全
        assertThat(provider.isLocked(null)).isFalse();
        assertThat(provider.isLocked("   ")).isFalse();
        assertThat(provider.getLockOwner(null)).isNull();

        // renew 非法入参 Fail-Fast
        assertThat(provider.renew(null, Duration.ofSeconds(5))).isFalse();
        assertThat(provider.renew(JobLockHandle.failed("edgeJob1"), Duration.ofSeconds(5))).isFalse();
        JobLockHandle validForRenew = provider.tryLock("edgeRenewJob", Duration.ofSeconds(5));
        assertThat(provider.renew(validForRenew, null)).isFalse();
        assertThat(provider.renew(validForRenew, Duration.ZERO)).isFalse();
        assertThat(provider.renew(validForRenew, Duration.ofSeconds(-5))).isFalse();
        provider.unlock(validForRenew);
    }

    @Test
    @DisplayName("对称租约续期 (renew)：属主匹配时成功延长租约，属主不匹配或过期后续期失败")
    void testLocalLockRenewAndOwnerCheck() throws Exception {
        ConcurrentMap<String, LockEntry> sharedLockStore = new ConcurrentHashMap<>();
        LocalJobClusterLockProvider nodeA = new LocalJobClusterLockProvider("node-A", sharedLockStore);
        LocalJobClusterLockProvider nodeB = new LocalJobClusterLockProvider("node-B", sharedLockStore);

        String jobName = "renewJob";
        JobLockHandle handleA = nodeA.tryLock(jobName, Duration.ofMillis(250));
        assertThat(handleA.isAcquired()).isTrue();
        assertThat(handleA.getAcquiredAt()).isNotNull();

        // 150ms 后续期 600ms，总持有应超过最初 250ms 仍有效
        Thread.sleep(150);
        assertThat(nodeA.renew(handleA, Duration.ofMillis(600))).isTrue();

        // 此时再过 180ms（累计 330ms > 250ms），因已续期，节点 B 仍不可抢占
        Thread.sleep(180);
        assertThat(nodeA.isLocked(jobName)).isTrue();
        assertThat(nodeB.tryLock(jobName, Duration.ofSeconds(5)).isAcquired()).isFalse();

        // 节点 B 使用伪造句柄无法续期
        JobLockHandle fakeHandle = JobLockHandle.success(jobName, "node-B@fake:123", java.time.Instant.now().plusSeconds(10));
        assertThat(nodeB.renew(fakeHandle, Duration.ofSeconds(10))).isFalse();

        // 释放后无法再续期
        assertThat(nodeA.unlock(handleA)).isTrue();
        assertThat(nodeA.renew(handleA, Duration.ofSeconds(10))).isFalse();
    }

    @Test
    @DisplayName("非阻塞最小持有时间释放：耗时不足 minHoldTime 时立即返回且在窗口期内阻挡其他节点，窗口期后自动可抢占")
    void testNonBlockingMinHoldUnlockInLocalProvider() throws Exception {
        ConcurrentMap<String, LockEntry> sharedLockStore = new ConcurrentHashMap<>();
        LocalJobClusterLockProvider nodeA = new LocalJobClusterLockProvider("node-A", sharedLockStore);
        LocalJobClusterLockProvider nodeB = new LocalJobClusterLockProvider("node-B", sharedLockStore);

        String jobName = "minHoldLocalJob";
        JobLockHandle handleA = nodeA.tryLock(jobName, Duration.ofSeconds(10));
        assertThat(handleA.isAcquired()).isTrue();

        long unlockStart = System.currentTimeMillis();
        // 最小持有 250ms，几乎立即调用释放
        boolean shrunk = nodeA.unlock(handleA, Duration.ofMillis(250));
        long unlockElapsed = System.currentTimeMillis() - unlockStart;

        assertThat(shrunk).isTrue();
        // 严禁阻塞 Worker 线程（释放耗时远小于 minHoldTime）
        assertThat(unlockElapsed).isLessThan(100);
        // 锁依然处于有效持有状态，节点 B 在窗口期内抢锁失败
        assertThat(nodeA.isLocked(jobName)).isTrue();
        assertThat(nodeB.tryLock(jobName, Duration.ofSeconds(5)).isAcquired()).isFalse();

        // 等待超过 250ms 最小持有窗口期后，锁自动失效，节点 B 可正常抢占
        Thread.sleep(300);
        assertThat(nodeA.isLocked(jobName)).isFalse();
        JobLockHandle handleB = nodeB.tryLock(jobName, Duration.ofSeconds(5));
        assertThat(handleB.isAcquired()).isTrue();
        nodeB.unlock(handleB);
    }

    @Test
    @DisplayName("端到端防时钟漂移最小持有窗口：声明式 @LocalJob(clusterLockMinHoldTime) 短任务释放不阻塞且窗口期内触发 SKIPPED_CLUSTER_LOCKED")
    void testEndToEndMinHoldTimeAndIsClusterLockedFacade() throws Exception {
        String jobName = "minHoldClusterJob";
        tasks.minHoldCount.set(0);

        assertThat(jobTemplate.isClusterLocked(jobName)).isFalse();
        assertThat(jobTemplate.isClusterLocked(null)).isFalse();
        assertThat(jobTemplate.isClusterLocked("  ")).isFalse();
        assertThat(jobTemplate.isClusterLocked("nonExistentJob")).isFalse();

        JobInfo initialInfo = jobTemplate.getJob(jobName);
        assertThat(initialInfo.getClusterLockMinHoldTime()).isEqualTo(Duration.ofMillis(250));

        // 1. 首次触发极短耗时任务（~5ms），应立即返回不阻塞 Worker 线程
        long start = System.currentTimeMillis();
        JobResult firstResult = jobTemplate.trigger(jobName);
        long firstElapsed = System.currentTimeMillis() - start;

        assertThat(firstResult.isSuccess()).isTrue();
        assertThat(firstElapsed).isLessThan(150);
        assertThat(tasks.minHoldCount.get()).isEqualTo(1);

        // 2. 窗口期内 LocalJobTemplate.isClusterLocked 仍返回 true，多节点尝试抢锁触发 SKIPPED_CLUSTER_LOCKED 并广播 JobDiscardedEvent
        assertThat(jobTemplate.isClusterLocked(jobName)).isTrue();
        List<JobDiscardedEvent> discardedEvents = new CopyOnWriteArrayList<>();
        ConcurrentMap<String, LockEntry> sharedStore = new ConcurrentHashMap<>();
        LocalJobClusterLockProvider nodeA = new LocalJobClusterLockProvider("node-A", sharedStore);
        LocalJobClusterLockProvider nodeB = new LocalJobClusterLockProvider("node-B", sharedStore);
        LocalJobRegistry regA = new LocalJobRegistry(null, null, new InMemoryJobMetadataRepository(),
                new InMemoryJobLogStorageProvider(), nodeA, null);
        LocalJobRegistry regB = new LocalJobRegistry(null, null, new InMemoryJobMetadataRepository(),
                new InMemoryJobLogStorageProvider(), nodeB, null);
        regA.setApplicationEventPublisher(event -> {});
        regB.setApplicationEventPublisher(event -> {
            if (event instanceof JobDiscardedEvent discarded) {
                discardedEvents.add(discarded);
            }
        });
        JobDefinition minHoldDef = JobDefinition.builder()
                .jobName("multiNodeMinHoldJob")
                .triggerType(TriggerType.MANUAL)
                .clusterLock(true)
                .clusterLockLeaseTime(Duration.ofSeconds(10))
                .clusterLockMinHoldTime(Duration.ofMillis(250))
                .build();
        regA.register(minHoldDef, ctx -> JobResult.success("node-A done"));
        regB.register(minHoldDef, ctx -> JobResult.success("node-B done"));

        assertThat(regA.trigger("multiNodeMinHoldJob", Collections.emptyMap()).isSuccess()).isTrue();
        assertThat(regB.isClusterLocked("multiNodeMinHoldJob")).isTrue();
        JobResult skippedOnB = regB.trigger("multiNodeMinHoldJob", Collections.emptyMap());
        assertThat(skippedOnB.getMessage()).contains("cluster lock held by another node");
        assertThat(regB.getJobInfo("multiNodeMinHoldJob").getLastStatus()).isEqualTo("SKIPPED_CLUSTER_LOCKED");
        assertThat(discardedEvents).singleElement()
                .satisfies(ev -> assertThat(ev.getDiscardReason()).isEqualTo(DiscardReason.CLUSTER_LOCKED));

        // 3. 等待超过 250ms 最小持有窗口期后，锁自动释放，再次触发恢复执行
        Thread.sleep(300);
        assertThat(jobTemplate.isClusterLocked(jobName)).isFalse();
        assertThat(regB.isClusterLocked("multiNodeMinHoldJob")).isFalse();
        assertThat(jobTemplate.trigger(jobName).isSuccess()).isTrue();
        assertThat(tasks.minHoldCount.get()).isEqualTo(2);
    }

    @Configuration
    @Import(AtengJobLocalAutoConfiguration.class)
    static class TestAppConfig {

        @Bean
        public DeclarativeClusterTasks declarativeClusterTasks() {
            return new DeclarativeClusterTasks();
        }
    }

    @Component
    static class DeclarativeClusterTasks {

        final AtomicInteger clusterJobCount = new AtomicInteger(0);
        final AtomicInteger minHoldCount = new AtomicInteger(0);

        @LocalJob(name = "declarativeClusterJob", clusterLock = true, clusterLockLeaseTime = "10s",
                fixedDelay = "1h", initialDelay = "1h")
        public JobResult executeClusterJob() {
            clusterJobCount.incrementAndGet();
            return JobResult.success("Cluster job executed successfully");
        }

        @LocalJob(name = "minHoldClusterJob", clusterLock = true, clusterLockLeaseTime = "10s",
                clusterLockMinHoldTime = "250ms", fixedDelay = "1h", initialDelay = "1h")
        public JobResult executeMinHoldJob() {
            minHoldCount.incrementAndGet();
            return JobResult.success("Fast min-hold job executed");
        }
    }
}
