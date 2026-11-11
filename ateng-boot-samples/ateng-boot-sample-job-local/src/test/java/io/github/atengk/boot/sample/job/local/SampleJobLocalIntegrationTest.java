package io.github.atengk.boot.sample.job.local;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.job.local.alert.JobAlertContext;
import io.github.atengk.boot.job.local.enums.JobStatus;
import io.github.atengk.boot.job.local.enums.TriggerType;
import io.github.atengk.boot.job.local.event.JobEvent;
import io.github.atengk.boot.job.local.event.JobStartingEvent;
import io.github.atengk.boot.job.local.event.JobSuccessEvent;
import io.github.atengk.boot.job.local.event.JobTimeoutEvent;
import io.github.atengk.boot.job.local.exception.JobTimeoutException;
import io.github.atengk.boot.job.local.model.JobExecutionRecord;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.template.LocalJobTemplate;
import io.github.atengk.boot.sample.job.local.handler.SampleCustomJobAlertHandler;
import io.github.atengk.boot.sample.job.local.job.ClusterDeduplicationScenarioJob;
import io.github.atengk.boot.sample.job.local.job.DailyReconciliationScenarioJob;
import io.github.atengk.boot.sample.job.local.job.MultiTenantBillingScenarioJob;
import io.github.atengk.boot.sample.job.local.job.OrderTimeoutCancelScenarioJob;
import io.github.atengk.boot.sample.job.local.job.SystemMetricsSamplingScenarioJob;
import io.github.atengk.boot.sample.job.local.job.TypedParamArchivingScenarioJob;
import io.github.atengk.boot.sample.job.local.listener.SampleJobEventListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工业级单机定时任务样例工程端到端自洽集成测试套件
 * <p>
 * 全真覆盖 6 大专职业务场景任务调度、三大高内聚领域 Controller (Schedule/Manage/Audit) 与 Actuator 运维管控端点交互。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
