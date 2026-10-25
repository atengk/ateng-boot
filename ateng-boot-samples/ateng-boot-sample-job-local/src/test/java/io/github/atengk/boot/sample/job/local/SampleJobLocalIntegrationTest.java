package io.github.atengk.boot.sample.job.local;

import io.github.atengk.boot.job.local.alert.JobAlertContext;
import io.github.atengk.boot.job.local.enums.JobStatus;
import io.github.atengk.boot.job.local.enums.TriggerType;
import io.github.atengk.boot.job.local.event.JobEvent;
import io.github.atengk.boot.job.local.event.JobFailedEvent;
import io.github.atengk.boot.job.local.event.JobStartingEvent;
import io.github.atengk.boot.job.local.event.JobSuccessEvent;
import io.github.atengk.boot.job.local.event.JobTimeoutEvent;
import io.github.atengk.boot.job.local.model.JobExecutionRecord;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.template.LocalJobTemplate;
import io.github.atengk.boot.sample.job.local.handler.SampleCustomJobAlertHandler;
import io.github.atengk.boot.sample.job.local.job.SampleScheduledJobs;
import io.github.atengk.boot.sample.job.local.listener.SampleJobEventListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工业级单机定时任务端到端自洽集成测试套件
 *
 * @author Ateng
 * @since 2026-10-25
 */
