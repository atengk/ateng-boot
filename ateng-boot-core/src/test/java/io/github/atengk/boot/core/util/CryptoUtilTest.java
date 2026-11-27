package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 原生加解密工具单测
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("原生加解密工具单测")
class CryptoUtilTest {

    @Test
    @DisplayName("AES 密钥与安全 IV 生成及 Base64 编解码验证")
    void shouldGenerateKeyAndIvAndBase64() {
        byte[] key128 = CryptoUtil.generateKey(128);
        assertEquals(16, key128.length);

        byte[] key192 = CryptoUtil.generateKey(192);
        assertEquals(24, key192.length);

        byte[] key256 = CryptoUtil.generateKey(256);
        assertEquals(32, key256.length);

        assertThrows(IllegalArgumentException.class, () -> CryptoUtil.generateKey(64));

        byte[] cbcIv = CryptoUtil.generateIv();
        assertEquals(16, cbcIv.length);

        byte[] gcmIv = CryptoUtil.generateGcmIv();
        assertEquals(12, gcmIv.length);

        String sample = "Hello ateng-boot!";
        String base64 = CryptoUtil.encodeBase64(sample.getBytes(StandardCharsets.UTF_8));
        byte[] decoded = CryptoUtil.decodeBase64(base64);
        assertEquals(sample, new String(decoded, StandardCharsets.UTF_8));

        assertNull(CryptoUtil.encodeBase64(null));
        assertNull(CryptoUtil.decodeBase64(null));
    }

    @Test
    @DisplayName("AES/CBC/PKCS5Padding 字符串与字节加解密闭环")
    void shouldEncryptAndDecryptAesCbc() {
        byte[] key = CryptoUtil.generateKey(128);
        byte[] iv = CryptoUtil.generateIv();
        String originalText = "机密数据：CardNo-6222020011223344";

        // 字符串 Base64 契约
        String cipherText = CryptoUtil.encryptCbc(originalText, key, iv);
        assertNotNull(cipherText);
        assertNotEquals(originalText, cipherText);

        String decryptedText = CryptoUtil.decryptCbc(cipherText, key, iv);
        assertEquals(originalText, decryptedText);

        // 字节数组契约
        byte[] plainBytes = originalText.getBytes(StandardCharsets.UTF_8);
        byte[] cipherBytes = CryptoUtil.encryptCbc(plainBytes, key, iv);
        byte[] decryptedBytes = CryptoUtil.decryptCbc(cipherBytes, key, iv);
        assertEquals(originalText, new String(decryptedBytes, StandardCharsets.UTF_8));

        // 空值安全
        assertNull(CryptoUtil.encryptCbc((String) null, key, iv));
        assertNull(CryptoUtil.decryptCbc((String) null, key, iv));
        assertNull(CryptoUtil.encryptCbc((byte[]) null, key, iv));
        assertNull(CryptoUtil.decryptCbc((byte[]) null, key, iv));
    }

