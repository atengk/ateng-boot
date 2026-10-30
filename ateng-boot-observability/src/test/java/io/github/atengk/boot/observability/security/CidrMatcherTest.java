package io.github.atengk.boot.observability.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CidrMatcher 纯原生 IP 与 CIDR 子网掩码匹配器单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("CidrMatcher 纯原生 IP 与 CIDR 匹配器单元测试")
class CidrMatcherTest {

    @Test
    @DisplayName("IPv4 精确匹配验证")
    void testIpv4ExactMatch() {
        assertThat(CidrMatcher.matches("192.168.1.100", "192.168.1.100")).isTrue();
        assertThat(CidrMatcher.matches("192.168.1.100", "192.168.1.101")).isFalse();
        assertThat(CidrMatcher.matches("10.0.0.1", "10.0.0.1")).isTrue();
        assertThat(CidrMatcher.matches("10.0.0.1", "10.0.0.2")).isFalse();
    }

    @Test
    @DisplayName("IPv4 CIDR 子网掩码范围匹配验证 (/8, /12, /16, /24, /0)")
    void testIpv4CidrMatch() {
        // /8 网段匹配
        assertThat(CidrMatcher.matches("10.0.0.1", "10.0.0.0/8")).isTrue();
        assertThat(CidrMatcher.matches("10.255.255.255", "10.0.0.0/8")).isTrue();
        assertThat(CidrMatcher.matches("11.0.0.1", "10.0.0.0/8")).isFalse();

        // /12 私有网段匹配 (172.16.0.0 - 172.31.255.255)
        assertThat(CidrMatcher.matches("172.16.0.1", "172.16.0.0/12")).isTrue();
        assertThat(CidrMatcher.matches("172.31.255.254", "172.16.0.0/12")).isTrue();
        assertThat(CidrMatcher.matches("172.32.0.1", "172.16.0.0/12")).isFalse();
        assertThat(CidrMatcher.matches("172.15.255.255", "172.16.0.0/12")).isFalse();

        // /16 网段匹配
        assertThat(CidrMatcher.matches("192.168.5.10", "192.168.0.0/16")).isTrue();
        assertThat(CidrMatcher.matches("192.169.1.1", "192.168.0.0/16")).isFalse();

        // /24 网段匹配
        assertThat(CidrMatcher.matches("192.168.1.50", "192.168.1.0/24")).isTrue();
        assertThat(CidrMatcher.matches("192.168.2.50", "192.168.1.0/24")).isFalse();

        // /0 全通掩码
        assertThat(CidrMatcher.matches("1.2.3.4", "0.0.0.0/0")).isTrue();
        assertThat(CidrMatcher.matches("223.255.255.255", "0.0.0.0/0")).isTrue();
    }

    @Test
    @DisplayName("IPv6 精确匹配与 CIDR 掩码匹配验证 (/10, /32, /128, /0)")
    void testIpv6Match() {
        // 精确匹配
        assertThat(CidrMatcher.matches("2001:db8::1", "2001:db8::1")).isTrue();
        assertThat(CidrMatcher.matches("2001:db8::1", "2001:db8::2")).isFalse();

        // /10 本地链路 fe80::/10
        assertThat(CidrMatcher.matches("fe80::1", "fe80::/10")).isTrue();
        assertThat(CidrMatcher.matches("febf::ffff", "fe80::/10")).isTrue();
        assertThat(CidrMatcher.matches("fec0::1", "fe80::/10")).isFalse();

        // /32 网段匹配
        assertThat(CidrMatcher.matches("2001:db8:abcd::1", "2001:db8::/32")).isTrue();
        assertThat(CidrMatcher.matches("2001:db9::1", "2001:db8::/32")).isFalse();

        // /128 单主机
        assertThat(CidrMatcher.matches("::1", "::1/128")).isTrue();

        // /0 全通掩码
        assertThat(CidrMatcher.matches("240e::1", "::/0")).isTrue();
    }

    @Test
    @DisplayName("本地环回地址跨协议等价映射 (127.0.0.1 与 ::1)")
    void testLoopbackCrossProtocolMapping() {
        assertThat(CidrMatcher.matches("127.0.0.1", "::1")).isTrue();
        assertThat(CidrMatcher.matches("::1", "127.0.0.1")).isTrue();
        assertThat(CidrMatcher.matches("127.0.0.1", "127.0.0.1")).isTrue();
        assertThat(CidrMatcher.matches("::1", "::1")).isTrue();
    }

