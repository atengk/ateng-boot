package io.github.atengk.boot.job.xxl.governor;

import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.job.xxl.actuator.XxlJobEndpoint;
import io.github.atengk.boot.job.xxl.aspect.XxlJobContextAspect;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper.XxlJobTestResult;
import io.github.atengk.boot.job.xxl.interceptor.XxlJobExecutionContext;
import io.github.atengk.boot.job.xxl.interceptor.XxlJobInterceptor;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 节点级任务运行时治理总线与切面联动端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-01
 */
@SpringBootTest(classes = JobRuntimeGovernorAspectIntegrationTest.TestConfig.class)
class JobRuntimeGovernorAspectIntegrationTest {

    @Autowired
    private JobRuntimeGovernor governor;

    @Autowired
    private XxlJobEndpoint xxlJobEndpoint;

    @Autowired
    private GovernorJobService jobService;

    @Autowired
    private XxlJobTestHelper testHelper;

    private static final AtomicInteger EXECUTION_COUNTER = new AtomicInteger(0);
    private static final List<String> INTERCEPTOR_EVENTS = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        EXECUTION_COUNTER.set(0);
        INTERCEPTOR_EVENTS.clear();
        governor.clear();
    }

    @AfterEach
    void tearDown() {
        XxlJobContext.setXxlJobContext(null);
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
        EXECUTION_COUNTER.set(0);
        INTERCEPTOR_EVENTS.clear();
        governor.clear();
    }

    @Test
    @DisplayName("测试任务动态治理：正常执行 -> 动态静默跳过(200成功) -> 动态恢复执行")
    void testDynamicPauseSkipAndResume() {
        // 1. 正常未暂停状态下触发执行
        XxlJobTestResult r1 = XxlJobTestHelper.trigger("governJob", "step=1");
        assertThat(r1.isSuccess()).isTrue();
        assertThat(r1.handleCode()).isEqualTo(200);
        assertThat(EXECUTION_COUNTER.get()).isEqualTo(1);
        assertThat(INTERCEPTOR_EVENTS).contains("preHandle:governJob");

        INTERCEPTOR_EVENTS.clear();

        // 2. 动态静默该任务处理器
        governor.pause("governJob");
        assertThat(governor.isPaused("governJob")).isTrue();

        MDC.put("traceId", "pre-test-trace");
        TenantContextHolder.setTenantId("pre-test-tenant");
        UserContextHolder.set("pre-test-user");

        // 3. 再次触发：断言目标方法与拦截器被安全旁路跳过，并返回 200 SUCCESS 且附带 [SKIPPED] 提示
        XxlJobTestResult r2 = XxlJobTestHelper.trigger("governJob", "step=2");
        assertThat(r2.isSuccess()).isTrue();
        assertThat(r2.handleCode()).isEqualTo(200);
        assertThat(r2.handleMsg()).contains("[SKIPPED]");
        assertThat(r2.handleMsg()).contains("Handler [governJob] is paused on local node");
        assertThat(EXECUTION_COUNTER.get()).isEqualTo(1); // 计数器未增加，确认未执行目标逻辑
        assertThat(INTERCEPTOR_EVENTS).isEmpty(); // 拦截器未执行

        // 强断言：任务静默跳过后，上下文严格还原，绝不发生 ThreadLocal / MDC 泄漏
        assertThat(MDC.get("traceId")).isEqualTo("pre-test-trace");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("pre-test-tenant");
        assertThat(UserContextHolder.getExplicit()).isEqualTo("pre-test-user");

        // 4. 动态恢复该任务调度
        governor.resume("governJob");
        assertThat(governor.isPaused("governJob")).isFalse();

        // 5. 恢复后触发：目标方法与拦截器再次正常执行
        XxlJobTestResult r3 = XxlJobTestHelper.trigger("governJob", "step=3");
        assertThat(r3.isSuccess()).isTrue();
        assertThat(r3.handleCode()).isEqualTo(200);
        assertThat(EXECUTION_COUNTER.get()).isEqualTo(2);
        assertThat(INTERCEPTOR_EVENTS).contains("preHandle:governJob");
    }

    @Test
    @DisplayName("测试通过 Actuator 运维端点联动执行动态静默跳过与恢复执行")
    void testActuatorEndpointPauseSkipAndResume() {
        // 1. 正常执行
        XxlJobTestResult r1 = XxlJobTestHelper.trigger("governJob");
        assertThat(r1.isSuccess()).isTrue();
        assertThat(EXECUTION_COUNTER.get()).isEqualTo(1);

        // 2. 通过 Actuator 端点写入操作暂停该任务
        Map<String, Object> pauseResult = xxlJobEndpoint.operateJob("governJob", "pause");
        assertThat(pauseResult.get("success")).isEqualTo(true);
        assertThat(pauseResult.get("paused")).isEqualTo(true);
        assertThat(governor.isPaused("governJob")).isTrue();

        // 3. 触发任务：切面感知静默状态并优雅跳过目标方法
        XxlJobTestResult r2 = XxlJobTestHelper.trigger("governJob");
        assertThat(r2.isSuccess()).isTrue();
        assertThat(r2.handleCode()).isEqualTo(200);
        assertThat(r2.handleMsg()).contains("[SKIPPED]");
        assertThat(EXECUTION_COUNTER.get()).isEqualTo(1);

        // 4. 通过 Actuator 端点写入操作恢复该任务
        Map<String, Object> resumeResult = xxlJobEndpoint.operateJob("governJob", "resume");
        assertThat(resumeResult.get("success")).isEqualTo(true);
        assertThat(resumeResult.get("paused")).isEqualTo(false);
        assertThat(governor.isPaused("governJob")).isFalse();

        // 5. 恢复后触发：任务正常恢复执行
        XxlJobTestResult r3 = XxlJobTestHelper.trigger("governJob");
        assertThat(r3.isSuccess()).isTrue();
        assertThat(EXECUTION_COUNTER.get()).isEqualTo(2);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class TestConfig {

        @Bean
        public JobRuntimeGovernor jobRuntimeGovernor() {
            return new JobRuntimeGovernor();
        }

        @Bean
        public XxlJobEndpoint xxlJobEndpoint(JobRuntimeGovernor governor) {
            XxlJobProperties properties = new XxlJobProperties();
            properties.setAppname("demo-executor");
            return new XxlJobEndpoint(properties, governor);
        }

        @Bean
        public XxlJobTestHelper xxlJobTestHelper() {
            return new XxlJobTestHelper();
        }

        @Bean
        public TestInterceptor testInterceptor() {
            return new TestInterceptor();
        }

        @Bean
        public XxlJobContextAspect xxlJobContextAspect(JobRuntimeGovernor governor, TestInterceptor interceptor) {
            return new XxlJobContextAspect(null, null, null, null, null, List.of(interceptor), governor);
        }

        @Bean
        public GovernorJobService governorJobService() {
            return new GovernorJobService();
        }
    }

    @Service
    public static class GovernorJobService {

        @XxlJob("governJob")
        public void executeGovernJob(String param) {
            EXECUTION_COUNTER.incrementAndGet();
        }
    }

    public static class TestInterceptor implements XxlJobInterceptor {
        @Override
        public boolean preHandle(XxlJobExecutionContext context) {
            INTERCEPTOR_EVENTS.add("preHandle:" + context.getJobName());
            return true;
        }
    }
}
