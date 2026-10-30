package io.github.atengk.boot.openapi.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenApiCidrMatcher 原生 CIDR 掩码解析与 IP 匹配器单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("OpenApiCidrMatcher 原生 CIDR 掩码解析与 IP 匹配器单元测试")
class OpenApiCidrMatcherTest {

    @ParameterizedTest
    @CsvSource({
            "192.168.1.100, 192.168.1.100, true",
            "192.168.1.100, 192.168.1.101, false",
            "10.0.5.12, 10.0.0.0/8, true",
            "10.255.255.255, 10.0.0.0/8, true",
            "11.0.0.1, 10.0.0.0/8, false",
            "192.168.1.55, 192.168.1.0/24, true",
            "192.168.2.1, 192.168.1.0/24, false",
            "172.16.5.10, 172.16.0.0/12, true",
            "172.31.255.254, 172.16.0.0/12, true",
            "172.32.0.1, 172.16.0.0/12, false",
            "1.2.3.4, 0.0.0.0/0, true"
    })
    @DisplayName("IPv4 精确与 CIDR 网段匹配测试")
    void testIpv4Matching(String clientIp, String rule, boolean expected) {
        assertThat(OpenApiCidrMatcher.matches(clientIp, rule)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "::1, ::1, true",
            "::1, 127.0.0.1, true",
            "127.0.0.1, ::1, true",
            "fe80::1, fe80::/10, true",
            "fe80::abcd:1234, fe80::/10, true",
            "fec0::1, fe80::/10, false",
            "2001:db8::1, 2001:db8::/32, true",
            "2001:db9::1, 2001:db8::/32, false",
            "2001:db8::abcd, ::/0, true"
    })
    @DisplayName("IPv6 精确与 CIDR 网段匹配测试（含本地环回跨协议映射）")
    void testIpv6Matching(String clientIp, String rule, boolean expected) {
        assertThat(OpenApiCidrMatcher.matches(clientIp, rule)).isEqualTo(expected);
    }

    @Test
    @DisplayName("IPv6 作用域标识 (Scope ID) 自动剥离测试")
    void testScopeIdStripping() {
        assertThat(OpenApiCidrMatcher.matches("fe80::1%eth0", "fe80::/10")).isTrue();
        assertThat(OpenApiCidrMatcher.matches("fe80::1%12", "fe80::1")).isTrue();
        assertThat(OpenApiCidrMatcher.matches("::1%0", "::1")).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "'', 192.168.1.0/24",
            "'   ', 192.168.1.0/24",
            "192.168.1.1, ''",
            "192.168.1.1, '   '",
            "invalid-ip, 192.168.1.0/24",
            "192.168.1.1, invalid-rule",
            "192.168.1.1, 192.168.1.0/invalid",
            "192.168.1.1, 192.168.1.0/33",
            "192.168.1.1, 192.168.1.0/-1",
            "fe80::1, fe80::/129",
            "example.com, 10.0.0.0/8"
    })
    @DisplayName("非法 IP 或规则防卫拦截（返回 false，不挂起 DNS 解析）")
    void testInvalidIpOrRuleDefense(String clientIp, String rule) {
        assertThat(OpenApiCidrMatcher.matches(clientIp, rule)).isFalse();
    }

    @Test
    @DisplayName("集合规则匹配 matchesAny 测试")
    void testMatchesAny() {
        List<String> rules = List.of("127.0.0.1", "10.0.0.0/8", "192.168.1.0/24");
        OpenApiCidrMatcher matcher = new OpenApiCidrMatcher(rules);

        assertThat(matcher.matches("127.0.0.1")).isTrue();
        assertThat(matcher.matches("10.5.20.1")).isTrue();
        assertThat(matcher.matches("192.168.1.88")).isTrue();
        assertThat(matcher.matches("172.16.1.1")).isFalse();
        assertThat(matcher.matches("8.8.8.8")).isFalse();
        assertThat(matcher.matches(null)).isFalse();
        assertThat(matcher.matches("")).isFalse();
    }
}
