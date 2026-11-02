package io.github.atengk.boot.job.snail.watchdog;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import io.github.atengk.boot.job.snail.autoconfigure.AtengSnailJobAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SnailJob 本地硬看门狗两级超时降级与就地补偿单元测试
 *
 * @author Ateng
 * @since 2026-11-02
 */
@SpringBootTest(classes = {
        AtengSnailJobAutoConfiguration.class,
        SnailJobTimeoutFallbackTest.TestConfig.class
})
class SnailJobTimeoutFallbackTest {

    @Autowired
    private TestFallbackJobService fallbackJobService;

    @Autowired
    private TestGlobalTimeoutFallbackHandler globalFallbackHandler;

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        if (globalFallbackHandler != null) {
            globalFallbackHandler.clear();
        }
        if (fallbackJobService != null) {
            fallbackJobService.reset();
        }
    }

    @Test
    @DisplayName("测试超时工作线程捕获中断后就地执行自定义降级方法并返回成功结果")
    void shouldExecuteInPlaceFallbackWithCustomResult() throws Exception {
        JobArgs jobArgs = new JobArgs();
        jobArgs.setJobId(101L);
        jobArgs.setTaskBatchId(201L);
        jobArgs.setJobParams("{\"traceId\":\"trace-fallback-custom\"}");

        ExecuteResult result = fallbackJobService.executeWithCustomFallback(jobArgs);

        assertThat(result).isNotNull();
        assertThat(result.getResult()).isEqualTo("custom-fallback-result");
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(globalFallbackHandler.getInvocations()).isEmpty();
    }

    @Test
    @DisplayName("测试超时就地降级补偿方法形参智能匹配 (metadata, elapsed, throwable, jobArgs)")
    void shouldExecuteInPlaceFallbackWithParameterMatching() throws Exception {
        JobArgs jobArgs = new JobArgs();
        jobArgs.setJobId(102L);
        jobArgs.setTaskBatchId(202L);
        jobArgs.setJobParams("{\"traceId\":\"trace-fallback-params\"}");

        ExecuteResult result = fallbackJobService.executeWithParamFallback(jobArgs);

        assertThat(result).isNotNull();
        assertThat(result.getResult()).isEqualTo("param-fallback-success");

        assertThat(fallbackJobService.getCapturedMetadata()).isNotNull();
        assertThat(fallbackJobService.getCapturedMetadata().getJobName()).isEqualTo("testParamFallbackJob");
        assertThat(fallbackJobService.getCapturedMetadata().getJobId()).isEqualTo(102L);
        assertThat(fallbackJobService.getCapturedElapsed()).isNotNull();
        assertThat(fallbackJobService.getCapturedElapsed().toMillis()).isGreaterThanOrEqualTo(50);
        assertThat(fallbackJobService.getCapturedThrowable()).isInstanceOf(InterruptedException.class);
        assertThat(fallbackJobService.getCapturedJobArgs()).isSameAs(jobArgs);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(globalFallbackHandler.getInvocations()).isEmpty();
    }

    @Test
    @DisplayName("测试无返回值 void 就地降级补偿方法执行并生成默认保底成功响应")
    void shouldExecuteInPlaceFallbackWithVoidReturn() throws Exception {
        JobArgs jobArgs = new JobArgs();
        jobArgs.setJobId(103L);
        jobArgs.setTaskBatchId(203L);
        jobArgs.setJobParams("{\"traceId\":\"trace-fallback-void\"}");

        ExecuteResult result = fallbackJobService.executeWithVoidFallback(jobArgs);

        assertThat(result).isNotNull();
        assertThat(result.getMessage()).contains("[FALLBACK] 任务超时就地降级补偿完成: voidFallback");
        assertThat(fallbackJobService.isVoidFallbackExecuted()).isTrue();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(globalFallbackHandler.getInvocations()).isEmpty();
    }

    @Test
    @DisplayName("测试就地降级补偿方法抛出异常时自动回退触发全局超时降级 SPI")
    void shouldTriggerGlobalSpiWhenInPlaceFallbackFails() throws InterruptedException {
        globalFallbackHandler.resetLatch(1);

        JobArgs jobArgs = new JobArgs();
        jobArgs.setJobId(104L);
        jobArgs.setTaskBatchId(204L);
        jobArgs.setJobParams("{\"traceId\":\"trace-fallback-broken\"}");

        assertThatThrownBy(() -> fallbackJobService.executeWithBrokenFallback(jobArgs))
                .isInstanceOf(InterruptedException.class);

        boolean received = globalFallbackHandler.await(3, TimeUnit.SECONDS);
        assertThat(received).isTrue();

        List<FallbackInvocation> invocations = globalFallbackHandler.getInvocations();
        assertThat(invocations).hasSize(1);
        FallbackInvocation invocation = invocations.get(0);
        assertThat(invocation.metadata().getJobName()).isEqualTo("testBrokenFallbackJob");
        assertThat(invocation.metadata().getJobId()).isEqualTo(104L);
        assertThat(invocation.elapsed().toMillis()).isGreaterThanOrEqualTo(50);
    }

    @Test
    @DisplayName("测试未声明就地降级补偿方法时直接触发全局超时降级 SPI")
    void shouldTriggerGlobalSpiWhenNoInPlaceFallbackConfigured() throws InterruptedException {
        globalFallbackHandler.resetLatch(1);

        JobArgs jobArgs = new JobArgs();
        jobArgs.setJobId(105L);
        jobArgs.setTaskBatchId(205L);
        jobArgs.setJobParams("{\"traceId\":\"trace-no-fallback\"}");

        assertThatThrownBy(() -> fallbackJobService.executeWithoutFallback(jobArgs))
                .isInstanceOf(InterruptedException.class);

        boolean received = globalFallbackHandler.await(3, TimeUnit.SECONDS);
        assertThat(received).isTrue();

        List<FallbackInvocation> invocations = globalFallbackHandler.getInvocations();
        assertThat(invocations).hasSize(1);
        FallbackInvocation invocation = invocations.get(0);
        assertThat(invocation.metadata().getJobName()).isEqualTo("testNoFallbackJob");
        assertThat(invocation.metadata().getJobId()).isEqualTo(105L);
    }

    @Test
    @DisplayName("测试工作线程顽固未终止时看门狗第二级宽限期异步派发全局降级补偿")
    void shouldDispatchEmergencyAsyncFallbackWhenWorkerThreadDoesNotTerminate() throws InterruptedException {
        CountDownLatch emergencyLatch = new CountDownLatch(1);
        AtomicReference<SnailJobExecutionMetadata> capturedMeta = new AtomicReference<>();
        AtomicReference<Duration> capturedDuration = new AtomicReference<>();

        JobTimeoutFallbackHandler emergencyHandler = (metadata, elapsed) -> {
            capturedMeta.set(metadata);
            capturedDuration.set(elapsed);
            emergencyLatch.countDown();
        };

        SnailLocalWatchdog watchdog = new SnailLocalWatchdog(
                null,
                null,
                Duration.ofMillis(80),
                List.of(emergencyHandler),
                null
        );

        AtomicBoolean keepRunning = new AtomicBoolean(true);
        Thread stubbornWorker = new Thread(() -> {
            while (keepRunning.get()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    // 顽固忽略强中断，持续霸占线程
                }
            }
        }, "stubborn-worker-test");

        stubbornWorker.start();

        SnailJobExecutionMetadata metadata = SnailJobExecutionMetadata.builder()
                .jobName("stubbornJob")
                .jobId(999L)
                .taskBatchId(888L)
                .traceId("trace-emergency")
                .build();

        try {
            // 设置 50ms 超时，宽限期 80ms
            SnailLocalWatchdog.WatchdogSession session = watchdog.startWatch(
                    stubbornWorker,
                    Duration.ofMillis(50),
                    metadata
            );

            boolean received = emergencyLatch.await(3, TimeUnit.SECONDS);
            assertThat(received).isTrue();

            assertThat(capturedMeta.get()).isNotNull();
            assertThat(capturedMeta.get().getJobName()).isEqualTo("stubbornJob");
            assertThat(capturedDuration.get()).isNotNull();
            assertThat(capturedDuration.get().toMillis()).isGreaterThanOrEqualTo(120);

            session.cancel();
        } finally {
            keepRunning.set(false);
            stubbornWorker.interrupt();
            stubbornWorker.join(2000);
            watchdog.destroy();
        }
    }

    @TestConfiguration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class TestConfig {

        @Bean
        public TestFallbackJobService testFallbackJobService() {
            return new TestFallbackJobService();
        }

        @Bean
        public TestGlobalTimeoutFallbackHandler testGlobalTimeoutFallbackHandler() {
            return new TestGlobalTimeoutFallbackHandler();
        }
    }

    @Service
    public static class TestFallbackJobService {

        final AtomicReference<SnailJobExecutionMetadata> capturedMetadata = new AtomicReference<>();
        final AtomicReference<Duration> capturedElapsed = new AtomicReference<>();
        final AtomicReference<Throwable> capturedThrowable = new AtomicReference<>();
        final AtomicReference<JobArgs> capturedJobArgs = new AtomicReference<>();
        final AtomicBoolean voidFallbackExecuted = new AtomicBoolean(false);

        public SnailJobExecutionMetadata getCapturedMetadata() {
            return capturedMetadata.get();
        }

        public Duration getCapturedElapsed() {
            return capturedElapsed.get();
        }

        public Throwable getCapturedThrowable() {
            return capturedThrowable.get();
        }

        public JobArgs getCapturedJobArgs() {
            return capturedJobArgs.get();
        }

        public boolean isVoidFallbackExecuted() {
            return voidFallbackExecuted.get();
        }

        public void reset() {
            capturedMetadata.set(null);
            capturedElapsed.set(null);
            capturedThrowable.set(null);
            capturedJobArgs.set(null);
            voidFallbackExecuted.set(false);
        }

        @JobExecutor(name = "testCustomFallbackJob")
        @AtengSnailJob(timeout = "60ms", fallbackMethod = "customFallback")
        public ExecuteResult executeWithCustomFallback(JobArgs args) throws InterruptedException {
            Thread.sleep(600);
            return ExecuteResult.success("original-success");
        }

        public ExecuteResult customFallback() {
            return ExecuteResult.success("custom-fallback-result");
        }

        @JobExecutor(name = "testParamFallbackJob")
        @AtengSnailJob(timeout = "60ms", fallbackMethod = "paramFallback")
        public ExecuteResult executeWithParamFallback(JobArgs args) throws InterruptedException {
            Thread.sleep(600);
            return ExecuteResult.success("original-success");
        }

        public ExecuteResult paramFallback(
                SnailJobExecutionMetadata metadata,
                Duration elapsed,
                Throwable t,
                JobArgs jobArgs) {
            this.capturedMetadata.set(metadata);
            this.capturedElapsed.set(elapsed);
            this.capturedThrowable.set(t);
            this.capturedJobArgs.set(jobArgs);
            return ExecuteResult.success("param-fallback-success");
        }

        @JobExecutor(name = "testVoidFallbackJob")
        @AtengSnailJob(timeout = "60ms", fallbackMethod = "voidFallback")
        public ExecuteResult executeWithVoidFallback(JobArgs args) throws InterruptedException {
            Thread.sleep(600);
            return ExecuteResult.success("original-success");
        }

        public void voidFallback() {
            this.voidFallbackExecuted.set(true);
        }

        @JobExecutor(name = "testBrokenFallbackJob")
        @AtengSnailJob(timeout = "60ms", fallbackMethod = "brokenFallback")
        public ExecuteResult executeWithBrokenFallback(JobArgs args) throws InterruptedException {
            Thread.sleep(600);
            return ExecuteResult.success("original-success");
        }

        public ExecuteResult brokenFallback() {
            throw new RuntimeException("fallback crashed intentionally");
        }

        @JobExecutor(name = "testNoFallbackJob")
        @AtengSnailJob(timeout = "60ms")
        public ExecuteResult executeWithoutFallback(JobArgs args) throws InterruptedException {
            Thread.sleep(600);
            return ExecuteResult.success("original-success");
        }
    }

    public static class TestGlobalTimeoutFallbackHandler implements JobTimeoutFallbackHandler {

        private final List<FallbackInvocation> invocations = Collections.synchronizedList(new ArrayList<>());
        private volatile CountDownLatch latch = new CountDownLatch(1);

        public void resetLatch(int count) {
            this.latch = new CountDownLatch(count);
        }

        public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        @Override
        public void onTimeout(SnailJobExecutionMetadata metadata, Duration elapsed) {
            invocations.add(new FallbackInvocation(metadata, elapsed));
            latch.countDown();
        }

        public List<FallbackInvocation> getInvocations() {
            return new ArrayList<>(invocations);
        }

        public void clear() {
            invocations.clear();
        }
    }

    public record FallbackInvocation(SnailJobExecutionMetadata metadata, Duration elapsed) {
    }
}
