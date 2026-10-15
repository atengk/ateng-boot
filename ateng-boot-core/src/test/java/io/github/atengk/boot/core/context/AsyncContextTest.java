package io.github.atengk.boot.core.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 异步上下文传播装饰器测试
 *
 * @author Ateng
 * @since 2026-10-15
 */
@DisplayName("异步上下文传播装饰器测试")
class AsyncContextTest {

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        MDC.clear();
        UserContextHolder.clear();
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        executor.shutdownNow();
    }

    @Test
    @DisplayName("异步 Runnable 任务成功传播 MDC traceId 与 UserContext 并安全清理")
    void shouldPropagateContextForRunnable() throws Exception {
        MDC.put("traceId", "trace-parent-101");
        MDC.put("customKey", "customValue");
        UserContextHolder.set("user-parent-101");

        AtomicReference<String> asyncTraceId = new AtomicReference<>();
        AtomicReference<String> asyncCustomKey = new AtomicReference<>();
        AtomicReference<String> asyncUser = new AtomicReference<>();

        Runnable task = AsyncContext.wrap(() -> {
            asyncTraceId.set(MDC.get("traceId"));
            asyncCustomKey.set(MDC.get("customKey"));
            asyncUser.set(UserContextHolder.get());
        });

        // 在父线程清理或变更，验证快照独立性
        MDC.clear();
        UserContextHolder.clear();

        Future<?> future = executor.submit(task);
        future.get(2, TimeUnit.SECONDS);

        assertEquals("trace-parent-101", asyncTraceId.get());
        assertEquals("customValue", asyncCustomKey.get());
        assertEquals("user-parent-101", asyncUser.get());

        // 验证执行完毕后异步线程池中的线程上下文已被严格清理
        Future<String> cleanCheckFuture = executor.submit(() -> MDC.get("traceId"));
        assertNull(cleanCheckFuture.get(2, TimeUnit.SECONDS));

        Future<String> userCleanCheckFuture = executor.submit(UserContextHolder::get);
        assertNull(userCleanCheckFuture.get(2, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("异步 Callable 任务成功传播上下文并正确返回计算结果")
    void shouldPropagateContextForCallable() throws Exception {
        MDC.put("traceId", "trace-parent-202");
        UserContextHolder.set("user-parent-202");

        Callable<String> task = AsyncContext.wrap(() -> {
            String currentTraceId = MDC.get("traceId");
            String currentUser = UserContextHolder.get();
            return currentTraceId + ":" + currentUser;
        });

        MDC.put("traceId", "trace-changed-in-parent");

        Future<String> future = executor.submit(task);
        String result = future.get(2, TimeUnit.SECONDS);

        assertEquals("trace-parent-202:user-parent-202", result);
    }

    @Test
    @DisplayName("对 null 任务包装时防御性返回 null")
    void shouldReturnNullWhenWrappingNullTask() {
        assertNull(AsyncContext.wrap((Runnable) null));
        assertNull(AsyncContext.wrap((Callable<String>) null));
    }
}