    @Test
    @DisplayName("IPv6 作用域 Scope ID 剥离防御 (fe80::1%eth0, 0:0:0:0:0:0:0:1%0)")
    void testScopeIdStripping() {
        assertThat(CidrMatcher.matches("fe80::1%eth0", "fe80::/10")).isTrue();
        assertThat(CidrMatcher.matches("fe80::1%1", "fe80::1")).isTrue();
        assertThat(CidrMatcher.matches("0:0:0:0:0:0:0:1%0", "127.0.0.1")).isTrue();
    }

    @Test
    @DisplayName("空值、非法格式与非数值主机名防卫（坚决不触发 DNS 解析）")
    void testInvalidAndNonNumericIpSafety() {
        // 空值防卫
        assertThat(CidrMatcher.matches(null, "10.0.0.0/8")).isFalse();
        assertThat(CidrMatcher.matches("10.0.0.1", null)).isFalse();
        assertThat(CidrMatcher.matches("", "10.0.0.0/8")).isFalse();
        assertThat(CidrMatcher.matches("   ", "10.0.0.0/8")).isFalse();
        assertThat(CidrMatcher.matches("10.0.0.1", "")).isFalse();

        // 非数值主机名拦截（确保绝不触发 DNS 查询）
        assertThat(CidrMatcher.matches("localhost", "127.0.0.1")).isFalse();
        assertThat(CidrMatcher.matches("10.0.0.1", "internal.corp.com/24")).isFalse();
        assertThat(CidrMatcher.matches("example.com", "0.0.0.0/0")).isFalse();

        // 越界与非法子网掩码前缀
        assertThat(CidrMatcher.matches("192.168.1.1", "192.168.1.0/33")).isFalse();
        assertThat(CidrMatcher.matches("192.168.1.1", "192.168.1.0/-1")).isFalse();
        assertThat(CidrMatcher.matches("192.168.1.1", "192.168.1.0/abc")).isFalse();
        assertThat(CidrMatcher.matches("::1", "::1/129")).isFalse();

        // 协议不匹配（非环回）
        assertThat(CidrMatcher.matches("192.168.1.1", "2001:db8::/32")).isFalse();
        assertThat(CidrMatcher.matches("2001:db8::1", "192.168.1.0/24")).isFalse();
    }

    @Test
    @DisplayName("规则集合与匹配器实例测试 (matchesAny 与 CidrMatcher 实例)")
    void testCollectionAndInstanceMatching() {
        List<String> whitelist = List.of("10.0.0.0/8", "192.168.1.0/24", "::1");

        // matchesAny 静态集合方法
        assertThat(CidrMatcher.matchesAny("10.1.2.3", whitelist)).isTrue();
        assertThat(CidrMatcher.matchesAny("192.168.1.55", whitelist)).isTrue();
        assertThat(CidrMatcher.matchesAny("127.0.0.1", whitelist)).isTrue();
        assertThat(CidrMatcher.matchesAny("172.16.0.1", whitelist)).isFalse();
        assertThat(CidrMatcher.matchesAny("10.1.2.3", (List<String>) null)).isFalse();
        assertThat(CidrMatcher.matchesAny("10.1.2.3", List.of())).isFalse();

        // matchesAny 静态变长参数方法
        assertThat(CidrMatcher.matchesAny("10.1.2.3", "10.0.0.0/8", "192.168.1.0/24")).isTrue();
        assertThat(CidrMatcher.matchesAny("172.16.1.1", "10.0.0.0/8", "192.168.1.0/24")).isFalse();
        assertThat(CidrMatcher.matchesAny("10.1.2.3", (String[]) null)).isFalse();

        // 实例构造方法
        CidrMatcher matcher = new CidrMatcher(whitelist);
        assertThat(matcher.matches("10.99.1.1")).isTrue();
        assertThat(matcher.matches("172.16.1.1")).isFalse();

        CidrMatcher emptyMatcher = new CidrMatcher(null);
        assertThat(emptyMatcher.matches("10.99.1.1")).isFalse();
    }
}
