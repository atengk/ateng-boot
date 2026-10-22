package io.github.atengk.boot.core.autoconfigure;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.enums.AsyncExecutionMode;
import io.github.atengk.boot.core.enums.AsyncRejectedPolicy;
import io.github.atengk.boot.core.properties.AsyncPoolProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 受管异步执行器实例构建工厂单测
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("受管异步执行器实例构建工厂单测")
class AsyncExecutorFactoryTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("createPlatformExecutor 成功应用自定义配置并挂载上下文透传装饰器")
    void shouldCreatePlatformExecutorWithCustomProperties() throws Exception {
        AsyncPoolProperties pool = new AsyncPoolProperties();
        pool.setCorePoolSize(3);
        pool.setMaxPoolSize(6);
        pool.setQueueCapacity(50);
        pool.setKeepAlive(Duration.ofSeconds(45));
        pool.setThreadNamePrefix("custom-platform-");
        pool.setRejectedPolicy(AsyncRejectedPolicy.CALLER_RUNS);
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTermination(Duration.ofSeconds(15));

        ThreadPoolTaskExecutor executor = AsyncExecutorFactory.createPlatformExecutor("myPool", pool);
        assertThat(executor.getCorePoolSize()).isEqualTo(3);
        assertThat(executor.getMaxPoolSize()).isEqualTo(6);
        assertThat(executor.getQueueCapacity()).isEqualTo(50);
        assertThat(executor.getKeepAliveSeconds()).isEqualTo(45);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("custom-platform-");

        // 编程式测试场景下手动初始化
        executor.initialize();
        try {
            // 验证四维上下文透传
            MDC.put("traceId", "TRACE-FACTORY-001");
            UserContextHolder.set("USER-001");
            TenantContextHolder.setTenantId("TENANT-001");

            AtomicReference<String> capturedTraceId = new AtomicReference<>();
            AtomicReference<String> capturedUser = new AtomicReference<>();
            AtomicReference<String> capturedTenant = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            executor.execute(() -> {
                capturedTraceId.set(MDC.get("traceId"));
                capturedUser.set(UserContextHolder.get());
                capturedTenant.set(TenantContextHolder.getTenantId());
                latch.countDown();
            });

            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(capturedTraceId.get()).isEqualTo("TRACE-FACTORY-001");
            assertThat(capturedUser.get()).isEqualTo("USER-001");
            assertThat(capturedTenant.get()).isEqualTo("TENANT-001");
        } finally {
            executor.destroy();
        }
    }

    @Test
    @DisplayName("createPlatformExecutor 传入 null 配置时能防御性应用默认参数")
    void shouldCreatePlatformExecutorWithNullPropertiesDefensively() {
        ThreadPoolTaskExecutor executor = AsyncExecutorFactory.createPlatformExecutor(null, null);
        assertThat(executor).isNotNull();
        assertThat(executor.getCorePoolSize()).isGreaterThanOrEqualTo(1);
        assertThat(executor.getMaxPoolSize()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("createVirtualExecutor 成功应用自定义配置并启用虚拟线程与上下文透传")
    void shouldCreateVirtualExecutorWithCustomProperties() throws Exception {
        AsyncPoolProperties pool = new AsyncPoolProperties();
        pool.setVirtualThreadNamePrefix("custom-vt-");
        pool.setConcurrencyLimit(10);
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTermination(Duration.ofSeconds(20));

        SimpleAsyncTaskExecutor executor = AsyncExecutorFactory.createVirtualExecutor("myVtPool", pool);
        assertThat(executor).isNotNull();
        assertThat(executor.getThreadNamePrefix()).isEqualTo("custom-vt-");
        assertThat(executor.getConcurrencyLimit()).isEqualTo(10);

        // 验证四维上下文透传
        MDC.put("traceId", "TRACE-VT-002");
        UserContextHolder.set("USER-VT");
        TenantContextHolder.setTenantId("TENANT-VT");

        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        AtomicReference<String> capturedUser = new AtomicReference<>();
        AtomicReference<String> capturedTenant = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        executor.execute(() -> {
            capturedTraceId.set(MDC.get("traceId"));
            capturedUser.set(UserContextHolder.get());
            capturedTenant.set(TenantContextHolder.getTenantId());
            latch.countDown();
        });

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(capturedTraceId.get()).isEqualTo("TRACE-VT-002");
        assertThat(capturedUser.get()).isEqualTo("USER-VT");
        assertThat(capturedTenant.get()).isEqualTo("TENANT-VT");

        executor.close();
    }

    @Test
    @DisplayName("createVirtualExecutor 传入 null 配置时能防御性应用默认参数")
    void shouldCreateVirtualExecutorWithNullPropertiesDefensively() {
        SimpleAsyncTaskExecutor executor = AsyncExecutorFactory.createVirtualExecutor(null, null);
        assertThat(executor).isNotNull();
        executor.close();
    }

    @Test
    @DisplayName("isVirtualMode 准确判定运行模式")
    void shouldCorrectlyDetermineVirtualMode() {
        AsyncPoolProperties platformPool = new AsyncPoolProperties();
        platformPool.setMode(AsyncExecutionMode.PLATFORM);
        assertThat(AsyncExecutorFactory.isVirtualMode(platformPool, null)).isFalse();

        AsyncPoolProperties virtualPool = new AsyncPoolProperties();
        virtualPool.setMode(AsyncExecutionMode.VIRTUAL);
        if (Runtime.version().feature() >= 21) {
            assertThat(AsyncExecutorFactory.isVirtualMode(virtualPool, null)).isTrue();
        } else {
            assertThat(AsyncExecutorFactory.isVirtualMode(virtualPool, null)).isFalse();
        }

        AsyncPoolProperties autoPool = new AsyncPoolProperties();
        autoPool.setMode(AsyncExecutionMode.AUTO);
        MockEnvironment env = new MockEnvironment();
        assertThat(AsyncExecutorFactory.isVirtualMode(autoPool, env)).isFalse();

        env.setProperty("spring.threads.virtual.enabled", "true");
        if (Runtime.version().feature() >= 21) {
            assertThat(AsyncExecutorFactory.isVirtualMode(autoPool, env)).isTrue();
        }

        // null pool 防卫
        assertThat(AsyncExecutorFactory.isVirtualMode(null, null)).isFalse();
    }

    @Test
    @DisplayName("createExecutor 门面依据模式自适应构建对应执行器")
    void shouldCreateExecutorAdaptively() {
        MockEnvironment env = new MockEnvironment();

        // 平台线程模式
        AsyncPoolProperties platformPool = new AsyncPoolProperties();
        platformPool.setMode(AsyncExecutionMode.PLATFORM);
        Executor platformExecutor = AsyncExecutorFactory.createExecutor("testPlatform", platformPool, env);
        assertThat(platformExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);

        // 虚拟线程模式（若 JDK 21+ 且配置 VIRTUAL）
        if (Runtime.version().feature() >= 21) {
            AsyncPoolProperties virtualPool = new AsyncPoolProperties();
            virtualPool.setMode(AsyncExecutionMode.VIRTUAL);
            Executor virtualExecutor = AsyncExecutorFactory.createExecutor("testVirtual", virtualPool, env);
            assertThat(virtualExecutor).isInstanceOf(SimpleAsyncTaskExecutor.class);
            ((SimpleAsyncTaskExecutor) virtualExecutor).close();
        }

        // null 保护
        Executor defaultExecutor = AsyncExecutorFactory.createExecutor(null, null, null);
        assertThat(defaultExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
    }
}
