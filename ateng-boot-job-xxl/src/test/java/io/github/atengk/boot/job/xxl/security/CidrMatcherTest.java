package io.github.atengk.boot.job.xxl.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CIDR 白名单匹配器单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class CidrMatcherTest {

    @Test
    @DisplayName("测试当白名单为空时默认放行所有 IP")
    void testEmptyWhitelistAllowsAll() {
        CidrMatcher matcher = new CidrMatcher(Collections.emptyList());
        assertTrue(matcher.isAllowed("192.168.1.1"));
        assertTrue(matcher.isAllowed("10.0.0.5"));
        assertTrue(matcher.isAllowed("127.0.0.1"));
        assertTrue(matcher.isAllowed("::1"));

        CidrMatcher nullMatcher = new CidrMatcher(null);
        assertTrue(nullMatcher.isAllowed("172.16.0.1"));
    }

    @Test
    @DisplayName("测试单 IP 精准匹配")
    void testExactSingleIpMatch() {
        CidrMatcher matcher = new CidrMatcher(List.of("127.0.0.1", "192.168.1.50"));
        assertTrue(matcher.isAllowed("127.0.0.1"));
        assertTrue(matcher.isAllowed("192.168.1.50"));

        assertFalse(matcher.isAllowed("192.168.1.51"));
        assertFalse(matcher.isAllowed("10.0.0.1"));
    }

    @Test
    @DisplayName("测试标准 CIDR 网段掩码匹配 (如 /24, /16, /8)")
    void testCidrRangeMatch() {
        CidrMatcher matcher = new CidrMatcher(List.of("192.168.1.0/24", "10.0.0.0/8"));

        // 192.168.1.0/24 内
        assertTrue(matcher.isAllowed("192.168.1.1"));
        assertTrue(matcher.isAllowed("192.168.1.254"));

        // 192.168.1.0/24 外
        assertFalse(matcher.isAllowed("192.168.2.1"));

        // 10.0.0.0/8 内
        assertTrue(matcher.isAllowed("10.12.34.56"));
        assertTrue(matcher.isAllowed("10.244.0.1"));

        // 10.0.0.0/8 外
        assertFalse(matcher.isAllowed("11.0.0.1"));
    }

    @Test
    @DisplayName("测试 IPv6 回环地址与 127.0.0.1 兼容映射")
    void testIpv6LoopbackCompatibility() {
        CidrMatcher matcher = new CidrMatcher(List.of("127.0.0.1"));
        assertTrue(matcher.isAllowed("::1"));
        assertTrue(matcher.isAllowed("0:0:0:0:0:0:0:1"));
    }

    @Test
    @DisplayName("测试非法 IP 格式防卫不发生异常并安全拦截")
    void testInvalidIpSafeRejection() {
        CidrMatcher matcher = new CidrMatcher(List.of("192.168.1.0/24"));
        assertFalse(matcher.isAllowed(null));
        assertFalse(matcher.isAllowed(""));
        assertFalse(matcher.isAllowed("invalid-ip-string"));
        assertFalse(matcher.isAllowed("999.999.999.999"));
    }

}
