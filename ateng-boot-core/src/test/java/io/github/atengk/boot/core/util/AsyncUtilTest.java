package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.ThreadPoolStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 平台受管异步任务并发执行门面单测
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("平台受管异步并发门面 AsyncUtil 测试")
class AsyncUtilTest {

    @BeforeEach
    void setUp() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        SpringUtil.clear();
        AsyncUtil.resetFallbackExecutor();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        SpringUtil.clear();
        AsyncUtil.resetFallbackExecutor();
    }

    @Test
    @DisplayName("run 成功执行无返回值任务并全链路透传 4D 上下文快照")
    void shouldExecuteRunAndPropagate4DContext() throws Exception {
        MDC.put("traceId", "trace-async-run-001");
        UserContextHolder.set("user-run-001");
        TenantContextHolder.setTenantId("tenant-run-001");

        AtomicReference<String> workerTraceId = new AtomicReference<>();
        AtomicReference<String> workerUser = new AtomicReference<>();
        AtomicReference<String> workerTenant = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        CompletableFuture<Void> future = AsyncUtil.run(() -> {
            workerTraceId.set(MDC.get("traceId"));
            workerUser.set(UserContextHolder.get());
            workerTenant.set(TenantContextHolder.getTenantId());
            latch.countDown();
        });

        // 父线程立即变更/清空上下文，验证快照隔离性
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();

        future.get(3, TimeUnit.SECONDS);
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(workerTraceId.get()).isEqualTo("trace-async-run-001");
        assertThat(workerUser.get()).isEqualTo("user-run-001");
        assertThat(workerTenant.get()).isEqualTo("tenant-run-001");
    }

    @Test
    @DisplayName("supply 成功执行有返回值任务并全链路透传 4D 上下文快照")
    void shouldExecuteSupplyAndPropagate4DContext() throws Exception {
        MDC.put("traceId", "trace-async-supply-002");
        UserContextHolder.set("user-supply-002");
        TenantContextHolder.setTenantId("tenant-supply-002");

        CompletableFuture<String> future = AsyncUtil.supply(() -> {
            String traceId = MDC.get("traceId");
            String user = UserContextHolder.get();
            String tenant = TenantContextHolder.getTenantId();
            return traceId + "#" + user + "#" + tenant;
        });

        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();

        String result = future.get(3, TimeUnit.SECONDS);
        assertThat(result).isEqualTo("trace-async-supply-002#user-supply-002#tenant-supply-002");
    }

    @Test
    @DisplayName("任务执行抛出异常时在工作线程安全恢复现场并将异常传递至 Future")
    void shouldPropagateExceptionAndRestoreContextOnFailure() {
        MDC.put("traceId", "trace-async-err-003");

        CompletableFuture<Void> future = AsyncUtil.run(() -> {
            throw new IllegalStateException("模拟异步内部业务异常");
        });

        assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模拟异步内部业务异常");
    }

    @Test
    @DisplayName("allOf 支持数组与集合重载，空输入安全返回已完成 Future")
    void shouldHandleAllOfOverloads() throws Exception {
        // 空入参保底安全
        CompletableFuture<Void> emptyArrayFuture = AsyncUtil.allOf();
        assertThat(emptyArrayFuture.isDone()).isTrue();

        CompletableFuture<Void> nullArrayFuture = AsyncUtil.allOf((CompletableFuture<?>[]) null);
        assertThat(nullArrayFuture.isDone()).isTrue();

        CompletableFuture<Void> emptyCollectionFuture = AsyncUtil.allOf(List.of());
        assertThat(emptyCollectionFuture.isDone()).isTrue();

        CompletableFuture<Void> nullCollectionFuture = AsyncUtil.allOf((List<CompletableFuture<?>>) null);
        assertThat(nullCollectionFuture.isDone()).isTrue();

        // 真实并行聚合
        CompletableFuture<String> f1 = AsyncUtil.supply(() -> "A");
        CompletableFuture<String> f2 = AsyncUtil.supply(() -> "B");

        CompletableFuture<Void> combinedFuture = AsyncUtil.allOf(f1, f2);
        combinedFuture.get(3, TimeUnit.SECONDS);

        assertThat(f1.get()).isEqualTo("A");
        assertThat(f2.get()).isEqualTo("B");
    }

    @Test
    @DisplayName("getStatus 与 resize 正确反映指标并支持在线动态调参")
    void shouldGetStatusAndResizeSuccessfully() {
        ThreadPoolStatus status = AsyncUtil.getStatus();
        assertThat(status).isNotNull();
        assertThat(status.corePoolSize()).isGreaterThan(0);
        assertThat(status.maxPoolSize()).isGreaterThanOrEqualTo(status.corePoolSize());

        // 动态调大容量
        int newCore = status.maxPoolSize() + 2;
        int newMax = newCore + 4;
        AsyncUtil.resize(newCore, newMax);

        ThreadPoolStatus resizedStatus = AsyncUtil.getStatus();
        assertThat(resizedStatus.corePoolSize()).isEqualTo(newCore);
        assertThat(resizedStatus.maxPoolSize()).isEqualTo(newMax);

        // 动态调小容量
        AsyncUtil.resize(2, 4);
        ThreadPoolStatus shrunkStatus = AsyncUtil.getStatus();
        assertThat(shrunkStatus.corePoolSize()).isEqualTo(2);
        assertThat(shrunkStatus.maxPoolSize()).isEqualTo(4);
    }

    @Test
    @DisplayName("resize 参数非法时触发前置卫语句拦截")
    void shouldValidateResizeParameters() {
        assertThatThrownBy(() -> AsyncUtil.resize(-1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("corePoolSize 不能小于 0");

        assertThatThrownBy(() -> AsyncUtil.resize(4, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPoolSize 必须大于 0");

        assertThatThrownBy(() -> AsyncUtil.resize(10, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能大于 maxPoolSize");
    }

    @Test
    @DisplayName("支持在已初始化的 Spring 容器中依据名称和别名调度执行与监控")
    void shouldSupportSpringNamedExecutorDispatch() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();

        ThreadPoolTaskExecutor exportExecutor = new ThreadPoolTaskExecutor();
        exportExecutor.setCorePoolSize(2);
        exportExecutor.setMaxPoolSize(4);
        exportExecutor.setQueueCapacity(50);
        exportExecutor.setThreadNamePrefix("test-export-async-");
        exportExecutor.initialize();

        try {
            context.getBeanFactory().registerSingleton("exportTaskExecutor", exportExecutor);
            context.getBeanFactory().registerAlias("exportTaskExecutor", "export");
            context.refresh();

            SpringUtil springUtil = new SpringUtil();
            springUtil.setApplicationContext(context);

            // 1. 通过别名调度
            CompletableFuture<String> future = AsyncUtil.supply("export", () -> Thread.currentThread().getName());
            String threadName = future.get(3, TimeUnit.SECONDS);
            assertThat(threadName).startsWith("test-export-async-");

            // 2. 通过别名获取状态
            ThreadPoolStatus status = AsyncUtil.getStatus("export");
            assertThat(status.corePoolSize()).isEqualTo(2);
            assertThat(status.maxPoolSize()).isEqualTo(4);

            // 3. 通过全 Bean 名动态调参
            AsyncUtil.resize("exportTaskExecutor", 3, 6);
            ThreadPoolStatus updatedStatus = AsyncUtil.getStatus("export");
            assertThat(updatedStatus.corePoolSize()).isEqualTo(3);
            assertThat(updatedStatus.maxPoolSize()).isEqualTo(6);

            // 4. 查找不存在的线程池时抛出异常
            assertThatThrownBy(() -> AsyncUtil.getExecutor("notExistPool"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("未找到名称或别名为 [notExistPool] 的受管异步执行器 Bean");
        } finally {
            exportExecutor.shutdown();
            context.close();
        }
    }

    @Test
    @DisplayName("入参为 null 时的边界防御校验")
    void shouldThrowExceptionOnNullTask() {
        assertThatThrownBy(() -> AsyncUtil.run(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("task 不能为空");

        assertThatThrownBy(() -> AsyncUtil.supply(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("task 不能为空");
    }

    @Test
    @DisplayName("在忽略租户闭包内异步执行任务成功透传 ignoreTenant 状态并在完成后还原")
    void shouldPropagateIgnoreTenantContext() throws Exception {
        TenantContextHolder.setTenantId("tenant-100");
        AtomicReference<Boolean> workerIgnore = new AtomicReference<>();
        AtomicReference<String> workerTenant = new AtomicReference<>();

        TenantContextHolder.runWithoutTenant(() -> {
            try {
                CompletableFuture<Void> future = AsyncUtil.run(() -> {
                    workerIgnore.set(TenantContextHolder.isIgnoreTenant());
                    workerTenant.set(TenantContextHolder.getTenantId());
                });
                future.get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(workerIgnore.get()).isTrue();
        assertThat(workerTenant.get()).isEqualTo("tenant-100");
        // 当前线程已退出闭包，恢复正常拦截
        assertThat(TenantContextHolder.isIgnoreTenant()).isFalse();
    }

    @Test
    @DisplayName("对不支持的执行器类型调用 getStatus 与 resize 抛出 UnsupportedOperationException")
    void shouldThrowWhenResizeOrStatusOnUnsupportedExecutor() {
        GenericApplicationContext context = new GenericApplicationContext();
        org.springframework.core.task.SimpleAsyncTaskExecutor virtualExecutor = new org.springframework.core.task.SimpleAsyncTaskExecutor();

        try {
            context.getBeanFactory().registerSingleton("virtualExecutor", virtualExecutor);
            context.refresh();

            SpringUtil springUtil = new SpringUtil();
            springUtil.setApplicationContext(context);

            assertThatThrownBy(() -> AsyncUtil.getStatus("virtualExecutor"))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("不支持获取 ThreadPoolStatus 监控指标");

            assertThatThrownBy(() -> AsyncUtil.resize("virtualExecutor", 2, 4))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("不支持动态在线调参");
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("多并发任务交替执行时各线程上下文严格隔离不发生串号污染")
    void shouldHandleConcurrentTaskExecutionWithUniqueContexts() throws Exception {
        int count = 50;
        CountDownLatch latch = new CountDownLatch(count);
        java.util.concurrent.atomic.AtomicInteger matchCount = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < count; i++) {
            final String traceId = "trace-concurrent-" + i;
            final String userId = "user-concurrent-" + i;

            // 模拟不同请求线程环境
            MDC.put("traceId", traceId);
            UserContextHolder.set(userId);

            AsyncUtil.supply(() -> {
                String currentTrace = MDC.get("traceId");
                String currentUser = UserContextHolder.get();
                if (traceId.equals(currentTrace) && userId.equals(currentUser)) {
                    matchCount.incrementAndGet();
                }
                return true;
            }).whenComplete((res, ex) -> latch.countDown());
        }

        MDC.clear();
        UserContextHolder.clear();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(matchCount.get()).isEqualTo(count);
    }
}
