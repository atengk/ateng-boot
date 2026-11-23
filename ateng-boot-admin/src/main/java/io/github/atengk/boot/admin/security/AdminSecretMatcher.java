package io.github.atengk.boot.admin.security;

import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 常量时间凭据比对工具，防范基于响应时差的侧信道密钥推算攻击
 *
 * @author Ateng
 * @since 2026-11-21
 */
public final class AdminSecretMatcher {

    private AdminSecretMatcher() {
    }

    /**
     * 以恒定时间语义比对两段明文凭据是否完全一致
     *
     * @param actual   请求携带的凭据值
     * @param expected 服务端配置的期望凭据值
     * @return 两者均为非 null 且 UTF-8 字节完全一致时返回 {@code true}
     */
    public static boolean constantTimeEquals(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * 校验非空白配置密钥是否匹配（任意一方为空白串时直接拒绝）
     *
     * @param actual   请求携带的密钥值
     * @param expected 服务端配置的期望密钥值
     * @return 双方均非空白且恒定时间比对一致时返回 {@code true}
     */
    public static boolean matchesConfiguredSecret(String actual, String expected) {
        if (!StringUtils.hasText(actual) || !StringUtils.hasText(expected)) {
            return false;
        }
        return constantTimeEquals(actual, expected);
    }
}
