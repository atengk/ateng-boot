package io.github.atengk.boot.mybatisplus.tenant;

import io.github.atengk.boot.mybatisplus.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户上下文持有者单元测试
 * 验证租户 ID 读写、类型转换、编程式闭包状态隔离与嵌套恢复、忽略状态栈深度以及多线程隔离
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("TenantContextHolder 租户上下文单元测试")
class TenantContextHolderTest {

    @BeforeEach
    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("1. 基础读写：设置字符串与数字租户 ID，正确读取字符串与 Long 形式")
    void shouldSetAndGetTenantId() {
        TenantContextHolder.setTenantId("10086");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("10086");
        assertThat(TenantContextHolder.getTenantIdAsLong()).isEqualTo(10086L);

        TenantContextHolder.setTenantId(20088L);
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("20088");
        assertThat(TenantContextHolder.getTenantIdAsLong()).isEqualTo(20088L);

        TenantContextHolder.setTenantId("tenant_corp_abc");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant_corp_abc");
        assertThat(TenantContextHolder.getTenantIdAsLong()).isNull();

        TenantContextHolder.clear();
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(TenantContextHolder.getTenantIdAsLong()).isNull();
    }

    @Test
    @DisplayName("2. 编程式闭包 runWithTenant：临时切换租户并在退出后自动安全还原")
    void shouldRunWithTenantAndRestoreContext() {
        TenantContextHolder.setTenantId("original_tenant");

        String result = TenantContextHolder.runWithTenant("temporary_tenant", () -> {
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("temporary_tenant");
            return "exec_success";
        });

        assertThat(result).isEqualTo("exec_success");
        // 验证执行完毕后严格恢复原上下文租户
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("original_tenant");
    }

    @Test
    @DisplayName("3. 嵌套闭包 runWithTenant：多层嵌套切换租户均能精准按栈还原")
    void shouldSupportNestedRunWithTenant() {
        TenantContextHolder.setTenantId("level_0");

        TenantContextHolder.runWithTenant("level_1", () -> {
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("level_1");

            TenantContextHolder.runWithTenant("level_2", () -> {
                assertThat(TenantContextHolder.getTenantId()).isEqualTo("level_2");
            });

            assertThat(TenantContextHolder.getTenantId()).isEqualTo("level_1");
        });

        assertThat(TenantContextHolder.getTenantId()).isEqualTo("level_0");
    }

    @Test
    @DisplayName("4. 忽略租户闭包 runWithoutTenant：临时忽略租户过滤且退出后恢复状态")
    void shouldRunWithoutTenantAndRestoreContext() {
        assertThat(TenantContextHolder.isIgnoreTenant()).isFalse();

        TenantContextHolder.setTenantId("tenant_active");

        TenantContextHolder.runWithoutTenant(() -> {
            assertThat(TenantContextHolder.isIgnoreTenant()).isTrue();
            // 在忽略闭包内租户 ID 依然保留供日志打印或必要时使用
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant_active");
        });

        assertThat(TenantContextHolder.isIgnoreTenant()).isFalse();
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant_active");
    }

    @Test
    @DisplayName("5. 多线程隔离：不同线程拥有独立的租户上下文，互不干扰")
    void shouldIsolateTenantContextAcrossThreads() throws ExecutionException, InterruptedException {
        TenantContextHolder.setTenantId("main_thread_tenant");

        AtomicReference<String> asyncTenant = new AtomicReference<>();
        CompletableFuture.runAsync(() -> {
            asyncTenant.set(TenantContextHolder.getTenantId());
            TenantContextHolder.setTenantId("async_thread_tenant");
        }).get();

        // 异步子线程在未显式传递时默认无租户
        assertThat(asyncTenant.get()).isNull();
        // 主线程租户保持不变
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("main_thread_tenant");
    }
}