@SpringBootTest(classes = SampleJobLocalApplication.class)
@AutoConfigureMockMvc
class SampleJobLocalIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocalJobTemplate localJobTemplate;

    @Autowired
    private DailyReconciliationScenarioJob dailyReconciliationScenarioJob;

    @Autowired
    private OrderTimeoutCancelScenarioJob orderTimeoutCancelScenarioJob;

    @Autowired
    private MultiTenantBillingScenarioJob multiTenantBillingScenarioJob;

    @Autowired
    private SystemMetricsSamplingScenarioJob systemMetricsSamplingScenarioJob;

    @Autowired
    private TypedParamArchivingScenarioJob typedParamArchivingScenarioJob;

    @Autowired
    private ClusterDeduplicationScenarioJob clusterDeduplicationScenarioJob;

    @Autowired
    private SampleJobEventListener sampleJobEventListener;

    @Autowired
    private SampleCustomJobAlertHandler sampleCustomJobAlertHandler;

    @Autowired
    private MeterRegistry meterRegistry;

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContextHolder.clear();
        sampleJobEventListener.clearEvents();
        sampleCustomJobAlertHandler.clearAlerts();
        orderTimeoutCancelScenarioJob.setSimulateTimeout(false);
        orderTimeoutCancelScenarioJob.resetAttemptCount();
        multiTenantBillingScenarioJob.setSimulatedFailedTenant(null);
        multiTenantBillingScenarioJob.clearExecutedTenants();
    }

    @Test
    @DisplayName("验证 6 大业务场景任务成功扫描并注册至受管注册表")
    void testAllScenariosRegistered() {
        List<JobInfo> jobs = localJobTemplate.listJobs();
        assertThat(jobs).isNotEmpty();

        List<String> jobNames = jobs.stream().map(JobInfo::getJobName).toList();
        assertThat(jobNames).contains(
                "dailyReconciliationJob",
                "orderTimeoutCancelJob",
                "multiTenantBillingJob",
                "systemMetricsSamplingJob",
                "typedParamArchivingJob",
                "clusterDeduplicationJob"
        );
    }

    @Test
    @DisplayName("场景 1：每日财务对账 Cron 任务执行、结构化日志与领域事件验证")
    void testScenario1DailyReconciliationCron() {
        JobResult result = localJobTemplate.trigger("dailyReconciliationJob");
        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("对账完成");
        assertThat(dailyReconciliationScenarioJob.getExecutionCount()).isGreaterThanOrEqualTo(1);

        // 验证执行历史审计日志
        List<JobExecutionRecord> records = localJobTemplate.listRecentLogs("dailyReconciliationJob", 5);
        assertThat(records).isNotEmpty();
        JobExecutionRecord latest = records.get(0);
        assertThat(latest.getJobName()).isEqualTo("dailyReconciliationJob");
        assertThat(latest.getStatus()).isEqualTo("SUCCESS");
        assertThat(latest.getTriggerType()).isEqualTo(TriggerType.MANUAL);
        assertThat(latest.getTraceId()).isNotBlank();
        assertThat(latest.getLogs()).anyMatch(log -> log.contains("对账批次加载完成"));

        // 验证领域事件发布
        List<JobEvent> events = sampleJobEventListener.getCapturedEvents();
        assertThat(events).anyMatch(e -> e instanceof JobStartingEvent && "dailyReconciliationJob".equals(e.getJobName()));
        assertThat(events).anyMatch(e -> e instanceof JobSuccessEvent && "dailyReconciliationJob".equals(e.getJobName()));
    }

    @Test
    @DisplayName("场景 2：订单超时关单任务的 FixedDelay 正常执行与硬超时熔断重试及告警")
    void testScenario2OrderTimeoutCancelAndRetry() {
        // 1. 正常调度验证
        JobResult normalResult = localJobTemplate.trigger("orderTimeoutCancelJob");
        assertThat(normalResult.isSuccess()).isTrue();
        assertThat(normalResult.getMessage()).contains("扫描并关闭超时订单");

        // 2. 模拟超时熔断与指数退避重试 (1次初始 + 2次重试 = 3次尝试)
        orderTimeoutCancelScenarioJob.resetAttemptCount();
        orderTimeoutCancelScenarioJob.setSimulateTimeout(true);
        sampleJobEventListener.clearEvents();
        sampleCustomJobAlertHandler.clearAlerts();

        try {
            assertThatThrownBy(() -> localJobTemplate.trigger("orderTimeoutCancelJob"))
                    .isInstanceOf(JobTimeoutException.class)
                    .hasMessageContaining("timed out");

            assertThat(orderTimeoutCancelScenarioJob.getAttemptCount()).isEqualTo(3);

            // 验证捕获超时领域事件
            List<JobEvent> events = sampleJobEventListener.getCapturedEvents();
            assertThat(events).anyMatch(e -> e instanceof JobTimeoutEvent && "orderTimeoutCancelJob".equals(e.getJobName()));

            // 验证告警处理器捕获超时告警
            List<JobAlertContext> alerts = sampleCustomJobAlertHandler.getHandledAlerts();
            assertThat(alerts).isNotEmpty();
            JobAlertContext alert = alerts.get(0);
            assertThat(alert.getJobName()).isEqualTo("orderTimeoutCancelJob");
            assertThat(alert.getAlertType()).isIn("TIMEOUT", "FAILED");
        } finally {
            orderTimeoutCancelScenarioJob.setSimulateTimeout(false);
        }
    }

    @Test
    @DisplayName("场景 3：SaaS 多租户账单 ITERATE 模态全成功与 Fail-Safe 容错隔离")
    void testScenario3MultiTenantBillingIterateAndFailSafe() {
        // 1. 全成功遍历执行
        multiTenantBillingScenarioJob.clearExecutedTenants();
        multiTenantBillingScenarioJob.setSimulatedFailedTenant(null);

        JobResult allSuccessResult = localJobTemplate.trigger("multiTenantBillingJob");
        assertThat(allSuccessResult.isSuccess()).isTrue();
        assertThat(allSuccessResult.getMessage()).isEqualTo("完成 3/3 租户调度，成功 3，失败 0");
        assertThat(multiTenantBillingScenarioJob.getExecutedTenants()).containsExactly("tenant-corp-a", "tenant-corp-b", "tenant-corp-c");

        // 2. Fail-Safe 容错隔离验证（中间租户 tenant-corp-b 报错，不阻断后续租户 tenant-corp-c）
        multiTenantBillingScenarioJob.clearExecutedTenants();
        multiTenantBillingScenarioJob.setSimulatedFailedTenant("tenant-corp-b");

        JobResult failSafeResult = localJobTemplate.trigger("multiTenantBillingJob");
        assertThat(failSafeResult.isSuccess()).isFalse();
        assertThat(failSafeResult.getMessage()).contains("完成 3/3 租户调度，成功 2，失败 1");
        assertThat(failSafeResult.getMessage()).contains("tenant-corp-b");
        assertThat(multiTenantBillingScenarioJob.getExecutedTenants()).containsExactly("tenant-corp-a", "tenant-corp-b", "tenant-corp-c");

        JobInfo jobInfo = localJobTemplate.getJob("multiTenantBillingJob");
        assertThat(jobInfo.getLastStatus()).isEqualTo("PARTIAL_SUCCESS");
    }

    @Test
    @DisplayName("场景 4：系统指标周期采样 FixedRate 执行与云原生 Micrometer Timer 指标采集")
    void testScenario4SystemMetricsSamplingAndMicrometer() {
        JobResult result = localJobTemplate.trigger("systemMetricsSamplingJob");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("指标采集正常");
        assertThat(systemMetricsSamplingScenarioJob.getSampleCount()).isGreaterThanOrEqualTo(1);

        // 验证 Micrometer 执行监控指标已正确记录
        Timer timer = meterRegistry.find("ateng.job.local.execution")
                .tag("jobName", "systemMetricsSamplingJob")
                .tag("status", "SUCCESS")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("场景 5：数据归档任务强类型 DTO 入参绑定与 JSR-303 Fail-Fast 校验阻断")
    void testScenario5TypedParamArchivingAndFailFastValidation() {
        // 1. 合规 DTO 入参触发
        Map<String, Object> validParams = Map.of(
                "tableName", "t_order_history",
                "retentionDays", 90,
                "batchSize", 500,
                "dryRun", true
        );
        JobResult validResult = localJobTemplate.trigger("typedParamArchivingJob", validParams);
        assertThat(validResult.isSuccess()).isTrue();
        assertThat(validResult.getMessage()).contains("历史记录归档完成");
        assertThat(typedParamArchivingScenarioJob.getLastReceivedParam()).isNotNull();
        assertThat(typedParamArchivingScenarioJob.getLastReceivedParam().getTableName()).isEqualTo("t_order_history");
        assertThat(typedParamArchivingScenarioJob.getLastReceivedParam().getRetentionDays()).isEqualTo(90);
        assertThat(typedParamArchivingScenarioJob.getLastReceivedParam().isDryRun()).isTrue();

        // 2. 违规入参触发 JSR-303 Fail-Fast 校验阻断
        Map<String, Object> invalidParams = Map.of(
                "tableName", "",
                "retentionDays", -5
        );
        JobResult invalidResult = localJobTemplate.trigger("typedParamArchivingJob", invalidParams);
        assertThat(invalidResult.isSuccess()).isFalse();
        assertThat(invalidResult.getMessage()).containsAnyOf("业务表名不能为空", "归档保留天数必须大于0");
    }

    @Test
    @DisplayName("场景 6：分布式多节点集群防重互斥锁抢占与执行")
    void testScenario6ClusterDeduplicationLock() {
        JobResult result = localJobTemplate.trigger("clusterDeduplicationJob");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("集群防重互斥锁获取成功");
        assertThat(clusterDeduplicationScenarioJob.getExecutionCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("运维端点：Actuator /actuator/localjobs 完整查询、时序预演与启停触发交互")
    void testActuatorLocalJobsEndpoints() throws Exception {
        // 1. GET /actuator/localjobs 任务清单查询
        mockMvc.perform(get("/actuator/localjobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.jobs").isArray())
                .andExpect(jsonPath("$.jobs[?(@.jobName == 'dailyReconciliationJob')]").exists())
                .andExpect(jsonPath("$.jobs[?(@.jobName == 'multiTenantBillingJob')]").exists());

        // 2. GET /actuator/localjobs 带 Cron 表达式时序预演
        mockMvc.perform(get("/actuator/localjobs")
                        .param("cron", "0 0 2 * * ?")
                        .param("count", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.cron").value("0 0 2 * * ?"))
                .andExpect(jsonPath("$.nextExecutionTimes").isArray());

        // 3. GET /actuator/localjobs/{jobName} 单任务详情查询
        mockMvc.perform(get("/actuator/localjobs/dailyReconciliationJob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.job.jobName").value("dailyReconciliationJob"))
                .andExpect(jsonPath("$.job.status").exists())
                .andExpect(jsonPath("$.recentLogs").isArray());

        // 4. POST /actuator/localjobs/{jobName} (action=trigger)
        mockMvc.perform(post("/actuator/localjobs/dailyReconciliationJob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"trigger\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 5. POST /actuator/localjobs/{jobName} (action=pause)
        mockMvc.perform(post("/actuator/localjobs/dailyReconciliationJob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"pause\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(localJobTemplate.getJob("dailyReconciliationJob").getStatus()).isEqualTo(JobStatus.PAUSED);

        // 6. POST /actuator/localjobs/{jobName} (action=resume)
        mockMvc.perform(post("/actuator/localjobs/dailyReconciliationJob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"resume\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(localJobTemplate.getJob("dailyReconciliationJob").getStatus()).isEqualTo(JobStatus.RUNNING);

    }

    @Test
    @DisplayName("生命周期：通过 LocalJobTemplate 编程式动态注册与注销任务")
    void testDynamicJobLifecycleViaTemplate() {
        String dynamicJobName = "dynamicCleanupJob";
        boolean registered = localJobTemplate.registerCron(
                dynamicJobName,
                "0 0 3 * * ?",
                () -> {}
        );
        assertThat(registered).isTrue();

        JobInfo registeredJob = localJobTemplate.getJob(dynamicJobName);
        assertThat(registeredJob).isNotNull();
        assertThat(registeredJob.getCron()).isEqualTo("0 0 3 * * ?");

        boolean removed = localJobTemplate.remove(dynamicJobName);
        assertThat(removed).isTrue();
        assertThat(localJobTemplate.getJob(dynamicJobName)).isNull();
    }

    @Test
    @DisplayName("领域控制器 1：LocalJobScheduleController 端到端 HTTP 触发与 JSR-303 防卫")
    void testLocalJobScheduleControllerEndpoints() throws Exception {
        // 1. POST /sample/job-local/schedule/trigger/{jobName} 手动立即触发
        mockMvc.perform(post("/sample/job-local/schedule/trigger/dailyReconciliationJob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("对账完成")));

        // 2. POST /sample/job-local/schedule/trigger/archiving 合规参数触发
        String validJson = "{\"tableName\":\"t_order_history\",\"retentionDays\":90,\"batchSize\":500,\"dryRun\":true}";
        mockMvc.perform(post("/sample/job-local/schedule/trigger/archiving")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("历史记录归档完成")));

        // 3. POST /sample/job-local/schedule/trigger/archiving 非法参数触发 JSR-303 Fail-Fast 校验拦截
        String invalidJson = "{\"tableName\":\"\",\"retentionDays\":-5}";
        mockMvc.perform(post("/sample/job-local/schedule/trigger/archiving")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));

        // 4. POST /sample/job-local/schedule/trigger/billing 多租户账单轮询触发
        mockMvc.perform(post("/sample/job-local/schedule/trigger/billing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        // 5. POST /sample/job-local/schedule/trigger/cluster-lock 轻量集群防重互斥锁触发
        mockMvc.perform(post("/sample/job-local/schedule/trigger/cluster-lock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("集群防重互斥锁获取成功")));
    }

    @Test
    @DisplayName("领域控制器 2：LocalJobManageController 任务动态生命周期运维管控交互")
    void testLocalJobManageControllerEndpoints() throws Exception {
        String targetJob = "dailyReconciliationJob";

        // 1. POST /sample/job-local/manage/pause/{jobName} 暂停任务
        mockMvc.perform(post("/sample/job-local/manage/pause/" + targetJob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
        assertThat(localJobTemplate.getJob(targetJob).getStatus()).isEqualTo(JobStatus.PAUSED);

        // 2. POST /sample/job-local/manage/resume/{jobName} 恢复任务
        mockMvc.perform(post("/sample/job-local/manage/resume/" + targetJob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
        assertThat(localJobTemplate.getJob(targetJob).getStatus()).isEqualTo(JobStatus.RUNNING);

        // 3. POST /sample/job-local/manage/update/cron 动态修改 Cron
        mockMvc.perform(post("/sample/job-local/manage/update/cron")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobName\":\"" + targetJob + "\",\"cronExpression\":\"0 0 5 * * ?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
        assertThat(localJobTemplate.getJob(targetJob).getCron()).isEqualTo("0 0 5 * * ?");

        // 4. POST /sample/job-local/manage/update/rate 动态修改 FixedRate
        mockMvc.perform(post("/sample/job-local/manage/update/rate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobName\":\"systemMetricsSamplingJob\",\"fixedRate\":\"10s\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 5. POST /sample/job-local/manage/update/delay 动态修改 FixedDelay
        mockMvc.perform(post("/sample/job-local/manage/update/delay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobName\":\"orderTimeoutCancelJob\",\"fixedDelay\":\"15s\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 6. POST /sample/job-local/manage/dynamic/register 动态注册新任务
        String dynamicJob = "dynamicControllerJob";
        mockMvc.perform(post("/sample/job-local/manage/dynamic/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobName\":\"" + dynamicJob + "\",\"cron\":\"0 0 4 * * ?\",\"description\":\"控制器动态注册任务\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
        assertThat(localJobTemplate.exists(dynamicJob)).isTrue();

        // 7. DELETE /sample/job-local/manage/{jobName} 注销并移除任务
        mockMvc.perform(delete("/sample/job-local/manage/" + dynamicJob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
        assertThat(localJobTemplate.exists(dynamicJob)).isFalse();

        // 8. 验证参数校验防御：传入非法时间格式触发 JSR-303 Fail-Fast 阻断
        mockMvc.perform(post("/sample/job-local/manage/update/rate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobName\":\"systemMetricsSamplingJob\",\"fixedRate\":\"invalid-time\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("领域控制器 3：LocalJobAuditController 任务清单巡检、审计日志与 Cron 预演")
    void testLocalJobAuditControllerEndpoints() throws Exception {
        // 1. GET /sample/job-local/audit/jobs 巡检所有任务
        mockMvc.perform(get("/sample/job-local/audit/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@.jobName == 'dailyReconciliationJob')]").exists());

        // 2. GET /sample/job-local/audit/jobs/{jobName} 查询单任务详情
        mockMvc.perform(get("/sample/job-local/audit/jobs/dailyReconciliationJob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.jobName").value("dailyReconciliationJob"))
                .andExpect(jsonPath("$.data.status").exists());

        // 3. GET /sample/job-local/audit/jobs/{jobName}/logs 查询最近审计历史日志
        localJobTemplate.trigger("dailyReconciliationJob");
        mockMvc.perform(get("/sample/job-local/audit/jobs/dailyReconciliationJob/logs")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 4. POST /sample/job-local/audit/cron/preview 校验与预演未来触发时钟点
        mockMvc.perform(post("/sample/job-local/audit/cron/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cron\":\"0 0 2 * * ?\",\"count\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.nextExecutionTimes").isArray())
                .andExpect(jsonPath("$.data.nextExecutionTimes.length()").value(3));

        // 5. DELETE /sample/job-local/audit/logs/expired 滚动清理过期历史记录
        mockMvc.perform(delete("/sample/job-local/audit/logs/expired")
                        .param("retention", "7d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNumber());
    }
}
