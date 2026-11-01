package io.github.atengk.boot.job.xxl.interceptor;

import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import io.github.atengk.boot.job.xxl.aspect.XxlJobContextAspect;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper.XxlJobTestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * XXL-Job 任务切面拦截器 SPI 契约定义与执行链编排集成测试
 *
 * @author Ateng
 * @since 2026-11-01
 */
@SpringBootTest(classes = XxlJobInterceptorIntegrationTest.TestConfig.class)
class XxlJobInterceptorIntegrationTest {

    @Autowired
    private InterceptorTestJobService jobService;

    @Autowired
    private XxlJobTestHelper testHelper;

    private static final List<String> EXECUTION_EVENTS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean CANARY_BLOCK_ENABLED = new AtomicBoolean(false);
    private static final AtomicBoolean THROW_IN_AFTER_COMPLETION = new AtomicBoolean(false);
    private static final AtomicBoolean THROW_IN_PRE_HANDLE = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        EXECUTION_EVENTS.clear();
        CANARY_BLOCK_ENABLED.set(false);
        THROW_IN_AFTER_COMPLETION.set(false);
        THROW_IN_PRE_HANDLE.set(false);
    }

    @AfterEach
    void tearDown() {
        XxlJobContext.setXxlJobContext(null);
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
        EXECUTION_EVENTS.clear();
        CANARY_BLOCK_ENABLED.set(false);
        THROW_IN_AFTER_COMPLETION.set(false);
        THROW_IN_PRE_HANDLE.set(false);
    }

    @Test
    @DisplayName("测试拦截器执行链：正序 preHandle、逆序 postHandle、逆序 afterCompletion，全链路上下文属性透传")
    void testOrderedInterceptorPipelineExecution() {
        XxlJobTestResult result = XxlJobTestHelper.trigger("normalJob", "param=success-test");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.handleCode()).isEqualTo(200);

        // 验证执行时序严格遵循：preHandle(10->20->30) -> Target -> postHandle(30->20->10) -> afterCompletion(30->20->10)
        assertThat(EXECUTION_EVENTS).containsExactly(
                "FirstInterceptor:preHandle:normalJob",
                "SecondInterceptor:preHandle:normalJob",
                "ThirdInterceptor:preHandle:normalJob",
                "TargetJob:executeNormalJob:param=success-test",
                "ThirdInterceptor:postHandle:result=OK",
                "SecondInterceptor:postHandle:result=OK",
                "FirstInterceptor:postHandle:result=OK",
                "ThirdInterceptor:afterCompletion:throwable=null",
                "SecondInterceptor:afterCompletion:throwable=null",
                "FirstInterceptor:afterCompletion:throwable=null"
        );
    }

    @Test
    @DisplayName("测试优雅拦截语义：preHandle 返回 false 时中断后续拦截器与目标任务，返回 200 SUCCESS 并输出 [INTERCEPTED]")
    void testGracefulSkipSemanticsWhenPreHandleReturnsFalse() {
        // 激活灰度拦截
        CANARY_BLOCK_ENABLED.set(true);

        XxlJobTestResult result = XxlJobTestHelper.trigger("normalJob", "param=canary-test");

        // 强断言：调度结果为成功 200，且包含 [INTERCEPTED] 标记与拦截器类名
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.handleCode()).isEqualTo(200);
        assertThat(result.handleMsg()).contains("[INTERCEPTED]");
        assertThat(result.handleMsg()).contains("SecondInterceptor");

        // 验证执行时序：FirstInterceptor 通过 -> SecondInterceptor 拦截 -> ThirdInterceptor 与 TargetJob 未执行 -> 仅 FirstInterceptor 触发 afterCompletion
        assertThat(EXECUTION_EVENTS).containsExactly(
                "FirstInterceptor:preHandle:normalJob",
                "SecondInterceptor:preHandle:normalJob:INTERCEPTED",
                "FirstInterceptor:afterCompletion:throwable=null"
        );
    }

    @Test
    @DisplayName("测试目标任务异常：postHandle 跳过，逆序触发全部已通过拦截器的 afterCompletion 并传递原始异常")
    void testTargetJobExceptionTriggersAfterCompletionInReverseOrder() {
        XxlJobTestResult result = XxlJobTestHelper.trigger("failingJob", "param=boom");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.throwable()).isInstanceOf(IllegalStateException.class);
        assertThat(result.throwable().getMessage()).isEqualTo("Intentional task error");

        // 验证执行时序：preHandle 全通过 -> 目标抛错 -> postHandle 全跳过 -> afterCompletion 逆序触发且携带异常
        assertThat(EXECUTION_EVENTS).containsExactly(
                "FirstInterceptor:preHandle:failingJob",
                "SecondInterceptor:preHandle:failingJob",
                "ThirdInterceptor:preHandle:failingJob",
                "ThirdInterceptor:afterCompletion:throwable=Intentional task error",
                "SecondInterceptor:afterCompletion:throwable=Intentional task error",
                "FirstInterceptor:afterCompletion:throwable=Intentional task error"
        );
    }

    @Test
    @DisplayName("测试 afterCompletion 异常防御：单个拦截器清理抛错不阻断其余拦截器执行，亦不掩盖原始异常")
    void testInterceptorAfterCompletionExceptionResilience() {
        THROW_IN_AFTER_COMPLETION.set(true);

        XxlJobTestResult result = XxlJobTestHelper.trigger("normalJob", "param=resilience-test");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.handleCode()).isEqualTo(200);

        // Third 抛出异常，Second 和 First 依然确定性执行完成
        assertThat(EXECUTION_EVENTS).contains(
                "ThirdInterceptor:afterCompletion:throwable=null:THROW_BOOM",
                "SecondInterceptor:afterCompletion:throwable=null",
                "FirstInterceptor:afterCompletion:throwable=null"
        );
    }

    @Test
    @DisplayName("测试 preHandle 异常防卫：前置拦截抛错时触发此前已通过拦截器的 afterCompletion 补偿清理，并向上抛出")
    void testInterceptorPreHandleExceptionTriggersRollback() {
        THROW_IN_PRE_HANDLE.set(true);

        assertThatThrownBy(() -> jobService.executeNormalJob("param=pre-throw"))
                .isInstanceOf(SecurityException.class)
                .hasMessage("SecondInterceptor denied access");

        // First 通过 -> Second 抛异常 -> Third 未执行 -> 目标未执行 -> First afterCompletion 触发并携带 SecurityException
        assertThat(EXECUTION_EVENTS).containsExactly(
                "FirstInterceptor:preHandle:normalJob",
                "SecondInterceptor:preHandle:normalJob:THROW_SECURITY",
                "FirstInterceptor:afterCompletion:throwable=SecondInterceptor denied access"
        );
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class TestConfig {

        @Bean
        public XxlJobTestHelper xxlJobTestHelper() {
            return new XxlJobTestHelper();
        }

        @Bean
        public XxlJobContextAspect xxlJobContextAspect(ObjectProvider<XxlJobInterceptor> interceptorsProvider) {
            List<XxlJobInterceptor> interceptors = interceptorsProvider != null
                    ? interceptorsProvider.orderedStream().toList()
                    : Collections.emptyList();
            return new XxlJobContextAspect(null, null, null, null, null, interceptors);
        }

        @Bean
        public InterceptorTestJobService interceptorTestJobService() {
            return new InterceptorTestJobService();
        }

        @Bean
        @Order(10)
        public XxlJobInterceptor firstInterceptor() {
            return new FirstInterceptor();
        }

        @Bean
        @Order(20)
        public XxlJobInterceptor secondInterceptor() {
            return new SecondInterceptor();
        }

        @Bean
        @Order(30)
        public XxlJobInterceptor thirdInterceptor() {
            return new ThirdInterceptor();
        }
    }

    @Service
    public static class InterceptorTestJobService {

        @XxlJob("normalJob")
        public String executeNormalJob(String param) {
            EXECUTION_EVENTS.add("TargetJob:executeNormalJob:" + param);
            return "OK";
        }

        @XxlJob("failingJob")
        @AtengXxlJob("failingJob")
        public void executeFailingJob(String param) {
            throw new IllegalStateException("Intentional task error");
        }
    }

    public static class FirstInterceptor implements XxlJobInterceptor {
        @Override
        public boolean preHandle(XxlJobExecutionContext context) {
            EXECUTION_EVENTS.add("FirstInterceptor:preHandle:" + context.getJobName());
            context.setAttribute("firstKey", "firstValue");
            return true;
        }

        @Override
        public void postHandle(XxlJobExecutionContext context, Object result) {
            assertThat(context.<String>getAttribute("firstKey")).isEqualTo("firstValue");
            EXECUTION_EVENTS.add("FirstInterceptor:postHandle:result=" + result);
        }

        @Override
        public void afterCompletion(XxlJobExecutionContext context, Throwable throwable) {
            assertThat(context.<String>getAttribute("firstKey")).isEqualTo("firstValue");
            String err = throwable != null ? throwable.getMessage() : "null";
            EXECUTION_EVENTS.add("FirstInterceptor:afterCompletion:throwable=" + err);
        }
    }

    public static class SecondInterceptor implements XxlJobInterceptor {
        @Override
        public boolean preHandle(XxlJobExecutionContext context) {
            if (THROW_IN_PRE_HANDLE.get()) {
                EXECUTION_EVENTS.add("SecondInterceptor:preHandle:" + context.getJobName() + ":THROW_SECURITY");
                throw new SecurityException("SecondInterceptor denied access");
            }
            if (CANARY_BLOCK_ENABLED.get()) {
                EXECUTION_EVENTS.add("SecondInterceptor:preHandle:" + context.getJobName() + ":INTERCEPTED");
                return false;
            }
            EXECUTION_EVENTS.add("SecondInterceptor:preHandle:" + context.getJobName());
            return true;
        }

        @Override
        public void postHandle(XxlJobExecutionContext context, Object result) {
            EXECUTION_EVENTS.add("SecondInterceptor:postHandle:result=" + result);
        }

        @Override
        public void afterCompletion(XxlJobExecutionContext context, Throwable throwable) {
            String err = throwable != null ? throwable.getMessage() : "null";
            EXECUTION_EVENTS.add("SecondInterceptor:afterCompletion:throwable=" + err);
        }
    }

    public static class ThirdInterceptor implements XxlJobInterceptor {
        @Override
        public boolean preHandle(XxlJobExecutionContext context) {
            EXECUTION_EVENTS.add("ThirdInterceptor:preHandle:" + context.getJobName());
            return true;
        }

        @Override
        public void postHandle(XxlJobExecutionContext context, Object result) {
            EXECUTION_EVENTS.add("ThirdInterceptor:postHandle:result=" + result);
        }

        @Override
        public void afterCompletion(XxlJobExecutionContext context, Throwable throwable) {
            if (THROW_IN_AFTER_COMPLETION.get()) {
                EXECUTION_EVENTS.add("ThirdInterceptor:afterCompletion:throwable=null:THROW_BOOM");
                throw new RuntimeException("ThirdInterceptor cleanup boom");
            }
            String err = throwable != null ? throwable.getMessage() : "null";
            EXECUTION_EVENTS.add("ThirdInterceptor:afterCompletion:throwable=" + err);
        }
    }
}
