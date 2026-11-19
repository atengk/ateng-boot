package io.github.atengk.boot.job.local;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.config.AtengJobLocalAutoConfiguration;
import io.github.atengk.boot.job.local.enums.DiscardReason;
import io.github.atengk.boot.job.local.enums.TriggerType;
import io.github.atengk.boot.job.local.event.JobDiscardedEvent;
import io.github.atengk.boot.job.local.event.JobFailedEvent;
import io.github.atengk.boot.job.local.event.JobTimeoutEvent;
import io.github.atengk.boot.job.local.exception.JobTimeoutException;
import io.github.atengk.boot.job.local.fallback.LocalJobTimeoutFallbackHandler;
import io.github.atengk.boot.job.local.handler.JobHandler;
import io.github.atengk.boot.job.local.interceptor.LocalJobInterceptor;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobDefinition;
import io.github.atengk.boot.job.local.model.JobExecutionRecord;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.properties.JobLocalProperties;
import io.github.atengk.boot.job.local.registry.LocalJobRegistry;
import io.github.atengk.boot.job.local.storage.InMemoryJobLogStorageProvider;
import io.github.atengk.boot.job.local.storage.InMemoryJobMetadataRepository;
import io.github.atengk.boot.job.local.template.LocalJobTemplate;
import io.github.atengk.boot.job.local.tenant.DefaultTenantProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 三阶段切面拦截器责任链与硬超时两级降级补偿对抗测试
 *
 * @author Ateng
 * @since 2026-11-19
 */
@DisplayName("LocalJobInterceptor 与两级超时降级补偿测试")
class LocalJobInterceptorAndFallbackTest {

