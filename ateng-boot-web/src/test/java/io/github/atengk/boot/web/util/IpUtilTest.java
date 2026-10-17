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
}
