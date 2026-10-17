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
}
