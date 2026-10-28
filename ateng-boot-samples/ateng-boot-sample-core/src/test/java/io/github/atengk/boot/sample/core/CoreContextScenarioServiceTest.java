package io.github.atengk.boot.sample.core;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.enums.TransactionCompletionStatus;
import io.github.atengk.boot.core.model.Pair;
import io.github.atengk.boot.core.model.ThreadPoolStatus;
import io.github.atengk.boot.core.util.AsyncUtil;
import io.github.atengk.boot.sample.core.service.CoreContextScenarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Core 上下文与异步透传场景端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-19
 */
@SpringBootTest
class CoreContextScenarioServiceTest {

    @Autowired
    private CoreContextScenarioService contextScenarioService;

    @AfterEach
    void tearDown() {
        // 全自洽单测铁律：无条件清理上下文，杜绝跨测试用例污染
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("测试用户上下文闭包执行与自动清理")
    void testUserContextExecution() {
        String result = contextScenarioService.executeInUserContext("user-10086");
        assertThat(result).isEqualTo("user-10086");
        // 闭包结束后现场自动清空
        assertThat(UserContextHolder.getUserId()).isNull();
    }

    @Test
    @DisplayName("测试租户上下文闭包执行与嵌套还原")
    void testTenantContextExecution() {
        TenantContextHolder.setTenantId("original-tenant");
        String result = contextScenarioService.executeInTenantContext("nested-tenant");
        assertThat(result).isEqualTo("nested-tenant");
        // 闭包结束后还原为外部租户
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("original-tenant");
    }

    @Test
    @DisplayName("测试受控忽略租户拦截闭包")
    void testIgnoreTenantExecution() {
        boolean isIgnored = contextScenarioService.executeWithoutTenant();
        assertThat(isIgnored).isTrue();
        // 闭包结束后恢复租户拦截
        assertThat(TenantContextHolder.isIgnoreTenant()).isFalse();
    }

    @Test
    @DisplayName("测试跨受管线程池异步透传四维上下文快照")
    void testPropagateContextAsync() throws Exception {
        CompletableFuture<Map<String, String>> future = contextScenarioService.propagateContextAsync(
                "trace-test-123",
                "user-test-456",
                "tenant-test-789"
        );
        Map<String, String> snapshot = future.get(3, TimeUnit.SECONDS);

        assertThat(snapshot).containsEntry("traceId", "trace-test-123")
                .containsEntry("userId", "user-test-456")
                .containsEntry("tenantId", "tenant-test-789")
                .containsEntry("ignoreTenant", "false");
    }

    @Test
    @DisplayName("测试Spring @Async结合ContextCopyingTaskDecorator透传上下文")
    void testExecuteWithSpringAsync() throws Exception {
        MDC.put("traceId", "trace-spring-async");
        UserContextHolder.setUserId("user-spring-async");

        CompletableFuture<Pair<String, String>> future = contextScenarioService.executeWithSpringAsync(
                "trace-spring-async",
                "user-spring-async"
        );
        Pair<String, String> result = future.get(3, TimeUnit.SECONDS);

        assertThat(result.left()).isEqualTo("trace-spring-async");
        assertThat(result.right()).isEqualTo("user-spring-async");
    }

    @Test
    @DisplayName("测试NamedThreadFactory可观测线程池")
    void testDemonstrateNamedThreadFactory() {
        Pair<String, Boolean> threadInfo = contextScenarioService.demonstrateNamedThreadFactory("sample-worker");
        assertThat(threadInfo.left()).startsWith("sample-worker-");
        assertThat(threadInfo.right()).isFalse(); // 非守护线程
    }

    @Test
    @DisplayName("测试事务同步生命周期门面非事务环境平滑降级")
    void testDemonstrateTransactionHooks() {
        Pair<Boolean, Boolean> hooks = contextScenarioService.demonstrateTransactionHooks();
        // 无事务时 executeAfterCommit 同步就地执行，executeAfterRollback 安全跳过
        assertThat(hooks.left()).isTrue();
        assertThat(hooks.right()).isFalse();
    }

    @Test
    @DisplayName("测试 AsyncUtil 门面异步调度并透明透传四维上下文")
    void testExecuteWithAsyncUtil() throws Exception {
        CompletableFuture<Map<String, String>> future = contextScenarioService.executeWithAsyncUtil(
                "trace-async-util",
                "user-async-util",
                "tenant-async-util"
        );
        Map<String, String> snapshot = future.get(3, TimeUnit.SECONDS);

        assertThat(snapshot.get("threadName")).startsWith("sample-export-async-");
        assertThat(snapshot).containsEntry("traceId", "trace-async-util")
                .containsEntry("userId", "user-async-util")
                .containsEntry("tenantId", "tenant-async-util")
                .containsEntry("ignoreTenant", "false");
    }

    @Test
    @DisplayName("测试 ThreadPoolStatus 监控指标与 AsyncUtil.resize 在线调参")
    void testDemonstrateThreadPoolGovernance() {
        Pair<ThreadPoolStatus, ThreadPoolStatus> pair = contextScenarioService.demonstrateThreadPoolGovernance("export", 3, 6);
        ThreadPoolStatus before = pair.left();
        ThreadPoolStatus after = pair.right();

        assertThat(before.corePoolSize()).isEqualTo(2);
        assertThat(before.maxPoolSize()).isEqualTo(4);
        assertThat(after.corePoolSize()).isEqualTo(3);
        assertThat(after.maxPoolSize()).isEqualTo(6);

        // 恢复原有配置
        AsyncUtil.resize("export", 2, 4);
    }

    @Test
    @DisplayName("测试 TransactionUtil 提交前与终态全状态机钩子")
    void testDemonstrateFullTransactionLifecycle() {
        Pair<Boolean, TransactionCompletionStatus> lifecycle = contextScenarioService.demonstrateFullTransactionLifecycle();
        // 无事务时 executeBeforeCommit 就地执行，executeAfterCompletion 降级透传 COMMITTED
        assertThat(lifecycle.left()).isTrue();
        assertThat(lifecycle.right()).isEqualTo(TransactionCompletionStatus.COMMITTED);
    }

    @Test
    @DisplayName("测试 ThreadPoolStatus 虚拟线程感知与自适应字段探测")
    void testDemonstrateVirtualThreadAwareness() {
        ThreadPoolStatus status = contextScenarioService.demonstrateVirtualThreadAwareness("export");
        assertThat(status).isNotNull();
        // export 线程池配置为 PLATFORM 模式
        assertThat(status.virtual()).isFalse();
        assertThat(status.concurrencyLimit()).isEqualTo(-1);
    }
}
