package io.github.atengk.boot.job.local;

import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.config.AtengJobLocalAutoConfiguration;
import io.github.atengk.boot.job.local.enums.OverlappingStrategy;
import io.github.atengk.boot.job.local.enums.TriggerType;
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
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
        assertThat(provider.getLockOwner(null)).isNull();
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

        @LocalJob(name = "declarativeClusterJob", clusterLock = true, clusterLockLeaseTime = "10s",
                fixedDelay = "1h", initialDelay = "1h")
        public JobResult executeClusterJob() {
            clusterJobCount.incrementAndGet();
            return JobResult.success("Cluster job executed successfully");
        }
    }
}
