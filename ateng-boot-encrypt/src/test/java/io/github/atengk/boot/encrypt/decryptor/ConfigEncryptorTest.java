package io.github.atengk.boot.encrypt.decryptor;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.exception.ConfigDecryptionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConfigEncryptor 纯原生静态对称门面单元测试
 * 验证 AES-GCM, AES-CBC, SM4-CBC, SM4-ECB, RSA, SM2 算法加解密对称往返、Null 防御与异常断言
 *
 * @author Ateng
 * @since 2026-11-01
 */
@DisplayName("ConfigEncryptor 静态对称门面单元测试")
class ConfigEncryptorTest {

    private static final String PASSWORD = "MasterSecretPassword2026!";
    private static final String PLAINTEXT = "ateng-boot-super-secret-credential";

    @Test
    @DisplayName("验证 AES-GCM 默认及显式加解密对称往返")
    void testAesGcmSymmetric() {
        // 1. 默认 2 参数重载
        String cipherDefault = ConfigEncryptor.encrypt(PLAINTEXT, PASSWORD);
        assertNotNull(cipherDefault);
        String decryptedDefault = ConfigEncryptor.decrypt(cipherDefault, PASSWORD);
        assertEquals(PLAINTEXT, decryptedDefault);

        // 2. 显式指定 AES_GCM
        String cipherExplicit = ConfigEncryptor.encrypt(PLAINTEXT, PASSWORD, EncryptAlgorithm.AES_GCM);
        assertNotNull(cipherExplicit);
        String decryptedExplicit = ConfigEncryptor.decrypt(cipherExplicit, PASSWORD, EncryptAlgorithm.AES_GCM);
        assertEquals(PLAINTEXT, decryptedExplicit);
    }

    @Test
    @DisplayName("验证 AES-CBC 加解密对称往返")
    void testAesCbcSymmetric() {
        String cipher = ConfigEncryptor.encrypt(PLAINTEXT, PASSWORD, EncryptAlgorithm.AES_CBC);
        assertNotNull(cipher);
        String decrypted = ConfigEncryptor.decrypt(cipher, PASSWORD, EncryptAlgorithm.AES_CBC);
        assertEquals(PLAINTEXT, decrypted);
    }

    @Test
    @DisplayName("验证国密 SM4-CBC 与 SM4-ECB 加解密对称往返")
    void testSm4Symmetric() {
        // SM4-CBC
        String cipherCbc = ConfigEncryptor.encrypt(PLAINTEXT, PASSWORD, EncryptAlgorithm.SM4_CBC);
        assertNotNull(cipherCbc);
        String decryptedCbc = ConfigEncryptor.decrypt(cipherCbc, PASSWORD, EncryptAlgorithm.SM4_CBC);
        assertEquals(PLAINTEXT, decryptedCbc);

        // SM4-ECB
        String cipherEcb = ConfigEncryptor.encrypt(PLAINTEXT, PASSWORD, EncryptAlgorithm.SM4_ECB);
        assertNotNull(cipherEcb);
        String decryptedEcb = ConfigEncryptor.decrypt(cipherEcb, PASSWORD, EncryptAlgorithm.SM4_ECB);
        assertEquals(PLAINTEXT, decryptedEcb);
    }

    @Test
    @DisplayName("验证非对称 RSA 加解密对称往返")
    void testRsaSymmetric() {
        KeyPair keyPair = RsaConfigDecryptor.generateKeyPair(2048);
        String pubKeyPem = RsaConfigDecryptor.encodePublicKeyToPem(keyPair.getPublic());
        String priKeyPem = RsaConfigDecryptor.encodePrivateKeyToPem(keyPair.getPrivate());

        String cipher = ConfigEncryptor.encrypt(PLAINTEXT, pubKeyPem, EncryptAlgorithm.RSA);
        assertNotNull(cipher);
        String decrypted = ConfigEncryptor.decrypt(cipher, priKeyPem, EncryptAlgorithm.RSA);
        assertEquals(PLAINTEXT, decrypted);
    }

    @Test
    @DisplayName("验证纯原生国密 SM2 加解密对称往返")
    void testSm2Symmetric() {
        Sm2Engine.Sm2KeyPair keyPair = Sm2ConfigDecryptor.generateKeyPair();
        String pubKeyHex = keyPair.getPublicKeyHex();
        String priKeyHex = keyPair.getPrivateKeyHex();

        String cipher = ConfigEncryptor.encrypt(PLAINTEXT, pubKeyHex, EncryptAlgorithm.SM2);
        assertNotNull(cipher);
        String decrypted = ConfigEncryptor.decrypt(cipher, priKeyHex, EncryptAlgorithm.SM2);
        assertEquals(PLAINTEXT, decrypted);
    }

    @Test
    @DisplayName("验证 Null 入参保底返回 Null")
    void testNullSafety() {
        assertNull(ConfigEncryptor.encrypt(null, PASSWORD));
        assertNull(ConfigEncryptor.encrypt(null, PASSWORD, EncryptAlgorithm.AES_GCM));
        assertNull(ConfigEncryptor.decrypt(null, PASSWORD));
        assertNull(ConfigEncryptor.decrypt(null, PASSWORD, EncryptAlgorithm.AES_GCM));
    }

    @Test
    @DisplayName("验证解密失败时抛出因果受管异常 ConfigDecryptionException")
    void testDecryptionException() {
        String cipher = ConfigEncryptor.encrypt(PLAINTEXT, PASSWORD);
        assertThrows(ConfigDecryptionException.class, () ->
                ConfigEncryptor.decrypt(cipher, "WrongPassword888!"));
    }
}
