package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 网络工具 NetUtil 单元与对抗性测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("网络工具 NetUtil 测试")
class NetUtilTest {

    @AfterEach
    void tearDown() {
        NetUtil.resetConfiguration();
        System.clearProperty(NetUtil.PROP_PREFERRED_IP);
        System.clearProperty(NetUtil.PROP_PREFERRED_NETWORKS);
        System.clearProperty(NetUtil.PROP_IGNORED_INTERFACES);
    }

    @Test
    @DisplayName("获取本机内网 IPv4 与主机名验证及缓存重置")
    void shouldGetLocalIpv4AndHostName() {
        NetUtil.clearLocalIpv4Cache();
        String ipv4 = NetUtil.getLocalIpv4();
        assertThat(ipv4).isNotBlank();
        // 验证基本 IPv4 点分十进制结构
        assertThat(ipv4.split("\\.")).hasSize(4);

        // 再次获取命中缓存
        assertThat(NetUtil.getLocalIpv4()).isEqualTo(ipv4);

        // 清理缓存后重新探测
        NetUtil.clearLocalIpv4Cache();
        assertThat(NetUtil.getLocalIpv4()).isNotBlank();

        String hostName = NetUtil.getLocalHostName();
        assertThat(hostName).isNotBlank();
    }

    @Test
    @DisplayName("虚拟网卡与常见模式黑名单识别验证")
    void shouldCheckVirtualInterfaceSafely() {
        assertThat(NetUtil.isVirtualInterface(null)).isFalse();

        // 模式匹配验证：VirtualBox、VMware、Hyper-V、WSL、Docker、VPN、中文虚拟等
        assertThat(NetUtil.matchPattern(
                "(?i).*(docker|veth|vmnet|virbr|vboxnet|vbox|virtualbox|vmware|hyper-v|wsl|dummy|tailscale|zerotier|wireguard|openvpn|tap|tun|vpn|sangfor|atrust|clash|npcap|虚拟).*",
                "VirtualBox Host-Only Ethernet Adapter")).isTrue();

        assertThat(NetUtil.matchPattern(
                "(?i).*(docker|veth|vmnet|virbr|vboxnet|vbox|virtualbox|vmware|hyper-v|wsl|dummy|tailscale|zerotier|wireguard|openvpn|tap|tun|vpn|sangfor|atrust|clash|npcap|虚拟).*",
                "VMware Virtual Ethernet Adapter for VMnet8")).isTrue();

        assertThat(NetUtil.matchPattern(
                "(?i).*(docker|veth|vmnet|virbr|vboxnet|vbox|virtualbox|vmware|hyper-v|wsl|dummy|tailscale|zerotier|wireguard|openvpn|tap|tun|vpn|sangfor|atrust|clash|npcap|虚拟).*",
                "Sangfor aTrust VNIC")).isTrue();

        assertThat(NetUtil.matchPattern(
                "(?i).*(docker|veth|vmnet|virbr|vboxnet|vbox|virtualbox|vmware|hyper-v|wsl|dummy|tailscale|zerotier|wireguard|openvpn|tap|tun|vpn|sangfor|atrust|clash|npcap|虚拟).*",
                "Microsoft Wi-Fi Direct 虚拟适配器")).isTrue();

        // 真实物理网卡不应匹配黑名单
        assertThat(NetUtil.matchPattern(
                "(?i).*(docker|veth|vmnet|virbr|vboxnet|vbox|virtualbox|vmware|hyper-v|wsl|dummy|tailscale|zerotier|wireguard|openvpn|tap|tun|vpn|sangfor|atrust|clash|npcap|虚拟).*",
                "Realtek RTL8852BE WiFi 6 802.11ax PCIe Adapter")).isFalse();
    }

    @Test
    @DisplayName("CIDR 子网掩码与通配符网段匹配验证")
    void shouldMatchCidrAndWildcardNetworks() {
        // CIDR 匹配
        assertThat(NetUtil.matchCidr("192.168.3.41", "192.168.3.0/24")).isTrue();
        assertThat(NetUtil.matchCidr("192.168.4.41", "192.168.3.0/24")).isFalse();
        assertThat(NetUtil.matchCidr("10.50.2.1", "10.0.0.0/8")).isTrue();
        assertThat(NetUtil.matchCidr("172.16.5.1", "172.16.0.0/12")).isTrue();
        assertThat(NetUtil.matchCidr("172.32.5.1", "172.16.0.0/12")).isFalse();
        assertThat(NetUtil.matchCidr("invalid-ip", "192.168.1.0/24")).isFalse();
        assertThat(NetUtil.matchCidr("192.168.1.1", "invalid-cidr")).isFalse();

        // 通配符匹配
        assertThat(NetUtil.matchesPreferredNetwork("192.168.3.41", "192.168.3.*")).isTrue();
        assertThat(NetUtil.matchesPreferredNetwork("192.168.4.41", "192.168.3.*")).isFalse();
        assertThat(NetUtil.matchesPreferredNetwork("192.168.3.41", "192.168.*")).isTrue();
        assertThat(NetUtil.matchesPreferredNetwork("192.168.3.41", "192.168.3.41")).isTrue();

        // 列表多规则匹配
        List<String> rules = List.of("10.0.0.0/8", "192.168.3.*");
        assertThat(NetUtil.matchesAnyPreferredNetwork("192.168.3.41", rules)).isTrue();
        assertThat(NetUtil.matchesAnyPreferredNetwork("10.1.2.3", rules)).isTrue();
        assertThat(NetUtil.matchesAnyPreferredNetwork("172.16.0.1", rules)).isFalse();
    }

