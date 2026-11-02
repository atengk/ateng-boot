package io.github.atengk.boot.job.snail.interceptor;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.job.snail.autoconfigure.AtengSnailJobAutoConfiguration;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SnailJobInterceptor} 调度生命周期责任链与拦截上下文单元测试
 *
 * @author Ateng
 * @since 2026-11-02
 */
@SpringBootTest(classes = {
        AtengSnailJobAutoConfiguration.class,
        SnailJobInterceptorTest.TestConfig.class
})
class SnailJobInterceptorTest {

    @Autowired
    private InterceptorTestJobService jobService;

    @Autowired
    private ExecutionTracer tracer;

    @Autowired
    private SkipControl skipControl;

    @AfterEach
    void tearDown() {
        tracer.clear();
        skipControl.reset();
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
        SnailJobTestHelper.clear();
    }

    @Test
    @DisplayName("测试拦截器责任链按 @Order 正序 preHandle，逆序 postHandle 与 afterCompletion")
    void testInterceptorExecutionOrder() {
        JobArgs args = new JobArgs();
        args.setJobParams("{\"flag\":\"normal\"}");
        ExecuteResult result = jobService.executeNormal(args);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(1); // 1 = SUCCESS in SnailJob ExecuteResult

        // 验证执行链路顺序: 10.pre -> 20.pre -> method -> 20.post -> 10.post -> 20.after -> 10.after
        assertThat(tracer.getEvents()).containsExactly(
                "order10.pre",
                "order20.pre",
                "method.executeNormal",
                "order20.post",
                "order10.post",
                "order20.after",
                "order10.after"
        );

        // 验证动态属性跨阶段透传
        assertThat(tracer.getAttributeRecords()).containsEntry("stage1", "val-10");
        assertThat(tracer.getAttributeRecords()).containsEntry("stage2", "val-20");
    }

    @Test
    @DisplayName("测试 preHandle 返回 false 时中断执行，记录日志并返回成功 ExecuteResult")
    void testPreHandleBypassReturnsSuccess() {
        skipControl.setSkipAtOrder20(true);

        JobArgs args = new JobArgs();
        args.setJobParams("{\"flag\":\"skip\"}");
        ExecuteResult result = jobService.executeNormal(args);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(1); // 返回成功防报警风暴
        assertThat(result.getMessage()).contains("[INTERCEPTED]");
        assertThat(String.valueOf(result.getResult())).contains("[INTERCEPTED]");

        // order20 preHandle 返回 false，阻止下游执行，order10 执行 afterCompletion 清理
        assertThat(tracer.getEvents()).containsExactly(
                "order10.pre",
                "order20.pre",
                "order10.after"
        );
    }

    @Test
    @DisplayName("测试拦截器 postHandle 与 afterCompletion 内部抛出异常时实现异常隔离，不掩盖业务结果与后续拦截器清理")
    void testInterceptorExceptionIsolation() {
        skipControl.setThrowInPost(true);
        skipControl.setThrowInAfter(true);

        JobArgs args = new JobArgs();
        args.setJobParams("{\"flag\":\"isolation\"}");
        ExecuteResult result = jobService.executeNormal(args);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(1);

        // 即使 order20 postHandle 和 afterCompletion 抛异常，不影响业务执行成功，order10 after 仍被确定性触发
        assertThat(tracer.getEvents()).contains(
                "order10.pre",
                "order20.pre",
                "method.executeNormal",
                "order20.post.throw",
                "order10.post",
                "order20.after.throw",
                "order10.after"
        );
    }

