package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 网络工具 NetUtil 单测
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("网络工具 NetUtil 测试")
class NetUtilTest {

    @Test
    @DisplayName("获取本机内网 IPv4 与主机名验证")
    void shouldGetLocalIpv4AndHostName() {
        String ipv4 = NetUtil.getLocalIpv4();
        assertThat(ipv4).isNotBlank();
        // 验证基本 IPv4 点分十进制结构
        assertThat(ipv4.split("\\.")).hasSize(4);

        String hostName = NetUtil.getLocalHostName();
        assertThat(hostName).isNotBlank();
    }

    @Test
    @DisplayName("端口空闲检测与动态可用端口搜寻验证")
    void shouldDetectAndFindAvailablePort() {
        int availablePort = NetUtil.findAvailablePort();
        assertThat(availablePort).isBetween(NetUtil.MIN_PORT, NetUtil.MAX_PORT);
        assertThat(NetUtil.isPortAvailable(availablePort)).isTrue();

        int rangePort = NetUtil.findAvailablePort(20000, 25000);
        assertThat(rangePort).isBetween(20000, 25000);
        assertThat(NetUtil.isPortAvailable(rangePort)).isTrue();

        // 端口范围校验
        assertThat(NetUtil.isPortAvailable(-1)).isFalse();
        assertThat(NetUtil.isPortAvailable(70000)).isFalse();

        assertThatThrownBy(() -> NetUtil.findAvailablePort(0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NetUtil.findAvailablePort(5000, 4000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("本地套接字连通性探针 isReachable 真实与离线自洽验证")
    void shouldCheckReachabilityAccurately() throws Exception {
        int port = NetUtil.findAvailablePort();

        // 端口未被监听时，探测结果为不可达
        assertThat(NetUtil.isReachable("127.0.0.1", port, 200)).isFalse();

        // 启动本地监听
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            // 当前端口已被占用
            assertThat(NetUtil.isPortAvailable(port)).isFalse();

            // 套接字应当成功连通
            assertThat(NetUtil.isReachable("127.0.0.1", port, 1000)).isTrue();
        }

        // 服务端关闭后恢复可用
        assertThat(NetUtil.isPortAvailable(port)).isTrue();

        // 非法入参防御
        assertThat(NetUtil.isReachable(null, port, 1000)).isFalse();
        assertThat(NetUtil.isReachable("   ", port, 1000)).isFalse();
        assertThat(NetUtil.isReachable("127.0.0.1", -1, 1000)).isFalse();
        assertThat(NetUtil.isReachable("127.0.0.1", port, 0)).isFalse();
    }
}
