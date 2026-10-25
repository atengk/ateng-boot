package io.github.atengk.boot.datasource.context;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.ContextCopyingTaskDecorator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 数据源上下文五维全链路异步透传测试
 * 验证：$\langle\text{MDC TraceId}, \text{UserId}, \text{TenantId}, \text{IgnoreTenant}, \mathbf{DataSourceKey}\rangle$
 *
 * @author Ateng
 * @since 2026-10-24
 */
@DisplayName("数据源五维异步全链路上下文透传单测")
class DataSourceAsyncContextTest {

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        DynamicDataSourceContextHolder.clear();
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        DynamicDataSourceContextHolder.clear();
        executor.shutdownNow();
    }

    @Test
    @DisplayName("验证五维全链路上下文通过 ContextCopyingTaskDecorator 完整透传并在任务结束后安全自清理")
    void shouldPropagate5DContextAcrossThreadBoundaries() throws Exception {
        // 1. 父线程准备五维上下文
        MDC.put("traceId", "trace-5d-test-101");
        UserContextHolder.set("user-5d-admin");
        TenantContextHolder.setTenantId("tenant-5d-corp");
        DynamicDataSourceContextHolder.push("ds-corp-sharding-01");

        AtomicReference<String> asyncTraceId = new AtomicReference<>();
        AtomicReference<String> asyncUser = new AtomicReference<>();
        AtomicReference<String> asyncTenant = new AtomicReference<>();
        AtomicReference<String> asyncDataSource = new AtomicReference<>();

        ContextCopyingTaskDecorator decorator = new ContextCopyingTaskDecorator();
        Runnable decoratedTask = decorator.decorate(() -> {
            asyncTraceId.set(MDC.get("traceId"));
            asyncUser.set(UserContextHolder.get());
            asyncTenant.set(TenantContextHolder.getTenantId());
            asyncDataSource.set(DynamicDataSourceContextHolder.peek());
        });

        // 父线程清理或变更，验证快照独立性
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        DynamicDataSourceContextHolder.clear();

        // 2. 异步执行
        Future<?> future = executor.submit(decoratedTask);
        future.get(2, TimeUnit.SECONDS);

        // 3. 校验子线程完整获取到五维快照
        assertThat(asyncTraceId.get()).isEqualTo("trace-5d-test-101");
        assertThat(asyncUser.get()).isEqualTo("user-5d-admin");
        assertThat(asyncTenant.get()).isEqualTo("tenant-5d-corp");
        assertThat(asyncDataSource.get()).isEqualTo("ds-corp-sharding-01");

        // 4. 验证执行完毕后异步工作线程上下文已被彻底清理
        Future<Boolean> threadCleanCheck = executor.submit(() ->
                MDC.get("traceId") == null &&
                        UserContextHolder.getExplicit() == null &&
                        TenantContextHolder.getTenantId() == null &&
                        DynamicDataSourceContextHolder.peek() == null
        );
        assertThat(threadCleanCheck.get(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("验证异步 Callable 抛出异常时五维现场依然严格被自清理还原")
    void shouldCleanUp5DContextEvenWhenAsyncTaskThrows() throws Exception {
        MDC.put("traceId", "trace-err-5d");
        UserContextHolder.set("user-err");
        TenantContextHolder.setTenantId("tenant-err");
        DynamicDataSourceContextHolder.push("ds-err-db");

        Callable<String> faultyTask = AsyncContext.wrap((Callable<String>) () -> {
            throw new IllegalStateException("模拟业务抛出异常");
        });

        Future<String> future = executor.submit(faultyTask);
        assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));

        // 验证即使抛出业务异常，工作线程的五维现场依然被彻底清理还原
        Future<Boolean> cleanCheck = executor.submit(() ->
                MDC.get("traceId") == null &&
                        UserContextHolder.getExplicit() == null &&
                        TenantContextHolder.getTenantId() == null &&
                        DynamicDataSourceContextHolder.peek() == null
        );
        assertThat(cleanCheck.get(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("验证工作线程已有既有上下文时任务完成后精准还原工作线程原始上下文")
    void shouldRestorePreviousWorkerThreadContext() throws Exception {
        // 先在 worker 线程上设置已有上下文
        executor.submit(() -> DynamicDataSourceContextHolder.push("worker-previous-ds")).get();

        MDC.put("traceId", "task-trace");
        DynamicDataSourceContextHolder.push("parent-ds");

        Runnable task = AsyncContext.wrap(() -> {
            assertThat(DynamicDataSourceContextHolder.peek()).isEqualTo("parent-ds");
        });

        executor.submit(task).get();

        // 验证任务执行后，worker 线程恢复为原先的 worker-previous-ds，且栈深没有累积
        Future<String> check = executor.submit(() -> {
            String current = DynamicDataSourceContextHolder.peek();
            DynamicDataSourceContextHolder.poll();
            String afterPoll = DynamicDataSourceContextHolder.peek();
            assertThat(afterPoll).isNull();
            return current;
        });

        assertThat(check.get()).isEqualTo("worker-previous-ds");
    }
}
