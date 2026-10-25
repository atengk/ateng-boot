package io.github.atengk.boot.job.local;

import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.config.AtengJobLocalAutoConfiguration;
import io.github.atengk.boot.job.local.enums.OverlappingStrategy;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.template.LocalJobTemplate;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 四阶并发重叠策略与 {@link LocalJobTemplate} 门面操作对抗性集成测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
@SpringBootTest(classes = LocalJobOverlappingAndFacadeTest.TestAppConfig.class)
@DisplayName("LocalJob 四阶并发防卫与动态执行门面测试")
class LocalJobOverlappingAndFacadeTest {

    @Autowired
    private LocalJobTemplate jobTemplate;

    @Autowired
    private ConcurrencyTasks concurrencyTasks;

    @Test
    @DisplayName("DISCARD 策略：重叠调度到来时直接跳过并标记 DISCARDED")
    void testDiscardOverlappingStrategy() throws Exception {
        String jobName = "discardJob";
        concurrencyTasks.discardStartedLatch = new CountDownLatch(1);
        concurrencyTasks.discardReleaseLatch = new CountDownLatch(1);

        // 启动第 1 次执行（阻塞在 releaseLatch）
        CompletableFuture<JobResult> firstRun = CompletableFuture.supplyAsync(() -> jobTemplate.trigger(jobName));

        // 等待第 1 次执行已进入执行状态持有锁
        boolean started = concurrencyTasks.discardStartedLatch.await(2, TimeUnit.SECONDS);
        assertThat(started).isTrue();

        // 在第 1 次执行期间，发起第 2 次触发，由于 DISCARD 策略应被立即跳过
        JobResult secondResult = jobTemplate.trigger(jobName);
        assertThat(secondResult.isSuccess()).isFalse();
        assertThat(secondResult.getMessage()).contains("DISCARD");

        JobInfo jobInfo = jobTemplate.getJob(jobName);
        assertThat(jobInfo.getLastStatus()).isEqualTo("DISCARDED");

        // 释放第 1 次执行
        concurrencyTasks.discardReleaseLatch.countDown();
        JobResult firstResult = firstRun.get(2, TimeUnit.SECONDS);
        assertThat(firstResult.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("QUEUE 策略：重叠调度到来时严格串行排队执行")
    void testQueueOverlappingStrategy() throws Exception {
        String jobName = "queueJob";
        concurrencyTasks.queueCount.set(0);

        long start = System.currentTimeMillis();
        CompletableFuture<JobResult> f1 = CompletableFuture.supplyAsync(() -> jobTemplate.trigger(jobName));
        CompletableFuture<JobResult> f2 = CompletableFuture.supplyAsync(() -> jobTemplate.trigger(jobName));

        CompletableFuture.allOf(f1, f2).get(3, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(f1.get().isSuccess()).isTrue();
        assertThat(f2.get().isSuccess()).isTrue();
        assertThat(concurrencyTasks.queueCount.get()).isEqualTo(2);
        // 单次执行耗时 200ms，严格排队串行后总耗时必须大于等于 350ms
        assertThat(elapsed).isGreaterThanOrEqualTo(350);
    }

    @Test
    @DisplayName("CONCURRENT 策略：重叠调度到来时允许多线程并发并行执行")
    void testConcurrentOverlappingStrategy() throws Exception {
        String jobName = "concurrentJob";
        concurrencyTasks.concurrentCount.set(0);

        long start = System.currentTimeMillis();
        CompletableFuture<JobResult> f1 = CompletableFuture.supplyAsync(() -> jobTemplate.trigger(jobName));
        CompletableFuture<JobResult> f2 = CompletableFuture.supplyAsync(() -> jobTemplate.trigger(jobName));

        CompletableFuture.allOf(f1, f2).get(3, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(f1.get().isSuccess()).isTrue();
        assertThat(f2.get().isSuccess()).isTrue();
        assertThat(concurrencyTasks.concurrentCount.get()).isEqualTo(2);
        // 2 个任务各自耗时 200ms，并发执行下总耗时显著小于串行（小于 380ms）
        assertThat(elapsed).isLessThan(380);
    }

    @Test
    @DisplayName("INTERRUPT 策略：新调度到来时尝试中断正在运行的旧线程")
    void testInterruptOverlappingStrategy() throws Exception {
        String jobName = "interruptJob";
        concurrencyTasks.interruptStartedLatch = new CountDownLatch(1);
        concurrencyTasks.interruptedFlag.set(false);

        // 启动第 1 次执行（长耗时任务）
        CompletableFuture.supplyAsync(() -> jobTemplate.trigger(jobName));

        boolean started = concurrencyTasks.interruptStartedLatch.await(2, TimeUnit.SECONDS);
        assertThat(started).isTrue();

        // 触发第 2 次执行，应当发出中断信号中断旧任务
        JobResult secondResult = jobTemplate.trigger(jobName);
        assertThat(secondResult.isSuccess()).isTrue();

        // 验证旧线程被成功设置了中断标记
        assertThat(concurrencyTasks.interruptedFlag.get()).isTrue();
    }

    @Test
    @DisplayName("方法参数 JobContext 自动注入与 JobResult/String 返回值成果捕获")
    void testJobContextAndResultExtraction() {
        // 1. 验证 JobContext 传参与 JobResult 返回
        JobResult result = jobTemplate.trigger("contextJob", Collections.singletonMap("batchSize", 100));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Processed 100 records");

        JobInfo info = jobTemplate.getJob("contextJob");
        assertThat(info.getLastResultMsg()).isEqualTo("Processed 100 records");
        assertThat(info.getLastStatus()).isEqualTo("SUCCESS");

        // 2. 验证纯 String 返回值
        JobResult stringResult = jobTemplate.trigger("stringReturnJob");
        assertThat(stringResult.isSuccess()).isTrue();
        assertThat(stringResult.getMessage()).isEqualTo("Custom String Summary");
        assertThat(jobTemplate.getJob("stringReturnJob").getLastResultMsg()).isEqualTo("Custom String Summary");
    }

    @Test
    @DisplayName("LocalJobTemplate 编程式注册、更新 Cron、启停与注销完整生命周期")
    void testTemplateProgrammaticLifecycle() {
        AtomicInteger dynamicCount = new AtomicInteger(0);
        String dynamicJobName = "dynamicJob1";

        // 1. 编程式注册固定频率任务
        boolean registered = jobTemplate.registerFixedRate(dynamicJobName, Duration.ofMillis(100), dynamicCount::incrementAndGet);
        assertThat(registered).isTrue();
        assertThat(jobTemplate.getJob(dynamicJobName)).isNotNull();

        // 2. 暂停任务
        boolean paused = jobTemplate.pause(dynamicJobName);
        assertThat(paused).isTrue();

        // 3. 动态更新为 Cron 任务
        boolean updated = jobTemplate.updateCron(dynamicJobName, "0/1 * * * * ?");
        assertThat(updated).isTrue();
        assertThat(jobTemplate.getJob(dynamicJobName).getCron()).isEqualTo("0/1 * * * * ?");

        // 4. 手动触发
        JobResult triggerResult = jobTemplate.trigger(dynamicJobName);
        assertThat(triggerResult.isSuccess()).isTrue();
        assertThat(dynamicCount.get()).isGreaterThanOrEqualTo(1);

        // 5. 注销移除任务
        boolean removed = jobTemplate.remove(dynamicJobName);
        assertThat(removed).isTrue();
        assertThat(jobTemplate.getJob(dynamicJobName)).isNull();

        // 6. 列出所有任务清单非空
        List<JobInfo> allJobs = jobTemplate.listJobs();
        assertThat(allJobs).isNotEmpty();
    }

    @Configuration
    @Import(AtengJobLocalAutoConfiguration.class)
    static class TestAppConfig {

        @Bean
        public ConcurrencyTasks concurrencyTasks() {
            return new ConcurrencyTasks();
        }
    }

    @Component
    static class ConcurrencyTasks {

        volatile CountDownLatch discardStartedLatch;
        volatile CountDownLatch discardReleaseLatch;

        final AtomicInteger queueCount = new AtomicInteger(0);
        final AtomicInteger concurrentCount = new AtomicInteger(0);

        volatile CountDownLatch interruptStartedLatch;
        final AtomicBoolean interruptedFlag = new AtomicBoolean(false);

        @LocalJob(name = "discardJob", strategy = OverlappingStrategy.DISCARD, fixedDelay = "1h")
        public void executeDiscard() {
            if (discardStartedLatch != null) {
                discardStartedLatch.countDown();
            }
            if (discardReleaseLatch != null) {
                try {
                    discardReleaseLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @LocalJob(name = "queueJob", strategy = OverlappingStrategy.QUEUE, fixedDelay = "1h")
        public void executeQueue() {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            queueCount.incrementAndGet();
        }

        @LocalJob(name = "concurrentJob", strategy = OverlappingStrategy.CONCURRENT, fixedDelay = "1h")
        public void executeConcurrent() {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            concurrentCount.incrementAndGet();
        }

        @LocalJob(name = "interruptJob", strategy = OverlappingStrategy.INTERRUPT, fixedDelay = "1h")
        public void executeInterrupt() {
            if (interruptStartedLatch != null && interruptStartedLatch.getCount() > 0) {
                interruptStartedLatch.countDown();
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    interruptedFlag.set(true);
                    Thread.currentThread().interrupt();
                }
            }
        }

        @LocalJob(name = "contextJob", fixedDelay = "1h")
        public JobResult executeWithContext(JobContext context) {
            context.log("processing batch");
            Object batchSize = context.getParams().getOrDefault("batchSize", 0);
            return JobResult.success("Processed " + batchSize + " records");
        }

        @LocalJob(name = "stringReturnJob", fixedDelay = "1h")
        public String executeStringReturn() {
            return "Custom String Summary";
        }
    }
}
