package io.github.atengk.boot.job.xxl.governor;

import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.job.xxl.aspect.XxlJobContextAspect;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper.XxlJobTestResult;
import io.github.atengk.boot.job.xxl.interceptor.XxlJobExecutionContext;
import io.github.atengk.boot.job.xxl.interceptor.XxlJobInterceptor;
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

        // 3. 再次触发：断言目标方法与拦截器被安全旁路跳过，并返回 200 SUCCESS 且附带 [SKIPPED] 提示
        XxlJobTestResult r2 = XxlJobTestHelper.trigger("governJob", "step=2");
        assertThat(r2.isSuccess()).isTrue();
        assertThat(r2.handleCode()).isEqualTo(200);
        assertThat(r2.handleMsg()).contains("[SKIPPED]");
        assertThat(r2.handleMsg()).contains("Handler [governJob] is paused on local node");
        assertThat(EXECUTION_COUNTER.get()).isEqualTo(1); // 计数器未增加，确认未执行目标逻辑
        assertThat(INTERCEPTOR_EVENTS).isEmpty(); // 拦截器未执行

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

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class TestConfig {

        @Bean
        public JobRuntimeGovernor jobRuntimeGovernor() {
            return new JobRuntimeGovernor();
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
