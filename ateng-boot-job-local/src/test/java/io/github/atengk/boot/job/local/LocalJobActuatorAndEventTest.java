package io.github.atengk.boot.job.local;

import io.github.atengk.boot.job.local.actuator.LocalJobEndpoint;
import io.github.atengk.boot.job.local.alert.JobAlertContext;
import io.github.atengk.boot.job.local.alert.JobAlertHandler;
import io.github.atengk.boot.job.local.alert.LoggingJobAlertHandler;
import io.github.atengk.boot.job.local.alert.NotificationJobAlertHandler;
import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.config.AtengJobLocalAutoConfiguration;
import io.github.atengk.boot.job.local.enums.JobStatus;
import io.github.atengk.boot.job.local.enums.OverlappingStrategy;
import io.github.atengk.boot.job.local.event.JobDiscardedEvent;
import io.github.atengk.boot.job.local.event.JobEvent;
import io.github.atengk.boot.job.local.event.JobFailedEvent;
import io.github.atengk.boot.job.local.event.JobStartingEvent;
import io.github.atengk.boot.job.local.event.JobSuccessEvent;
import io.github.atengk.boot.job.local.event.JobTimeoutEvent;
import io.github.atengk.boot.job.local.exception.JobTimeoutException;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.template.LocalJobTemplate;
import io.github.atengk.boot.notification.model.MarkdownMessage;
import io.github.atengk.boot.notification.model.NotificationMessage;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Actuator 运维端点、领域事件总线与告警桥接端到端测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
@SpringBootTest(classes = LocalJobActuatorAndEventTest.TestAppConfig.class,
        properties = {
                "ateng.boot.job.local.alert.enabled=true",
                "ateng.boot.job.local.alert.channel=wecom-robot"
        })
@DisplayName("LocalJob Actuator 运维端点、领域事件总线与告警桥接测试")
class LocalJobActuatorAndEventTest {

    @Autowired
    private LocalJobEndpoint localJobEndpoint;

    @Autowired
    private LocalJobTemplate jobTemplate;

    @Autowired
    private TestEventListener eventListener;

    @Autowired
    private RecordingJobAlertHandler alertHandler;

    @Autowired
    private ActuatorAndEventTasks tasks;

    @BeforeEach
    void setUp() {
        eventListener.clear();
        alertHandler.clear();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        org.slf4j.MDC.clear();
        io.github.atengk.boot.core.context.UserContextHolder.clear();
        io.github.atengk.boot.core.context.TenantContextHolder.clear();
    }

    @Test
    @DisplayName("Actuator 运维端点：listJobs() 巡检返回全局任务清单状态")
    void testActuatorListJobs() {
        Map<String, Object> response = localJobEndpoint.listJobs();
        assertThat(response.get("status")).isEqualTo("UP");
        assertThat((Integer) response.get("total")).isGreaterThanOrEqualTo(3);

        @SuppressWarnings("unchecked")
        List<JobInfo> jobs = (List<JobInfo>) response.get("jobs");
        assertThat(jobs).extracting(JobInfo::getJobName)
                .contains("actuatorNormalJob", "actuatorFailingJob", "actuatorTimeoutJob");
    }

