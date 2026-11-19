package io.github.atengk.boot.job.xxl.executor;

import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.IJobHandler;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import io.github.atengk.boot.job.xxl.aspect.XxlJobContextAspect;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AtengXxlJobSpringExecutor 对独立 @AtengXxlJob 扫描注册与双标幂等去重集成测试
 *
 * @author Ateng
 * @since 2026-11-19
 */
class AtengXxlJobSpringExecutorRegistrationTest {

    @TempDir
    static Path tempLogDir;

    @AfterEach
    void tearDown() {
        XxlJobContext.setXxlJobContext(null);
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
        XxlJobTestHelper.clear();
    }

    @Test
    @DisplayName("测试独立 @AtengXxlJob 扫描注册：支持显式命名、缺省方法名回退、init/destroy 生命周期及双标幂等去重")
    void testStandaloneAtengXxlJobRegistrationAndExecution() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(TestConfig.class);
            context.refresh();

            AtengXxlJobSpringExecutor executor = context.getBean(AtengXxlJobSpringExecutor.class);
            SampleStandaloneJobs jobs = context.getBean(SampleStandaloneJobs.class);

            // 1. 仅标注 @AtengXxlJob("atengOnlyWithLifecycle", init="onInit", destroy="onDestroy")
            IJobHandler lifecycleHandler = executor.loadJobHandler("atengOnlyWithLifecycle");
            assertThat(lifecycleHandler).isNotNull();

            XxlJobContext jobContext = new XxlJobContext(501L, "standalone-param", 6001L, System.currentTimeMillis(), "test.log", 0, 1);
            XxlJobContext.setXxlJobContext(jobContext);
            lifecycleHandler.init();
            lifecycleHandler.execute();
            lifecycleHandler.destroy();

            assertThat(jobs.getEvents()).containsExactly(
                    "init",
                    "execute:standalone-param",
                    "destroy"
            );
            assertThat(jobs.getCapturedTraceId()).isNotBlank();
            assertThat(jobContext.getHandleCode()).isEqualTo(XxlJobContext.HANDLE_CODE_SUCCESS);

            // 2. 仅标注 @AtengXxlJob 无显式 value 时回退方法名
            IJobHandler defaultNameHandler = executor.loadJobHandler("fallbackMethodNameJob");
            assertThat(defaultNameHandler).isNotNull();
            defaultNameHandler.execute();
            assertThat(jobs.getFallbackMethodNameCount().get()).isEqualTo(1);

            // 同时验证 XxlJobTestHelper 对无 value 独立 @AtengXxlJob 的一致性
            assertThat(XxlJobTestHelper.trigger("fallbackMethodNameJob").isSuccess()).isTrue();
            assertThat(jobs.getFallbackMethodNameCount().get()).isEqualTo(2);

            // 3. 双标幂等兼容：@XxlJob("dualSameJob") + @AtengXxlJob("dualSameJob")
            assertThat(executor.loadJobHandler("dualSameJob")).isNotNull();
            // @XxlJob("dualImplicitJob") + @AtengXxlJob (无 value) 不应额外注册方法名
            assertThat(executor.loadJobHandler("dualImplicitJob")).isNotNull();
            assertThat(executor.loadJobHandler("executeDualImplicit")).isNull();

            // @XxlJob("dualAnnotatedPrimaryName") + @AtengXxlJob("dualAnnotatedSecondaryName") 统一以 @XxlJob 为准
            assertThat(executor.loadJobHandler("dualAnnotatedPrimaryName")).isNotNull();
            assertThat(executor.loadJobHandler("dualAnnotatedSecondaryName")).isNull();
            assertThat(XxlJobTestHelper.trigger("dualAnnotatedPrimaryName").isSuccess()).isTrue();
            assertThat(jobs.getLastResolvedDualEvent()).isEqualTo("dualAnnotatedPrimaryName");

            // 4. 重复调用 afterSingletonsInstantiated 保持幂等不报错
            executor.afterSingletonsInstantiated();
            assertThat(executor.loadJobHandler("atengOnlyWithLifecycle")).isNotNull();
        }
    }

    @Test
    @DisplayName("测试同名冲突防御：两个不同方法声明同一 @AtengXxlJob 名称时启动快速失败")
    void testDuplicateAtengXxlJobNameConflictThrows() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(TestConfig.class);
            context.registerBeanDefinition("conflictBean", new RootBeanDefinition(ConflictingJobBean.class));

            assertThatThrownBy(context::refresh)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("naming conflicts");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class TestConfig {

        @Bean
        public XxlJobProperties xxlJobProperties() {
            XxlJobProperties properties = new XxlJobProperties();
            properties.setPort(0);
            properties.setLogRetentionDays(0);
            return properties;
        }

        @Bean
        public XxlJobContextAspect xxlJobContextAspect(XxlJobProperties properties) {
            return new XxlJobContextAspect(null, properties, null);
        }

        @Bean
        public XxlJobTestHelper xxlJobTestHelper() {
            return new XxlJobTestHelper();
        }

        @Bean
        public AtengXxlJobSpringExecutor atengXxlJobSpringExecutor(XxlJobProperties properties) {
            AtengXxlJobSpringExecutor executor = new AtengXxlJobSpringExecutor(properties);
            executor.setPort(0);
            executor.setLogPath(tempLogDir.toString());
            executor.setLogRetentionDays(0);
            return executor;
        }

        @Bean
        public SampleStandaloneJobs sampleStandaloneJobs() {
            return new SampleStandaloneJobs();
        }
    }

    @Component
    public static class SampleStandaloneJobs {

        private final List<String> events = new CopyOnWriteArrayList<>();
        private final AtomicInteger fallbackMethodNameCount = new AtomicInteger();
        private volatile String capturedTraceId;
        private volatile String lastResolvedDualEvent;

        @AtengXxlJob(value = "atengOnlyWithLifecycle", init = "onInit", destroy = "onDestroy")
        public void executeWithLifecycle() {
            this.capturedTraceId = MDC.get("traceId");
            events.add("execute:" + XxlJobHelper.getJobParam());
            XxlJobHelper.handleSuccess("OK");
        }

        public void onInit() {
            events.add("init");
        }

        public void onDestroy() {
            events.add("destroy");
        }

        @AtengXxlJob
        public void fallbackMethodNameJob() {
            fallbackMethodNameCount.incrementAndGet();
            XxlJobHelper.handleSuccess("DEFAULT_NAME_OK");
        }

        @XxlJob("dualSameJob")
        @AtengXxlJob("dualSameJob")
        public void executeDualSame() {
            XxlJobHelper.handleSuccess("DUAL_SAME_OK");
        }

        @XxlJob("dualImplicitJob")
        @AtengXxlJob
        public void executeDualImplicit() {
            XxlJobHelper.handleSuccess("DUAL_IMPLICIT_OK");
        }

        @XxlJob("dualAnnotatedPrimaryName")
        @AtengXxlJob("dualAnnotatedSecondaryName")
        public void executeDualExplicitDiff() {
            this.lastResolvedDualEvent = "dualAnnotatedPrimaryName";
            XxlJobHelper.handleSuccess("DUAL_PRIMARY_OK");
        }

        public List<String> getEvents() {
            return events;
        }

        public AtomicInteger getFallbackMethodNameCount() {
            return fallbackMethodNameCount;
        }

        public String getCapturedTraceId() {
            return capturedTraceId;
        }

        public String getLastResolvedDualEvent() {
            return lastResolvedDualEvent;
        }
    }

    public static class ConflictingJobBean {

        @AtengXxlJob("atengOnlyWithLifecycle")
        public void duplicateHandler() {
        }
    }
}
