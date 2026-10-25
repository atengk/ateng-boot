package io.github.atengk.boot.job.local;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.config.AtengJobLocalAutoConfiguration;
import io.github.atengk.boot.job.local.exception.JobTimeoutException;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.template.LocalJobTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务硬超时熔断、指数退避重试自愈与 MDC 全链路追踪对抗性测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
@SpringBootTest(classes = LocalJobTimeoutRetryAndMdcTest.TestAppConfig.class)
@DisplayName("LocalJob 硬超时熔断、指数退避重试与 MDC 链路追踪测试")
class LocalJobTimeoutRetryAndMdcTest {

    @Autowired
    private LocalJobTemplate jobTemplate;

    @Autowired
    private TimeoutAndRetryTasks tasks;

    @Autowired
    private ThreadPoolTaskScheduler taskScheduler;

    @Autowired
    @Qualifier("localJobWorkerExecutor")
    private ThreadPoolTaskExecutor workerExecutor;

    @AfterEach
    void tearDown() {
        // 全自洽单测铁律：无条件清理当前线程上下文现场，防止跨测试用例污染
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("硬超时熔断：任务执行超过 timeout 阈值时强制发送中断信号并抛出 JobTimeoutException")
    void testTimeoutInterruption() {
        String jobName = "timeoutJob";
        tasks.timeoutInterrupted.set(false);

        long start = System.currentTimeMillis();

        assertThatThrownBy(() -> jobTemplate.trigger(jobName))
                .isInstanceOf(JobTimeoutException.class)
                .satisfies(ex -> {
                    JobTimeoutException timeoutEx = (JobTimeoutException) ex;
                    assertThat(timeoutEx.getJobName()).isEqualTo(jobName);
                    assertThat(timeoutEx.getTimeout()).isEqualTo(Duration.ofMillis(500));
                    assertThat(timeoutEx.getMessage()).contains("execution timed out after");
                });

        long elapsed = System.currentTimeMillis() - start;

        // 验证执行耗时明显小于 5000ms（在 450ms~3000ms 之间截断）
        assertThat(elapsed).isLessThan(3500);

        // 验证被超时的工作线程收到了中断信号
        assertThat(tasks.timeoutInterrupted.get()).isTrue();

        // 验证运行时元数据记录了 TIMEOUT 终态
        JobInfo jobInfo = jobTemplate.getJob(jobName);
        assertThat(jobInfo.getLastStatus()).isEqualTo("TIMEOUT");
        assertThat(jobInfo.getLastResultMsg()).contains("execution timed out after");
    }

    @Test
    @DisplayName("指数退避重试：前 2 次偶发异常后第 3 次成功自愈并记录重试阶段日志")
    void testRetrySuccessSelfHealing() {
        String jobName = "retrySuccessJob";
        tasks.retrySuccessCount.set(0);

        JobResult result = jobTemplate.trigger(jobName);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Recovered on attempt 3");
        assertThat(tasks.retrySuccessCount.get()).isEqualTo(3);

        JobInfo jobInfo = jobTemplate.getJob(jobName);
        assertThat(jobInfo.getLastStatus()).isEqualTo("SUCCESS");
        assertThat(jobInfo.getLastResultMsg()).isEqualTo("Recovered on attempt 3");
    }

    @Test
    @DisplayName("重试耗尽判定失败：连续达到 maxRetries 仍未自愈时标记 FAILED 并保留根因")
    void testRetryExhaustedFailure() {
        String jobName = "retryExhaustedJob";
        tasks.retryExhaustedCount.set(0);

        JobResult result = jobTemplate.trigger(jobName);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("Permanent simulated error");
        // 初始 1 次 + 重试 2 次 = 共 3 次尝试
        assertThat(tasks.retryExhaustedCount.get()).isEqualTo(3);

        JobInfo jobInfo = jobTemplate.getJob(jobName);
        assertThat(jobInfo.getLastStatus()).isEqualTo("FAILED");
        assertThat(jobInfo.getLastResultMsg()).contains("Permanent simulated error");
    }

    @Test
    @DisplayName("MDC 链路追踪注入：调度触发前自动注入 32 位 UUID 并透传至 JobContext 与 User 上下文")
    void testMdcAndContextInjectionDuringExecution() {
        String jobName = "mdcJob";
        tasks.capturedTraceId.set(null);
        tasks.capturedMdcTraceId.set(null);
        tasks.capturedUser.set(null);

        // 执行前当前测试线程现场应保持纯净
        assertThat(MDC.get("traceId")).isNull();
        assertThat(UserContextHolder.get()).isNull();

        JobResult result = jobTemplate.trigger(jobName);
        assertThat(result.isSuccess()).isTrue();

        // 验证 JobContext 与 MDC 中的 traceId 一致且为 32 位无横线 UUID
        String traceIdInContext = tasks.capturedTraceId.get();
        String traceIdInMdc = tasks.capturedMdcTraceId.get();

        assertThat(traceIdInContext).isNotNull();
        assertThat(traceIdInContext).hasSize(32);
        assertThat(traceIdInContext).doesNotContain("-");
        assertThat(traceIdInMdc).isEqualTo(traceIdInContext);

        // 验证默认系统操作人上下文注入为 SYSTEM
        assertThat(tasks.capturedUser.get()).isEqualTo("SYSTEM");

        // 验证任务记录也已同步记录上次的 lastTraceId
        JobInfo jobInfo = jobTemplate.getJob(jobName);
        assertThat(jobInfo.getLastTraceId()).isEqualTo(traceIdInContext);
    }

    @Test
    @DisplayName("现场清理不变量：任务执行完毕后调用方线程与调度/Worker 线程池 MDC 现场被彻底清空")
    void testContextAndMdcCleanupAcrossThreadPool() throws ExecutionException, InterruptedException, TimeoutException {
        // 1. 触发任务执行
        jobTemplate.trigger("mdcJob");

        // 2. 验证当前主调用线程 MDC 与用户上下文已被严格清理
        assertThat(MDC.get("traceId")).isNull();
        assertThat(UserContextHolder.get()).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();

        // 3. 验证调度线程池各工作线程中的 MDC 也处于绝对纯净状态
        Future<String> schedulerMdc = taskScheduler.submit(() -> MDC.get("traceId"));
        assertThat(schedulerMdc.get(2, TimeUnit.SECONDS)).isNull();

        // 4. 验证 Worker 线程池中的 MDC 与操作人也处于绝对纯净状态
        Future<String> workerMdc = workerExecutor.submit(() -> MDC.get("traceId"));
        Future<String> workerUser = workerExecutor.submit(UserContextHolder::get);
        assertThat(workerMdc.get(2, TimeUnit.SECONDS)).isNull();
        assertThat(workerUser.get(2, TimeUnit.SECONDS)).isNull();
    }

    @Test
    @DisplayName("超时熔断与指数退避重试复合对抗：前 2 次超时中断后第 3 次成功自愈完成")
    void testTimeoutWithRetryRecovery() {
        String jobName = "timeoutRetryJob";
        tasks.timeoutRetryCount.set(0);

        JobResult result = jobTemplate.trigger(jobName);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Recovered after timeout on attempt 3");
        assertThat(tasks.timeoutRetryCount.get()).isEqualTo(3);

        JobInfo jobInfo = jobTemplate.getJob(jobName);
        assertThat(jobInfo.getLastStatus()).isEqualTo("SUCCESS");
        assertThat(jobInfo.getLastResultMsg()).isEqualTo("Recovered after timeout on attempt 3");
    }

    @Test
    @DisplayName("调用方原有上下文保护：当调用线程已持有原有 MDC 与 User 时，执行后精准还原而不被擦除")
    void testCallerContextPreservedWhenPreExisting() {
        MDC.put("traceId", "caller-trace-999");
        UserContextHolder.set("operator-admin");
        TenantContextHolder.setTenantId("tenant-100");

        JobResult result = jobTemplate.trigger("mdcJob");
        assertThat(result.isSuccess()).isTrue();

        // 调用方原有现场完好无损地被保留
        assertThat(MDC.get("traceId")).isEqualTo("caller-trace-999");
        assertThat(UserContextHolder.get()).isEqualTo("operator-admin");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-100");
    }

    @Test
    @DisplayName("JobResult.failure 触发自适应退避重试：前 2 次显式返回 failure 后第 3 次返回 success 自愈，并验证 TenantId 透传")
    void testRetryOnJobResultFailure() {
        String jobName = "jobResultRetryJob";
        tasks.jobResultRetryCount.set(0);
        tasks.capturedTenant.set(null);

        TenantContextHolder.setTenantId("tenant-456");

        JobResult result = jobTemplate.trigger(jobName);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Recovered from business failure on attempt 3");
        assertThat(tasks.jobResultRetryCount.get()).isEqualTo(3);
        assertThat(tasks.capturedTenant.get()).isEqualTo("tenant-456");

        JobInfo jobInfo = jobTemplate.getJob(jobName);
        assertThat(jobInfo.getLastStatus()).isEqualTo("SUCCESS");
        assertThat(jobInfo.getLastResultMsg()).isEqualTo("Recovered from business failure on attempt 3");
    }

    @Configuration
    @Import(AtengJobLocalAutoConfiguration.class)
    static class TestAppConfig {

        @Bean
        public TimeoutAndRetryTasks timeoutAndRetryTasks() {
            return new TimeoutAndRetryTasks();
        }
    }

    @Component
    static class TimeoutAndRetryTasks {

        final AtomicBoolean timeoutInterrupted = new AtomicBoolean(false);
        final AtomicInteger retrySuccessCount = new AtomicInteger(0);
        final AtomicInteger retryExhaustedCount = new AtomicInteger(0);
        final AtomicInteger timeoutRetryCount = new AtomicInteger(0);
        final AtomicInteger jobResultRetryCount = new AtomicInteger(0);
        final AtomicReference<String> capturedTenant = new AtomicReference<>();

        final AtomicReference<String> capturedTraceId = new AtomicReference<>();
        final AtomicReference<String> capturedMdcTraceId = new AtomicReference<>();
        final AtomicReference<String> capturedUser = new AtomicReference<>();

        @LocalJob(name = "timeoutJob", timeout = "500ms", fixedDelay = "1h", initialDelay = "1h")
        public void executeTimeoutJob() {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                timeoutInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
        }

        @LocalJob(name = "retrySuccessJob", maxRetries = 2, retryInterval = "50ms", fixedDelay = "1h", initialDelay = "1h")
        public JobResult executeRetrySuccess() {
            int attempt = retrySuccessCount.incrementAndGet();
            if (attempt <= 2) {
                throw new IllegalStateException("Transient simulated error attempt " + attempt);
            }
            return JobResult.success("Recovered on attempt " + attempt);
        }

        @LocalJob(name = "retryExhaustedJob", maxRetries = 2, retryInterval = "50ms", fixedDelay = "1h", initialDelay = "1h")
        public void executeRetryExhausted() {
            int attempt = retryExhaustedCount.incrementAndGet();
            throw new RuntimeException("Permanent simulated error on attempt " + attempt);
        }

        @LocalJob(name = "mdcJob", fixedDelay = "1h", initialDelay = "1h")
        public JobResult executeMdcJob(JobContext context) {
            capturedTraceId.set(context.getTraceId());
            capturedMdcTraceId.set(MDC.get("traceId"));
            capturedUser.set(UserContextHolder.get());
            context.log("Executed with traceId=" + context.getTraceId());
            return JobResult.success("MDC verified");
        }

        @LocalJob(name = "timeoutRetryJob", timeout = "300ms", maxRetries = 2, retryInterval = "50ms", fixedDelay = "1h", initialDelay = "1h")
        public String executeTimeoutRetry() {
            int attempt = timeoutRetryCount.incrementAndGet();
            if (attempt <= 2) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return "Recovered after timeout on attempt " + attempt;
        }

        @LocalJob(name = "jobResultRetryJob", maxRetries = 2, retryInterval = "50ms", fixedDelay = "1h", initialDelay = "1h")
        public JobResult executeJobResultRetry() {
            capturedTenant.set(TenantContextHolder.getTenantId());
            int attempt = jobResultRetryCount.incrementAndGet();
            if (attempt <= 2) {
                return JobResult.failure("Business error attempt " + attempt);
            }
            return JobResult.success("Recovered from business failure on attempt " + attempt);
        }
    }
}
