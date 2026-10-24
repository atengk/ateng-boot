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
        UserContextHolder.setUserProvider(null);
        TenantContextHolder.clear();
        TenantContextHolder.setTenantProvider(null);
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        UserContextHolder.setUserProvider(null);
        TenantContextHolder.clear();
        TenantContextHolder.setTenantProvider(null);
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

        Callable<String> task = AsyncContext.wrap((Callable<String>) () -> {
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
    @DisplayName("四维上下文全链路透传：MDC + User + Tenant + IgnoreTenant 完整流动并安全自清理")
    void shouldPropagate4DContextAndCleanUp() throws Exception {
        MDC.put("traceId", "trace-4d-999");
        UserContextHolder.set("user-4d-888");
        TenantContextHolder.setTenantId("tenant-4d-777");

        TenantContextHolder.runWithoutTenant(() -> {
            // 在忽略租户环境下包装异步任务
            Callable<String> task = AsyncContext.wrap((Callable<String>) () -> {
                String mdcTrace = MDC.get("traceId");
                String user = UserContextHolder.get();
                String tenant = TenantContextHolder.getTenantId();
                boolean ignoreTenant = TenantContextHolder.isIgnoreTenant();
                return mdcTrace + "|" + user + "|" + tenant + "|" + ignoreTenant;
            });

            Future<String> future = executor.submit(task);
            try {
                String result = future.get(2, TimeUnit.SECONDS);
                assertEquals("trace-4d-999|user-4d-888|tenant-4d-777|true", result);
            } catch (Exception e) {
                fail("异步执行异常: " + e.getMessage());
            }
        });

        // 验证执行后工作线程中租户与忽略标志彻底恢复
        Future<Boolean> threadCleanCheck = executor.submit(() ->
                MDC.get("traceId") == null &&
                        UserContextHolder.get() == null &&
                        TenantContextHolder.getTenantId() == null &&
                        !TenantContextHolder.isIgnoreTenant()
        );
        assertTrue(threadCleanCheck.get(2, TimeUnit.SECONDS), "工作线程四维上下文应被严格自清理还原");
    }

    @Test
    @DisplayName("异步 Supplier 任务结合 CompletableFuture 成功传播四维上下文并安全清理")
    void shouldPropagateContextForSupplier() throws Exception {
        MDC.put("traceId", "trace-supplier-303");
        UserContextHolder.set("user-supplier-303");
        TenantContextHolder.setTenantId("tenant-supplier-303");

        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                AsyncContext.wrapSupplier(() -> MDC.get("traceId") + "@" + UserContextHolder.get() + "@" + TenantContextHolder.getTenantId()),
                executor
        );

        String result = future.get(2, TimeUnit.SECONDS);
        assertEquals("trace-supplier-303@user-supplier-303@tenant-supplier-303", result);

        Future<String> cleanCheck = executor.submit(() -> MDC.get("traceId"));
        assertNull(cleanCheck.get(2, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("对 null 任务包装时防御性返回 null")
    void shouldReturnNullWhenWrappingNullTask() {
        assertNull(AsyncContext.wrap((Runnable) null));
        assertNull(AsyncContext.wrap((Callable<String>) null));
        assertNull(AsyncContext.wrapSupplier(null));
    }

    @Test
    @DisplayName("配置全局 CurrentUserProvider SPI 时工作线程不被 SPI 用户固化污染")
    void shouldNotPolluteWorkerThreadWithSpiUserWhenWorkerHasNoExplicitUser() throws Exception {
        UserContextHolder.setUserProvider(() -> "global-spi-user-999");

        // 父线程未显式绑定 ThreadLocal 用户
        assertNull(UserContextHolder.getExplicit());
        assertEquals("global-spi-user-999", UserContextHolder.get());

        Runnable task = AsyncContext.wrap(() -> {
            // 异步任务内执行普通逻辑
        });

        Future<?> future = executor.submit(task);
        future.get(2, TimeUnit.SECONDS);

        // 任务完成后，工作线程必须恢复为未显式绑定的纯净现场，严禁被 SPI 用户固化写入 ThreadLocal
        Future<String> explicitUserFuture = executor.submit(UserContextHolder::getExplicit);
        assertNull(explicitUserFuture.get(2, TimeUnit.SECONDS), "工作线程显式用户必须为 null，严禁固化 SPI 用户");
    }

    @Test
    @DisplayName("异步任务抛出未受检异常时四维现场仍被安全完整还原")
    void shouldSafelyRestore4DContextWhenAsyncTaskThrowsException() throws Exception {
        MDC.put("traceId", "trace-parent-err");
        UserContextHolder.set("user-parent-err");
        TenantContextHolder.setTenantId("tenant-parent-err");

        Callable<String> faultyTask = AsyncContext.wrap((Callable<String>) () -> {
            throw new IllegalStateException("模拟业务抛出异常");
        });

        Future<String> future = executor.submit(faultyTask);
        assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));

        // 验证执行抛出异常后，工作线程中的四维现场依然被彻底清理还原
        Future<Boolean> threadCleanCheck = executor.submit(() ->
                MDC.get("traceId") == null &&
                        UserContextHolder.getExplicit() == null &&
                        TenantContextHolder.getTenantId() == null &&
                        !TenantContextHolder.isIgnoreTenant()
        );
        assertTrue(threadCleanCheck.get(2, TimeUnit.SECONDS), "抛出异常时工作线程四维现场仍应被严格自清理还原");
    }

    @Test
    @DisplayName("通过 AsyncContextSnapshotProvider 扩展第 5 维上下文（如数据源）在异步线程中透传与还原")
    void shouldPropagateCustomSnapshotProvider() throws Exception {
        ThreadLocal<String> customContext = new ThreadLocal<>();
        AsyncContextSnapshotProvider testProvider = new AsyncContextSnapshotProvider() {
            @Override
            public Object capture() {
                return customContext.get();
            }

            @Override
            public Object capturePrevious() {
                return customContext.get();
            }

            @Override
            public void apply(Object snapshot) {
                if (snapshot != null) {
                    customContext.set((String) snapshot);
                } else {
                    customContext.remove();
                }
            }

            @Override
            public void restore(Object previous) {
                if (previous != null) {
                    customContext.set((String) previous);
                } else {
                    customContext.remove();
                }
            }
        };

        try {
            AsyncContext.registerSnapshotProvider(testProvider);
            customContext.set("ds-order-master");

            AtomicReference<String> asyncContextValue = new AtomicReference<>();
            Runnable task = AsyncContext.wrap(() -> {
                asyncContextValue.set(customContext.get());
            });

            // 父线程清理
            customContext.remove();

            Future<?> future = executor.submit(task);
            future.get(2, TimeUnit.SECONDS);

            assertEquals("ds-order-master", asyncContextValue.get(), "扩展提供者快照必须正确透传至子线程");

            // 验证子线程执行完毕后现场被还原
            Future<String> cleanCheck = executor.submit(customContext::get);
            assertNull(cleanCheck.get(2, TimeUnit.SECONDS), "异步工作线程执行完毕后扩展上下文必须被严格清理");
        } finally {
            AsyncContext.unregisterSnapshotProvider(testProvider);
            customContext.remove();
        }
    }
}
