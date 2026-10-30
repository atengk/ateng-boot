package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CidrMatcher 原生 CIDR 掩码解析与 IP 匹配器单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("CidrMatcher 原生 CIDR 掩码解析与 IP 匹配器单元测试")
class CidrMatcherTest {

    @ParameterizedTest(name = "IPv4 匹配: clientIp={0}, rule={1} -> expected={2}")
    @CsvSource({
            "127.0.0.1, 127.0.0.1, true",
            "127.0.0.1, 127.0.0.2, false",
            "192.168.1.50, 192.168.1.0/24, true",
            "192.168.2.1, 192.168.1.0/24, false",
            "10.254.1.2, 10.0.0.0/8, true",
            "11.0.0.1, 10.0.0.0/8, false",
            "172.16.5.9, 172.16.0.0/12, true",
            "172.31.255.254, 172.16.0.0/12, true",
            "172.32.0.1, 172.16.0.0/12, false",
            "1.2.3.4, 0.0.0.0/0, true"
    })
    void testIpv4Matching(String clientIp, String rule, boolean expected) {
        assertThat(CidrMatcher.matches(clientIp, rule)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "IPv6 匹配: clientIp={0}, rule={1} -> expected={2}")
    @CsvSource({
            "::1, ::1, true",
            "::1, 127.0.0.1, true",
            "127.0.0.1, ::1, true",
            "fe80::1, fe80::/10, true",
            "2001:db8:85a3::8a2e:370:7334, 2001:db8::/32, true",
            "2001:db9::1, 2001:db8::/32, false",
            "::1, ::/0, true"
    })
    void testIpv6Matching(String clientIp, String rule, boolean expected) {
        assertThat(CidrMatcher.matches(clientIp, rule)).isEqualTo(expected);
    }

    @Test
    @DisplayName("IPv6 作用域标识 (Scope ID) 自动安全剥离")
    void testIpv6ScopeStripping() {
        assertThat(CidrMatcher.matches("fe80::1%eth0", "fe80::/10")).isTrue();
        assertThat(CidrMatcher.matches("fe80::1%12", "fe80::1")).isTrue();
        assertThat(CidrMatcher.matches("::1%0", "::1")).isTrue();
    }

    @Test
    @DisplayName("客户端 IP 端口号剥离与清洗测试 (stripPort)")
    void testStripPort() {
        assertThat(CidrMatcher.stripPort("192.168.1.100:8080")).isEqualTo("192.168.1.100");
        assertThat(CidrMatcher.stripPort("10.0.0.1:443")).isEqualTo("10.0.0.1");
        assertThat(CidrMatcher.stripPort("[fe80::1]:8080")).isEqualTo("fe80::1");
        assertThat(CidrMatcher.stripPort("[::1]")).isEqualTo("::1");
        assertThat(CidrMatcher.stripPort("127.0.0.1")).isEqualTo("127.0.0.1");
        assertThat(CidrMatcher.stripPort("fe80::1")).isEqualTo("fe80::1");
        assertThat(CidrMatcher.stripPort(null)).isNull();
        assertThat(CidrMatcher.stripPort("")).isEqualTo("");

        // 验证 matches 直接支持带端口匹配
        assertThat(CidrMatcher.matches("192.168.1.50:8080", "192.168.1.0/24")).isTrue();
        assertThat(CidrMatcher.matches("[fe80::1]:9090", "fe80::/10")).isTrue();
    }

    @ParameterizedTest(name = "非法输入防 DNS 悬挂防御: clientIp={0}, rule={1}")
    @CsvSource({
            "localhost, 127.0.0.1",
            "example.com, 10.0.0.0/8",
            "192.168.1.256, 192.168.1.0/24",
            "192.168.1.1, 192.168.1.0/33",
            "192.168.1.1, 192.168.1.0/-1",
            "'', 10.0.0.0/8"
    })
    void testInvalidInputDoesNotThrowOrHang(String clientIp, String rule) {
        assertThat(CidrMatcher.matches(clientIp, rule)).isFalse();
    }

    @Test
    @DisplayName("多规则集合 matchesAny 与实例方法匹配")
    void testMatchesAnyAndInstanceMethod() {
        List<String> rules = List.of("192.168.1.0/24", "10.0.0.0/8", "::1");
        CidrMatcher matcher = new CidrMatcher(rules);

        assertThat(matcher.matches("192.168.1.88")).isTrue();
        assertThat(matcher.matches("10.5.6.7")).isTrue();
        assertThat(matcher.matches("127.0.0.1")).isTrue();
        assertThat(matcher.matches("172.16.1.1")).isFalse();

        assertThat(CidrMatcher.matchesAny("10.1.2.3", "192.168.0.0/16", "10.0.0.0/8")).isTrue();
        assertThat(CidrMatcher.matchesAny("172.16.0.1", "192.168.0.0/16", "10.0.0.0/8")).isFalse();
    }
}
