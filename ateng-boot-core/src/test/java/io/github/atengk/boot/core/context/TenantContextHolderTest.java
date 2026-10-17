package io.github.atengk.boot.core.context;

import io.github.atengk.boot.core.spi.CurrentTenantProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 租户上下文持有者单元测试
 * 验证租户 ID 读写、类型转换、SPI 回退机制、编程式闭包状态隔离与嵌套恢复、忽略状态栈深度、快照捕获还原以及多线程隔离
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("TenantContextHolder 租户上下文单元测试")
class TenantContextHolderTest {

    @BeforeEach
    @AfterEach
    void clear() {
        TenantContextHolder.clear();
        TenantContextHolder.setTenantProvider(null);
    }

    @Test
    @DisplayName("1. 基础读写：设置字符串与数字租户 ID，正确读取字符串与 Long 形式")
    void shouldSetAndGetTenantId() {
        TenantContextHolder.setTenantId("10086");
        assertEquals("10086", TenantContextHolder.getTenantId());
        assertEquals(10086L, TenantContextHolder.getTenantIdAsLong());

        TenantContextHolder.setTenantId(20088L);
        assertEquals("20088", TenantContextHolder.getTenantId());
        assertEquals(20088L, TenantContextHolder.getTenantIdAsLong());

        TenantContextHolder.setTenantId("tenant_corp_abc");
        assertEquals("tenant_corp_abc", TenantContextHolder.getTenantId());
        assertNull(TenantContextHolder.getTenantIdAsLong());

        TenantContextHolder.clear();
        assertNull(TenantContextHolder.getTenantId());
        assertNull(TenantContextHolder.getTenantIdAsLong());
    }

    @Test
    @DisplayName("2. SPI 回退机制：当本地栈无租户时回退至 CurrentTenantProvider，栈有租户时栈优先")
    void shouldSupportCurrentTenantProviderSpi() {
        CurrentTenantProvider mockProvider = () -> "spi_tenant_999";
        TenantContextHolder.setTenantProvider(mockProvider);

        assertSame(mockProvider, TenantContextHolder.getTenantProvider());
        // 本地栈为空，自动回退到 SPI
        assertEquals("spi_tenant_999", TenantContextHolder.getTenantId());

        // 本地栈显式设置后，以本地栈为最高优先级
        TenantContextHolder.setTenantId("local_tenant_111");
        assertEquals("local_tenant_111", TenantContextHolder.getTenantId());

        // 清理本地栈后，恢复回退到 SPI
        TenantContextHolder.clear();
        assertEquals("spi_tenant_999", TenantContextHolder.getTenantId());
    }

    @Test
    @DisplayName("3. 编程式闭包 runWithTenant：临时切换租户并在退出后自动安全还原")
    void shouldRunWithTenantAndRestoreContext() {
        TenantContextHolder.setTenantId("original_tenant");

        String result = TenantContextHolder.runWithTenant("temporary_tenant", () -> {
            assertEquals("temporary_tenant", TenantContextHolder.getTenantId());
            return "exec_success";
        });

        assertEquals("exec_success", result);
        // 验证执行完毕后严格恢复原上下文租户
        assertEquals("original_tenant", TenantContextHolder.getTenantId());
    }

    @Test
    @DisplayName("4. 嵌套闭包 runWithTenant：多层嵌套切换租户均能精准按栈还原")
    void shouldSupportNestedRunWithTenant() {
        TenantContextHolder.setTenantId("level_0");

        TenantContextHolder.runWithTenant("level_1", () -> {
            assertEquals("level_1", TenantContextHolder.getTenantId());

            TenantContextHolder.runWithTenant("level_2", () -> {
                assertEquals("level_2", TenantContextHolder.getTenantId());
            });

            assertEquals("level_1", TenantContextHolder.getTenantId());
        });

        assertEquals("level_0", TenantContextHolder.getTenantId());
    }

    @Test
    @DisplayName("5. 忽略租户闭包 runWithoutTenant：临时忽略租户过滤且退出后恢复状态")
    void shouldRunWithoutTenantAndRestoreContext() {
        assertFalse(TenantContextHolder.isIgnoreTenant());

        TenantContextHolder.setTenantId("tenant_active");

        TenantContextHolder.runWithoutTenant(() -> {
            assertTrue(TenantContextHolder.isIgnoreTenant());
            // 在忽略闭包内租户 ID 依然保留供日志打印或必要时使用
            assertEquals("tenant_active", TenantContextHolder.getTenantId());
        });

        assertFalse(TenantContextHolder.isIgnoreTenant());
        assertEquals("tenant_active", TenantContextHolder.getTenantId());
    }

    @Test
    @DisplayName("6. 嵌套忽略租户闭包 runWithoutTenant：支持多层嵌套计数器安全递减")
    void shouldSupportNestedRunWithoutTenant() {
        assertFalse(TenantContextHolder.isIgnoreTenant());

        TenantContextHolder.runWithoutTenant(() -> {
            assertTrue(TenantContextHolder.isIgnoreTenant());

            TenantContextHolder.runWithoutTenant(() -> {
                assertTrue(TenantContextHolder.isIgnoreTenant());
            });

            assertTrue(TenantContextHolder.isIgnoreTenant());
        });

        assertFalse(TenantContextHolder.isIgnoreTenant());
    }

    @Test
    @DisplayName("7. 多租户快照 capture 与 restore：捕获现场并在恢复后不污染原始线程")
    void shouldCaptureAndRestoreTenantSnapshot() {
        TenantContextHolder.setTenantId("snapshot_tenant_1");
        TenantContextHolder.runWithoutTenant(() -> {
            // 在忽略状态下抓取快照
            TenantContextHolder.TenantSnapshot snapshot = TenantContextHolder.capture();
            assertEquals("snapshot_tenant_1", snapshot.getTenantId());
            assertTrue(snapshot.isIgnoreTenant());

            // 模拟在另一个线程或上下文中还原
            TenantContextHolder.clear();
            assertNull(TenantContextHolder.getTenantId());
            assertFalse(TenantContextHolder.isIgnoreTenant());

            TenantContextHolder.restore(snapshot);
            assertEquals("snapshot_tenant_1", TenantContextHolder.getTenantId());
            assertTrue(TenantContextHolder.isIgnoreTenant());
        });
    }

    @Test
    @DisplayName("8. 多线程隔离：不同线程拥有独立的租户上下文，互不干扰")
    void shouldIsolateTenantContextAcrossThreads() throws ExecutionException, InterruptedException {
        TenantContextHolder.setTenantId("main_thread_tenant");

        AtomicReference<String> asyncTenant = new AtomicReference<>();
        CompletableFuture.runAsync(() -> {
            asyncTenant.set(TenantContextHolder.getTenantId());
            TenantContextHolder.setTenantId("async_thread_tenant");
        }).get();

        // 异步子线程在未显式传递时默认无租户
        assertNull(asyncTenant.get());
        // 主线程租户保持不变
        assertEquals("main_thread_tenant", TenantContextHolder.getTenantId());
    }
}
