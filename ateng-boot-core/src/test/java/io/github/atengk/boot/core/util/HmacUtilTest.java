package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HmacUtil} 纯原生安全 HMAC 密钥加签与防侧信道比对单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("HmacUtil 密钥散列加签工具测试")
class HmacUtilTest {

    private static final String SECRET_KEY = "AtengBootSecretKey2026";
    private static final String PAYLOAD = "orderId=10086&amount=99.9&timestamp=1761500000";

    @Test
    @DisplayName("应该支持标准的 HMAC-SHA256、HMAC-SHA1 与 HMAC-MD5 签名生成")
    void shouldGenerateHmacSignaturesSuccessfully() {
        // HMAC-SHA256
        String sha256Hex = HmacUtil.hmacSha256Hex(PAYLOAD, SECRET_KEY);
        String sha256Base64 = HmacUtil.hmacSha256Base64(PAYLOAD, SECRET_KEY);
        assertNotNull(sha256Hex);
        assertEquals(64, sha256Hex.length()); // SHA-256 产出 32 字节，Hex 为 64 字符
        assertNotNull(sha256Base64);

        // HMAC-SHA1
        String sha1Hex = HmacUtil.hmacSha1Hex(PAYLOAD, SECRET_KEY);
        String sha1Base64 = HmacUtil.hmacSha1Base64(PAYLOAD, SECRET_KEY);
        assertNotNull(sha1Hex);
        assertEquals(40, sha1Hex.length()); // SHA-1 产出 20 字节，Hex 为 40 字符
        assertNotNull(sha1Base64);

        // HMAC-MD5
        String md5Hex = HmacUtil.hmacMd5Hex(PAYLOAD, SECRET_KEY);
        String md5Base64 = HmacUtil.hmacMd5Base64(PAYLOAD, SECRET_KEY);
        assertNotNull(md5Hex);
        assertEquals(32, md5Hex.length()); // MD5 产出 16 字节，Hex 为 32 字符
        assertNotNull(md5Base64);
    }

    @Test
    @DisplayName("验证针对标准已知 RFC 4231 测试向量的 HMAC-SHA256 签名正确性")
    void shouldMatchRfc4231TestVector() {
        // RFC 4231 Test Case 2: key = "Jefe", data = "what do ya want for nothing?"
        byte[] key = "Jefe".getBytes(StandardCharsets.UTF_8);
        byte[] data = "what do ya want for nothing?".getBytes(StandardCharsets.UTF_8);

        String expectedHex = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843";
        String actualHex = HmacUtil.hmacSha256Hex(data, key);

        assertEquals(expectedHex, actualHex.toLowerCase());
        assertTrue(HmacUtil.verifyHex(expectedHex, actualHex));
    }

    @Test
    @DisplayName("恒定时间比对：应该正确校验签名匹配并防御篡改")
    void shouldVerifySignaturesWithConstantTimeComparison() {
        String signHex = HmacUtil.hmacSha256Hex(PAYLOAD, SECRET_KEY);
        String signBase64 = HmacUtil.hmacSha256Base64(PAYLOAD, SECRET_KEY);

        // 正确匹配
        assertTrue(HmacUtil.verifyHex(signHex, signHex));
        assertTrue(HmacUtil.verifyBase64(signBase64, signBase64));

        // 载荷被篡改或签名被篡改
        assertFalse(HmacUtil.verifyHex(signHex, "0000000000000000000000000000000000000000000000000000000000000000"));
        assertFalse(HmacUtil.verifyBase64(signBase64, "invalidBase64String=="));
        assertFalse(HmacUtil.verifyHex(signHex, null));
        assertFalse(HmacUtil.verifyHex(null, signHex));
    }

    @Test
    @DisplayName("边界与前置防卫：null 入参应该抛出 NullPointerException")
    void shouldThrowNpeOnNullInputs() {
        assertThrows(NullPointerException.class, () -> HmacUtil.hmacSha256(null, new byte[1]));
        assertThrows(NullPointerException.class, () -> HmacUtil.hmacSha256(new byte[1], null));
        assertThrows(NullPointerException.class, () -> HmacUtil.hmacSha256Hex(null, "key"));
        assertThrows(NullPointerException.class, () -> HmacUtil.hmacSha256Hex("data", null));
    }
}
