package io.github.atengk.boot.job.xxl.aspect;

import com.xxl.job.core.context.XxlJobContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import io.github.atengk.boot.job.xxl.fallback.JobTimeoutFallbackHandler;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import io.github.atengk.boot.job.xxl.watchdog.JobExecutionMetadata;
import io.github.atengk.boot.job.xxl.watchdog.LocalWatchdog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XXL-Job 看门狗超时降级补偿与全局 SPI 回调集成测试
 *
 * @author Ateng
 * @since 2026-11-02
 */
@SpringBootTest(classes = XxlJobTimeoutFallbackTest.TestConfig.class)
class XxlJobTimeoutFallbackTest {

    @Autowired
    private FallbackTestService testService;

    @Autowired
    private GlobalFallbackTestHandler globalHandler;

    @AfterEach
    void tearDown() {
        testService.reset();
        globalHandler.reset();
        XxlJobContext.setXxlJobContext(null);
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("测试同 Bean 上的降级方法：看门狗超时中断后自动调度执行 fallback 方法并透传元数据与四维上下文")
    void testBeanLevelFallbackInvocation() throws InterruptedException {
        long jobId = 301L;
        long logId = 90001L;
        XxlJobContext context = new XxlJobContext(jobId, "", logId, System.currentTimeMillis(), "test.log", 0, 1);
        XxlJobContext.setXxlJobContext(context);
        MDC.put("traceId", "trace-fallback-123");
        TenantContextHolder.setTenantId("tenant-fb-999");

        testService.executeJobWithFallback();

        // 等待异步受管线程池中的降级补偿方法完成
        boolean reached = testService.getFallbackLatch().await(3, TimeUnit.SECONDS);
        assertThat(reached).isTrue();

        assertThat(testService.isJobInterrupted()).isTrue();
        assertThat(testService.isFallbackInvoked()).isTrue();

        JobExecutionMetadata metadata = testService.getCapturedMetadata();
        assertThat(metadata).isNotNull();
        assertThat(metadata.getJobId()).isEqualTo(jobId);
        assertThat(metadata.getJobName()).isEqualTo("fallbackJob");
        assertThat(metadata.getLogId()).isEqualTo(logId);
        assertThat(metadata.getTraceId()).isEqualTo("trace-fallback-123");
        assertThat(metadata.getTenantId()).isEqualTo("tenant-fb-999");

        assertThat(testService.getCapturedElapsed()).isNotNull();
        assertThat(testService.getCapturedElapsed().toMillis()).isGreaterThanOrEqualTo(150);

        // 验证全局 SPI 未被调用 (优先同 Bean fallback)
        assertThat(globalHandler.isInvoked()).isFalse();
    }

    @Test
    @DisplayName("测试无参降级方法签名：fallback 方法可声明为无参方法并正常回调")
    void testNoArgFallbackInvocation() throws InterruptedException {
        XxlJobContext context = new XxlJobContext(302L, "", 90002L, System.currentTimeMillis(), "test.log", 0, 1);
        XxlJobContext.setXxlJobContext(context);

        testService.executeNoArgFallbackJob();

        boolean reached = testService.getNoArgFallbackLatch().await(3, TimeUnit.SECONDS);
        assertThat(reached).isTrue();
        assertThat(testService.isNoArgFallbackInvoked()).isTrue();
        assertThat(globalHandler.isInvoked()).isFalse();
    }

    @Test
    @DisplayName("测试全局 JobTimeoutFallbackHandler SPI：未声明方法级 fallback 时回调全局兜底 SPI")
    void testGlobalFallbackHandlerInvocation() throws InterruptedException {
        long jobId = 303L;
        long logId = 90003L;
        XxlJobContext context = new XxlJobContext(jobId, "", logId, System.currentTimeMillis(), "test.log", 0, 1);
        XxlJobContext.setXxlJobContext(context);
        MDC.put("traceId", "trace-global-spi");
        TenantContextHolder.setTenantId("tenant-spi-888");

        testService.executeGlobalSpiJob();

        boolean reached = globalHandler.getLatch().await(3, TimeUnit.SECONDS);
        assertThat(reached).isTrue();
        assertThat(globalHandler.isInvoked()).isTrue();

        JobExecutionMetadata metadata = globalHandler.getCapturedMetadata();
        assertThat(metadata).isNotNull();
        assertThat(metadata.getJobId()).isEqualTo(jobId);
        assertThat(metadata.getJobName()).isEqualTo("globalSpiJob");
        assertThat(metadata.getTraceId()).isEqualTo("trace-global-spi");
        assertThat(metadata.getTenantId()).isEqualTo("tenant-spi-888");
        assertThat(globalHandler.getCapturedElapsed().toMillis()).isGreaterThanOrEqualTo(150);
    }

    @Test
    @DisplayName("测试正常极速任务：正常完成不触发任何降级回调")
    void testQuickJobDoesNotTriggerFallback() throws InterruptedException {
        testService.executeNormalQuickJob();

        TimeUnit.MILLISECONDS.sleep(300);
        assertThat(testService.isFallbackInvoked()).isFalse();
        assertThat(globalHandler.isInvoked()).isFalse();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class TestConfig {

        @Bean
        public XxlJobProperties xxlJobProperties() {
            return new XxlJobProperties();
        }

        @Bean
        public LocalWatchdog localWatchdog(ApplicationEventPublisher publisher) {
            return new LocalWatchdog(publisher);
        }

        @Bean(name = "taskExecutor")
        public ThreadPoolTaskExecutor taskExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(2);
            executor.setMaxPoolSize(4);
            executor.setThreadNamePrefix("fallback-job-pool-");
            executor.initialize();
            return executor;
        }

        @Bean
        public GlobalFallbackTestHandler globalFallbackTestHandler() {
            return new GlobalFallbackTestHandler();
        }

        @Bean
        public XxlJobContextAspect xxlJobContextAspect(LocalWatchdog watchdog,
                                                       XxlJobProperties properties,
                                                       ThreadPoolTaskExecutor taskExecutor,
                                                       GlobalFallbackTestHandler globalFallbackHandler) {
            return new XxlJobContextAspect(
                    watchdog,
                    properties,
                    taskExecutor,
                    null,
                    null,
                    null,
                    null,
                    List.of(globalFallbackHandler)
            );
        }

        @Bean
        public FallbackTestService fallbackTestService() {
            return new FallbackTestService();
        }
    }

    @Component
    public static class FallbackTestService {

        private final AtomicBoolean jobInterrupted = new AtomicBoolean(false);
        private final AtomicBoolean fallbackInvoked = new AtomicBoolean(false);
        private final AtomicBoolean noArgFallbackInvoked = new AtomicBoolean(false);

        private final AtomicReference<JobExecutionMetadata> capturedMetadata = new AtomicReference<>();
        private final AtomicReference<Duration> capturedElapsed = new AtomicReference<>();

        private volatile CountDownLatch fallbackLatch = new CountDownLatch(1);
        private volatile CountDownLatch noArgFallbackLatch = new CountDownLatch(1);

        @AtengXxlJob(value = "fallbackJob", timeout = "150ms", fallback = "onTimeoutFallback")
        public void executeJobWithFallback() {
            jobInterrupted.set(false);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                jobInterrupted.set(true);
            }
        }

        public void onTimeoutFallback(JobExecutionMetadata metadata, Duration elapsed) {
            fallbackInvoked.set(true);
            capturedMetadata.set(metadata);
            capturedElapsed.set(elapsed);
            fallbackLatch.countDown();
        }

        @AtengXxlJob(value = "noArgJob", timeout = "150ms", fallback = "onNoArgFallback")
        public void executeNoArgFallbackJob() {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                jobInterrupted.set(true);
            }
        }

