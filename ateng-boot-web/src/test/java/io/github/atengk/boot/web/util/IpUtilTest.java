package io.github.atengk.boot.web.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客户端 IP 提取工具单测
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("客户端 IP 提取工具单测")
class IpUtilTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("优先从 X-Forwarded-For 提取多级代理中的首个有效客户端 IP")
    void shouldExtractIpFromXForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "192.0.2.1, 10.0.0.1, 192.168.1.1");
        request.addHeader("X-Real-IP", "198.51.100.1");
        request.setRemoteAddr("10.0.0.1");

        String clientIp = IpUtil.getClientIp(request);
        assertThat(clientIp).isEqualTo("192.0.2.1");
    }

    @Test
    @DisplayName("当 X-Forwarded-For 存在 unknown 时跳过并提取后续有效 IP")
    void shouldSkipUnknownInXForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "unknown, 192.0.2.2, 10.0.0.1");

        String clientIp = IpUtil.getClientIp(request);
        assertThat(clientIp).isEqualTo("192.0.2.2");
    }

    @Test
    @DisplayName("当 X-Forwarded-For 全为 unknown 时安全回退至下一有效头或 RemoteAddr")
    void shouldFallbackWhenXForwardedForHasMultipleUnknown() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "unknown, unknown,   ");
        request.addHeader("X-Real-IP", "198.51.100.2");
        request.setRemoteAddr("10.0.0.1");

        String clientIp = IpUtil.getClientIp(request);
        assertThat(clientIp).isEqualTo("198.51.100.2");
    }

    @Test
    @DisplayName("从 X-Real-IP 或 Proxy-Client-IP 顺序回退提取")
    void shouldFallbackToXRealIpAndProxyHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Real-IP", "198.51.100.3");
        request.setRemoteAddr("127.0.0.1");

        assertThat(IpUtil.getClientIp(request)).isEqualTo("198.51.100.3");

        MockHttpServletRequest proxyRequest = new MockHttpServletRequest();
        proxyRequest.addHeader("Proxy-Client-IP", "203.0.113.1");
        assertThat(IpUtil.getClientIp(proxyRequest)).isEqualTo("203.0.113.1");
    }

    @Test
    @DisplayName("无反向代理请求头时回退至 RemoteAddr")
    void shouldFallbackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.100");

        assertThat(IpUtil.getClientIp(request)).isEqualTo("192.168.1.100");
    }

    @Test
    @DisplayName("IPv6 本地环回地址自动规整转换为标准 IPv4 环回地址 127.0.0.1")
    void shouldNormalizeIpv6Loopback() {
        MockHttpServletRequest request1 = new MockHttpServletRequest();
        request1.setRemoteAddr("0:0:0:0:0:0:0:1");
        assertThat(IpUtil.getClientIp(request1)).isEqualTo("127.0.0.1");

        MockHttpServletRequest request2 = new MockHttpServletRequest();
        request2.setRemoteAddr("::1");
        assertThat(IpUtil.getClientIp(request2)).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("通过 RequestContextHolder 上下文无参提取客户端 IP")
    void shouldExtractFromRequestContextHolder() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Real-IP", "198.51.100.4");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(IpUtil.getClientIp()).isEqualTo("198.51.100.4");
    }

    @Test
    @DisplayName("防御性边界测试：null 请求与非法 IP")
    void shouldHandleNullAndInvalidIpDefensively() {
        assertThat(IpUtil.getClientIp((HttpServletRequest) null)).isEmpty();
        assertThat(IpUtil.getClientIp()).isEmpty();
        assertThat(IpUtil.isValidIp(null)).isFalse();
        assertThat(IpUtil.isValidIp("")).isFalse();
        assertThat(IpUtil.isValidIp("   ")).isFalse();
        assertThat(IpUtil.isValidIp("unknown")).isFalse();
        assertThat(IpUtil.isValidIp("UNKNOWN")).isFalse();
        assertThat(IpUtil.isValidIp("<script>alert(1)</script>")).isFalse();
        assertThat(IpUtil.isValidIp("999.999.999.999")).isFalse();
        assertThat(IpUtil.isValidIp("abc.def.ghi.jkl")).isFalse();
        assertThat(IpUtil.isValidIp("127.0.0.1")).isTrue();
        assertThat(IpUtil.isValidIp("192.168.1.1")).isTrue();
        assertThat(IpUtil.isValidIp("2001:db8::1")).isTrue();
    }

    @Test
    @DisplayName("当 X-Forwarded-For 包含伪造注入字符串时自动跳过并识别后续合法 IP")
    void shouldSkipMaliciousInjectionInXForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "<script>alert(1)</script>, 203.0.113.195, 10.0.0.1");

        String clientIp = IpUtil.getClientIp(request);
        assertThat(clientIp).isEqualTo("203.0.113.195");
    }

    @Test
    @DisplayName("IPv4 单 IP 与 CIDR 子网判定测试")
    void shouldMatchIpv4CidrAndSingleIp() {
        // 单 IP 精确匹配
        assertThat(IpUtil.isInRange("127.0.0.1", "127.0.0.1")).isTrue();
        assertThat(IpUtil.isInRange("127.0.0.1", "127.0.0.1/32")).isTrue();
        assertThat(IpUtil.isInRange("127.0.0.2", "127.0.0.1")).isFalse();

        // /24 子网
        assertThat(IpUtil.isInRange("192.168.1.1", "192.168.1.0/24")).isTrue();
        assertThat(IpUtil.isInRange("192.168.1.254", "192.168.1.0/24")).isTrue();
        assertThat(IpUtil.isInRange("192.168.2.1", "192.168.1.0/24")).isFalse();

        // /8 子网
        assertThat(IpUtil.isInRange("10.254.1.2", "10.0.0.0/8")).isTrue();
        assertThat(IpUtil.isInRange("11.0.0.1", "10.0.0.0/8")).isFalse();

        // /12 子网
        assertThat(IpUtil.isInRange("172.16.50.1", "172.16.0.0/12")).isTrue();
        assertThat(IpUtil.isInRange("172.31.255.254", "172.16.0.0/12")).isTrue();
        assertThat(IpUtil.isInRange("172.32.0.1", "172.16.0.0/12")).isFalse();

        // /25 跨字节边界子网 (192.168.1.128 ~ 192.168.1.255)
        assertThat(IpUtil.isInRange("192.168.1.129", "192.168.1.128/25")).isTrue();
        assertThat(IpUtil.isInRange("192.168.1.200", "192.168.1.128/25")).isTrue();
        assertThat(IpUtil.isInRange("192.168.1.127", "192.168.1.128/25")).isFalse();

        // /0 全通掩码
        assertThat(IpUtil.isInRange("1.2.3.4", "0.0.0.0/0")).isTrue();
        assertThat(IpUtil.isInRange("220.181.38.148", "0.0.0.0/0")).isTrue();
    }

    @Test
    @DisplayName("IPv6 单 IP 与 CIDR 子网判定测试")
    void shouldMatchIpv6CidrAndSingleIp() {
        // 单 IP 与环回
        assertThat(IpUtil.isInRange("::1", "::1")).isTrue();
        assertThat(IpUtil.isInRange("::1", "::1/128")).isTrue();
        assertThat(IpUtil.isInRange("0:0:0:0:0:0:0:1", "::1")).isTrue();
        assertThat(IpUtil.isInRange("::1", "0:0:0:0:0:0:0:1")).isTrue();

        // IPv4 与 IPv6 本地环回等价判定兼容
        assertThat(IpUtil.isInRange("127.0.0.1", "::1")).isTrue();
        assertThat(IpUtil.isInRange("127.0.0.1", "::1/128")).isTrue();
        assertThat(IpUtil.isInRange("::1", "127.0.0.1")).isTrue();
        assertThat(IpUtil.isInRange("::1", "127.0.0.1/32")).isTrue();
        assertThat(IpUtil.isInRange("127.0.0.1", "0:0:0:0:0:0:0:1")).isTrue();

        // /32 子网
        assertThat(IpUtil.isInRange("2001:db8:85a3::8a2e:370:7334", "2001:db8::/32")).isTrue();
        assertThat(IpUtil.isInRange("2001:db9::1", "2001:db8::/32")).isFalse();

        // /64 子网
        assertThat(IpUtil.isInRange("fe80::1", "fe80::/64")).isTrue();
        assertThat(IpUtil.isInRange("fe80::ffff:1", "fe80::/64")).isTrue();
        assertThat(IpUtil.isInRange("fe81::1", "fe80::/64")).isFalse();

        // IPv6 作用域标识安全处理
        assertThat(IpUtil.isInRange("fe80::1%eth0", "fe80::/64")).isTrue();

        // /0 全通掩码
        assertThat(IpUtil.isInRange("2001:db8::1", "::/0")).isTrue();
    }

    @Test
    @DisplayName("多规则集合与可变参数重载判定")
    void shouldMatchMultipleRulesOverloads() {
        java.util.List<String> rules = java.util.List.of("192.168.1.0/24", "10.0.0.0/8", "::1");

        assertThat(IpUtil.isInRange("192.168.1.50", rules)).isTrue();
        assertThat(IpUtil.isInRange("10.1.2.3", rules)).isTrue();
        assertThat(IpUtil.isInRange("::1", rules)).isTrue();
        assertThat(IpUtil.isInRange("172.16.1.1", rules)).isFalse();

        assertThat(IpUtil.isInRange("192.168.1.50", "10.0.0.0/8", "192.168.1.0/24")).isTrue();
        assertThat(IpUtil.isInRange("8.8.8.8", "10.0.0.0/8", "192.168.1.0/24")).isFalse();
    }

    @Test
    @DisplayName("防御性边界对抗测试：非法输入、超限掩码、跨协议族与域名防解析")
    void shouldHandleAdversarialEdgeCasesDefensively() {
        // 空值或空白
        assertThat(IpUtil.isInRange(null, "192.168.1.0/24")).isFalse();
        assertThat(IpUtil.isInRange("192.168.1.1", (String) null)).isFalse();
        assertThat(IpUtil.isInRange("", "192.168.1.0/24")).isFalse();
        assertThat(IpUtil.isInRange("   ", "192.168.1.0/24")).isFalse();
        assertThat(IpUtil.isInRange("192.168.1.1", "")).isFalse();
        assertThat(IpUtil.isInRange("192.168.1.1", (java.util.Collection<String>) null)).isFalse();
        assertThat(IpUtil.isInRange("192.168.1.1", java.util.Collections.emptyList())).isFalse();

        // 域名或非法字符：杜绝网络 DNS 解析
        assertThat(IpUtil.isInRange("localhost", "127.0.0.1")).isFalse();
        assertThat(IpUtil.isInRange("google.com", "10.0.0.0/8")).isFalse();
        assertThat(IpUtil.isInRange("192.168.1.1", "example.com/24")).isFalse();
        assertThat(IpUtil.isInRange("<script>", "10.0.0.0/8")).isFalse();

        // 非法掩码前缀
        assertThat(IpUtil.isInRange("192.168.1.1", "192.168.1.0/33")).isFalse();
        assertThat(IpUtil.isInRange("192.168.1.1", "192.168.1.0/-1")).isFalse();
        assertThat(IpUtil.isInRange("192.168.1.1", "192.168.1.0/abc")).isFalse();
        assertThat(IpUtil.isInRange("::1", "::1/129")).isFalse();

        // 非法 IP 格式或段溢出
        assertThat(IpUtil.isInRange("999.999.999.999", "10.0.0.0/8")).isFalse();
        assertThat(IpUtil.isInRange("192.168.1.1.1", "192.168.1.0/24")).isFalse();

        // IPv4 与 IPv6 跨协议族不匹配
        assertThat(IpUtil.isInRange("192.168.1.1", "2001:db8::/32")).isFalse();
        assertThat(IpUtil.isInRange("::1", "192.168.1.0/24")).isFalse();
        assertThat(IpUtil.isInRange("192.168.1.1", "::/0")).isFalse();
        assertThat(IpUtil.isInRange("::1", "0.0.0.0/0")).isFalse();
    }
}
