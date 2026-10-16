package io.github.atengk.boot.mybatisplus.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 细粒度数据权限上下文持有者单元测试
 * 验证忽略标志与超管标志读写、编程式闭包状态嵌套还原以及多线程隔离
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("DataPermissionContextHolder 数据权限上下文单元测试")
class DataPermissionContextHolderTest {

    @BeforeEach
    @AfterEach
    void clear() {
        DataPermissionContextHolder.clear();
    }

    @Test
    @DisplayName("1. 基础状态读写：设置与重置 ignore 和 superAdmin 状态")
    void shouldSetAndGetFlags() {
        assertThat(DataPermissionContextHolder.isIgnore()).isFalse();
        assertThat(DataPermissionContextHolder.isSuperAdmin()).isFalse();

        DataPermissionContextHolder.setIgnore(true);
        assertThat(DataPermissionContextHolder.isIgnore()).isTrue();

        DataPermissionContextHolder.setSuperAdmin(true);
        assertThat(DataPermissionContextHolder.isSuperAdmin()).isTrue();

        DataPermissionContextHolder.setIgnore(false);
        assertThat(DataPermissionContextHolder.isIgnore()).isFalse();

        DataPermissionContextHolder.setSuperAdmin(false);
        assertThat(DataPermissionContextHolder.isSuperAdmin()).isFalse();
    }

    @Test
    @DisplayName("2. 编程式闭包 runWithoutPermission：临时忽略数据权限并在退出后自动安全还原")
    void shouldRunWithoutPermissionAndRestoreContext() {
        assertThat(DataPermissionContextHolder.isIgnore()).isFalse();

        String result = DataPermissionContextHolder.runWithoutPermission(() -> {
            assertThat(DataPermissionContextHolder.isIgnore()).isTrue();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(DataPermissionContextHolder.isIgnore()).isFalse();
    }

    @Test
    @DisplayName("3. 嵌套闭包 runWithoutPermission：多层嵌套调用能精准恢复前序状态")
    void shouldSupportNestedRunWithoutPermission() {
        assertThat(DataPermissionContextHolder.isIgnore()).isFalse();

        DataPermissionContextHolder.runWithoutPermission(() -> {
            assertThat(DataPermissionContextHolder.isIgnore()).isTrue();

            DataPermissionContextHolder.runWithoutPermission(() -> {
                assertThat(DataPermissionContextHolder.isIgnore()).isTrue();
            });

            assertThat(DataPermissionContextHolder.isIgnore()).isTrue();
        });

        assertThat(DataPermissionContextHolder.isIgnore()).isFalse();
    }

    @Test
    @DisplayName("4. 编程式闭包 runAsSuperAdmin：临时获得超管身份并在退出后安全还原")
    void shouldRunAsSuperAdminAndRestoreContext() {
        assertThat(DataPermissionContextHolder.isSuperAdmin()).isFalse();

        DataPermissionContextHolder.runAsSuperAdmin(() -> {
            assertThat(DataPermissionContextHolder.isSuperAdmin()).isTrue();
        });

        assertThat(DataPermissionContextHolder.isSuperAdmin()).isFalse();
    }

    @Test
    @DisplayName("5. 多线程隔离：子线程上下文与父线程完全独立互不污染")
    void shouldMaintainThreadIsolation() throws ExecutionException, InterruptedException {
        DataPermissionContextHolder.setSuperAdmin(true);
        DataPermissionContextHolder.setIgnore(true);

        AtomicBoolean childSuperAdmin = new AtomicBoolean(true);
        AtomicBoolean childIgnore = new AtomicBoolean(true);

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            childSuperAdmin.set(DataPermissionContextHolder.isSuperAdmin());
            childIgnore.set(DataPermissionContextHolder.isIgnore());
        });

        future.get();

        assertThat(childSuperAdmin.get()).isFalse();
        assertThat(childIgnore.get()).isFalse();
        assertThat(DataPermissionContextHolder.isSuperAdmin()).isTrue();
        assertThat(DataPermissionContextHolder.isIgnore()).isTrue();
    }
}
