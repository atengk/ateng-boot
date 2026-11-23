package io.github.atengk.boot.job.xxl.aspect;

import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.context.XxlJobHelper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.xxl.alert.XxlJobAlertAggregator;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import io.github.atengk.boot.job.xxl.event.JobTimeoutEvent;
import io.github.atengk.boot.job.xxl.event.XxlJobAlertEvent;
import io.github.atengk.boot.job.xxl.fallback.JobTimeoutFallbackHandler;
import io.github.atengk.boot.job.xxl.governor.JobRuntimeGovernor;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper.XxlJobTestResult;
import io.github.atengk.boot.job.xxl.interceptor.XxlJobExecutionContext;
import io.github.atengk.boot.job.xxl.interceptor.XxlJobInterceptor;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import io.github.atengk.boot.job.xxl.watchdog.JobExecutionMetadata;
import io.github.atengk.boot.job.xxl.watchdog.LocalWatchdog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XXL-Job 方法级本地退避重试、致命异常零重试边界与看门狗总超时约束集成测试
 *
 * @author Ateng
 * @since 2026-11-19
 */
@SpringBootTest(classes = XxlJobRetryIntegrationTest.TestConfig.class)
class XxlJobRetryIntegrationTest {

    @Autowired
    private RetryTestJobService jobService;

    @Autowired
    private AuditInterceptor auditInterceptor;

    @Autowired
    private EventCollector eventCollector;

    @Autowired
    private GlobalTimeoutFallback globalTimeoutFallback;

    @Autowired
    private JobRuntimeGovernor governor;

    @Autowired
    private XxlJobProperties properties;

    @BeforeEach
    void setUp() {
        resetAll();
    }

    @AfterEach
    void tearDown() {
        resetAll();
        XxlJobContext.setXxlJobContext(null);
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
        Thread.interrupted();
    }

    private void resetAll() {
        jobService.reset();
        auditInterceptor.reset();
        eventCollector.clear();
        globalTimeoutFallback.reset();
        governor.clear();
        properties.setManagedPoolEnabled(false);
    }

    @Test
    @DisplayName("测试瞬态异常本地退避重试自愈：前 2 次失败后第 3 次成功，不触发错误告警且 postHandle 正常回调")
    void testTransientFailureRecoversViaLocalRetry() {
        XxlJobTestResult result = XxlJobTestHelper.trigger("transientRecoverJob");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.handleCode()).isEqualTo(200);
        assertThat(jobService.getTransientAttempts().get()).isEqualTo(3);