    @Test
    @DisplayName("通配符与正则表达式网卡模式匹配验证")
    void shouldMatchInterfacePatterns() {
        assertThat(NetUtil.matchPattern("VirtualBox.*", "VirtualBox Host-Only Ethernet Adapter")).isTrue();
        assertThat(NetUtil.matchPattern("*VirtualBox*", "VirtualBox Host-Only Ethernet Adapter")).isTrue();
        assertThat(NetUtil.matchPattern(".*VirtualBox.*", "VirtualBox Host-Only Ethernet Adapter")).isTrue();
        assertThat(NetUtil.matchPattern("以太网 2", "以太网 2")).isTrue();
        assertThat(NetUtil.matchPattern("wlan*", "WLAN")).isTrue();
        assertThat(NetUtil.matchPattern("eth*", "docker0")).isFalse();

        // 空防御
        assertThat(NetUtil.matchPattern(null, "text")).isFalse();
        assertThat(NetUtil.matchPattern("pattern", null)).isFalse();
    }

    @Test
    @DisplayName("IPv4 格式合法性校验验证")
    void shouldValidateIpv4Format() {
        assertThat(NetUtil.isValidIpv4("192.168.3.41")).isTrue();
        assertThat(NetUtil.isValidIpv4("10.0.0.1")).isTrue();
        assertThat(NetUtil.isValidIpv4("127.0.0.1")).isTrue();
        assertThat(NetUtil.isValidIpv4("255.255.255.255")).isTrue();

        assertThat(NetUtil.isValidIpv4("256.0.0.1")).isFalse();
        assertThat(NetUtil.isValidIpv4("192.168.1")).isFalse();
        assertThat(NetUtil.isValidIpv4("192.168.1.1.1")).isFalse();
        assertThat(NetUtil.isValidIpv4("abc.def.ghi.jkl")).isFalse();
        assertThat(NetUtil.isValidIpv4("")).isFalse();
        assertThat(NetUtil.isValidIpv4(null)).isFalse();
    }

    @Test
    @DisplayName("显式配置首选固定 IP（逃生舱最高优先级）")
    void shouldHonorPreferredIpConfig() {
        NetUtil.configure("192.168.99.100", null, null);
        assertThat(NetUtil.getEffectivePreferredIp()).isEqualTo("192.168.99.100");
        assertThat(NetUtil.getLocalIpv4()).isEqualTo("192.168.99.100");

        // 重置后恢复
        NetUtil.resetConfiguration();
        assertThat(NetUtil.getEffectivePreferredIp()).isNull();
    }

    @Test
    @DisplayName("通过系统属性配置网络探测规则")
    void shouldHonorSystemPropertiesConfig() {
        System.setProperty(NetUtil.PROP_PREFERRED_IP, "10.88.99.1");
        System.setProperty(NetUtil.PROP_PREFERRED_NETWORKS, "10.88.*, 192.168.1.0/24");
        System.setProperty(NetUtil.PROP_IGNORED_INTERFACES, "VirtualBox.*, Sangfor.*");

        try {
            assertThat(NetUtil.getEffectivePreferredIp()).isEqualTo("10.88.99.1");
            assertThat(NetUtil.getEffectivePreferredNetworks()).containsExactly("10.88.*", "192.168.1.0/24");
            assertThat(NetUtil.getEffectiveIgnoredInterfaces()).containsExactly("VirtualBox.*", "Sangfor.*");
            assertThat(NetUtil.getLocalIpv4()).isEqualTo("10.88.99.1");
        } finally {
            System.clearProperty(NetUtil.PROP_PREFERRED_IP);
            System.clearProperty(NetUtil.PROP_PREFERRED_NETWORKS);
            System.clearProperty(NetUtil.PROP_IGNORED_INTERFACES);
            NetUtil.clearLocalIpv4Cache();
        }
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
            assertThat(NetUtil.isUsableLocalPort(port)).isFalse();

            // 套接字应当成功连通
            assertThat(NetUtil.isReachable("127.0.0.1", port, 1000)).isTrue();
            assertThat(NetUtil.canConnect("127.0.0.1", port, 1000)).isTrue();
        }

        // 服务端关闭后恢复可用
        assertThat(NetUtil.isPortAvailable(port)).isTrue();
        assertThat(NetUtil.isUsableLocalPort(port)).isTrue();

        // 非法入参防御
        assertThat(NetUtil.isReachable(null, port, 1000)).isFalse();
        assertThat(NetUtil.isReachable("   ", port, 1000)).isFalse();
        assertThat(NetUtil.isReachable("127.0.0.1", -1, 1000)).isFalse();
        assertThat(NetUtil.isReachable("127.0.0.1", port, 0)).isFalse();
    }

    @Test
    @DisplayName("受信物理网卡 MAC 提取：空安全保障与排除全部网卡平滑返回空数组")
    void shouldGetLocalMacBytesSafely() {
        byte[] mac = NetUtil.getLocalMacBytes();
        assertThat(mac).isNotNull();

        NetUtil.configure(null, null, List.of("*"));
        byte[] ignoredMac = NetUtil.getLocalMacBytes();
        assertThat(ignoredMac).isNotNull().isEmpty();
    }
}
