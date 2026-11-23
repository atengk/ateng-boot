package io.github.atengk.boot.liteflow.thread;

import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.core.NodeComponent;
import com.yomahub.liteflow.flow.LiteflowResponse;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LiteFlow 并行异步编排线程池四维上下文透传集成测试
 * 验证在并行 WHEN 组件调度时，MDC TraceId、UserId、TenantId 及 IgnoreTenant
 * 能够无缝穿透至并行子线程，且任务完成后现场得到确定性恢复与重置
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest(classes = LiteflowThreadContextPropagationTest.TestConfig.class, properties = {
        "liteflow.rule-source=classpath:liteflow/test-flow.el.xml",
        "liteflow.enable=true"
})
@DisplayName("LiteFlow 线程池四维上下文透传集成测试")
class LiteflowThreadContextPropagationTest {

    @Autowired
    private FlowExecutor flowExecutor;

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        TestNodeA.clear();
        TestNodeB.clear();
    }

    @Test
    @DisplayName("验证并行 WHEN 组件中子线程完整继承四维上下文快照")
    void testContextPropagationInWhenParallel() {
        String traceId = "test-trace-" + UUID.randomUUID();
        String userId = "user-10086";
        String tenantId = "tenant-china";

        MDC.put("traceId", traceId);
        UserContextHolder.set(userId);
        TenantContextHolder.setTenantId(tenantId);

        try {
            LiteflowResponse response = flowExecutor.execute2Resp("parallelChain", "testParam");
            assertThat(response.isSuccess()).isTrue();

            // 1. 验证 NodeA 在子线程中成功获取四维上下文
            assertThat(TestNodeA.TRACE_ID.get()).isEqualTo(traceId);
            assertThat(TestNodeA.USER_ID.get()).isEqualTo(userId);
            assertThat(TestNodeA.TENANT_ID.get()).isEqualTo(tenantId);
            assertThat(TestNodeA.IGNORE_TENANT.get()).isFalse();

            // 2. 验证 NodeB 在子线程中成功获取四维上下文
            assertThat(TestNodeB.TRACE_ID.get()).isEqualTo(traceId);
            assertThat(TestNodeB.USER_ID.get()).isEqualTo(userId);
            assertThat(TestNodeB.TENANT_ID.get()).isEqualTo(tenantId);
            assertThat(TestNodeB.IGNORE_TENANT.get()).isFalse();

            // 3. 验证主线程上下文未受破坏
            assertThat(MDC.get("traceId")).isEqualTo(traceId);
            assertThat(UserContextHolder.get()).isEqualTo(userId);
            assertThat(TenantContextHolder.getTenantId()).isEqualTo(tenantId);
        } finally {
            MDC.clear();
            UserContextHolder.clear();
            TenantContextHolder.clear();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({TestNodeA.class, TestNodeB.class})
    static class TestConfig {
    }

    @Component("testNodeA")
    public static class TestNodeA extends NodeComponent {
        static final AtomicReference<String> TRACE_ID = new AtomicReference<>();
        static final AtomicReference<String> USER_ID = new AtomicReference<>();
        static final AtomicReference<String> TENANT_ID = new AtomicReference<>();
        static final AtomicReference<Boolean> IGNORE_TENANT = new AtomicReference<>();

        @Override
        public void process() {
            TRACE_ID.set(MDC.get("traceId"));
            USER_ID.set(UserContextHolder.get());
            TENANT_ID.set(TenantContextHolder.getTenantId());
            IGNORE_TENANT.set(TenantContextHolder.isIgnoreTenant());
        }

        static void clear() {
            TRACE_ID.set(null);
            USER_ID.set(null);
            TENANT_ID.set(null);
            IGNORE_TENANT.set(null);
        }
    }

    @Component("testNodeB")
    public static class TestNodeB extends NodeComponent {
        static final AtomicReference<String> TRACE_ID = new AtomicReference<>();
        static final AtomicReference<String> USER_ID = new AtomicReference<>();
        static final AtomicReference<String> TENANT_ID = new AtomicReference<>();
        static final AtomicReference<Boolean> IGNORE_TENANT = new AtomicReference<>();

        @Override
        public void process() {
            TRACE_ID.set(MDC.get("traceId"));
            USER_ID.set(UserContextHolder.get());
            TENANT_ID.set(TenantContextHolder.getTenantId());
            IGNORE_TENANT.set(TenantContextHolder.isIgnoreTenant());
        }

        static void clear() {
            TRACE_ID.set(null);
            USER_ID.set(null);
            TENANT_ID.set(null);
            IGNORE_TENANT.set(null);
        }
    }
}
