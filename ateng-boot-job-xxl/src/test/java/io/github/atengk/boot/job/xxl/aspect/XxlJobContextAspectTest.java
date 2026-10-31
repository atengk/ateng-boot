package io.github.atengk.boot.job.xxl.aspect;

import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import io.github.atengk.boot.job.xxl.enums.JobTenantMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * XXL-Job 四维上下文全链路透传与清理切面单测
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootTest(classes = XxlJobContextAspectTest.TestConfig.class)
class XxlJobContextAspectTest {

    @Autowired
    private SampleJobService sampleJobService;

    @AfterEach
    void tearDown() {
        XxlJobContext.setXxlJobContext(null);
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("测试标准 @XxlJob 任务：自动注入 32 位无锁 traceId 与默认 SYSTEM 操作人，执行后严格清理现场")
    void testStandardJobContextInjectionAndCleanup() {
        // 前置断言无上下文
        assertThat(MDC.get("traceId")).isNull();
        assertThat(UserContextHolder.get()).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();

        sampleJobService.executeStandardJob();

        // 终态严格断言无泄露
        assertThat(MDC.get("traceId")).isNull();
        assertThat(UserContextHolder.get()).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    @DisplayName("测试 @AtengXxlJob 且 tenantMode=IGNORE 时：自动跳过行级租户过滤 (isIgnoreTenant=true)")
    void testIgnoreTenantJob() {
        sampleJobService.executeIgnoreTenantJob();

        // 终态还原
        assertThat(TenantContextHolder.isIgnoreTenant()).isFalse();
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("测试 @AtengXxlJob 且 tenantMode=PARAM 时：自动从调度参数中提取 tenantId 并绑定")
    void testParamTenantJob() {
        // 模拟 XXL-Job 调度中心传入参数
        XxlJobContext context = new XxlJobContext(101L, "tenantId=tenant_abc_123", 2001L, System.currentTimeMillis(), "test.log", 0, 1);
        XxlJobContext.setXxlJobContext(context);

        sampleJobService.executeParamTenantJob();

        // 终态还原
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("测试 JSON 格式调度参数：自动提取 traceId, userId 与 tenantId")
    void testJsonParamJob() {
        String jsonParam = "{\"traceId\":\"custom-trace-888\",\"userId\":\"finance-admin\",\"tenantId\":\"tenant-999\"}";
        XxlJobContext context = new XxlJobContext(102L, jsonParam, 2002L, System.currentTimeMillis(), "test.log", 0, 1);
        XxlJobContext.setXxlJobContext(context);

        sampleJobService.executeJsonJob();

        // 终态还原
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(UserContextHolder.get()).isNull();
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("测试任务发生异常时：异常正常向外抛出且上下文现场绝对安全清理，杜绝线程污染")
    void testFailingJobCleanup() {
        XxlJobContext context = new XxlJobContext(103L, "tenantId=err_tenant", 2003L, System.currentTimeMillis(), "test.log", 0, 1);
        XxlJobContext.setXxlJobContext(context);

        assertThatThrownBy(() -> sampleJobService.executeFailingJob())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Intentional task failure");

        // 即使抛错，终态仍然必须完全清理
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(UserContextHolder.get()).isNull();
        assertThat(MDC.get("traceId")).isNull();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class TestConfig {
        @Bean
        public XxlJobContextAspect xxlJobContextAspect() {
            return new XxlJobContextAspect();
        }

        @Bean
        public SampleJobService sampleJobService() {
            return new SampleJobService();
        }
    }

    @Service
    static class SampleJobService {

        @XxlJob("standardJob")
        public void executeStandardJob() {
            String traceId = MDC.get("traceId");
            assertThat(traceId).isNotNull();
            assertThat(traceId).hasSize(32);
            assertThat(UserContextHolder.get()).isEqualTo("SYSTEM");
            assertThat(TenantContextHolder.isIgnoreTenant()).isFalse();
        }

        @AtengXxlJob(value = "ignoreTenantJob", tenantMode = JobTenantMode.IGNORE)
        public void executeIgnoreTenantJob() {
            assertThat(TenantContextHolder.isIgnoreTenant()).isTrue();
            assertThat(MDC.get("traceId")).isNotNull();
        }

        @AtengXxlJob(value = "paramTenantJob", tenantMode = JobTenantMode.PARAM)
        public void executeParamTenantJob() {
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant_abc_123");
            assertThat(MDC.get("traceId")).isNotNull();
        }

        @AtengXxlJob(value = "jsonJob", tenantMode = JobTenantMode.PARAM)
        public void executeJsonJob() {
            assertThat(MDC.get("traceId")).isEqualTo("custom-trace-888");
            assertThat(UserContextHolder.get()).isEqualTo("finance-admin");
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-999");
        }

        @AtengXxlJob("failingJob")
        public void executeFailingJob() {
            assertThat(MDC.get("traceId")).isNotNull();
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("err_tenant");
            throw new IllegalStateException("Intentional task failure");
        }
    }

}
