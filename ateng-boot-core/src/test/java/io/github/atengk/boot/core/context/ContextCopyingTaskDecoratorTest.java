package io.github.atengk.boot.core.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 上下文感知任务装饰器单测
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("上下文感知任务装饰器单测")
class ContextCopyingTaskDecoratorTest {

    private ContextCopyingTaskDecorator decorator;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        MDC.clear();
        UserContextHolder.clear();
        decorator = new ContextCopyingTaskDecorator();
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        executor.shutdownNow();
    }

    @Test
    @DisplayName("通过 decorate 装饰 Runnable 成功流转父线程 MDC 与用户上下文")
    void shouldPropagateContextWhenDecoratingRunnable() throws Exception {
        MDC.put("traceId", "trace-decorator-888");
        UserContextHolder.set("user-decorator-999");

        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        AtomicReference<String> capturedUserId = new AtomicReference<>();

        Runnable task = decorator.decorate(() -> {
            capturedTraceId.set(MDC.get("traceId"));
            capturedUserId.set(UserContextHolder.get());
        });

        // 在父线程变更上下文，验证快照隔离
        MDC.put("traceId", "trace-modified");
        UserContextHolder.set("user-modified");

        Future<?> future = executor.submit(task);
        future.get(2, TimeUnit.SECONDS);

        assertEquals("trace-decorator-888", capturedTraceId.get());
        assertEquals("user-decorator-999", capturedUserId.get());

        // 验证执行后工作线程状态被彻底复原清理
        Future<String> traceCheck = executor.submit(() -> MDC.get("traceId"));
        assertNull(traceCheck.get(2, TimeUnit.SECONDS));

        Future<String> userCheck = executor.submit(UserContextHolder::get);
        assertNull(userCheck.get(2, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("传入 null 任务时安全返回 null")
    void shouldReturnNullWhenTaskIsNull() {
        assertNull(decorator.decorate(null));
    }
}
