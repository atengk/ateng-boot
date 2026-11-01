package io.github.atengk.boot.job.local;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.config.AtengJobLocalAutoConfiguration;
import io.github.atengk.boot.job.local.enums.JobTenantMode;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.template.LocalJobTemplate;
import io.github.atengk.boot.job.local.tenant.DefaultTenantProvider;
import io.github.atengk.boot.job.local.tenant.TenantProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SaaS 多租户全模态编排与 Fail-Safe 遍历容错集成测试
 *
 * @author Ateng
 * @since 2026-11-01
 */
@SpringBootTest(classes = LocalJobMultiTenantTest.TestConfig.class)
@DisplayName("LocalJob SaaS 多租户全模态隔离与 Fail-Safe 遍历测试")
class LocalJobMultiTenantTest {

    @Autowired
    private LocalJobTemplate jobTemplate;

    @Autowired
    private TenantJobTasks tasks;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        tasks.reset();
    }

    @Test
    @DisplayName("测试 IGNORE 模态：全局忽略租户隔离，TenantContext 处于 ignore 状态且 context.getTenantId() 为 null")
    void testIgnoreTenantMode() {
        JobResult result = jobTemplate.trigger("jobIgnoreTenant");
        assertThat(result.isSuccess()).isTrue();
        assertThat(tasks.lastIgnoreTenantId).isNull();
        assertThat(tasks.lastIgnoreWasIgnored).isTrue();
    }

    @Test
    @DisplayName("测试 PARAM 模态：从触发参数中提取 tenantId，正确注入上下文并执行")
    void testParamTenantMode() {
        JobResult result = jobTemplate.trigger("jobParamTenant", Map.of("tenantId", "tenant-vip"));
        assertThat(result.isSuccess()).isTrue();
        assertThat(tasks.lastParamTenantId).isEqualTo("tenant-vip");
        assertThat(tasks.lastParamContextTenantId).isEqualTo("tenant-vip");
    }

    @Test
    @DisplayName("测试 ITERATE 模态全成功：按租户清单逐个隔离调度，并汇总聚合 JobResult")
    void testIterateTenantModeAllSuccess() {
        JobResult result = jobTemplate.trigger("jobIterateSuccess");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("完成 3/3 租户调度，成功 3，失败 0");
        assertThat(tasks.executedTenantsSuccess).containsExactly("tenant-alpha", "tenant-beta", "tenant-gamma");

        JobInfo jobInfo = jobTemplate.getJob("jobIterateSuccess");
        assertThat(jobInfo.getTenantMode()).isEqualTo(JobTenantMode.ITERATE);
        assertThat(jobInfo.getLastStatus()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("测试 ITERATE 模态 Fail-Safe 容错：中间租户异常不阻断后续租户，聚合返回失败统计与详情")
    void testIterateTenantModeFailSafeIsolation() {
        JobResult result = jobTemplate.trigger("jobIterateFailSafe");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("完成 3/3 租户调度，成功 2，失败 1");
        assertThat(result.getMessage()).contains("tenant-beta");

        // 验证中间租户 tenant-beta 抛错后，后续的 tenant-gamma 依然被安全执行
        assertThat(tasks.executedTenantsFailSafe).containsExactly("tenant-alpha", "tenant-beta", "tenant-gamma");

        JobInfo jobInfo = jobTemplate.getJob("jobIterateFailSafe");
        assertThat(jobInfo.getLastStatus()).isEqualTo("PARTIAL_SUCCESS");
    }

    @Test
    @DisplayName("测试 DefaultTenantProvider 保底机制：无租户上下文时安全回退至 default 租户")
    void testDefaultTenantProviderFallback() {
        DefaultTenantProvider defaultProvider = new DefaultTenantProvider();
        List<String> tenants = defaultProvider.getTenantIds();
        assertThat(tenants).containsExactly("default");

        try {
            TenantContextHolder.setTenantId("ctx-tenant-100");
            assertThat(defaultProvider.getTenantIds()).containsExactly("ctx-tenant-100");
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Configuration
    @Import({AtengJobLocalAutoConfiguration.class, TenantJobTasks.class})
    static class TestConfig {

        @Bean
        public TenantProvider customTenantProvider() {
            return () -> List.of("tenant-alpha", "tenant-beta", "tenant-gamma");
        }
    }

    @Component
    public static class TenantJobTasks {

        volatile String lastIgnoreTenantId;
        volatile boolean lastIgnoreWasIgnored;

        volatile String lastParamTenantId;
        volatile String lastParamContextTenantId;

        final List<String> executedTenantsSuccess = new CopyOnWriteArrayList<>();
        final List<String> executedTenantsFailSafe = new CopyOnWriteArrayList<>();

        void reset() {
            lastIgnoreTenantId = null;
            lastIgnoreWasIgnored = false;
            lastParamTenantId = null;
            lastParamContextTenantId = null;
            executedTenantsSuccess.clear();
            executedTenantsFailSafe.clear();
        }

        @LocalJob(name = "jobIgnoreTenant", tenantMode = JobTenantMode.IGNORE, fixedDelay = "1h", initialDelay = "1h")
        public JobResult jobIgnoreTenant(JobContext context) {
            this.lastIgnoreTenantId = context.getTenantId();
            this.lastIgnoreWasIgnored = TenantContextHolder.isIgnoreTenant();
            return JobResult.success("Ignore OK");
        }

        @LocalJob(name = "jobParamTenant", tenantMode = JobTenantMode.PARAM, fixedDelay = "1h", initialDelay = "1h")
        public JobResult jobParamTenant(JobContext context) {
            this.lastParamTenantId = TenantContextHolder.getTenantId();
            this.lastParamContextTenantId = context.getTenantId();
            return JobResult.success("Param OK: " + this.lastParamTenantId);
        }

        @LocalJob(name = "jobIterateSuccess", tenantMode = JobTenantMode.ITERATE, fixedDelay = "1h", initialDelay = "1h")
        public JobResult jobIterateSuccess(JobContext context) {
            String tenantId = context.getTenantId();
            executedTenantsSuccess.add(tenantId);
            return JobResult.success("Processed " + tenantId);
        }

        @LocalJob(name = "jobIterateFailSafe", tenantMode = JobTenantMode.ITERATE, fixedDelay = "1h", initialDelay = "1h")
        public JobResult jobIterateFailSafe(JobContext context) {
            String tenantId = context.getTenantId();
            executedTenantsFailSafe.add(tenantId);
            if ("tenant-beta".equals(tenantId)) {
                throw new IllegalStateException("Simulated database failure on tenant-beta");
            }
            return JobResult.success("Processed " + tenantId);
        }
    }
}