    private final List<LocalJobRegistry> registriesToCleanup = new ArrayList<>();
    private final List<ThreadPoolTaskExecutor> executorsToCleanup = new ArrayList<>();
    private final List<ThreadPoolTaskScheduler> schedulersToCleanup = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (LocalJobRegistry registry : registriesToCleanup) {
            registry.destroy();
        }
        for (ThreadPoolTaskExecutor executor : executorsToCleanup) {
            executor.shutdown();
        }
        for (ThreadPoolTaskScheduler scheduler : schedulersToCleanup) {
            scheduler.shutdown();
        }
        registriesToCleanup.clear();
        executorsToCleanup.clear();
        schedulersToCleanup.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
    }

    private ThreadPoolTaskExecutor createWorkerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setThreadNamePrefix("test-job-worker-");
        executor.initialize();
        executorsToCleanup.add(executor);
        return executor;
    }

    private ThreadPoolTaskScheduler createScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("test-job-sched-");
        scheduler.initialize();
        schedulersToCleanup.add(scheduler);
        return scheduler;
    }

    @Test
    @DisplayName("多拦截器应按 Ordered 正序执行 preHandle/postHandle 并严格逆序执行 afterCompletion")
    void shouldExecuteInterceptorsInOrderedSequenceAndReverseAfterCompletion() {
        List<String> trace = new ArrayList<>();
        LocalJobInterceptor third = new RecordingInterceptor("C", 50, true, false, trace);
        LocalJobInterceptor first = new RecordingInterceptor("A", 1, true, false, trace);
        LocalJobInterceptor second = new RecordingInterceptor("B", 10, true, false, trace);

        InMemoryJobLogStorageProvider logStorage = new InMemoryJobLogStorageProvider(50);
        LocalJobRegistry registry = new LocalJobRegistry(
                createScheduler(), createWorkerExecutor(), new InMemoryJobMetadataRepository(),
                logStorage, null, new JobLocalProperties(), new DefaultTenantProvider(),
                List.of(third, first, second), null);
        registriesToCleanup.add(registry);

        registry.register(JobDefinition.builder()
                        .jobName("orderedInterceptorJob")
                        .triggerType(TriggerType.MANUAL)
                        .build(),
                ctx -> {
                    trace.add("BODY");
                    return JobResult.success("ok");
                });

        JobResult result = registry.trigger("orderedInterceptorJob", null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(trace).containsExactly(
                "A:pre", "B:pre", "C:pre",
                "BODY",
                "A:post", "B:post", "C:post",
                "C:after(err=false)", "B:after(err=false)", "A:after(err=false)"
        );
    }

    @Test
    @DisplayName("中间拦截器 preHandle 返回 false 时应阻断后续执行、发布 INTERCEPTOR_REJECTED 并对已通过拦截器逆序清理")
    void shouldShortCircuitOnPreHandleRejectAndTriggerAfterCompletionInReverseForPassedOnly() {
        List<String> trace = new ArrayList<>();
        LocalJobInterceptor first = new RecordingInterceptor("A", 1, true, false, trace);
        LocalJobInterceptor secondReject = new RecordingInterceptor("B", 10, false, false, trace);
        LocalJobInterceptor third = new RecordingInterceptor("C", 50, true, false, trace);

        List<ApplicationEvent> events = new CopyOnWriteArrayList<>();
        InMemoryJobLogStorageProvider logStorage = new InMemoryJobLogStorageProvider(50);
        LocalJobRegistry registry = new LocalJobRegistry(
                createScheduler(), createWorkerExecutor(), new InMemoryJobMetadataRepository(),
                logStorage, null, new JobLocalProperties(), new DefaultTenantProvider(),
                List.of(third, secondReject, first), null);
        registry.setApplicationEventPublisher((ApplicationEventPublisher) event -> {
            if (event instanceof ApplicationEvent ae) {
                events.add(ae);
            }
        });
        registriesToCleanup.add(registry);

        AtomicBoolean bodyExecuted = new AtomicBoolean(false);
        registry.register(JobDefinition.builder()
                        .jobName("rejectedJob")
                        .triggerType(TriggerType.MANUAL)
                        .build(),
                ctx -> {
                    bodyExecuted.set(true);
                    return JobResult.success("should-not-run");
                });

        JobResult result = registry.trigger("rejectedJob", null);
        assertThat(result.isSuccess()).isFalse();
        assertThat(bodyExecuted).isFalse();
        assertThat(trace).containsExactly("A:pre", "B:pre", "A:after(err=false)");

        JobInfo info = registry.getJobInfo("rejectedJob");
        assertThat(info.getLastStatus()).isEqualTo("DISCARDED");

        List<JobExecutionRecord> logs = logStorage.listLogs("rejectedJob", 10);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getStatus()).isEqualTo("DISCARDED");

        assertThat(events).anySatisfy(e -> {
            assertThat(e).isInstanceOf(JobDiscardedEvent.class);
            JobDiscardedEvent discardedEvent = (JobDiscardedEvent) e;
            assertThat(discardedEvent.getJobName()).isEqualTo("rejectedJob");
            assertThat(discardedEvent.getDiscardReason()).isEqualTo(DiscardReason.INTERCEPTOR_REJECTED);
        });
    }

    @Test
    @DisplayName("afterCompletion 异常不阻断上游清理，且未降级硬超时抛出 JobTimeoutException 时跳过 postHandle")
    void shouldIsolateAfterCompletionExceptionAndSkipPostHandleOnUncaughtTimeout() {
        List<String> trace = new ArrayList<>();
        LocalJobInterceptor first = new RecordingInterceptor("A", 1, true, false, trace);
        LocalJobInterceptor secondFaultyCleanup = new RecordingInterceptor("B", 10, true, true, trace);

        LocalJobRegistry registry = new LocalJobRegistry(
                createScheduler(), createWorkerExecutor(), new InMemoryJobMetadataRepository(),
                new InMemoryJobLogStorageProvider(50), null, new JobLocalProperties(), new DefaultTenantProvider(),
                List.of(secondFaultyCleanup, first), null);
        registriesToCleanup.add(registry);

        registry.register(JobDefinition.builder()
                        .jobName("uncaughtTimeoutJob")
                        .triggerType(TriggerType.MANUAL)
                        .timeout(Duration.ofMillis(60))
                        .build(),
                ctx -> {
                    Thread.sleep(500);
                    return JobResult.success("late");
                });

        assertThatThrownBy(() -> registry.trigger("uncaughtTimeoutJob", null))
                .isInstanceOf(JobTimeoutException.class);

        assertThat(trace).containsExactly(
                "A:pre", "B:pre",
                "B:after(err=true)", "A:after(err=true)"
        );
    }

    @Test
    @DisplayName("同 Bean fallbackMethod 应优先于全局 SPI 且先记录 TIMEOUT 状态并广播 JobTimeoutEvent")
    void shouldPreferDeclaredFallbackMethodOverGlobalHandlerAfterRecordingTimeout() {
        List<ApplicationEvent> events = new CopyOnWriteArrayList<>();
        AtomicBoolean globalInvoked = new AtomicBoolean(false);
        LocalJobTimeoutFallbackHandler globalHandler = (ctx, ex) -> {
            globalInvoked.set(true);
            return JobResult.success("global-fallback");
        };
        List<String> trace = new ArrayList<>();
        LocalJobInterceptor interceptor = new RecordingInterceptor("I", 1, true, false, trace);

        InMemoryJobLogStorageProvider logStorage = new InMemoryJobLogStorageProvider(50);
        LocalJobRegistry registry = new LocalJobRegistry(
                createScheduler(), createWorkerExecutor(), new InMemoryJobMetadataRepository(),
                logStorage, null, new JobLocalProperties(), new DefaultTenantProvider(),
                List.of(interceptor), globalHandler);
        registry.setApplicationEventPublisher((ApplicationEventPublisher) event -> {
            if (event instanceof ApplicationEvent ae) {
                events.add(ae);
            }
        });
        registriesToCleanup.add(registry);

        TimeoutFallbackBean bean = new TimeoutFallbackBean();
        registerBeanJob(registry, bean, "slowWithFullFallback", "fullTimeoutJob", "onFullFallback", Duration.ofMillis(60));

        JobResult result = registry.trigger("fullTimeoutJob", null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("method-fallback-ctx-ex:fullTimeoutJob");
        assertThat(globalInvoked).isFalse();
        assertThat(bean.capturedThrowable.get()).isInstanceOf(JobTimeoutException.class);

        // 严格先记录 TIMEOUT 与事件，且降级完成后仍触发 postHandle 与 afterCompletion
        assertThat(events).anySatisfy(ev -> assertThat(ev).isInstanceOf(JobTimeoutEvent.class));
        assertThat(registry.getJobInfo("fullTimeoutJob").getLastStatus()).isEqualTo("TIMEOUT");
        List<JobExecutionRecord> records = logStorage.listLogs("fullTimeoutJob", 10);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getStatus()).isEqualTo("TIMEOUT");
        assertThat(records.get(0).getResultMsg()).contains("method-fallback-ctx-ex:fullTimeoutJob");
        assertThat(trace).containsExactly("I:pre", "I:post", "I:after(err=false)");
    }

    @Test
    @DisplayName("fallbackMethod 应全面兼容 (JobContext)、(Throwable) 与无参签名并支持降级到全局 SPI")
    void shouldSupportAlternativeFallbackSignaturesAndGlobalSpiFallback() {
        List<ApplicationEvent> events = new CopyOnWriteArrayList<>();
        LocalJobTimeoutFallbackHandler globalHandler = (ctx, ex) ->
                JobResult.success("global-spi-fallback:" + ctx.getJobName() + ":" + ex.getTimeout().toMillis());

        InMemoryJobLogStorageProvider logStorage = new InMemoryJobLogStorageProvider(50);
        LocalJobRegistry registry = new LocalJobRegistry(
                createScheduler(), createWorkerExecutor(), new InMemoryJobMetadataRepository(),
                logStorage, null, new JobLocalProperties(), new DefaultTenantProvider(),
                Collections.emptyList(), globalHandler);
        registry.setApplicationEventPublisher((ApplicationEventPublisher) event -> {
            if (event instanceof ApplicationEvent ae) {
                events.add(ae);
            }
        });
        registriesToCleanup.add(registry);

        TimeoutFallbackBean bean = new TimeoutFallbackBean();
        registerBeanJob(registry, bean, "slowWithCtxOnly", "ctxOnlyJob", "onCtxOnlyFallback", Duration.ofMillis(60));
        registerBeanJob(registry, bean, "slowWithExOnly", "exOnlyJob", "onExOnlyFallback", Duration.ofMillis(60));
        registerBeanJob(registry, bean, "slowWithNoArgs", "noArgJob", "onNoArgFallback", Duration.ofMillis(60));
        registerBeanJob(registry, bean, "slowWithoutFallback", "globalSpiJob", "", Duration.ofMillis(60));

        assertThat(registry.trigger("ctxOnlyJob", null).getMessage()).isEqualTo("fallback-ctx:ctxOnlyJob");
        assertThat(registry.trigger("exOnlyJob", null).getMessage()).contains("fallback-ex:JobTimeoutException");
        assertThat(registry.trigger("noArgJob", null).getMessage()).isEqualTo("fallback-no-arg");

        JobResult globalRes = registry.trigger("globalSpiJob", null);
        assertThat(globalRes.isSuccess()).isTrue();
        assertThat(globalRes.getMessage()).isEqualTo("global-spi-fallback:globalSpiJob:60");
        assertThat(registry.getJobInfo("globalSpiJob").getLastStatus()).isEqualTo("TIMEOUT");
        assertThat(logStorage.listLogs("globalSpiJob", 10).get(0).getResultMsg())
                .isEqualTo("global-spi-fallback:globalSpiJob:60");
        assertThat(events.stream().filter(JobTimeoutEvent.class::isInstance).count()).isEqualTo(4);
    }

    @Test
    @DisplayName("fallbackMethod 自身抛异常时仍须保留 TIMEOUT 审计记录与事件且不发重复 FAILED 事件")
    void shouldPreserveTimeoutAuditWhenFallbackMethodThrows() {
        List<ApplicationEvent> events = new CopyOnWriteArrayList<>();
        List<String> trace = new ArrayList<>();
        LocalJobInterceptor interceptor = new RecordingInterceptor("I", 1, true, false, trace);

        InMemoryJobLogStorageProvider logStorage = new InMemoryJobLogStorageProvider(50);
        LocalJobRegistry registry = new LocalJobRegistry(
                createScheduler(), createWorkerExecutor(), new InMemoryJobMetadataRepository(),
                logStorage, null, new JobLocalProperties(), new DefaultTenantProvider(),
                List.of(interceptor), null);
        registry.setApplicationEventPublisher((ApplicationEventPublisher) event -> {
            if (event instanceof ApplicationEvent ae) {
                events.add(ae);
            }
        });
        registriesToCleanup.add(registry);

        TimeoutFallbackBean bean = new TimeoutFallbackBean();
        registerBeanJob(registry, bean, "slowWithFailingFallback", "brokenFallbackJob", "onBrokenFallback", Duration.ofMillis(60));

        assertThatThrownBy(() -> registry.trigger("brokenFallbackJob", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("fallback crashed")
                .hasCauseInstanceOf(IllegalStateException.class)
                .satisfies(thrown -> assertThat(thrown.getSuppressed())
                        .anyMatch(JobTimeoutException.class::isInstance));

        assertThat(registry.getJobInfo("brokenFallbackJob").getLastStatus()).isEqualTo("TIMEOUT");
        List<JobExecutionRecord> records = logStorage.listLogs("brokenFallbackJob", 10);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getStatus()).isEqualTo("TIMEOUT");
        assertThat(events.stream().filter(JobTimeoutEvent.class::isInstance).count()).isEqualTo(1);
        assertThat(events.stream().filter(JobFailedEvent.class::isInstance).count()).isEqualTo(0);
        assertThat(trace).containsExactly("I:pre", "I:after(err=true)");
    }

    @Test
    @DisplayName("默认 getOrder 为 0，preHandle 抛异常时应记录 DISCARDED 并发布 INTERCEPTOR_REJECTED 事件")
    void shouldDefaultOrderToZeroAndRecordDiscardedWhenPreHandleThrows() {
        LocalJobInterceptor defaultInterceptor = new LocalJobInterceptor() {};
        assertThat(defaultInterceptor.getOrder()).isEqualTo(0);

        List<String> trace = new ArrayList<>();
        LocalJobInterceptor first = new RecordingInterceptor("A", -10, true, false, trace);
        LocalJobInterceptor throwingSecond = new LocalJobInterceptor() {
            @Override
            public boolean preHandle(JobContext context) {
                trace.add("B:pre-throw");
                throw new IllegalArgumentException("pre-check error");
            }
        };
        LocalJobInterceptor third = new RecordingInterceptor("C", 10, true, false, trace);

        List<ApplicationEvent> events = new CopyOnWriteArrayList<>();
        InMemoryJobLogStorageProvider logStorage = new InMemoryJobLogStorageProvider(50);
        LocalJobRegistry registry = new LocalJobRegistry(
                createScheduler(), createWorkerExecutor(), new InMemoryJobMetadataRepository(),
                logStorage, null, new JobLocalProperties(), new DefaultTenantProvider(),
                List.of(third, throwingSecond, first), null);
        registry.setApplicationEventPublisher((ApplicationEventPublisher) event -> {
            if (event instanceof ApplicationEvent ae) {
                events.add(ae);
            }
        });
        registriesToCleanup.add(registry);

        registry.register(JobDefinition.builder()
                        .jobName("preThrowJob")
                        .triggerType(TriggerType.MANUAL)
                        .build(),
                ctx -> JobResult.success("unreachable"));

        JobResult result = registry.trigger("preThrowJob", null);
        assertThat(result.isSuccess()).isFalse();
        assertThat(trace).containsExactly("A:pre", "B:pre-throw", "A:after(err=true)");
        assertThat(registry.getJobInfo("preThrowJob").getLastStatus()).isEqualTo("DISCARDED");
        assertThat(logStorage.listLogs("preThrowJob", 10).get(0).getStatus()).isEqualTo("DISCARDED");
        assertThat(events).anySatisfy(e -> {
            assertThat(e).isInstanceOf(JobDiscardedEvent.class);
            assertThat(((JobDiscardedEvent) e).getDiscardReason()).isEqualTo(DiscardReason.INTERCEPTOR_REJECTED);
        });
        assertThat(events).noneMatch(JobFailedEvent.class::isInstance);
    }

    @Test
    @DisplayName("非 JobResult/void 降级返回值应包装为 failure，且支持 JobHandler#executeTimeoutFallback 默认扩展")
    void shouldWrapNonJobResultFallbackAsFailureAndSupportJobHandlerDefaultFallbackHook() {
        InMemoryJobLogStorageProvider logStorage = new InMemoryJobLogStorageProvider(50);
        LocalJobRegistry registry = new LocalJobRegistry(
                createScheduler(), createWorkerExecutor(), new InMemoryJobMetadataRepository(),
                logStorage, null, new JobLocalProperties(), new DefaultTenantProvider(),
                Collections.emptyList(), null);
        registriesToCleanup.add(registry);

        TimeoutFallbackBean bean = new TimeoutFallbackBean();
        registerBeanJob(registry, bean, "slowWithNoArgs", "voidFallbackJob", "onVoidFallback", Duration.ofMillis(60));
        registerBeanJob(registry, bean, "slowWithNoArgs", "stringFallbackJob", "onStringFallback", Duration.ofMillis(60));

        JobResult voidResult = registry.trigger("voidFallbackJob", null);
        assertThat(voidResult.isSuccess()).isFalse();
        assertThat(voidResult.getMessage()).contains("Timeout fallback executed: onVoidFallback");

        JobResult stringResult = registry.trigger("stringFallbackJob", null);
        assertThat(stringResult.isSuccess()).isFalse();
        assertThat(stringResult.getMessage()).isEqualTo("Timeout fallback executed: degraded-msg");

        registry.register(JobDefinition.builder()
                        .jobName("handlerHookFallbackJob")
                        .triggerType(TriggerType.MANUAL)
                        .timeout(Duration.ofMillis(60))
                        .build(),
                new JobHandler() {
                    @Override
                    public Object execute(JobContext context) throws Exception {
                        Thread.sleep(500);
                        return JobResult.success("late");
                    }

                    @Override
                    public JobResult executeTimeoutFallback(JobContext context, JobTimeoutException ex) {
                        return JobResult.success("handler-hook:" + context.getJobName());
                    }
                });

        JobResult hookResult = registry.trigger("handlerHookFallbackJob", null);
        assertThat(hookResult.isSuccess()).isTrue();
        assertThat(hookResult.getMessage()).isEqualTo("handler-hook:handlerHookFallbackJob");
    }

    @Test
    @DisplayName("AutoConfiguration 自动装配应注入排序后的拦截器与全局超时降级器并解析注解新增属性")
    void shouldAutoWireInterceptorsFallbackAndAnnotationMetadata() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengJobLocalAutoConfiguration.class))
                .withUserConfiguration(AutoConfigFixtureConfig.class)
                .withPropertyValues("ateng.boot.job.local.lifecycle-auto-startup=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(LocalJobTemplate.class);
                    LocalJobTemplate template = context.getBean(LocalJobTemplate.class);
                    AutoConfigFixtureConfig fixture = context.getBean(AutoConfigFixtureConfig.class);

                    JobInfo info = template.getJob("autoFallbackJob");
                    assertThat(info.getFallbackMethod()).isEqualTo("onTimeoutFallback");
                    assertThat(info.getTenantConcurrency()).isEqualTo(1);

                    JobResult result = template.trigger("autoFallbackJob", null);
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.getMessage()).isEqualTo("auto-bean-fallback");
                    assertThat(fixture.interceptorTrace).containsExactly("low-order:pre", "high-order:pre",
                            "low-order:post", "high-order:post",
                            "high-order:after", "low-order:after");

                    JobInfo globalFallbackInfo = template.getJob("autoGlobalSpiFallbackJob");
                    assertThat(globalFallbackInfo.getTenantConcurrency()).isEqualTo(3);
                    JobResult globalResult = template.trigger("autoGlobalSpiFallbackJob", null);
                    assertThat(globalResult.isSuccess()).isTrue();
                    assertThat(globalResult.getMessage()).isEqualTo("auto-global-spi:autoGlobalSpiFallbackJob");
                });
    }

    private void registerBeanJob(LocalJobRegistry registry, Object bean, String methodName,
                                 String jobName, String fallbackMethod, Duration timeout) {
        try {
            var method = bean.getClass().getDeclaredMethod(methodName, JobContext.class);
            registry.register(JobDefinition.builder()
                            .jobName(jobName)
                            .triggerType(TriggerType.MANUAL)
                            .timeout(timeout)
                            .fallbackMethod(fallbackMethod.isBlank() ? null : fallbackMethod)
                            .build(),
                    new io.github.atengk.boot.job.local.handler.MethodInvokingJobHandler(bean, method));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    static class TimeoutFallbackBean {
        final AtomicReference<Throwable> capturedThrowable = new AtomicReference<>();

        public JobResult slowWithFullFallback(JobContext ctx) throws InterruptedException {
            Thread.sleep(500);
            return JobResult.success("unreachable");
        }

        private JobResult onFullFallback(JobContext ctx, Throwable ex) {
            capturedThrowable.set(ex);
            return JobResult.success("method-fallback-ctx-ex:" + ctx.getJobName());
        }

        public JobResult slowWithCtxOnly(JobContext ctx) throws InterruptedException {
            Thread.sleep(500);
            return JobResult.success("unreachable");
        }

        public JobResult onCtxOnlyFallback(JobContext ctx) {
            return JobResult.success("fallback-ctx:" + ctx.getJobName());
        }

        public JobResult slowWithExOnly(JobContext ctx) throws InterruptedException {
            Thread.sleep(500);
            return JobResult.success("unreachable");
        }

        public JobResult onExOnlyFallback(JobTimeoutException ex) {
            return JobResult.success("fallback-ex:" + ex.getClass().getSimpleName());
        }

        public JobResult slowWithNoArgs(JobContext ctx) throws InterruptedException {
            Thread.sleep(500);
            return JobResult.success("unreachable");
        }

        public JobResult onNoArgFallback() {
            return JobResult.success("fallback-no-arg");
        }

        public JobResult slowWithoutFallback(JobContext ctx) throws InterruptedException {
            Thread.sleep(500);
            return JobResult.success("unreachable");
        }

        public JobResult slowWithFailingFallback(JobContext ctx) throws InterruptedException {
            Thread.sleep(500);
            return JobResult.success("unreachable");
        }

        public JobResult onBrokenFallback(JobContext ctx, Throwable ex) {
            throw new IllegalStateException("fallback crashed");
        }

        public void onVoidFallback(JobContext ctx) {
        }

        public String onStringFallback() {
            return "degraded-msg";
        }
    }

    static class RecordingInterceptor implements LocalJobInterceptor {
        private final String name;
        private final int order;
        private final boolean allowProceed;
        private final boolean throwInAfter;
        private final List<String> trace;

        RecordingInterceptor(String name, int order, boolean allowProceed, boolean throwInAfter, List<String> trace) {
            this.name = name;
            this.order = order;
            this.allowProceed = allowProceed;
            this.throwInAfter = throwInAfter;
            this.trace = trace;
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public boolean preHandle(JobContext context) {
            trace.add(name + ":pre");
            return allowProceed;
        }

        @Override
        public void postHandle(JobContext context, JobResult result) {
            trace.add(name + ":post");
        }

        @Override
        public void afterCompletion(JobContext context, JobResult result, Throwable ex) {
            trace.add(name + ":after(err=" + (ex != null) + ")");
            if (throwInAfter) {
                throw new IllegalStateException("cleanup error from " + name);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AutoConfigFixtureConfig {
        final List<String> interceptorTrace = new CopyOnWriteArrayList<>();

        @Bean
        public LocalJobInterceptor highOrderInterceptor() {
            return new LocalJobInterceptor() {
                @Override
                public int getOrder() {
                    return 20;
                }

                @Override
                public boolean preHandle(JobContext context) {
                    interceptorTrace.add("high-order:pre");
                    return true;
                }

                @Override
                public void postHandle(JobContext context, JobResult result) {
                    interceptorTrace.add("high-order:post");
                }

                @Override
                public void afterCompletion(JobContext context, JobResult result, Throwable ex) {
                    interceptorTrace.add("high-order:after");
                }
            };
        }

        @Bean
        public LocalJobInterceptor lowOrderInterceptor() {
            return new LocalJobInterceptor() {
                @Override
                public int getOrder() {
                    return 5;
                }

                @Override
                public boolean preHandle(JobContext context) {
                    interceptorTrace.add("low-order:pre");
                    return true;
                }

                @Override
                public void postHandle(JobContext context, JobResult result) {
                    interceptorTrace.add("low-order:post");
                }

                @Override
                public void afterCompletion(JobContext context, JobResult result, Throwable ex) {
                    interceptorTrace.add("low-order:after");
                }
            };
        }

        @LocalJob(name = "autoFallbackJob", cron = "0 0 * * * ?", timeout = "50ms",
                fallbackMethod = "onTimeoutFallback", tenantConcurrency = 0)
        public JobResult autoFallbackJob(JobContext ctx) throws InterruptedException {
            Thread.sleep(300);
            return JobResult.success("late");
        }

        public JobResult onTimeoutFallback(JobContext ctx, Throwable ex) {
            return JobResult.success("auto-bean-fallback");
        }

        @LocalJob(name = "autoGlobalSpiFallbackJob", cron = "0 0 * * * ?", timeout = "50ms",
                tenantConcurrency = 3)
        public JobResult autoGlobalSpiFallbackJob(JobContext ctx) throws InterruptedException {
            Thread.sleep(300);
            return JobResult.success("late");
        }

        @Bean
        public LocalJobTimeoutFallbackHandler globalTimeoutFallbackHandler() {
            return (ctx, ex) -> JobResult.success("auto-global-spi:" + ctx.getJobName());
        }
    }
}
