package io.github.atengk.boot.job.xxl.detector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 容器与网卡 IP 智能嗅探器单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class ContainerIpDetectorTest {

    @Test
    @DisplayName("测试当显式配置了 IP 时直接优先使用配置 IP")
    void testConfiguredIpPriority() {
        String configured = "192.168.10.100";
        String detected = ContainerIpDetector.detectIp(configured, env -> "10.244.0.5");
        assertEquals("192.168.10.100", detected);
    }

    @Test
    @DisplayName("测试优先嗅探 Kubernetes POD_IP 环境变量")
    void testPodIpPriority() {
        Map<String, String> env = Map.of(
                "POD_IP", "10.244.1.20",
                "HOST_IP", "192.168.1.50"
        );
        String detected = ContainerIpDetector.detectIp(null, env::get);
        assertEquals("10.244.1.20", detected);
    }

    @Test
    @DisplayName("测试当 POD_IP 为空时降级选用 HOST_IP 环境变量")
    void testHostIpFallback() {
        Map<String, String> env = Map.of(
                "HOST_IP", "192.168.1.50"
        );
        String detected = ContainerIpDetector.detectIp("", env::get);
        assertEquals("192.168.1.50", detected);
    }

    @Test
    @DisplayName("测试忽略无效的回环或空白 POD_IP 环境变量")
    void testInvalidPodIpIgnored() {
        Map<String, String> env = Map.of(
                "POD_IP", "127.0.0.1",
                "HOST_IP", "192.168.2.88"
        );
        String detected = ContainerIpDetector.detectIp(null, env::get);
        assertEquals("192.168.2.88", detected);
    }

    @Test
    @DisplayName("测试当无环境变量时通过本机网卡探测到可路由 IPv4 地址")
    void testNetworkInterfaceFallback() {
        String detected = ContainerIpDetector.detectIp(null);
        assertNotNull(detected);
        assertFalse(detected.isBlank());
        assertDoesNotThrow(() -> InetAddress.getByName(detected));
    }

    @Test
    @DisplayName("测试虚拟网卡名称过滤规则")
    void testVirtualInterfaceFiltering() {
        assertTrue(ContainerIpDetector.isVirtualOrContainerInterface("docker0"));
        assertTrue(ContainerIpDetector.isVirtualOrContainerInterface("flannel.1"));
        assertTrue(ContainerIpDetector.isVirtualOrContainerInterface("cni0"));
        assertTrue(ContainerIpDetector.isVirtualOrContainerInterface("veth1234a"));
        assertTrue(ContainerIpDetector.isVirtualOrContainerInterface("virbr0"));
        assertTrue(ContainerIpDetector.isVirtualOrContainerInterface("calico-veth"));
        assertTrue(ContainerIpDetector.isVirtualOrContainerInterface("tun0"));
        assertTrue(ContainerIpDetector.isVirtualOrContainerInterface("tap0"));
        assertTrue(ContainerIpDetector.isVirtualOrContainerInterface("br-abc1234"));

        assertFalse(ContainerIpDetector.isVirtualOrContainerInterface("eth0"));
        assertFalse(ContainerIpDetector.isVirtualOrContainerInterface("ens33"));
        assertFalse(ContainerIpDetector.isVirtualOrContainerInterface("enp0s3"));
        assertFalse(ContainerIpDetector.isVirtualOrContainerInterface("wlan0"));
    }

}
