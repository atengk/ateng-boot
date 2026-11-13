package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

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
    @DisplayName("对称门面测试：验证 byte[] 重载的 HMAC-SHA1 与 HMAC-MD5 Base64 签名生成")
    void shouldSupportByteArrayBase64SignaturesSymmetrically() {
        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = SECRET_KEY.getBytes(StandardCharsets.UTF_8);

        String sha1Base64Bytes = HmacUtil.hmacSha1Base64(payloadBytes, keyBytes);
        String sha1Base64Str = HmacUtil.hmacSha1Base64(PAYLOAD, SECRET_KEY);
        assertEquals(sha1Base64Str, sha1Base64Bytes);

        String md5Base64Bytes = HmacUtil.hmacMd5Base64(payloadBytes, keyBytes);
        String md5Base64Str = HmacUtil.hmacMd5Base64(PAYLOAD, SECRET_KEY);
        assertEquals(md5Base64Str, md5Base64Bytes);
    }

    @Test
    @DisplayName("极端密钥长度对抗测试：支持空密钥、单字节密钥与超长 1024 字节密钥安全加签")
    void shouldHandleKeyLengthBoundaries() {
        byte[] payload = "BoundaryTestPayload".getBytes(StandardCharsets.UTF_8);

        // 1. 空密钥 (0 字节) 应该快速失败抛出 IllegalArgumentException
        byte[] emptyKey = new byte[0];
        assertThrows(IllegalArgumentException.class, () -> HmacUtil.hmacSha256Hex(payload, emptyKey));

        // 2. 单字节极短密钥
        byte[] singleByteKey = new byte[]{(byte) 0x7F};
        assertNotNull(HmacUtil.hmacSha256Hex(payload, singleByteKey));

        // 3. 超长 1024 字节密钥（HMAC 会按 RFC 2104 自动哈希收敛）
        byte[] ultraLongKey = new byte[1024];
        for (int i = 0; i < ultraLongKey.length; i++) {
            ultraLongKey[i] = (byte) (i % 128);
        }
        String ultraLongSign = HmacUtil.hmacSha256Hex(payload, ultraLongKey);
        assertNotNull(ultraLongSign);
        assertEquals(64, ultraLongSign.length());
        assertTrue(HmacUtil.verifyHex(ultraLongSign, HmacUtil.hmacSha256Hex(payload, ultraLongKey)));
    }

    @Test
    @DisplayName("边界与前置防卫：null 入参应该抛出 NullPointerException")
    void shouldThrowNpeOnNullInputs() {
        assertThrows(NullPointerException.class, () -> HmacUtil.hmacSha256(null, new byte[1]));
        assertThrows(NullPointerException.class, () -> HmacUtil.hmacSha256(new byte[1], null));
        assertThrows(NullPointerException.class, () -> HmacUtil.hmacSha256Hex(null, "key"));
        assertThrows(NullPointerException.class, () -> HmacUtil.hmacSha256Hex("data", null));
    }

    @Test
    @DisplayName("纯 JDK 原生国密 HmacSM3：6 组对称重载、RFC2104 长密钥压缩与恒定时间验签")
    void shouldSupportHmacSm3AndVerifySymmetrically() {
        byte[] dataBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = SECRET_KEY.getBytes(StandardCharsets.UTF_8);

        byte[] rawFromBytes = HmacUtil.hmacSm3(dataBytes, keyBytes);
        byte[] rawFromStrings = HmacUtil.hmacSm3(PAYLOAD, SECRET_KEY);
        byte[] rawViaGeneric = HmacUtil.hmac(HmacUtil.HMAC_SM3, dataBytes, keyBytes);
        assertEquals(32, rawFromBytes.length);
        assertArrayEquals(rawFromBytes, rawFromStrings);
        assertArrayEquals(rawFromBytes, rawViaGeneric);

        String hexFromBytes = HmacUtil.hmacSm3Hex(dataBytes, keyBytes);
        String hexFromStrings = HmacUtil.hmacSm3Hex(PAYLOAD, SECRET_KEY);
        assertEquals(64, hexFromBytes.length());
        assertEquals(hexFromBytes, hexFromStrings);
        assertTrue(HmacUtil.verifyHex(hexFromBytes, hexFromStrings));
        assertFalse(HmacUtil.verifyHex(hexFromBytes, HmacUtil.hmacSm3Hex(PAYLOAD + "&tampered=1", SECRET_KEY)));

        String b64FromBytes = HmacUtil.hmacSm3Base64(dataBytes, keyBytes);
        String b64FromStrings = HmacUtil.hmacSm3Base64(PAYLOAD, SECRET_KEY);
        assertEquals(b64FromBytes, b64FromStrings);
        assertArrayEquals(rawFromBytes, Base64.getDecoder().decode(b64FromStrings));
        assertTrue(HmacUtil.verifyBase64(b64FromBytes, b64FromStrings));

        // 空报文属于合法 RFC2104 输入
        assertEquals(32, HmacUtil.hmacSm3("", SECRET_KEY).length);

        // 超过 64 字节的超长密钥应先经 DigestUtil.sm3 压缩至 32 字节再参与 RFC2104 计算
        byte[] over64Key = new byte[100];
        for (int i = 0; i < over64Key.length; i++) {
            over64Key[i] = (byte) (i + 1);
        }
        byte[] compressedKey = DigestUtil.sm3(over64Key);
        assertArrayEquals(HmacUtil.hmacSm3(dataBytes, compressedKey), HmacUtil.hmacSm3(dataBytes, over64Key));

        // 恰好 64 字节边界密钥不做 SM3 预压缩
        byte[] exact64Key = new byte[64];
        exact64Key[0] = 0x11;
        exact64Key[63] = 0x22;
        assertEquals(64, HmacUtil.hmacSm3Hex(dataBytes, exact64Key).length());
    }

    @Test
    @DisplayName("纯 JDK 原生国密 HmacSM3：空入参快速失败抛出 BusinessException")
    void shouldFailFastWithBusinessExceptionOnEmptyHmacSm3Inputs() {
        BusinessException ex1 = assertThrows(BusinessException.class,
                () -> HmacUtil.hmacSm3((byte[]) null, "key".getBytes(StandardCharsets.UTF_8)));
        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), ex1.getCode());

        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> HmacUtil.hmacSm3("data".getBytes(StandardCharsets.UTF_8), null));
        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), ex2.getCode());

        BusinessException ex3 = assertThrows(BusinessException.class,
                () -> HmacUtil.hmacSm3("data".getBytes(StandardCharsets.UTF_8), new byte[0]));
        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), ex3.getCode());

        BusinessException ex4 = assertThrows(BusinessException.class,
                () -> HmacUtil.hmacSm3Hex((String) null, SECRET_KEY));
        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), ex4.getCode());

        BusinessException ex5 = assertThrows(BusinessException.class,
                () -> HmacUtil.hmacSm3Base64(PAYLOAD, ""));
        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), ex5.getCode());
    }
}
