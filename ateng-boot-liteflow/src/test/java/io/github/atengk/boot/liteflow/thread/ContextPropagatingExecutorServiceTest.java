package io.github.atengk.boot.liteflow.thread;

import com.yomahub.liteflow.springboot.config.LiteflowMainAutoConfiguration;
import com.yomahub.liteflow.springboot.config.LiteflowPropertyAutoConfiguration;
import com.yomahub.liteflow.thread.ExecutorHelper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.liteflow.autoconfigure.AtengLiteflowAutoConfiguration;
import io.github.atengk.boot.liteflow.properties.AtengLiteflowProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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
        rawPool.shutdownNow();
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
        CountDownLatch latch = new CountDownLatch(1);

        executorService.execute(() -> {
            try {
                capturedTrace.set(MDC.get("traceId"));
                capturedUser.set(UserContextHolder.get());
                capturedTenant.set(TenantContextHolder.getTenantId());
            } finally {
                latch.countDown();
            }
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

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

    @Test
    @DisplayName("验证 propagateContext 为 false 时不执行四维上下文穿透透传")
    void testPropagateContextDisabled() throws Exception {
        ContextPropagatingExecutorService nonPropagating = new ContextPropagatingExecutorService(rawPool, true, false);
        assertThat(nonPropagating.isPropagateContext()).isFalse();

        MDC.put("traceId", "trace-disabled-123");
        UserContextHolder.set("user-disabled");
        TenantContextHolder.setTenantId("tenant-disabled");

        AtomicReference<String> capturedTrace = new AtomicReference<>("initial");
        AtomicReference<String> capturedUser = new AtomicReference<>("initial");
        CountDownLatch latch = new CountDownLatch(1);

        nonPropagating.execute(() -> {
            try {
                capturedTrace.set(MDC.get("traceId"));
                capturedUser.set(UserContextHolder.get());
            } finally {
                latch.countDown();
            }
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(capturedTrace.get()).isNull();
        assertThat(capturedUser.get()).isNull();
    }

    @Test
    @DisplayName("验证 LiteflowGlobalExecutorBuilder 独立回退线程池在 destroy 时优雅关闭，而共享受管池不被误关")
    void testFallbackExecutorGracefulShutdownOnDestroy() {
        // 1. 独立回退线程池模式（useManagedPool = false）
        AtengLiteflowProperties fallbackProps = new AtengLiteflowProperties();
        fallbackProps.getThreadPool().setUseManagedPool(false);
        fallbackProps.getThreadPool().setCorePoolSize(1);
        fallbackProps.getThreadPool().setMaxPoolSize(2);
        fallbackProps.getThreadPool().setQueueCapacity(10);

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(AtengLiteflowProperties.class, () -> fallbackProps);
            ctx.refresh();

            LiteflowGlobalExecutorBuilder builder = new LiteflowGlobalExecutorBuilder();
            builder.setApplicationContext(ctx);
            ExecutorService built = builder.buildExecutor();
            assertThat(built).isInstanceOf(ContextPropagatingExecutorService.class);
            ContextPropagatingExecutorService proxy = (ContextPropagatingExecutorService) built;
            assertThat(proxy.isShutdownProtected()).isFalse();
            assertThat(proxy.getDelegate().isShutdown()).isFalse();

            builder.destroy();
            assertThat(proxy.getDelegate().isShutdown()).isTrue();
        }

        // 2. 共享受管线程池模式（useManagedPool = true）
        ThreadPoolTaskExecutor springTaskExecutor = new ThreadPoolTaskExecutor();
        springTaskExecutor.setCorePoolSize(1);
        springTaskExecutor.setMaxPoolSize(2);
        springTaskExecutor.setThreadNamePrefix("test-managed-");
        springTaskExecutor.initialize();
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean("taskExecutor", ThreadPoolTaskExecutor.class, () -> springTaskExecutor);
            ctx.refresh();

            LiteflowGlobalExecutorBuilder builder = new LiteflowGlobalExecutorBuilder();
            builder.setApplicationContext(ctx);
            ExecutorService built = builder.buildExecutor();
            ContextPropagatingExecutorService proxy = (ContextPropagatingExecutorService) built;
            assertThat(proxy.isShutdownProtected()).isTrue();

            builder.destroy();
            assertThat(springTaskExecutor.getThreadPoolExecutor().isShutdown()).isFalse();
        } finally {
            springTaskExecutor.shutdown();
        }

        // 3. 验证通过 LiteFlow ExecutorHelper SPI 在真实容器关闭时联动销毁独立回退线程池
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        LiteflowPropertyAutoConfiguration.class,
                        LiteflowMainAutoConfiguration.class,
                        AtengLiteflowAutoConfiguration.class
                ))
                .withPropertyValues(
                        "liteflow.enable=true",
                        "liteflow.parse-mode=PARSE_ONE_ON_FIRST_EXEC",
                        "liteflow.rule-source=classpath*:liteflow/non-existent/*.el.xml",
                        "ateng.boot.liteflow.thread-pool.use-managed-pool=false",
                        "ateng.boot.liteflow.thread-pool.core-pool-size=1",
                        "ateng.boot.liteflow.thread-pool.max-pool-size=2"
                )
                .run(context -> {
                    ExecutorHelper.loadInstance().clearExecutorServiceMap();
                    try {
                        ExecutorService whenExecutor = ExecutorHelper.loadInstance()
                                .buildWhenExecutor(LiteflowGlobalExecutorBuilder.class.getName());
                        assertThat(whenExecutor).isInstanceOf(ContextPropagatingExecutorService.class);
                        ContextPropagatingExecutorService proxy = (ContextPropagatingExecutorService) whenExecutor;
                        assertThat(proxy.isShutdownProtected()).isFalse();
                        assertThat(proxy.getDelegate().isShutdown()).isFalse();

                        context.close();
                        assertThat(proxy.getDelegate().isShutdown()).isTrue();
                    } finally {
                        ExecutorHelper.loadInstance().clearExecutorServiceMap();
                    }
                });
    }
}