        public void onNoArgFallback() {
            noArgFallbackInvoked.set(true);
            noArgFallbackLatch.countDown();
        }

        @AtengXxlJob(value = "globalSpiJob", timeout = "150ms")
        public void executeGlobalSpiJob() {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                jobInterrupted.set(true);
            }
        }

        @AtengXxlJob(value = "normalQuickJob", timeout = "5s", fallback = "onTimeoutFallback")
        public void executeNormalQuickJob() {
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                jobInterrupted.set(true);
            }
        }

        public boolean isJobInterrupted() {
            return jobInterrupted.get();
        }

        public boolean isFallbackInvoked() {
            return fallbackInvoked.get();
        }

        public boolean isNoArgFallbackInvoked() {
            return noArgFallbackInvoked.get();
        }

        public JobExecutionMetadata getCapturedMetadata() {
            return capturedMetadata.get();
        }

        public Duration getCapturedElapsed() {
            return capturedElapsed.get();
        }

        public CountDownLatch getFallbackLatch() {
            return fallbackLatch;
        }

        public CountDownLatch getNoArgFallbackLatch() {
            return noArgFallbackLatch;
        }

        public void reset() {
            jobInterrupted.set(false);
            fallbackInvoked.set(false);
            noArgFallbackInvoked.set(false);
            capturedMetadata.set(null);
            capturedElapsed.set(null);
            fallbackLatch = new CountDownLatch(1);
            noArgFallbackLatch = new CountDownLatch(1);
        }
    }

    @Component
    public static class GlobalFallbackTestHandler implements JobTimeoutFallbackHandler {

        private final AtomicBoolean invoked = new AtomicBoolean(false);
        private final AtomicReference<JobExecutionMetadata> capturedMetadata = new AtomicReference<>();
        private final AtomicReference<Duration> capturedElapsed = new AtomicReference<>();
        private volatile CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void onTimeout(JobExecutionMetadata metadata, Duration elapsed) {
            invoked.set(true);
            capturedMetadata.set(metadata);
            capturedElapsed.set(elapsed);
            latch.countDown();
        }

        public boolean isInvoked() {
            return invoked.get();
        }

        public JobExecutionMetadata getCapturedMetadata() {
            return capturedMetadata.get();
        }

        public Duration getCapturedElapsed() {
            return capturedElapsed.get();
        }

        public CountDownLatch getLatch() {
            return latch;
        }

        public void reset() {
            invoked.set(false);
            capturedMetadata.set(null);
            capturedElapsed.set(null);
            latch = new CountDownLatch(1);
        }
    }

}
