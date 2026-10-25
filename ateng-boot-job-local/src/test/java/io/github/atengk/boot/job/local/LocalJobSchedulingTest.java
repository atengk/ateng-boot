package io.github.atengk.boot.job.local;

import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.config.AtengJobLocalAutoConfiguration;
import io.github.atengk.boot.job.local.enums.JobStatus;
import io.github.atengk.boot.job.local.enums.TriggerType;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.registry.LocalJobRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LocalJob} 基础定时调度端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
@SpringBootTest(classes = LocalJobSchedulingTest.TestAppConfig.class)
@DisplayName("LocalJob 声明式基础定时调度测试")
class LocalJobSchedulingTest {

    @Autowired
    private LocalJobRegistry registry;

    @Autowired
    private SampleScheduledTasks sampleTasks;

    @Test
    @DisplayName("FixedRate 任务成功按指定频率周期性触发执行")
    void testFixedRateJobExecution() throws InterruptedException {
        boolean completed = sampleTasks.fixedRateLatch.await(3, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(sampleTasks.fixedRateCount.get()).isGreaterThanOrEqualTo(2);

        JobInfo jobInfo = registry.getJobInfo("sampleFixedRateJob");
        assertThat(jobInfo).isNotNull();
        assertThat(jobInfo.getTriggerType()).isEqualTo(TriggerType.FIXED_RATE);
        assertThat(jobInfo.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(jobInfo.getLastExecutionTime()).isNotNull();
        assertThat(jobInfo.getLastStatus()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("FixedDelay 任务成功按指定延迟周期性触发执行")
    void testFixedDelayJobExecution() throws InterruptedException {
        boolean completed = sampleTasks.fixedDelayLatch.await(3, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(sampleTasks.fixedDelayCount.get()).isGreaterThanOrEqualTo(2);

        JobInfo jobInfo = registry.getJobInfo("sampleFixedDelayJob");
        assertThat(jobInfo).isNotNull();
        assertThat(jobInfo.getTriggerType()).isEqualTo(TriggerType.FIXED_DELAY);
        assertThat(jobInfo.getStatus()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    @DisplayName("Cron 任务成功按秒级 Cron 表达式准确触发执行")
    void testCronJobExecution() throws InterruptedException {
        boolean completed = sampleTasks.cronLatch.await(4, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(sampleTasks.cronCount.get()).isGreaterThanOrEqualTo(1);

        JobInfo jobInfo = registry.getJobInfo("sampleCronJob");
        assertThat(jobInfo).isNotNull();
        assertThat(jobInfo.getTriggerType()).isEqualTo(TriggerType.CRON);
        assertThat(jobInfo.getCron()).isEqualTo("0/1 * * * * ?");
    }

    @Test
    @DisplayName("支持动态暂停 (pause) 与恢复 (resume) 任务调度")
    void testPauseAndResumeJob() throws InterruptedException {
        String jobName = "sampleFixedRateJob";
        boolean paused = registry.pause(jobName);
        assertThat(paused).isTrue();
        assertThat(registry.getJobInfo(jobName).getStatus()).isEqualTo(JobStatus.PAUSED);

        int countAfterPause = sampleTasks.fixedRateCount.get();
        Thread.sleep(400);
        // 暂停期间执行计数不应发生显著递增（允许当前已入队的至多 1 次）
        assertThat(sampleTasks.fixedRateCount.get()).isLessThanOrEqualTo(countAfterPause + 1);

        boolean resumed = registry.resume(jobName);
        assertThat(resumed).isTrue();
        assertThat(registry.getJobInfo(jobName).getStatus()).isEqualTo(JobStatus.RUNNING);

        Thread.sleep(400);
        assertThat(sampleTasks.fixedRateCount.get()).isGreaterThan(countAfterPause);
    }

    @Test
    @DisplayName("支持手动立即触发执行一次 (trigger)")
    void testManualTriggerJob() {
        String jobName = "sampleFixedRateJob";
        int beforeCount = sampleTasks.fixedRateCount.get();

        JobResult result = registry.trigger(jobName, null);
        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(sampleTasks.fixedRateCount.get()).isGreaterThan(beforeCount);
    }

    @Test
    @DisplayName("LocalJobRegistry 注册前置 Fail-Fast 卫语句防卫")
    void testRegistryDefensiveValidation() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                registry.register(null, context -> null));

        io.github.atengk.boot.job.local.model.JobDefinition emptyNameDef =
                io.github.atengk.boot.job.local.model.JobDefinition.builder().jobName("").build();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                registry.register(emptyNameDef, context -> null));

        io.github.atengk.boot.job.local.model.JobDefinition validDef =
                io.github.atengk.boot.job.local.model.JobDefinition.builder().jobName("validJob").build();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                registry.register(validDef, null));
    }

    @Configuration
    @Import(AtengJobLocalAutoConfiguration.class)
    static class TestAppConfig {

        @Bean
        public SampleScheduledTasks sampleScheduledTasks() {
            return new SampleScheduledTasks();
        }
    }

    @Component
    static class SampleScheduledTasks {

        final AtomicInteger fixedRateCount = new AtomicInteger(0);
        final CountDownLatch fixedRateLatch = new CountDownLatch(2);

        final AtomicInteger fixedDelayCount = new AtomicInteger(0);
        final CountDownLatch fixedDelayLatch = new CountDownLatch(2);

        final AtomicInteger cronCount = new AtomicInteger(0);
        final CountDownLatch cronLatch = new CountDownLatch(1);

        @LocalJob(name = "sampleFixedRateJob", fixedRate = "100ms", initialDelay = "10ms", description = "测试固定频率任务")
        public void executeFixedRate() {
            fixedRateCount.incrementAndGet();
            fixedRateLatch.countDown();
        }

        @LocalJob(name = "sampleFixedDelayJob", fixedDelay = "100ms", initialDelay = "10ms", description = "测试固定延迟任务")
        public void executeFixedDelay() {
            fixedDelayCount.incrementAndGet();
            fixedDelayLatch.countDown();
        }

        @LocalJob(name = "sampleCronJob", cron = "0/1 * * * * ?", description = "测试秒级Cron任务")
        public void executeCron() {
            cronCount.incrementAndGet();
            cronLatch.countDown();
        }
    }
}
