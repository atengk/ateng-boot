package io.github.atengk.boot.job.local;

import io.github.atengk.boot.job.local.enums.JobStatus;
import io.github.atengk.boot.job.local.enums.OverlappingStrategy;
import io.github.atengk.boot.job.local.enums.TriggerType;
import io.github.atengk.boot.job.local.lifecycle.LocalJobLifecycleManager;
import io.github.atengk.boot.job.local.model.JobDefinition;
import io.github.atengk.boot.job.local.model.JobExecutionRecord;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.properties.JobLocalProperties;
import io.github.atengk.boot.job.local.registry.LocalJobRegistry;
import io.github.atengk.boot.job.local.registry.ScheduledJobTask;
import io.github.atengk.boot.job.local.storage.InMemoryJobLogStorageProvider;
import io.github.atengk.boot.job.local.storage.InMemoryJobMetadataRepository;
import io.github.atengk.boot.job.local.storage.JobLogStorageProvider;
import io.github.atengk.boot.job.local.storage.JobMetadataRepository;
import io.github.atengk.boot.job.local.template.DefaultLocalJobTemplate;
import io.github.atengk.boot.job.local.template.LocalJobTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两级存储 SPI、日志防 OOM 环形淘汰与生命周期守卫对抗性测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
@DisplayName("两级存储 SPI、日志防 OOM 环形淘汰与生命周期守卫测试")
class LocalJobStorageAndLifecycleTest {

    private ThreadPoolTaskScheduler scheduler;
    private ThreadPoolTaskExecutor workerExecutor;
    private InMemoryJobMetadataRepository metadataRepository;
    private InMemoryJobLogStorageProvider logStorageProvider;
    private JobLocalProperties properties;
    private LocalJobRegistry registry;
    private LocalJobLifecycleManager lifecycleManager;
    private LocalJobTemplate jobTemplate;

    @BeforeEach
    void setUp() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("test-scheduler-");
        scheduler.initialize();

        workerExecutor = new ThreadPoolTaskExecutor();
        workerExecutor.setCorePoolSize(4);
        workerExecutor.setThreadNamePrefix("test-worker-");
        workerExecutor.initialize();

        metadataRepository = new InMemoryJobMetadataRepository();
        logStorageProvider = new InMemoryJobLogStorageProvider(100);
        properties = new JobLocalProperties();
        properties.setInMemoryLogCapacity(100);
        properties.setPreferDatabaseConfig(true);

