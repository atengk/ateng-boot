package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

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
}
