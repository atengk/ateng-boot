package io.github.atengk.boot.job.local;

import io.github.atengk.boot.job.local.actuator.LocalJobEndpoint;
import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.config.AtengJobLocalAutoConfiguration;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LocalJobTemplate} 完备对称门面与 Cron 校验预演集成测试
 *
 * @author Ateng
 * @since 2026-11-01
 */
@SpringBootTest(classes = LocalJobFacadeAndQueryTest.TestConfig.class)
@DisplayName("LocalJob 完备对称门面与 Cron 预演测试")
class LocalJobFacadeAndQueryTest {

    @Autowired
    private LocalJobTemplate jobTemplate;

    @Autowired
    private LocalJobEndpoint localJobEndpoint;

    @Autowired
    private FacadeQueryTasks tasks;

    @Test
    @DisplayName("测试 exists 门面：已注册任务返回 true，未知任务及空字符串返回 false")
    void testJobExists() {
        assertThat(jobTemplate.exists("cronQueryJob")).isTrue();
        assertThat(jobTemplate.exists("slowQueryJob")).isTrue();
        assertThat(jobTemplate.exists("nonExistentJob")).isFalse();
        assertThat(jobTemplate.exists("")).isFalse();
        assertThat(jobTemplate.exists("   ")).isFalse();
        assertThat(jobTemplate.exists(null)).isFalse();
    }

    @Test
    @DisplayName("测试 isExecuting 门面与 JobInfo.isExecuting：空闲时为 false，运行时为 true")
    void testJobIsExecuting() throws Exception {
        String jobName = "slowQueryJob";
        assertThat(jobTemplate.isExecuting(jobName)).isFalse();

        tasks.startedLatch = new CountDownLatch(1);
        tasks.releaseLatch = new CountDownLatch(1);

        // 异步触发慢任务
        CompletableFuture<JobResult> runFuture = CompletableFuture.supplyAsync(() -> jobTemplate.trigger(jobName));

        // 等待任务进入执行中状态
        boolean started = tasks.startedLatch.await(3, TimeUnit.SECONDS);
        assertThat(started).isTrue();

        // 验证正在执行中
        assertThat(jobTemplate.isExecuting(jobName)).isTrue();
        JobInfo executingInfo = jobTemplate.getJob(jobName);
        assertThat(executingInfo).isNotNull();
        assertThat(executingInfo.isExecuting()).isTrue();

        // 释放锁，等待任务正常完成
        tasks.releaseLatch.countDown();
        JobResult result = runFuture.get(3, TimeUnit.SECONDS);
        assertThat(result.isSuccess()).isTrue();

        // 验证执行完成后状态恢复为 false
        assertThat(jobTemplate.isExecuting(jobName)).isFalse();
        JobInfo finishedInfo = jobTemplate.getJob(jobName);
        assertThat(finishedInfo).isNotNull();
        assertThat(finishedInfo.isExecuting()).isFalse();

        // 验证未知任务返回 false
        assertThat(jobTemplate.isExecuting("nonExistentJob")).isFalse();
        assertThat(jobTemplate.isExecuting(null)).isFalse();
    }

    @Test
    @DisplayName("测试 isValidCron 门面：合法 6 位 Cron 表达式校验与非法格式拦截")
    void testIsValidCron() {
        // 合法 Spring 6 Cron
        assertThat(jobTemplate.isValidCron("0 0 2 * * ?")).isTrue();
        assertThat(jobTemplate.isValidCron("0/5 * * * * ?")).isTrue();
        assertThat(jobTemplate.isValidCron("0 0 12 ? * MON-FRI")).isTrue();
        assertThat(jobTemplate.isValidCron("0 15 10 ? * *")).isTrue();
        assertThat(jobTemplate.isValidCron("0 0 0 1 1 ?")).isTrue();

        // 非法格式
        assertThat(jobTemplate.isValidCron("invalid-cron")).isFalse();
        assertThat(jobTemplate.isValidCron("60 * * * * ?")).isFalse();
        assertThat(jobTemplate.isValidCron("* * * * * * *")).isFalse(); // 7 位不符合
        assertThat(jobTemplate.isValidCron("")).isFalse();
        assertThat(jobTemplate.isValidCron("   ")).isFalse();
        assertThat(jobTemplate.isValidCron(null)).isFalse();
    }

    @Test
    @DisplayName("测试 getNextExecutionTimes 门面：时钟点预演正序排列与非法保底")
    void testGetNextExecutionTimes() {
        String cron = "0 0 2 * * ?"; // 每天凌晨 2 点
        Instant baseTime = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneId.systemDefault()).toInstant();

