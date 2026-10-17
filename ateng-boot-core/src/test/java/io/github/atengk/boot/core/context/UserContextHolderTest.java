package io.github.atengk.boot.core.context;

import io.github.atengk.boot.core.spi.CurrentUserProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户上下文持有者与 SPI 测试
 *
 * @author Ateng
 * @since 2026-10-15
 */
@DisplayName("用户上下文持有者与 SPI 测试")
class UserContextHolderTest {

    @BeforeEach
    @AfterEach
    void cleanUp() {
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("当前线程设置、读取与清理用户标识")
    void shouldSetGetAndClearCurrentUserInSameThread() {
        assertNull(UserContextHolder.get());

        UserContextHolder.set("ateng-admin");
        assertEquals("ateng-admin", UserContextHolder.get());

        UserContextHolder.clear();
        assertNull(UserContextHolder.get());
    }

    @Test
    @DisplayName("验证不同线程间的用户上下文隔离性")
    void shouldIsolateUserContextBetweenDifferentThreads() throws InterruptedException {
        UserContextHolder.set("main-thread-user");

        AtomicReference<String> childThreadUser = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread childThread = new Thread(() -> {
            childThreadUser.set(UserContextHolder.get());
            UserContextHolder.set("child-thread-user");
            latch.countDown();
        });

        childThread.start();
        latch.await();

        // 子线程未显式传递时默认应为 null
        assertNull(childThreadUser.get());
        // 主线程依然保持自己的值不受子线程影响
        assertEquals("main-thread-user", UserContextHolder.get());
    }

    @Test
    @DisplayName("验证 CurrentUserProvider 函数式接口契约")
    void shouldSupportCurrentUserProviderSpi() {
        CurrentUserProvider provider = () -> "spi-mock-user";
        assertEquals("spi-mock-user", provider.getCurrentUser());

        CurrentUserProvider fallbackProvider = UserContextHolder::get;
        UserContextHolder.set("fallback-user");
        assertEquals("fallback-user", fallbackProvider.getCurrentUser());
    }

    @Test
    @DisplayName("验证 setUserProvider 全局 SPI 自动回退与 ThreadLocal 优先级")
    void shouldFallbackToUserProviderWhenThreadLocalIsEmpty() {
        try {
            UserContextHolder.setUserProvider(() -> "spi-injected-user");

            // 1. ThreadLocal 为空时回退至 SPI
            assertEquals("spi-injected-user", UserContextHolder.get());
            assertEquals("spi-injected-user", UserContextHolder.getUserId());

            // 2. ThreadLocal 显式赋值时优先使用 ThreadLocal
            UserContextHolder.set("explicit-local-user");
            assertEquals("explicit-local-user", UserContextHolder.get());

            // 3. ThreadLocal 清除后再次回退至 SPI
            UserContextHolder.clear();
            assertEquals("spi-injected-user", UserContextHolder.get());

            // 4. SPI 抛出异常时防御性返回 null 而不中断业务
            UserContextHolder.setUserProvider(() -> {
                throw new RuntimeException("SPI error");
            });
            assertNull(UserContextHolder.get());
        } finally {
            UserContextHolder.setUserProvider(null);
        }
    }

    @Test
    @DisplayName("验证 runWithUser 编程式闭包执行与现场安全还原（含 SPI 回退无泄漏验证）")
    void shouldExecuteRunWithUserAndRestoreContextWithoutSpiLeak() {
        // 1. 无 SPI 且 ThreadLocal 为空时的闭包执行
        assertNull(UserContextHolder.get());
        String result = UserContextHolder.runWithUser("temp-user-1", () -> {
            assertEquals("temp-user-1", UserContextHolder.get());
            return "ok";
        });
        assertEquals("ok", result);
        assertNull(UserContextHolder.get());

        // 2. 原本有 ThreadLocal 时的嵌套闭包执行
        UserContextHolder.set("outer-user");
        UserContextHolder.runWithUser("inner-user", () -> {
            assertEquals("inner-user", UserContextHolder.get());
            UserContextHolder.runWithUser("deep-inner-user", (Runnable) () -> {
                assertEquals("deep-inner-user", UserContextHolder.get());
            });
            assertEquals("inner-user", UserContextHolder.get());
        });
        assertEquals("outer-user", UserContextHolder.get());
        UserContextHolder.clear();

        // 3. 关键防卫测试：存在 SPI 时执行 runWithUser，执行完毕后不得将 SPI 用户固化写入 ThreadLocal
        java.util.concurrent.atomic.AtomicReference<String> dynamicSpiUser = new java.util.concurrent.atomic.AtomicReference<>("spi-alice");
        try {
            UserContextHolder.setUserProvider(dynamicSpiUser::get);
            assertEquals("spi-alice", UserContextHolder.get());

            // 闭包内临时切换为 temp-bob
            UserContextHolder.runWithUser("temp-bob", () -> {
                assertEquals("temp-bob", UserContextHolder.get());
            });

            // 闭包结束后，ThreadLocal 必须已清理，能够正确感知 SPI 动态变更为 spi-charlie
            assertEquals("spi-alice", UserContextHolder.get());
            dynamicSpiUser.set("spi-charlie");
            assertEquals("spi-charlie", UserContextHolder.get(), "闭包结束后不得将之前的 SPI 值固化到 ThreadLocal 中，应继续动态跟随 SPI 变更");
        } finally {
            UserContextHolder.setUserProvider(null);
            UserContextHolder.clear();
        }
    }
}