@SpringBootTest(classes = SampleJobLocalApplication.class)
@AutoConfigureMockMvc
class SampleJobLocalIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocalJobTemplate localJobTemplate;

    @Autowired
    private SampleScheduledJobs sampleScheduledJobs;

    @Autowired
    private SampleJobEventListener sampleJobEventListener;

    @Autowired
    private SampleCustomJobAlertHandler sampleCustomJobAlertHandler;

    @AfterEach
    void tearDown() {
        MDC.clear();
        sampleJobEventListener.clearEvents();
        sampleCustomJobAlertHandler.clearAlerts();
    }

    @Test
    @DisplayName("验证所有示例定时任务成功扫描并注册至受管注册表")
    void testJobsRegisteredSuccessfully() {
        List<JobInfo> jobs = localJobTemplate.listJobs();
        assertThat(jobs).isNotEmpty();

        List<String> jobNames = jobs.stream().map(JobInfo::getJobName).toList();
        assertThat(jobNames).contains(
                "sampleCronJob",
                "sampleFixedRateJob",
                "sampleFixedDelayJob",
                "sampleDiscardJob",
                "sampleQueueJob",
                "sampleInterruptJob",
                "sampleTimeoutRetryJob",
                "sampleClusterLockJob"
        );
    }

    @Test
    @DisplayName("测试编程式手动触发 sampleCronJob 并验证日志记录与产物返回")
    void testTriggerCronJobViaTemplate() {
        JobResult result = localJobTemplate.trigger("sampleCronJob");
        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("对账完成");

        // 验证执行历史记录
        List<JobExecutionRecord> records = localJobTemplate.listRecentLogs("sampleCronJob", 5);
        assertThat(records).isNotEmpty();
        JobExecutionRecord latest = records.get(0);
        assertThat(latest.getJobName()).isEqualTo("sampleCronJob");
        assertThat(latest.getStatus()).isEqualTo("SUCCESS");
        assertThat(latest.getTriggerType()).isEqualTo(TriggerType.MANUAL);
        assertThat(latest.getTraceId()).isNotBlank();
        assertThat(latest.getLogs()).anyMatch(log -> log.contains("对账批次加载完成"));

        // 验证领域事件发布
        List<JobEvent> events = sampleJobEventListener.getCapturedEvents();
        assertThat(events).anyMatch(e -> e instanceof JobStartingEvent && "sampleCronJob".equals(e.getJobName()));
        assertThat(events).anyMatch(e -> e instanceof JobSuccessEvent && "sampleCronJob".equals(e.getJobName()));
    }

    @Test
    @DisplayName("测试任务暂停与恢复调度生命周期")
    void testPauseAndResumeJob() {
        boolean paused = localJobTemplate.pause("sampleCronJob");
        assertThat(paused).isTrue();

        JobInfo pausedJob = localJobTemplate.getJob("sampleCronJob");
        assertThat(pausedJob).isNotNull();
        assertThat(pausedJob.getStatus()).isEqualTo(JobStatus.PAUSED);

        boolean resumed = localJobTemplate.resume("sampleCronJob");
        assertThat(resumed).isTrue();

        JobInfo resumedJob = localJobTemplate.getJob("sampleCronJob");
        assertThat(resumedJob).isNotNull();
        assertThat(resumedJob.getStatus()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    @DisplayName("测试任务硬超时熔断、重试与失败告警桥接")
    void testTimeoutRetryAndAlertBridge() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> localJobTemplate.trigger("sampleTimeoutRetryJob"))
                .isInstanceOf(io.github.atengk.boot.job.local.exception.JobTimeoutException.class)
                .hasMessageContaining("timed out");

        // 验证捕获超时与失败领域事件
        List<JobEvent> events = sampleJobEventListener.getCapturedEvents();
        assertThat(events).anyMatch(e -> e instanceof JobTimeoutEvent && "sampleTimeoutRetryJob".equals(e.getJobName()));

        // 验证自定义告警处理器捕获告警上下文
        List<JobAlertContext> alerts = sampleCustomJobAlertHandler.getHandledAlerts();
        assertThat(alerts).isNotEmpty();
        JobAlertContext alert = alerts.get(0);
        assertThat(alert.getJobName()).isEqualTo("sampleTimeoutRetryJob");
        assertThat(alert.getAlertType()).isIn("TIMEOUT", "FAILED");
    }

    @Test
    @DisplayName("测试轻量集群互斥锁任务获取并执行")
    void testClusterLockJob() {
        JobResult result = localJobTemplate.trigger("sampleClusterLockJob");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("集群互斥锁获取成功");
    }

    @Test
    @DisplayName("测试 Actuator /actuator/localjobs 运维端点完整交互契约")
    void testActuatorEndpoint() throws Exception {
        // 1. GET 列表
        mockMvc.perform(get("/actuator/localjobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.jobs").isArray())
                .andExpect(jsonPath("$.jobs[?(@.jobName == 'sampleCronJob')]").exists());

        // 2. GET 单任务详情
        mockMvc.perform(get("/actuator/localjobs/sampleCronJob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.job.jobName").value("sampleCronJob"))
                .andExpect(jsonPath("$.job.status").exists())
                .andExpect(jsonPath("$.recentLogs").isArray());

        // 3. POST 触发操作
        mockMvc.perform(post("/actuator/localjobs/sampleCronJob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"trigger\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 4. POST 暂停操作
        mockMvc.perform(post("/actuator/localjobs/sampleCronJob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"pause\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 5. POST 恢复操作
        mockMvc.perform(post("/actuator/localjobs/sampleCronJob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"resume\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("测试 JobSampleController REST 契约接口")
    void testJobSampleControllerRestEndpoints() throws Exception {
        // 1. GET /api/v1/jobs
        mockMvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 2. GET /api/v1/jobs/{jobName}
        mockMvc.perform(get("/api/v1/jobs/sampleCronJob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.jobName").value("sampleCronJob"));

        // 3. POST /api/v1/jobs/{jobName}/trigger
        mockMvc.perform(post("/api/v1/jobs/sampleCronJob/trigger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        // 4. GET /api/v1/jobs/{jobName}/logs
        mockMvc.perform(get("/api/v1/jobs/sampleCronJob/logs").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 5. POST /api/v1/jobs/{jobName}/pause & resume
        mockMvc.perform(post("/api/v1/jobs/sampleCronJob/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(post("/api/v1/jobs/sampleCronJob/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 6. POST /api/v1/jobs/dynamic-register
        String requestJson = """
                {
                    "jobName": "dynamicHealthCheckJob",
                    "cron": "0 0 * * * ?",
                    "description": "动态注册的健康巡检任务"
                }
                """;
        mockMvc.perform(post("/api/v1/jobs/dynamic-register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 验证动态注册的任务在注册表中存在
        JobInfo dynamicJob = localJobTemplate.getJob("dynamicHealthCheckJob");
        assertThat(dynamicJob).isNotNull();
        assertThat(dynamicJob.getCron()).isEqualTo("0 0 * * * ?");
    }
}