        List<Instant> nextTimes = jobTemplate.getNextExecutionTimes(cron, 3, baseTime);
        assertThat(nextTimes).hasSize(3);

        // 严格正序递增
        assertThat(nextTimes.get(0)).isBefore(nextTimes.get(1));
        assertThat(nextTimes.get(1)).isBefore(nextTimes.get(2));

        // 验证第 1 次触发时间为 2026-11-01 02:00:00
        ZonedDateTime firstZdt = ZonedDateTime.ofInstant(nextTimes.get(0), ZoneId.systemDefault());
        assertThat(firstZdt.getYear()).isEqualTo(2026);
        assertThat(firstZdt.getMonthValue()).isEqualTo(11);
        assertThat(firstZdt.getDayOfMonth()).isEqualTo(1);
        assertThat(firstZdt.getHour()).isEqualTo(2);
        assertThat(firstZdt.getMinute()).isEqualTo(0);

        // 验证第 2 次触发时间为 2026-11-02 02:00:00 (间隔 24 小时)
        Duration gap = Duration.between(nextTimes.get(0), nextTimes.get(1));
        assertThat(gap).isEqualTo(Duration.ofDays(1));

        // 验证默认当前时间重载
        List<Instant> defaultTimes = jobTemplate.getNextExecutionTimes(cron, 2);
        assertThat(defaultTimes).hasSize(2);
        assertThat(defaultTimes.get(0)).isAfter(Instant.now().minusSeconds(1));

        // 验证非法输入防卫返回空集合
        assertThat(jobTemplate.getNextExecutionTimes("invalid-cron", 5)).isEmpty();
        assertThat(jobTemplate.getNextExecutionTimes(cron, 0)).isEmpty();
        assertThat(jobTemplate.getNextExecutionTimes(cron, -1)).isEmpty();

        // 验证上限防 OOM 防卫（超过 100 自动截断为 100）
        assertThat(jobTemplate.getNextExecutionTimes(cron, 500)).hasSize(100);
        assertThat(jobTemplate.getNextExecutionTimes(null, 5)).isEmpty();
    }

    @Test
    @DisplayName("测试 Actuator 端点：Cron 预演与单个任务详情中的 nextExecutionTimes")
    void testActuatorEndpointCronPreview() {
        // 1. 测试通过 /actuator/localjobs?cron=...&count=3 预演
        Map<String, Object> previewResp = localJobEndpoint.listJobs("0 0 2 * * ?", 3);
        assertThat(previewResp.get("cron")).isEqualTo("0 0 2 * * ?");
        assertThat(previewResp.get("valid")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Instant> nextTimes = (List<Instant>) previewResp.get("nextExecutionTimes");
        assertThat(nextTimes).hasSize(3);

        // 2. 测试非法 Cron
        Map<String, Object> invalidResp = localJobEndpoint.listJobs("bad-cron", 3);
        assertThat(invalidResp.get("valid")).isEqualTo(false);
        assertThat(invalidResp.get("message")).isNotNull();

        // 3. 测试查询单个任务详情包含 isExecuting 与 nextExecutionTimes
        Map<String, Object> jobDetail = localJobEndpoint.getJob("cronQueryJob");
        assertThat(jobDetail.get("status")).isEqualTo("OK");
        assertThat(jobDetail.get("isExecuting")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Instant> jobNextTimes = (List<Instant>) jobDetail.get("nextExecutionTimes");
        assertThat(jobNextTimes).isNotEmpty();
    }

    @Configuration(proxyBeanMethods = false)
    @Import(AtengJobLocalAutoConfiguration.class)
    static class TestConfig {
        @Bean
        public FacadeQueryTasks facadeQueryTasks() {
            return new FacadeQueryTasks();
        }
    }

    @Component
    static class FacadeQueryTasks {
        volatile CountDownLatch startedLatch = new CountDownLatch(1);
        volatile CountDownLatch releaseLatch = new CountDownLatch(1);

        @LocalJob(name = "cronQueryJob", cron = "0 0 2 * * ?", description = "每日健康巡检任务")
        public JobResult executeCron() {
            return JobResult.success("Cron execution completed");
        }

        @LocalJob(name = "slowQueryJob", description = "用于测试活跃执行状态的慢任务", fixedDelay = "1h", initialDelay = "1h")
        public JobResult executeSlow() throws InterruptedException {
            startedLatch.countDown();
            releaseLatch.await(5, TimeUnit.SECONDS);
            return JobResult.success("Slow task completed");
        }
    }
}
