package io.github.atengk.boot.http.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HttpRequestContextHolder 单元测试
 * 验证单次请求读取与建联超时的线程上下文绑定、获取、独立性与现场清理
 *
 * @author Ateng
 * @since 2026-10-29
 */
class HttpRequestContextHolderTest {

    @AfterEach
    void tearDown() {
        HttpRequestContextHolder.clear();
    }

    @Test
    @DisplayName("测试读取与建联超时的设置、获取与清除")
    void testSetGetAndClear() {
        assertNull(HttpRequestContextHolder.getReadTimeout());
        assertNull(HttpRequestContextHolder.getConnectTimeout());

        Duration readTimeout = Duration.ofSeconds(5);
        Duration connectTimeout = Duration.ofSeconds(2);

        HttpRequestContextHolder.setReadTimeout(readTimeout);
        HttpRequestContextHolder.setConnectTimeout(connectTimeout);

        assertEquals(readTimeout, HttpRequestContextHolder.getReadTimeout());
        assertEquals(connectTimeout, HttpRequestContextHolder.getConnectTimeout());

        HttpRequestContextHolder.clear();
        assertNull(HttpRequestContextHolder.getReadTimeout());
        assertNull(HttpRequestContextHolder.getConnectTimeout());
    }

    @Test
    @DisplayName("测试传入 null 时移除对应的 ThreadLocal")
    void testSetNullRemovesHolder() {
        HttpRequestContextHolder.setReadTimeout(Duration.ofSeconds(3));
        HttpRequestContextHolder.setConnectTimeout(Duration.ofSeconds(1));

        HttpRequestContextHolder.setReadTimeout(null);
        assertNull(HttpRequestContextHolder.getReadTimeout());
        assertNotNull(HttpRequestContextHolder.getConnectTimeout());

        HttpRequestContextHolder.setConnectTimeout(null);
        assertNull(HttpRequestContextHolder.getConnectTimeout());
    }

    @Test
    @DisplayName("测试多线程环境下的上下文隔离性")
    void testThreadLocalIsolation() throws Exception {
        HttpRequestContextHolder.setReadTimeout(Duration.ofSeconds(10));
        HttpRequestContextHolder.setConnectTimeout(Duration.ofSeconds(4));

        Thread childThread = new Thread(() -> {
            assertNull(HttpRequestContextHolder.getReadTimeout(), "子线程应具有独立的 ThreadLocal 上下文");
            assertNull(HttpRequestContextHolder.getConnectTimeout(), "子线程应具有独立的 ThreadLocal 上下文");
            HttpRequestContextHolder.setReadTimeout(Duration.ofSeconds(1));
            HttpRequestContextHolder.setConnectTimeout(Duration.ofMillis(500));
            assertEquals(Duration.ofSeconds(1), HttpRequestContextHolder.getReadTimeout());
            assertEquals(Duration.ofMillis(500), HttpRequestContextHolder.getConnectTimeout());
            HttpRequestContextHolder.clear();
        });

        childThread.start();
        childThread.join();

        assertEquals(Duration.ofSeconds(10), HttpRequestContextHolder.getReadTimeout());
        assertEquals(Duration.ofSeconds(4), HttpRequestContextHolder.getConnectTimeout());
    }
}
