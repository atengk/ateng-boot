package io.github.atengk.boot.datasource.readwrite;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.datasource.enums.ReadAfterWriteScope;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写后读主防延迟机制 (ReadAfterWrite) 单元测试
 *
 * @author Ateng
 * @since 2026-10-24
 */
class ReadAfterWriteTest {

    private DatasourceProperties.ReadAfterWriteProperties properties;
    private DefaultWriteOccurredResolver resolver;
    private ReadAfterWriteManager manager;

    @BeforeEach
    void setUp() {
        properties = new DatasourceProperties.ReadAfterWriteProperties();
        properties.setEnabled(true);
        properties.setScope(ReadAfterWriteScope.REQUEST);
        properties.setWindowTtl(Duration.ofSeconds(3));

        resolver = new DefaultWriteOccurredResolver();
        manager = new ReadAfterWriteManager(properties, resolver);
    }

    @AfterEach
    void tearDown() {
        ReadAfterWriteContextHolder.clear();
        resolver.clearAll();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("测试 REQUEST 作用域下写操作标记与防延迟判定")
    void testRequestScopeReadAfterWrite() {
        assertThat(manager.isWriteOccurred()).isFalse();

        // 触发写操作标记
        manager.markWriteOccurred();
        assertThat(manager.isWriteOccurred()).isTrue();

        // 清理当前请求上下文
        manager.clear();
        assertThat(manager.isWriteOccurred()).isFalse();
    }

    @Test
    @DisplayName("测试 WINDOW 时间窗口作用域下基于 UserContextHolder 的防延迟与过期清理")
    void testWindowScopeReadAfterWrite() throws InterruptedException {
        properties.setScope(ReadAfterWriteScope.WINDOW);
        properties.setWindowTtl(Duration.ofMillis(300));

        UserContextHolder.setUserId("user_888");
        assertThat(manager.isWriteOccurred()).isFalse();

        // 触发写操作
        manager.markWriteOccurred();
        assertThat(manager.isWriteOccurred()).isTrue();

        // 切换不同用户，不受影响
        UserContextHolder.setUserId("user_999");
        assertThat(manager.isWriteOccurred()).isFalse();

        // 切回原用户，在窗口内仍然有效
        UserContextHolder.setUserId("user_888");
        assertThat(manager.isWriteOccurred()).isTrue();

        // 等待窗口 TTL 过期
        Thread.sleep(350);
        assertThat(manager.isWriteOccurred()).isFalse();
    }

    @Test
    @DisplayName("测试禁用 readAfterWrite 时始终返回 false")
    void testDisabledReadAfterWrite() {
        properties.setEnabled(false);

        manager.markWriteOccurred();
        assertThat(manager.isWriteOccurred()).isFalse();
    }

    @Test
    @DisplayName("测试 WINDOW 模式下相同 userId 在不同租户间写后即读窗口完全隔离")
    void testWindowScope_IsolatesSameUserIdAcrossDifferentTenants() {
        properties.setScope(ReadAfterWriteScope.WINDOW);
        properties.setWindowTtl(Duration.ofSeconds(3));

        // 租户 1001 的 admin 用户触发写操作
        TenantContextHolder.setTenantId("1001");
        UserContextHolder.setUserId("admin");
        manager.markWriteOccurred();
        assertThat(manager.isWriteOccurred()).isTrue();

        // 切换到租户 1002 且同样为 admin 用户：必须隔离不受租户 1001 影响
        TenantContextHolder.setTenantId("1002");
        assertThat(manager.isWriteOccurred()).isFalse();

        // 切换到全局无租户且同样为 admin 用户：必须隔离不受租户 1001 影响
        TenantContextHolder.clear();
        assertThat(manager.isWriteOccurred()).isFalse();

        // 切回租户 1001 的 admin 用户：在窗口期内依然命中
        TenantContextHolder.setTenantId("1001");
        assertThat(manager.isWriteOccurred()).isTrue();
    }

    @Test
    @DisplayName("测试 WINDOW 模式下未登录场景按租户与线程隔离互不串扰")
    void testWindowScope_ThreadFallbackIsolatesAcrossTenantsAndThreads() throws Exception {
        properties.setScope(ReadAfterWriteScope.WINDOW);
        properties.setWindowTtl(Duration.ofSeconds(3));

        TenantContextHolder.setTenantId("1001");
        manager.markWriteOccurred();
        assertThat(manager.isWriteOccurred()).isTrue();

        // 同线程切换租户 1002 隔离
        TenantContextHolder.setTenantId("1002");
        assertThat(manager.isWriteOccurred()).isFalse();

        // 跨线程即使相同租户 1001 也隔离
        Boolean otherThreadActive = CompletableFuture.supplyAsync(() -> {
            TenantContextHolder.setTenantId("1001");
            try {
                return manager.isWriteOccurred();
            } finally {
                TenantContextHolder.clear();
            }
        }).get();
        assertThat(otherThreadActive).isFalse();
    }
}
