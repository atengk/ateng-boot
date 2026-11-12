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

    @Test
    @DisplayName("验证 Long 重载与 getUserIdAsLong() 强类型解析及非数字异常防卫")
    void shouldSupportLongOverloadsAndValidateNumericUserId() {
        assertNull(UserContextHolder.getUserIdAsLong());

        UserContextHolder.set(10086L);
        assertEquals("10086", UserContextHolder.get());
        assertEquals(10086L, UserContextHolder.getUserIdAsLong());

        UserContextHolder.setUserId(20088L);
        assertEquals("20088", UserContextHolder.getUserId());
        assertEquals(20088L, UserContextHolder.getUserIdAsLong());

        UserContextHolder.set((Long) null);
        assertNull(UserContextHolder.get());
        assertNull(UserContextHolder.getUserIdAsLong());

        UserContextHolder.set("   ");
        assertNull(UserContextHolder.getUserIdAsLong());

        UserContextHolder.set("ateng-admin");
        IllegalStateException ex = assertThrows(IllegalStateException.class, UserContextHolder::getUserIdAsLong);
        assertTrue(ex.getMessage().contains("ateng-admin"));

        // Long 重载 runWithUser 嵌套栈恢复
        UserContextHolder.set(100L);
        Long innerResult = UserContextHolder.runWithUser(200L, () -> {
            assertEquals(200L, UserContextHolder.getUserIdAsLong());
            UserContextHolder.runWithUser(300L, (Runnable) () -> assertEquals(300L, UserContextHolder.getUserIdAsLong()));
            assertEquals(200L, UserContextHolder.getUserIdAsLong());
            return UserContextHolder.getUserIdAsLong();
        });
        assertEquals(200L, innerResult);
        assertEquals(100L, UserContextHolder.getUserIdAsLong());
    }

    @Test
    @DisplayName("验证栈深防护与 capture / capturePrevious / restore 快照镜像对称与惰性清理")
    void shouldPreserveStackDepthAndSupportSnapshotsWithoutThreadLocalLeak() {
        // 1. 只读查询绝不分配或驻留 ThreadLocal 栈
        AtomicReference<String> spiUser = new AtomicReference<>("spi-live-1");
        try {
            UserContextHolder.setUserProvider(spiUser::get);
            assertEquals("spi-live-1", UserContextHolder.get());
            assertNull(UserContextHolder.getExplicit());

            // 触发 capture / capturePrevious 只读探测，不应固化 SPI 到当前线程
            UserContextHolder.UserSnapshot spiSnap = UserContextHolder.capture();
            assertEquals("spi-live-1", spiSnap.getUserId());
            UserContextHolder.UserSnapshot emptyPrev = UserContextHolder.capturePrevious();
            assertNull(emptyPrev.getUserId());
            assertNull(UserContextHolder.getExplicit());
            assertFalse(UserContextHolder.hasActiveThreadLocal());

            // SPI 动态变更可即时感知
            spiUser.set("spi-live-2");
            assertEquals("spi-live-2", UserContextHolder.get());

            // 恢复 SPI 快照后，显式持有 "spi-live-1"
            UserContextHolder.restore(spiSnap);
            assertEquals("spi-live-1", UserContextHolder.getExplicit());
            assertTrue(UserContextHolder.hasActiveThreadLocal());

            // 恢复空快照或 null 触发清理，释放 ThreadLocal
            UserContextHolder.restore(emptyPrev);
            assertNull(UserContextHolder.getExplicit());
            assertFalse(UserContextHolder.hasActiveThreadLocal());

            // 2. 嵌套闭包内 set(null) 不破坏外层栈深
            UserContextHolder.runWithUser("outer-user", () -> {
                UserContextHolder.runWithUser("inner-user", () -> {
                    UserContextHolder.set((String) null);
                    assertNull(UserContextHolder.getExplicit());
                });
                assertEquals("outer-user", UserContextHolder.getExplicit());
            });
            assertFalse(UserContextHolder.hasActiveThreadLocal());

            // 3. 根层级 set(null) 自动清理 ThreadLocal
            UserContextHolder.set("root-user");
            assertTrue(UserContextHolder.hasActiveThreadLocal());
            UserContextHolder.set((String) null);
            assertFalse(UserContextHolder.hasActiveThreadLocal());

            // 4. 闭包内调用 clear() 或 restore() 仍能严格还原外层或清理根级现场
            UserContextHolder.set("other-user");
            UserContextHolder.UserSnapshot otherSnap = UserContextHolder.capture();
            UserContextHolder.clear();

            UserContextHolder.runWithUser("temp-root", () -> {
                UserContextHolder.restore(otherSnap);
                assertEquals("other-user", UserContextHolder.getExplicit());
            });
            assertNull(UserContextHolder.getExplicit());
            assertFalse(UserContextHolder.hasActiveThreadLocal());

            UserContextHolder.set("outer-user");
            UserContextHolder.runWithUser("inner-clear", () -> {
                UserContextHolder.clear();
                assertNull(UserContextHolder.getExplicit());
            });
            assertEquals("outer-user", UserContextHolder.getExplicit());

            UserContextHolder.runWithUser("inner-restore", () -> {
                UserContextHolder.restore(otherSnap);
                assertEquals("other-user", UserContextHolder.getExplicit());
            });
            assertEquals("outer-user", UserContextHolder.getExplicit());
        } finally {
            UserContextHolder.setUserProvider(null);
            UserContextHolder.clear();
        }
    }
}
