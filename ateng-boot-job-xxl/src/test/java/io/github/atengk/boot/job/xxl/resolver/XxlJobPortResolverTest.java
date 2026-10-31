package io.github.atengk.boot.job.xxl.resolver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * XXL-Job 执行器端口自愈解析器单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class XxlJobPortResolverTest {

    @Test
    @DisplayName("测试当端口可用时直接返回原配置端口")
    void testResolvePortWhenAvailable() throws IOException {
        // 先获取一个随机临时空闲端口
        int availablePort;
        try (ServerSocket ss = new ServerSocket(0)) {
            availablePort = ss.getLocalPort();
        }

        int resolved = XxlJobPortResolver.resolvePort(availablePort, 10);
        assertEquals(availablePort, resolved);
    }

    @Test
    @DisplayName("测试配置端口小于等于0时自动分配随机空闲端口")
    void testResolvePortWhenRandom() {
        int resolvedNegative = XxlJobPortResolver.resolvePort(-1, 10);
        assertTrue(resolvedNegative > 0);

        int resolvedZero = XxlJobPortResolver.resolvePort(0, 10);
        assertTrue(resolvedZero > 0);
    }

    @Test
    @DisplayName("测试当原端口被占用时自动顺延至可用端口")
    void testResolvePortWhenOccupied() throws IOException {
        // 先占用一个端口
        try (ServerSocket occupiedSocket = new ServerSocket(0)) {
            int occupiedPort = occupiedSocket.getLocalPort();

            // 预期它能顺延探测到 occupiedPort + 1 (若可用) 或更大端口
            int resolved = XxlJobPortResolver.resolvePort(occupiedPort, 10);
            assertTrue(resolved > occupiedPort, "顺延后的端口应当大于被占用的初始端口");
            assertTrue(XxlJobPortResolver.isPortAvailable(resolved), "顺延后的端口必须真实可用");
        }
    }

    @Test
    @DisplayName("测试超出最大重试步长时抛出合法异常")
    void testResolvePortExceedMaxRetry() throws IOException {
        // 占用两个连续端口
        try (ServerSocket s1 = new ServerSocket(0)) {
            int p1 = s1.getLocalPort();
            // 如果仅允许重试 0 次，且当前端口已被占用，则必然抛出 IllegalStateException
            assertThrows(IllegalStateException.class, () -> XxlJobPortResolver.resolvePort(p1, 0));
        }
    }

}
