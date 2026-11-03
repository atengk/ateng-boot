package io.github.atengk.boot.liteflow.thread;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文透传执行器服务代理单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
@DisplayName("上下文透传执行器服务代理单元测试")
class ContextPropagatingExecutorServiceTest {

    private final ExecutorService rawPool = Executors.newFixedThreadPool(2);
    private final ContextPropagatingExecutorService executorService = new ContextPropagatingExecutorService(rawPool, true);

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("验证 execute(Runnable) 能够透传 MDC 与租户上下文并在子线程执行后清理")
    void testExecuteRunnable() throws Exception {
        MDC.put("traceId", "trace-runnable-123");
        UserContextHolder.set("user-runnable");
        TenantContextHolder.setTenantId("tenant-runnable");

        AtomicReference<String> capturedTrace = new AtomicReference<>();
        AtomicReference<String> capturedUser = new AtomicReference<>();
        AtomicReference<String> capturedTenant = new AtomicReference<>();

        executorService.execute(() -> {
            capturedTrace.set(MDC.get("traceId"));
            capturedUser.set(UserContextHolder.get());
            capturedTenant.set(TenantContextHolder.getTenantId());
        });

        // 等待执行完成
        Thread.sleep(100);

        assertThat(capturedTrace.get()).isEqualTo("trace-runnable-123");
        assertThat(capturedUser.get()).isEqualTo("user-runnable");
        assertThat(capturedTenant.get()).isEqualTo("tenant-runnable");
    }

    @Test
    @DisplayName("验证 submit(Callable) 能够透传四维上下文并获得返回值")
    void testSubmitCallable() throws Exception {
        MDC.put("traceId", "trace-callable-456");
        UserContextHolder.set("user-callable");
        TenantContextHolder.setTenantId("tenant-callable");

        Future<String> future = executorService.submit(() -> {
            assertThat(MDC.get("traceId")).isEqualTo("trace-callable-456");
            assertThat(UserContextHolder.get()).isEqualTo("user-callable");
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-callable");
            return "SUCCESS";
        });

        assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("验证 invokeAll 批量任务均能透传上下文")
    void testInvokeAll() throws Exception {
        MDC.put("traceId", "trace-batch-789");
        Callable<String> task = () -> MDC.get("traceId");

        List<Future<String>> futures = executorService.invokeAll(Collections.singletonList(task));
        assertThat(futures).hasSize(1);
        assertThat(futures.get(0).get()).isEqualTo("trace-batch-789");
    }

    @Test
    @DisplayName("验证受保护模式下 shutdown 与 shutdownNow 被安全拦截保护")
    void testShutdownProtection() {
        assertThat(executorService.isShutdownProtected()).isTrue();
        executorService.shutdown();
        assertThat(executorService.isShutdown()).isFalse();

        List<Runnable> runnables = executorService.shutdownNow();
        assertThat(runnables).isEmpty();
        assertThat(executorService.isShutdown()).isFalse();
    }
}
