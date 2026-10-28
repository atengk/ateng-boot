package io.github.atengk.boot.datasource.readwrite;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.datasource.enums.ReadAfterWriteScope;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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
}