        // 重试恢复成功后不触发首发告警，且拦截器 postHandle / afterCompletion(null) 各触发一次
        assertThat(eventCollector.getAlertEvents()).isEmpty();
        assertThat(auditInterceptor.getPreHandleCount().get()).isEqualTo(1);
        assertThat(auditInterceptor.getPostHandleCount().get()).isEqualTo(1);
        assertThat(auditInterceptor.getAfterCompletionCount().get()).isEqualTo(1);
        assertThat(auditInterceptor.getLastThrowable().get()).isNull();
    }

    @Test
    @DisplayName("测试重试耗尽最终失败：保留原始异常原因链，且仅收敛触发一次 INITIAL_FAILURE 告警")
    void testRetryExhaustedReportsSingleAlertAndPreservesCause() {
        XxlJobTestResult result = XxlJobTestHelper.trigger("alwaysFailRetryJob");

        assertThat(result.isSuccess()).isFalse();
        assertThat(jobService.getAlwaysFailAttempts().get()).isEqualTo(3);
        assertThat(result.throwable())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Downstream unavailable attempt-3");

        // 仅收敛发布一次首发失败告警
        assertThat(eventCollector.getAlertEvents()).hasSize(1);
        assertThat(eventCollector.getAlertEvents().get(0).getAlertType())
                .isEqualTo(XxlJobAlertEvent.AlertType.INITIAL_FAILURE);

        assertThat(auditInterceptor.getPostHandleCount().get()).isZero();
        assertThat(auditInterceptor.getAfterCompletionCount().get()).isEqualTo(1);
        assertThat(auditInterceptor.getLastThrowable().get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Downstream unavailable attempt-3");
    }

    @Test
    @DisplayName("测试非法或负数 retryInterval：快速失败拦截")
    void testNegativeRetryIntervalFailsFast() {
        XxlJobTestResult result = XxlJobTestHelper.trigger("negativeRetryIntervalJob");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.throwable()).isInstanceOf(IllegalArgumentException.class);
        assertThat(jobService.getNegativeIntervalAttempts().get()).isZero();
        // 配置非法必须在 preHandle 之前快速失败，不能触发单边 preHandle 而漏掉 afterCompletion
        assertThat(auditInterceptor.getPreHandleCount().get()).isZero();
        assertThat(auditInterceptor.getAfterCompletionCount().get()).isZero();
    }

    @Test
    @DisplayName("测试致命入参异常零重试边界：JSR-303 校验失败与畸形 JSON 反序列化失败均 0 次重试快速失败")
    void testValidationAndDeserializationErrorsDoNotRetry() {
        // 1. JSR-303 校验失败
        XxlJobTestResult invalidParamResult = XxlJobTestHelper.trigger(
                "validatedRetryJob", "{\"batchNo\":\"\",\"count\":0}");
        assertThat(invalidParamResult.isSuccess()).isFalse();
        assertThat(invalidParamResult.throwable()).isInstanceOf(BusinessException.class);
        assertThat(jobService.getValidatedAttempts().get()).isZero();

        // 2. 畸形 JSON 反序列化失败
        XxlJobTestResult malformedJsonResult = XxlJobTestHelper.trigger(
                "validatedRetryJob", "{not-valid-json");
        assertThat(malformedJsonResult.isSuccess()).isFalse();
        assertThat(malformedJsonResult.throwable()).isInstanceOf(BusinessException.class);
        assertThat(jobService.getValidatedAttempts().get()).isZero();
    }

    @Test
    @DisplayName("测试看门狗总超时作用域与零重试：整次含重试调度触达 timeout 即刻中断并触发降级补偿")
    void testWatchdogTimeoutBoundsEntireRetryLifecycleAndStopsRetries() throws InterruptedException {
        XxlJobTestResult result = XxlJobTestHelper.trigger("timeoutDuringRetryJob");

        assertThat(result.isSuccess()).isFalse();
        // 5 次重试若不中断需执行 6 次、耗时远超 400ms；在 160ms 总看门狗约束下应提前中断
        assertThat(jobService.getTimeoutRetryAttempts().get()).isBetween(1, 3);
        assertThat(eventCollector.getTimeoutEvents()).hasSize(1);

        boolean reached = jobService.getFallbackLatch().await(3, TimeUnit.SECONDS);
        assertThat(reached).isTrue();
        assertThat(jobService.isBeanFallbackInvoked()).isTrue();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("测试受管线程池模式下的整次重试总超时约束与全局 SPI 降级补偿")
    void testManagedPoolRetryBoundedByTotalWatchdogTimeout() throws InterruptedException {
        properties.setManagedPoolEnabled(true);

        XxlJobTestResult result = XxlJobTestHelper.trigger("managedPoolTimeoutRetryJob");

        assertThat(result.isSuccess()).isFalse();
        assertThat(jobService.getManagedPoolAttempts().get()).isBetween(1, 3);
        assertThat(eventCollector.getTimeoutEvents()).hasSize(1);

        boolean reached = globalTimeoutFallback.getLatch().await(3, TimeUnit.SECONDS);
        assertThat(reached).isTrue();
    }

    @Test
    @DisplayName("测试运行时静默与拦截器拦截：均执行 0 次重试优雅跳过或拦截")
    void testGovernorPauseAndInterceptorSkipDoNotRetry() {
        governor.pause("transientRecoverJob");
        XxlJobTestResult pausedResult = XxlJobTestHelper.trigger("transientRecoverJob");
        assertThat(pausedResult.isSuccess()).isTrue();
        assertThat(pausedResult.handleMsg()).contains("[SKIPPED]");
        assertThat(jobService.getTransientAttempts().get()).isZero();

        governor.resume("transientRecoverJob");
        auditInterceptor.setInterceptHandler("transientRecoverJob");
        XxlJobTestResult interceptedResult = XxlJobTestHelper.trigger("transientRecoverJob");
        assertThat(interceptedResult.isSuccess()).isTrue();
        assertThat(interceptedResult.handleMsg()).contains("[INTERCEPTED]");
        assertThat(jobService.getTransientAttempts().get()).isZero();
    }

    @Test
    @DisplayName("测试重试恢复清理残留失败状态：上一轮调用 handleFail 后重试恢复，最终结果应重置为成功 200 且无旧失败消息")
    void testStaleHandleFailResetOnRetryRecovery() {
        XxlJobTestResult result = XxlJobTestHelper.trigger("staleHandleFailRetryJob");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.handleCode()).isEqualTo(XxlJobContext.HANDLE_CODE_SUCCESS);
        assertThat(result.handleMsg()).isNull();
        assertThat(jobService.getStaleHandleFailAttempts().get()).isEqualTo(2);
    }

    @Test
    @DisplayName("测试 TimeoutException 零重试边界与空白 retryInterval 快速失败拦截")
    void testTimeoutExceptionDoesNotRetryAndBlankIntervalFailsFast() {
        // 1. TimeoutException 严禁本地退避重试
        XxlJobTestResult timeoutResult = XxlJobTestHelper.trigger("timeoutExceptionJob");
        assertThat(timeoutResult.isSuccess()).isFalse();
        assertThat(timeoutResult.throwable()).isInstanceOf(TimeoutException.class);
        assertThat(jobService.getTimeoutExceptionAttempts().get()).isEqualTo(1);

        // 2. retryCount > 0 且 retryInterval 为空白时快速失败
        auditInterceptor.reset();
        XxlJobTestResult blankIntervalResult = XxlJobTestHelper.trigger("blankRetryIntervalJob");
        assertThat(blankIntervalResult.isSuccess()).isFalse();
        assertThat(blankIntervalResult.throwable()).isInstanceOf(IllegalArgumentException.class);
        assertThat(jobService.getBlankIntervalAttempts().get()).isZero();
        assertThat(auditInterceptor.getPreHandleCount().get()).isZero();
        assertThat(auditInterceptor.getAfterCompletionCount().get()).isZero();
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
            executor.setThreadNamePrefix("retry-job-pool-");
            executor.initialize();
            return executor;
        }

        @Bean
        public XxlJobAlertAggregator xxlJobAlertAggregator(XxlJobProperties properties, ApplicationEventPublisher publisher) {
            return new XxlJobAlertAggregator(properties, publisher, null);
        }

        @Bean
        public JobRuntimeGovernor jobRuntimeGovernor() {
            return new JobRuntimeGovernor();
        }

        @Bean
        public AuditInterceptor auditInterceptor() {
            return new AuditInterceptor();
        }

        @Bean
        public GlobalTimeoutFallback globalTimeoutFallback() {
            return new GlobalTimeoutFallback();
        }

        @Bean
        public EventCollector eventCollector() {
            return new EventCollector();
        }

        @Bean
        public XxlJobTestHelper xxlJobTestHelper() {
            return new XxlJobTestHelper();
        }

        @Bean
        public XxlJobContextAspect xxlJobContextAspect(
                LocalWatchdog watchdog,
                XxlJobProperties properties,
                ThreadPoolTaskExecutor taskExecutor,
                XxlJobAlertAggregator alertAggregator,
                ObjectProvider<XxlJobInterceptor> interceptors,
                JobRuntimeGovernor governor,
                ObjectProvider<JobTimeoutFallbackHandler> fallbackHandlers) {
            return new XxlJobContextAspect(
                    watchdog,
                    properties,
                    taskExecutor,
                    alertAggregator,
                    null,
                    interceptors.orderedStream().toList(),
                    governor,
                    fallbackHandlers.orderedStream().toList()
            );
        }

        @Bean
        public RetryTestJobService retryTestJobService() {
            return new RetryTestJobService();
        }
    }

    @Component
    public static class RetryTestJobService {

        private final AtomicInteger transientAttempts = new AtomicInteger();
        private final AtomicInteger alwaysFailAttempts = new AtomicInteger();
        private final AtomicInteger negativeIntervalAttempts = new AtomicInteger();
        private final AtomicInteger validatedAttempts = new AtomicInteger();
        private final AtomicInteger timeoutRetryAttempts = new AtomicInteger();
        private final AtomicInteger managedPoolAttempts = new AtomicInteger();
        private final AtomicInteger staleHandleFailAttempts = new AtomicInteger();
        private final AtomicInteger timeoutExceptionAttempts = new AtomicInteger();
        private final AtomicInteger blankIntervalAttempts = new AtomicInteger();
        private final AtomicBoolean beanFallbackInvoked = new AtomicBoolean(false);
        private volatile CountDownLatch fallbackLatch = new CountDownLatch(1);

        @AtengXxlJob(value = "transientRecoverJob", retryCount = 2, retryInterval = "30ms")
        public void executeTransientRecover() {
            int attempt = transientAttempts.incrementAndGet();
            if (attempt < 3) {
                throw new IllegalStateException("Transient error attempt-" + attempt);
            }
            XxlJobHelper.handleSuccess("RECOVERED_AT_" + attempt);
        }

        @AtengXxlJob(value = "alwaysFailRetryJob", retryCount = 2, retryInterval = "20ms")
        public void executeAlwaysFail() {
            int attempt = alwaysFailAttempts.incrementAndGet();
            throw new IllegalStateException("Downstream unavailable attempt-" + attempt);
        }

        @AtengXxlJob(value = "negativeRetryIntervalJob", retryCount = 1, retryInterval = "-10ms")
        public void executeNegativeInterval() {
            negativeIntervalAttempts.incrementAndGet();
        }

        @AtengXxlJob(value = "validatedRetryJob", retryCount = 2, retryInterval = "20ms")
        public void executeValidated(@Valid RetryJobParam param) {
            validatedAttempts.incrementAndGet();
            XxlJobHelper.handleSuccess("VALID_OK");
        }

        @AtengXxlJob(
                value = "timeoutDuringRetryJob",
                timeout = "160ms",
                retryCount = 5,
                retryInterval = "90ms",
                fallback = "onRetryTimeoutFallback"
        )
        public void executeTimeoutDuringRetry() {
            timeoutRetryAttempts.incrementAndGet();
            throw new IllegalStateException("Slow transient failure");
        }

        public void onRetryTimeoutFallback(JobExecutionMetadata metadata, Duration elapsed) {
            beanFallbackInvoked.set(true);
            fallbackLatch.countDown();
        }

        @AtengXxlJob(value = "managedPoolTimeoutRetryJob", timeout = "160ms", retryCount = 5, retryInterval = "90ms")
        public void executeManagedPoolTimeoutRetry() {
            managedPoolAttempts.incrementAndGet();
            throw new IllegalStateException("Managed pool transient failure");
        }

        @AtengXxlJob(value = "staleHandleFailRetryJob", retryCount = 1, retryInterval = "10ms")
        public void executeStaleHandleFailRetry() {
            if (staleHandleFailAttempts.incrementAndGet() == 1) {
                XxlJobHelper.handleFail("FIRST_ATTEMPT_FAIL_MSG");
                throw new IllegalStateException("First attempt failed");
            }
        }

        @AtengXxlJob(value = "timeoutExceptionJob", retryCount = 3, retryInterval = "10ms")
        public void executeTimeoutExceptionJob() throws TimeoutException {
            timeoutExceptionAttempts.incrementAndGet();
            throw new TimeoutException("Remote RPC timed out");
        }

        @AtengXxlJob(value = "blankRetryIntervalJob", retryCount = 1, retryInterval = "  ")
        public void executeBlankRetryIntervalJob() {
            blankIntervalAttempts.incrementAndGet();
        }

        public void reset() {
            transientAttempts.set(0);
            alwaysFailAttempts.set(0);
            negativeIntervalAttempts.set(0);
            validatedAttempts.set(0);
            timeoutRetryAttempts.set(0);
            managedPoolAttempts.set(0);
            staleHandleFailAttempts.set(0);
            timeoutExceptionAttempts.set(0);
            blankIntervalAttempts.set(0);
            beanFallbackInvoked.set(false);
            fallbackLatch = new CountDownLatch(1);
        }

        public AtomicInteger getTransientAttempts() {
            return transientAttempts;
        }

        public AtomicInteger getAlwaysFailAttempts() {
            return alwaysFailAttempts;
        }

        public AtomicInteger getNegativeIntervalAttempts() {
            return negativeIntervalAttempts;
        }

        public AtomicInteger getValidatedAttempts() {
            return validatedAttempts;
        }

        public AtomicInteger getTimeoutRetryAttempts() {
            return timeoutRetryAttempts;
        }

        public AtomicInteger getManagedPoolAttempts() {
            return managedPoolAttempts;
        }

        public AtomicInteger getStaleHandleFailAttempts() {
            return staleHandleFailAttempts;
        }

        public AtomicInteger getTimeoutExceptionAttempts() {
            return timeoutExceptionAttempts;
        }

        public AtomicInteger getBlankIntervalAttempts() {
            return blankIntervalAttempts;
        }

        public boolean isBeanFallbackInvoked() {
            return beanFallbackInvoked.get();
        }

        public CountDownLatch getFallbackLatch() {
            return fallbackLatch;
        }
    }

    public static class RetryJobParam {
        @NotBlank(message = "批次号不能为空")
        private String batchNo;

        @Min(value = 1, message = "数量至少为 1")
        private int count;

        public String getBatchNo() {
            return batchNo;
        }

        public void setBatchNo(String batchNo) {
            this.batchNo = batchNo;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    public static class AuditInterceptor implements XxlJobInterceptor {
        private final AtomicInteger preHandleCount = new AtomicInteger();
        private final AtomicInteger postHandleCount = new AtomicInteger();
        private final AtomicInteger afterCompletionCount = new AtomicInteger();
        private final AtomicReference<Throwable> lastThrowable = new AtomicReference<>();
        private volatile String interceptHandler;

        @Override
        public boolean preHandle(XxlJobExecutionContext context) {
            preHandleCount.incrementAndGet();
            return interceptHandler == null || !interceptHandler.equals(context.getJobHandler());
        }

        @Override
        public void postHandle(XxlJobExecutionContext context, Object result) {
            postHandleCount.incrementAndGet();
        }

        @Override
        public void afterCompletion(XxlJobExecutionContext context, Throwable throwable) {
            afterCompletionCount.incrementAndGet();
            lastThrowable.set(throwable);
        }

        public void reset() {
            preHandleCount.set(0);
            postHandleCount.set(0);
            afterCompletionCount.set(0);
            lastThrowable.set(null);
            interceptHandler = null;
        }

        public void setInterceptHandler(String interceptHandler) {
            this.interceptHandler = interceptHandler;
        }

        public AtomicInteger getPreHandleCount() {
            return preHandleCount;
        }

        public AtomicInteger getPostHandleCount() {
            return postHandleCount;
        }

        public AtomicInteger getAfterCompletionCount() {
            return afterCompletionCount;
        }

        public AtomicReference<Throwable> getLastThrowable() {
            return lastThrowable;
        }
    }

    public static class GlobalTimeoutFallback implements JobTimeoutFallbackHandler {
        private volatile CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void onTimeout(JobExecutionMetadata metadata, Duration elapsed) {
            latch.countDown();
        }

        public CountDownLatch getLatch() {
            return latch;
        }

        public void reset() {
            latch = new CountDownLatch(1);
        }
    }

    public static class EventCollector {
        private final List<XxlJobAlertEvent> alertEvents = new CopyOnWriteArrayList<>();
        private final List<JobTimeoutEvent> timeoutEvents = new CopyOnWriteArrayList<>();

        @EventListener
        public void onAlert(XxlJobAlertEvent event) {
            alertEvents.add(event);
        }

        @EventListener
        public void onTimeout(JobTimeoutEvent event) {
            timeoutEvents.add(event);
        }

        public List<XxlJobAlertEvent> getAlertEvents() {
            return alertEvents;
        }

        public List<JobTimeoutEvent> getTimeoutEvents() {
            return timeoutEvents;
        }

        public void clear() {
            alertEvents.clear();
            timeoutEvents.clear();
        }
    }
}