        registry = new LocalJobRegistry(scheduler, workerExecutor, metadataRepository, logStorageProvider, properties);
        lifecycleManager = new LocalJobLifecycleManager(registry, properties);
        jobTemplate = new DefaultLocalJobTemplate(registry);
    }

    @AfterEach
    void tearDown() {
        if (lifecycleManager != null && lifecycleManager.isRunning()) {
            lifecycleManager.stop();
        }
        if (registry != null) {
            registry.destroy();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (workerExecutor != null) {
            workerExecutor.shutdown();
        }
    }

    @Test
    @DisplayName("有界环形淘汰：写入 200 条日志且容量为 100 时，严格执行 FIFO 淘汰，队列长度恒定为 100")
    void testBoundedRingBufferEviction() {
        String jobName = "ringBufferJob";

        // 写入 200 条历史日志
        for (int i = 1; i <= 200; i++) {
            JobExecutionRecord record = JobExecutionRecord.builder()
                    .jobName(jobName)
                    .traceId("trace-" + i)
                    .fireTime(Instant.now().plusMillis(i))
                    .status("SUCCESS")
                    .resultMsg("Log sequence: " + i)
                    .build();
            logStorageProvider.saveLog(record);
        }

        // 验证内存中实际保留记录数恒定等于容量 100
        assertThat(logStorageProvider.getLogCount(jobName)).isEqualTo(100);

        // 倒序查询最近日志
        List<JobExecutionRecord> recentLogs = logStorageProvider.listLogs(jobName, 100);
        assertThat(recentLogs).hasSize(100);

        // 最新的一条是第 200 条
        assertThat(recentLogs.get(0).getResultMsg()).isEqualTo("Log sequence: 200");
        // 最老保留的一条是第 101 条（1~100 已被 FIFO 自动淘汰）
        assertThat(recentLogs.get(99).getResultMsg()).isEqualTo("Log sequence: 101");

        // 通过门面查询
        List<JobExecutionRecord> templateLogs = jobTemplate.listRecentLogs(jobName, 10);
        assertThat(templateLogs).hasSize(10);
        assertThat(templateLogs.get(0).getResultMsg()).isEqualTo("Log sequence: 200");
    }

    @Test
    @DisplayName("配置仲裁：preferDatabaseConfig=true 时，已持久化的 PAUSED 状态与自定义 Cron 优先于静态定义")
    void testPreferDatabaseConfigArbitration() {
        String jobName = "arbitratedJob";

        // 1. 预先在持久化存储中存入经由管理后台调整后的配置：PAUSED 且修改了 Cron 与 fixedRate
        JobDefinition persistedDef = JobDefinition.builder()
                .jobName(jobName)
                .triggerType(TriggerType.FIXED_RATE)
                .fixedRate(Duration.ofMinutes(15))
                .status(JobStatus.PAUSED)
                .enabled(false)
                .build();
        metadataRepository.save(persistedDef);

        // 2. 模拟系统重启或 Bean 初始化，代码中静态注解配置为 RUNNING 且 fixedRate 为 5 分钟
        JobDefinition staticDef = JobDefinition.builder()
                .jobName(jobName)
                .triggerType(TriggerType.FIXED_RATE)
                .fixedRate(Duration.ofMinutes(5))
                .strategy(OverlappingStrategy.DISCARD)
                .status(JobStatus.RUNNING)
                .enabled(true)
                .build();

        ScheduledJobTask task = registry.register(staticDef, context -> JobResult.success("arbitrated ok"));

        // 3. 验证仲裁结果：成功继承持久化中的 PAUSED 状态与 fixedRate 15分钟，未被代码中的静态配置覆盖
        assertThat(task.getStatus()).isEqualTo(JobStatus.PAUSED);
        assertThat(task.getDefinition().getFixedRate()).isEqualTo(Duration.ofMinutes(15));
        assertThat(task.getDefinition().isEnabled()).isFalse();

        // 启动生命周期，验证 PAUSED 任务不会被 schedule
        lifecycleManager.start();
        assertThat((Object) task.getScheduledFuture()).isNull();
    }

    @Test
    @DisplayName("日志滚动清理：根据 RetentionDuration 精准淘汰过期记录")
    void testCleanExpiredLogs() {
        String jobName = "expireTestJob";
        Instant now = Instant.now();

        // 插入 3 条日志：10 天前（应清理）、5 天前（应保留）、1 小时前（应保留）
        JobExecutionRecord recordOld = JobExecutionRecord.builder()
                .jobName(jobName)
                .fireTime(now.minus(10, ChronoUnit.DAYS))
                .status("SUCCESS")
                .resultMsg("10 days ago")
                .build();
        JobExecutionRecord recordMiddle = JobExecutionRecord.builder()
                .jobName(jobName)
                .fireTime(now.minus(5, ChronoUnit.DAYS))
                .status("SUCCESS")
                .resultMsg("5 days ago")
                .build();
        JobExecutionRecord recordRecent = JobExecutionRecord.builder()
                .jobName(jobName)
                .fireTime(now.minus(1, ChronoUnit.HOURS))
                .status("SUCCESS")
                .resultMsg("1 hour ago")
                .build();

        logStorageProvider.saveLog(recordOld);
        logStorageProvider.saveLog(recordMiddle);
        logStorageProvider.saveLog(recordRecent);

        assertThat(logStorageProvider.getLogCount(jobName)).isEqualTo(3);

        // 清理 7 天前的过期日志
        int cleaned = jobTemplate.cleanExpiredLogs(Duration.ofDays(7));
        assertThat(cleaned).isEqualTo(1);

        // 验证剩余 2 条日志
        List<JobExecutionRecord> remaining = jobTemplate.listRecentLogs(jobName, 10);
        assertThat(remaining).hasSize(2);
        assertThat(remaining).extracting(JobExecutionRecord::getResultMsg)
                .containsExactly("1 hour ago", "5 days ago");
    }

    @Test
    @DisplayName("生命周期延迟激活与优雅停机：SmartLifecycle 延迟触发调度，停机时确定性等待运行中任务排空")
    void testLifecycleDelayedActivationAndGracefulShutdown() throws Exception {
        assertThat(lifecycleManager.getPhase()).isEqualTo(Integer.MAX_VALUE - 100);
        assertThat(lifecycleManager.isAutoStartup()).isTrue();
        assertThat(lifecycleManager.isRunning()).isFalse();

        CountDownLatch taskRunningLatch = new CountDownLatch(1);
        CountDownLatch taskReleaseLatch = new CountDownLatch(1);
        AtomicBoolean taskExecuted = new AtomicBoolean(false);

        JobDefinition definition = JobDefinition.builder()
                .jobName("gracefulJob")
                .triggerType(TriggerType.FIXED_RATE)
                .fixedRate(Duration.ofMillis(100))
                .strategy(OverlappingStrategy.CONCURRENT)
                .status(JobStatus.RUNNING)
                .enabled(true)
                .build();

        ScheduledJobTask task = registry.register(definition, context -> {
            taskRunningLatch.countDown();
            try {
                taskReleaseLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            taskExecuted.set(true);
            return JobResult.success("Graceful completed");
        });

        // 验证尚未启动生命周期前，虽然任务状态是 RUNNING，但尚未被调度到底层 Scheduler
        assertThat(registry.isStarted()).isFalse();
        assertThat((Object) task.getScheduledFuture()).isNull();

        // 启动生命周期（模拟 Spring 完成依赖注入后启动 SmartLifecycle）
        lifecycleManager.start();
        assertThat(lifecycleManager.isRunning()).isTrue();
        assertThat(registry.isStarted()).isTrue();
        assertThat((Object) task.getScheduledFuture()).isNotNull();

        // 等待任务被触发并进入执行挂起
        boolean started = taskRunningLatch.await(3, TimeUnit.SECONDS);
        assertThat(started).isTrue();
        assertThat(task.isExecuting()).isTrue();

        // 异步发起生命周期停止（带 3 秒优雅超时），此时由于任务被 releaseLatch 挂起，stop 会进入排空等待
        CompletableFuture<Void> stopFuture = CompletableFuture.runAsync(() -> lifecycleManager.stop());

        // 确认 stop 正在等待任务排空
        Thread.sleep(200);
        assertThat(stopFuture.isDone()).isFalse();

        // 释放任务
        taskReleaseLatch.countDown();

        // 等待停机顺利完成
        stopFuture.get(3, TimeUnit.SECONDS);
        assertThat(lifecycleManager.isRunning()).isFalse();
        assertThat(taskExecuted.get()).isTrue();
        assertThat(task.isExecuting()).isFalse();
    }

    @Test
    @DisplayName("空安全与对抗性边界保护：空参数与非法查询保底返回，杜绝 NPE 与崩溃")
    void testAdversarialEdgeCases() {
        // 空日志查询
        assertThat(logStorageProvider.listLogs(null, 10)).isEmpty();
        assertThat(logStorageProvider.listLogs("nonExistingJob", 10)).isEmpty();
        assertThat(jobTemplate.listRecentLogs("nonExistingJob", 10)).isEmpty();

        // 非法保留时间
        assertThat(jobTemplate.cleanExpiredLogs(null)).isEqualTo(0);
        assertThat(jobTemplate.cleanExpiredLogs(Duration.ZERO)).isEqualTo(0);
        assertThat(jobTemplate.cleanExpiredLogs(Duration.ofSeconds(-10))).isEqualTo(0);

        // 空记录存储
        logStorageProvider.saveLog(null);
        logStorageProvider.saveLog(JobExecutionRecord.builder().build());

        // 元数据空安全
        metadataRepository.save(null);
        assertThat(metadataRepository.findByJobName(null)).isEmpty();
        assertThat(metadataRepository.findByJobName("notExist")).isEmpty();
        assertThat(metadataRepository.updateStatus(null, JobStatus.RUNNING)).isFalse();
        assertThat(metadataRepository.updateStatus("notExist", null)).isFalse();
        assertThat(metadataRepository.updateCron(null, "0 0 1 * * ?")).isFalse();
        assertThat(metadataRepository.updateCron("notExist", "")).isFalse();
        assertThat(metadataRepository.delete(null)).isFalse();
        assertThat(metadataRepository.delete("notExist")).isFalse();
        assertThat(metadataRepository.findAll()).isEmpty();
    }
}