    @Test
    @DisplayName("Actuator 运维端点：getJob() 查询单个任务详情与历史审计日志")
    void testActuatorGetJob() {
        // 先触发一次生成日志
        localJobEndpoint.executeAction("actuatorNormalJob", "trigger", Map.of("paramKey", "testVal"));

        Map<String, Object> response = localJobEndpoint.getJob("actuatorNormalJob");
        assertThat(response.get("status")).isEqualTo("OK");

        JobInfo job = (JobInfo) response.get("job");
        assertThat(job.getJobName()).isEqualTo("actuatorNormalJob");
        assertThat(job.getLastStatus()).isEqualTo("SUCCESS");

        @SuppressWarnings("unchecked")
        List<?> recentLogs = (List<?>) response.get("recentLogs");
        assertThat(recentLogs).isNotEmpty();

        // 查询不存在的任务
        Map<String, Object> notFound = localJobEndpoint.getJob("nonExistingJob");
        assertThat(notFound.get("status")).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("Actuator 运维端点：executeAction() 动态触发、暂停与恢复")
    void testActuatorExecuteActions() {
        String jobName = "actuatorNormalJob";

        // 1. 立即触发
        Map<String, Object> triggerResult = localJobEndpoint.executeAction(jobName, "trigger", Map.of("batch", 50));
        assertThat(triggerResult.get("success")).isEqualTo(true);

        // 2. 暂停任务
        Map<String, Object> pauseResult = localJobEndpoint.executeAction(jobName, "pause", null);
        assertThat(pauseResult.get("success")).isEqualTo(true);
        assertThat(jobTemplate.getJob(jobName).getStatus()).isEqualTo(JobStatus.PAUSED);

        // 3. 恢复任务
        Map<String, Object> resumeResult = localJobEndpoint.executeAction(jobName, "resume", null);
        assertThat(resumeResult.get("success")).isEqualTo(true);
        assertThat(jobTemplate.getJob(jobName).getStatus()).isEqualTo(JobStatus.RUNNING);

        // 4. 不支持的操作指令
        Map<String, Object> invalidResult = localJobEndpoint.executeAction(jobName, "invalidAction", null);
        assertThat(invalidResult.get("success")).isEqualTo(false);
        assertThat((String) invalidResult.get("message")).contains("Unsupported action");
    }

    @Test
    @DisplayName("领域事件总线：生命周期各个关键阶段精准发布对应的领域事件")
    void testDomainEventsPublication() throws Exception {
        // 1. 成功事件链测试：触发 actuatorNormalJob，验证发布 JobStartingEvent 与 JobSuccessEvent
        eventListener.clear();
        jobTemplate.trigger("actuatorNormalJob", Map.of("reqId", "123456"));

        assertThat(eventListener.capturedEvents).hasSize(2);
        assertThat(eventListener.capturedEvents.get(0)).isInstanceOf(JobStartingEvent.class);
        JobStartingEvent startEvent = (JobStartingEvent) eventListener.capturedEvents.get(0);
        assertThat(startEvent.getJobName()).isEqualTo("actuatorNormalJob");
        assertThat(startEvent.getParams()).containsEntry("reqId", "123456");

        assertThat(eventListener.capturedEvents.get(1)).isInstanceOf(JobSuccessEvent.class);
        JobSuccessEvent successEvent = (JobSuccessEvent) eventListener.capturedEvents.get(1);
        assertThat(successEvent.getJobName()).isEqualTo("actuatorNormalJob");
        assertThat(successEvent.getDurationMs()).isGreaterThanOrEqualTo(0);

        // 2. 失败事件链测试：触发 actuatorFailingJob，验证发布 JobFailedEvent
        eventListener.clear();
        jobTemplate.trigger("actuatorFailingJob");

        assertThat(eventListener.capturedEvents).hasSize(2);
        assertThat(eventListener.capturedEvents.get(0)).isInstanceOf(JobStartingEvent.class);
        assertThat(eventListener.capturedEvents.get(1)).isInstanceOf(JobFailedEvent.class);
        JobFailedEvent failedEvent = (JobFailedEvent) eventListener.capturedEvents.get(1);
        assertThat(failedEvent.getJobName()).isEqualTo("actuatorFailingJob");
        assertThat(failedEvent.getErrorMsg()).contains("Simulated task business failure");

        // 3. 超时事件链测试：触发 actuatorTimeoutJob，验证发布 JobTimeoutEvent
        eventListener.clear();
        assertThatThrownBy(() -> jobTemplate.trigger("actuatorTimeoutJob"))
                .isInstanceOf(JobTimeoutException.class);

        assertThat(eventListener.capturedEvents).hasSize(2);
        assertThat(eventListener.capturedEvents.get(0)).isInstanceOf(JobStartingEvent.class);
        assertThat(eventListener.capturedEvents.get(1)).isInstanceOf(JobTimeoutEvent.class);
        JobTimeoutEvent timeoutEvent = (JobTimeoutEvent) eventListener.capturedEvents.get(1);
        assertThat(timeoutEvent.getJobName()).isEqualTo("actuatorTimeoutJob");
        assertThat(timeoutEvent.getTimeout()).isEqualTo(Duration.ofMillis(300));

        // 4. 跳过/丢弃事件测试：并发重叠触发 DISCARD 任务
        eventListener.clear();
        tasks.discardLatch = new CountDownLatch(1);
        tasks.discardRelease = new CountDownLatch(1);

        CompletableFuture<JobResult> f1 = CompletableFuture.supplyAsync(() -> jobTemplate.trigger("actuatorDiscardJob"));
        tasks.discardLatch.await(3, TimeUnit.SECONDS);

        // 第 2 次触发，触发 DISCARD
        jobTemplate.trigger("actuatorDiscardJob");
        tasks.discardRelease.countDown();
        f1.get(3, TimeUnit.SECONDS);

        assertThat(eventListener.capturedEvents).anyMatch(e -> e instanceof JobDiscardedEvent
                && ((JobDiscardedEvent) e).getReason().contains("DISCARD"));
    }

    @Test
    @DisplayName("告警桥接监听器：当 alert.enabled=true 时，自动捕获 Failed 与 Timeout 事件并分发给 JobAlertHandler")
    void testAlertBridgeDispatch() {
        alertHandler.clear();

        // 1. 触发失败任务
        jobTemplate.trigger("actuatorFailingJob");
        assertThat(alertHandler.recordedAlerts).hasSize(1);
        JobAlertContext failedAlert = alertHandler.recordedAlerts.get(0);
        assertThat(failedAlert.getJobName()).isEqualTo("actuatorFailingJob");
        assertThat(failedAlert.getAlertType()).isEqualTo("FAILED");
        assertThat(failedAlert.getErrorMsg()).contains("Simulated task business failure");
        assertThat(failedAlert.getTraceId()).isNotNull();

        // 2. 触发超时任务
        assertThatThrownBy(() -> jobTemplate.trigger("actuatorTimeoutJob"))
                .isInstanceOf(JobTimeoutException.class);

        assertThat(alertHandler.recordedAlerts).hasSize(2);
        JobAlertContext timeoutAlert = alertHandler.recordedAlerts.get(1);
        assertThat(timeoutAlert.getJobName()).isEqualTo("actuatorTimeoutJob");
        assertThat(timeoutAlert.getAlertType()).isEqualTo("TIMEOUT");
        assertThat(timeoutAlert.getErrorMsg()).contains("timed out after");
    }

    @Test
    @DisplayName("NotificationJobAlertHandler 协同通知群机器人告警消息构建与异步分发自验")
    void testNotificationJobAlertHandler() {
        NotificationTemplate mockTemplate = mock(NotificationTemplate.class);
        NotificationJobAlertHandler handler = new NotificationJobAlertHandler(mockTemplate, "wecom-devops");

        JobAlertContext context = JobAlertContext.builder()
                .jobName("orderSyncJob")
                .alertType("FAILED")
                .triggerType(io.github.atengk.boot.job.local.enums.TriggerType.CRON)
                .traceId("trace-alert-999")
                .durationMs(1234)
                .retryCount(2)
                .errorMsg("Database connection timeout")
                .timestamp(Instant.now())
                .build();

        handler.handleAlert(context);

        ArgumentCaptor<NotificationMessage> messageCaptor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(mockTemplate).sendAsync(eq("wecom-devops"), messageCaptor.capture());

        NotificationMessage sentMessage = messageCaptor.getValue();
        assertThat(sentMessage).isInstanceOf(MarkdownMessage.class);
        MarkdownMessage markdown = (MarkdownMessage) sentMessage;
        assertThat(markdown.getTitle()).contains("orderSyncJob FAILED");
        assertThat(markdown.getContent()).contains("orderSyncJob", "trace-alert-999", "Database connection timeout", "1234 ms");
    }

    @Test
    @DisplayName("LoggingJobAlertHandler 兜底日志输出空安全防护")
    void testLoggingJobAlertHandlerSafe() {
        LoggingJobAlertHandler loggingHandler = new LoggingJobAlertHandler();
        loggingHandler.handleAlert(null); // 不抛出异常

        JobAlertContext context = JobAlertContext.builder()
                .jobName("safeJob")
                .alertType("TIMEOUT")
                .durationMs(500)
                .errorMsg("Safe timeout")
                .build();
        loggingHandler.handleAlert(context);
    }

    @Configuration
    @Import(AtengJobLocalAutoConfiguration.class)
    static class TestAppConfig {

        @Bean
        public ActuatorAndEventTasks actuatorAndEventTasks() {
            return new ActuatorAndEventTasks();
        }

        @Bean
        public TestEventListener testEventListener() {
            return new TestEventListener();
        }

        @Bean
        @Primary
        public RecordingJobAlertHandler recordingJobAlertHandler() {
            return new RecordingJobAlertHandler();
        }
    }

    @Component
    static class TestEventListener {

        final List<JobEvent> capturedEvents = new CopyOnWriteArrayList<>();

        @EventListener
        public void onJobEvent(JobEvent event) {
            capturedEvents.add(event);
        }

        public void clear() {
            capturedEvents.clear();
        }
    }

    @Component
    static class RecordingJobAlertHandler implements JobAlertHandler {

        final List<JobAlertContext> recordedAlerts = new CopyOnWriteArrayList<>();

        @Override
        public void handleAlert(JobAlertContext context) {
            recordedAlerts.add(context);
        }

        public void clear() {
            recordedAlerts.clear();
        }
    }

    @Component
    static class ActuatorAndEventTasks {

        volatile CountDownLatch discardLatch;
        volatile CountDownLatch discardRelease;

        @LocalJob(name = "actuatorNormalJob", fixedDelay = "1h", initialDelay = "1h")
        public String executeNormal(JobContext context) {
            return "Normal job finished";
        }

        @LocalJob(name = "actuatorFailingJob", fixedDelay = "1h", initialDelay = "1h")
        public void executeFailing() {
            throw new RuntimeException("Simulated task business failure");
        }

        @LocalJob(name = "actuatorTimeoutJob", timeout = "300ms", fixedDelay = "1h", initialDelay = "1h")
        public void executeTimeout() {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @LocalJob(name = "actuatorDiscardJob", strategy = OverlappingStrategy.DISCARD, fixedDelay = "1h", initialDelay = "1h")
        public void executeDiscard() {
            if (discardLatch != null) {
                discardLatch.countDown();
            }
            if (discardRelease != null) {
                try {
                    discardRelease.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
