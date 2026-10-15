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
}