    @Test
    @DisplayName("AES/GCM/NoPadding 字符串与字节加解密闭环（防篡改认证）")
    void shouldEncryptAndDecryptAesGcm() {
        byte[] key = CryptoUtil.generateKey(256);
        byte[] iv = CryptoUtil.generateGcmIv();
        String originalText = "GCM模式机密数据：Token-abcdef123456";

        // 字符串 Base64 契约
        String cipherText = CryptoUtil.encryptGcm(originalText, key, iv);
        assertNotNull(cipherText);
        assertNotEquals(originalText, cipherText);

        String decryptedText = CryptoUtil.decryptGcm(cipherText, key, iv);
        assertEquals(originalText, decryptedText);

        // 带 AAD 附加认证数据
        byte[] aad = "tenantId:001".getBytes(StandardCharsets.UTF_8);
        byte[] plainBytes = originalText.getBytes(StandardCharsets.UTF_8);
        byte[] cipherBytes = CryptoUtil.encryptGcm(plainBytes, key, iv, aad);

        byte[] decryptedWithAad = CryptoUtil.decryptGcm(cipherBytes, key, iv, aad);
        assertEquals(originalText, new String(decryptedWithAad, StandardCharsets.UTF_8));

        // 验证篡改 AAD 时认证失败
        byte[] tamperedAad = "tenantId:002".getBytes(StandardCharsets.UTF_8);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> CryptoUtil.decryptGcm(cipherBytes, key, iv, tamperedAad));
        assertTrue(ex.getMessage().contains("AES/GCM 解密或认证校验失败"));
    }

    @Test
    @DisplayName("非法密钥与 IV 校验防御")
    void shouldValidateKeyAndIv() {
        byte[] validKey = CryptoUtil.generateKey(128);
        byte[] validIv = CryptoUtil.generateIv();
        byte[] invalidKey = new byte[10];
        byte[] invalidIv = new byte[8];

        IllegalArgumentException exKey = assertThrows(IllegalArgumentException.class,
                () -> CryptoUtil.encryptCbc("data", invalidKey, validIv));
        assertTrue(exKey.getMessage().contains("AES 密钥字节长度必须为 16, 24 或 32"));

        IllegalArgumentException exIv = assertThrows(IllegalArgumentException.class,
                () -> CryptoUtil.encryptCbc("data", validKey, invalidIv));
        assertTrue(exIv.getMessage().contains("当前模式要求的 IV 字节长度必须为 16"));

        IllegalArgumentException exNullIv = assertThrows(IllegalArgumentException.class,
                () -> CryptoUtil.encryptCbc("data", validKey, null));
        assertTrue(exNullIv.getMessage().contains("IV 初始向量不能为空"));
    }

    @Test
    @DisplayName("国密 SM4/CBC 加解密字符串与字节闭环验证")
    void shouldEncryptAndDecryptSm4Cbc() {
        byte[] key = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] iv = "fedcba9876543210".getBytes(StandardCharsets.UTF_8);
        String plaintext = "国密SM4-机密数据库密码: Root@Pass_2026";

        String ciphertext = CryptoUtil.encryptSm4Cbc(plaintext, key, iv);
        assertNotNull(ciphertext);
        assertNotEquals(plaintext, ciphertext);

        String decrypted = CryptoUtil.decryptSm4Cbc(ciphertext, key, iv);
        assertEquals(plaintext, decrypted);

        // 字节级闭环
        byte[] plainBytes = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] cipherBytes = CryptoUtil.encryptSm4Cbc(plainBytes, key, iv);
        byte[] decryptedBytes = CryptoUtil.decryptSm4Cbc(cipherBytes, key, iv);
        assertEquals(plaintext, new String(decryptedBytes, StandardCharsets.UTF_8));

        // 空值安全
        assertNull(CryptoUtil.encryptSm4Cbc((String) null, key, iv));
        assertNull(CryptoUtil.decryptSm4Cbc((String) null, key, iv));
        assertNull(CryptoUtil.encryptSm4Cbc((byte[]) null, key, iv));
        assertNull(CryptoUtil.decryptSm4Cbc((byte[]) null, key, iv));
    }

    @Test
    @DisplayName("国密 SM4/ECB 加解密字符串与字节闭环验证")
    void shouldEncryptAndDecryptSm4Ecb() {
        byte[] key = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        String plaintext = "ECB模式国密SM4凭证测试";

        String ciphertext = CryptoUtil.encryptSm4Ecb(plaintext, key);
        assertNotNull(ciphertext);
        assertNotEquals(plaintext, ciphertext);

        String decrypted = CryptoUtil.decryptSm4Ecb(ciphertext, key);
        assertEquals(plaintext, decrypted);

        // 字节级闭环
        byte[] plainBytes = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] cipherBytes = CryptoUtil.encryptSm4Ecb(plainBytes, key);
        byte[] decryptedBytes = CryptoUtil.decryptSm4Ecb(cipherBytes, key);
        assertEquals(plaintext, new String(decryptedBytes, StandardCharsets.UTF_8));

        // 空值安全
        assertNull(CryptoUtil.encryptSm4Ecb((String) null, key));
        assertNull(CryptoUtil.decryptSm4Ecb((String) null, key));
        assertNull(CryptoUtil.encryptSm4Ecb((byte[]) null, key));
        assertNull(CryptoUtil.decryptSm4Ecb((byte[]) null, key));
    }

    @Test
    @DisplayName("国密 SM4 非法密钥与 IV 校验防御")
    void shouldValidateSm4KeyAndIv() {
        byte[] invalidKey = new byte[8];
        byte[] validKey = new byte[16];
        byte[] invalidIv = new byte[10];

        assertThrows(IllegalArgumentException.class, () -> CryptoUtil.encryptSm4Cbc("data", invalidKey, validKey));
        assertThrows(IllegalArgumentException.class, () -> CryptoUtil.encryptSm4Cbc("data", validKey, invalidIv));
        assertThrows(IllegalArgumentException.class, () -> CryptoUtil.encryptSm4Ecb("data", invalidKey));
    }

    @Test
    @DisplayName("generateSm4Key 生成 16 字节随机密钥并与 SM4 加解密无缝协同")
    void shouldGenerateSm4KeyAndEncrypt() {
        byte[] k1 = CryptoUtil.generateSm4Key();
        byte[] k2 = CryptoUtil.generateSm4Key();
        assertEquals(16, k1.length);
        assertEquals(16, k2.length);
        assertFalse(Arrays.equals(k1, k2));

        byte[] iv = CryptoUtil.generateIv(CryptoUtil.SM4_IV_SIZE);
        String plain = "国密SM4动态密钥测试";
        String cipher = CryptoUtil.encryptSm4Cbc(plain, k1, iv);
        assertEquals(plain, CryptoUtil.decryptSm4Cbc(cipher, k1, iv));
    }

    @Test
    @DisplayName("AES/GCM 字符串级带 AAD 重载：正常加解密、空安全与篡改抛出保留 cause 的 BusinessException")
    void shouldEncryptAndDecryptGcmStringWithAadAndPreserveCause() {
        byte[] key = CryptoUtil.generateKey(256);
        byte[] iv = CryptoUtil.generateGcmIv();
        byte[] aad = "tenant=t-001;biz=payment".getBytes(StandardCharsets.UTF_8);
        String plain = "SensitiveStringWithAAD";

        assertNull(CryptoUtil.encryptGcm((String) null, key, iv, aad));
        assertNull(CryptoUtil.decryptGcm((String) null, key, iv, aad));

        String cipherBase64 = CryptoUtil.encryptGcm(plain, key, iv, aad);
        assertNotNull(cipherBase64);
        assertEquals(plain, CryptoUtil.decryptGcm(cipherBase64, key, iv, aad));

        // AAD 篡改校验
        byte[] tamperedAad = "tenant=t-002;biz=payment".getBytes(StandardCharsets.UTF_8);
        BusinessException exAad = assertThrows(BusinessException.class,
                () -> CryptoUtil.decryptGcm(cipherBase64, key, iv, tamperedAad));
        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), exAad.getCode());
        assertInstanceOf(AEADBadTagException.class, exAad.getCause());

        // 密文篡改校验
        byte[] cipherRaw = CryptoUtil.decodeBase64(cipherBase64);
        cipherRaw[0] ^= 0x01;
        String tamperedCipher = CryptoUtil.encodeBase64(cipherRaw);
        BusinessException exCipher = assertThrows(BusinessException.class,
                () -> CryptoUtil.decryptGcm(tamperedCipher, key, iv, aad));
        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), exCipher.getCode());
        assertInstanceOf(AEADBadTagException.class, exCipher.getCause());

        // 加密或解密密钥非法同样保留 cause 抛出 BusinessException
        BusinessException exBadKey = assertThrows(BusinessException.class,
                () -> CryptoUtil.encryptGcm(plain, new byte[5], iv, aad));
        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), exBadKey.getCode());
        assertNotNull(exBadKey.getCause());
    }

    @Test
    @DisplayName("国密 SM4/GCM/NoPadding 字节与字符串加解密、AAD 认证、短密文与篡改校验")
    void shouldEncryptAndDecryptSm4GcmWithAadAndTamperDetection() {
        byte[] key = CryptoUtil.generateSm4Key();
        byte[] iv = CryptoUtil.generateGcmIv();
        byte[] iv16 = CryptoUtil.generateIv();
        byte[] aad = "app=default|keyId=v1|alg=SM2_SM4_GCM".getBytes(StandardCharsets.UTF_8);

        // 1. 字节级多长度（空、非对齐、多块）与非 12 字节 IV 往返验证
        byte[][] samples = {
                new byte[0],
                new byte[]{0x42},
                "1234567890abcdef".getBytes(StandardCharsets.UTF_8),
                "国密SM4-GCM多分组明文载荷验证：orderNo=ORD_20261127_001, amount=9999.99".getBytes(StandardCharsets.UTF_8)
        };
        for (byte[] plainBytes : samples) {
            byte[] cipherBytes = CryptoUtil.encryptSm4Gcm(plainBytes, key, iv, aad);
            assertEquals(plainBytes.length + 16, cipherBytes.length);
            assertArrayEquals(plainBytes, CryptoUtil.decryptSm4Gcm(cipherBytes, key, iv, aad));

            byte[] cipherNoAad = CryptoUtil.encryptSm4Gcm(plainBytes, key, iv16);
            assertArrayEquals(plainBytes, CryptoUtil.decryptSm4Gcm(cipherNoAad, key, iv16));
        }

        // 2. 字符串级 Base64 契约与空安全
        String plain = "机密传输载荷：user=ateng&phone=13812345678";
        assertNull(CryptoUtil.encryptSm4Gcm((String) null, key, iv));
        assertNull(CryptoUtil.decryptSm4Gcm((String) null, key, iv));
        assertNull(CryptoUtil.encryptSm4Gcm((String) null, key, iv, aad));
        assertNull(CryptoUtil.decryptSm4Gcm((String) null, key, iv, aad));
        assertNull(CryptoUtil.encryptSm4Gcm((byte[]) null, key, iv, aad));
        assertNull(CryptoUtil.decryptSm4Gcm((byte[]) null, key, iv, aad));

        String cipherNoAadStr = CryptoUtil.encryptSm4Gcm(plain, key, iv);
        assertEquals(plain, CryptoUtil.decryptSm4Gcm(cipherNoAadStr, key, iv));

        String cipherBase64 = CryptoUtil.encryptSm4Gcm(plain, key, iv, aad);
        assertEquals(plain, CryptoUtil.decryptSm4Gcm(cipherBase64, key, iv, aad));

        // 3. 篡改 AAD / 密文 / Tag / 密钥 / IV 均必须抛出保留 AEADBadTagException 的异常
        byte[] tamperedAad = "app=evil|keyId=v1|alg=SM2_SM4_GCM".getBytes(StandardCharsets.UTF_8);
        BusinessException exAad = assertThrows(BusinessException.class,
                () -> CryptoUtil.decryptSm4Gcm(cipherBase64, key, iv, tamperedAad));
        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), exAad.getCode());
        assertInstanceOf(AEADBadTagException.class, exAad.getCause());

        byte[] rawCipher = CryptoUtil.decodeBase64(cipherBase64);
        byte[] tamperedBody = rawCipher.clone();
        tamperedBody[0] ^= 0x01;
        BusinessException exBody = assertThrows(BusinessException.class,
                () -> CryptoUtil.decryptSm4Gcm(CryptoUtil.encodeBase64(tamperedBody), key, iv, aad));
        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), exBody.getCode());
        assertInstanceOf(AEADBadTagException.class, exBody.getCause());

        byte[] tamperedTag = rawCipher.clone();
        tamperedTag[tamperedTag.length - 1] ^= 0x01;
        IllegalStateException exTag = assertThrows(IllegalStateException.class,
                () -> CryptoUtil.decryptSm4Gcm(tamperedTag, key, iv, aad));
        assertInstanceOf(AEADBadTagException.class, exTag.getCause());

        // 4. 长度不足 16 字节与非法密钥/IV 参数防御
        IllegalStateException exShort = assertThrows(IllegalStateException.class,
                () -> CryptoUtil.decryptSm4Gcm(new byte[15], key, iv, aad));
        assertInstanceOf(AEADBadTagException.class, exShort.getCause());
        assertThrows(IllegalArgumentException.class, () -> CryptoUtil.encryptSm4Gcm("x".getBytes(StandardCharsets.UTF_8), new byte[8], iv, aad));
        assertThrows(IllegalArgumentException.class, () -> CryptoUtil.encryptSm4Gcm("x".getBytes(StandardCharsets.UTF_8), key, new byte[0], aad));
    }
}