    @Test
    @DisplayName("测试任务发生业务异常时 postHandle 跳过，afterCompletion 严格逆序触发并接收异常")
    void testExceptionLifecycle() {
        JobArgs args = new JobArgs();
        args.setJobParams("{\"flag\":\"error\"}");

        assertThatThrownBy(() -> jobService.executeError(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模拟任务内部故障");

        // 发生异常时 postHandle 不执行，afterCompletion 逆序执行并捕获异常
        assertThat(tracer.getEvents()).containsExactly(
                "order10.pre",
                "order20.pre",
                "method.executeError",
                "order20.after",
                "order10.after"
        );
        assertThat(tracer.getLastException()).isInstanceOf(IllegalStateException.class);
    }

    @TestConfiguration
    @EnableAspectJAutoProxy
    static class TestConfig {

        @Bean
        public ExecutionTracer executionTracer() {
            return new ExecutionTracer();
        }

        @Bean
        public SkipControl skipControl() {
            return new SkipControl();
        }

        @Bean
        @Order(10)
        public FirstInterceptor firstInterceptor(ExecutionTracer tracer) {
            return new FirstInterceptor(tracer);
        }

        @Bean
        @Order(20)
        public SecondInterceptor secondInterceptor(ExecutionTracer tracer, SkipControl skipControl) {
            return new SecondInterceptor(tracer, skipControl);
        }

        @Bean
        public InterceptorTestJobService interceptorTestJobService(ExecutionTracer tracer) {
            return new InterceptorTestJobService(tracer);
        }
    }

    static class ExecutionTracer {
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final java.util.Map<String, Object> attributeRecords = new java.util.concurrent.ConcurrentHashMap<>();
        private volatile Throwable lastException;

        public void record(String event) {
            events.add(event);
        }

        public List<String> getEvents() {
            return new ArrayList<>(events);
        }

        public void recordAttribute(String key, Object val) {
            if (key != null && val != null) {
                attributeRecords.put(key, val);
            }
        }

        public java.util.Map<String, Object> getAttributeRecords() {
            return attributeRecords;
        }

        public void setLastException(Throwable t) {
            this.lastException = t;
        }

        public Throwable getLastException() {
            return lastException;
        }

        public void clear() {
            events.clear();
            attributeRecords.clear();
            lastException = null;
        }
    }

    static class SkipControl {
        private volatile boolean skipAtOrder20 = false;
        private volatile boolean throwInPost = false;
        private volatile boolean throwInAfter = false;

        public void setSkipAtOrder20(boolean skip) {
            this.skipAtOrder20 = skip;
        }

        public boolean isSkipAtOrder20() {
            return skipAtOrder20;
        }

        public void setThrowInPost(boolean throwInPost) {
            this.throwInPost = throwInPost;
        }

        public boolean isThrowInPost() {
            return throwInPost;
        }

        public void setThrowInAfter(boolean throwInAfter) {
            this.throwInAfter = throwInAfter;
        }

        public boolean isThrowInAfter() {
            return throwInAfter;
        }

        public void reset() {
            this.skipAtOrder20 = false;
            this.throwInPost = false;
            this.throwInAfter = false;
        }
    }

    @Order(10)
    static class FirstInterceptor implements SnailJobInterceptor {
        private final ExecutionTracer tracer;

        public FirstInterceptor(ExecutionTracer tracer) {
            this.tracer = tracer;
        }

        @Override
        public boolean preHandle(SnailJobExecutionContext context) {
            tracer.record("order10.pre");
            context.setAttribute("stage1", "val-10");
            return true;
        }

        @Override
        public void postHandle(SnailJobExecutionContext context, Object result) {
            tracer.record("order10.post");
        }

        @Override
        public void afterCompletion(SnailJobExecutionContext context, Object result, Throwable ex) {
            tracer.record("order10.after");
            if (context.hasAttribute("stage1")) {
                tracer.recordAttribute("stage1", context.getAttribute("stage1"));
            }
            if (ex != null) {
                tracer.setLastException(ex);
            }
        }
    }

    @Order(20)
    static class SecondInterceptor implements SnailJobInterceptor {
        private final ExecutionTracer tracer;
        private final SkipControl skipControl;

        public SecondInterceptor(ExecutionTracer tracer, SkipControl skipControl) {
            this.tracer = tracer;
            this.skipControl = skipControl;
        }

        @Override
        public boolean preHandle(SnailJobExecutionContext context) {
            tracer.record("order20.pre");
            if (skipControl.isSkipAtOrder20()) {
                return false;
            }
            context.setAttribute("stage2", "val-20");
            return true;
        }

        @Override
        public void postHandle(SnailJobExecutionContext context, Object result) {
            if (skipControl.isThrowInPost()) {
                tracer.record("order20.post.throw");
                throw new RuntimeException("模拟 postHandle 异常");
            }
            tracer.record("order20.post");
        }

        @Override
        public void afterCompletion(SnailJobExecutionContext context, Object result, Throwable ex) {
            if (context.hasAttribute("stage2")) {
                tracer.recordAttribute("stage2", context.getAttribute("stage2"));
            }
            if (ex != null) {
                tracer.setLastException(ex);
            }
            if (skipControl.isThrowInAfter()) {
                tracer.record("order20.after.throw");
                throw new RuntimeException("模拟 afterCompletion 异常");
            }
            tracer.record("order20.after");
        }
    }

    @Service
    static class InterceptorTestJobService {
        private final ExecutionTracer tracer;

        public InterceptorTestJobService(ExecutionTracer tracer) {
            this.tracer = tracer;
        }

        @JobExecutor(name = "interceptorJob")
        public ExecuteResult executeNormal(JobArgs args) {
            tracer.record("method.executeNormal");
            return ExecuteResult.success("done");
        }

        @JobExecutor(name = "interceptorErrorJob")
        public ExecuteResult executeError(JobArgs args) {
            tracer.record("method.executeError");
            throw new IllegalStateException("模拟任务内部故障");
        }
    }
}
