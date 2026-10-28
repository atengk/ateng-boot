package io.github.atengk.boot.encrypt.decryptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AES-GCM 核心解密器单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class AesGcmConfigDecryptorTest {

    private final AesGcmConfigDecryptor decryptor = new AesGcmConfigDecryptor();

    @Test
    @DisplayName("测试 AES-GCM 正常加解密闭环")
    void testEncryptDecryptSuccess() throws Exception {
        String plaintext = "root_password_123456!@#$%^&*()";
        String password = "MyVeryStrongMasterKey2026";

        String ciphertext = AesGcmConfigDecryptor.encrypt(plaintext, password);
        assertNotNull(ciphertext);
        assertNotEquals(plaintext, ciphertext);

        String decrypted = decryptor.decrypt(ciphertext, password);
        assertEquals(plaintext, decrypted);
    }

    @Test
    @DisplayName("测试 AES-GCM 错误密码解密失败 (AEAD Tag 校验拒绝)")
    void testWrongPasswordThrows() {
        String plaintext = "secret_value";
        String password = "correct_password";
        String wrongPassword = "wrong_password";

        String ciphertext = AesGcmConfigDecryptor.encrypt(plaintext, password);
        assertThrows(Exception.class, () -> decryptor.decrypt(ciphertext, wrongPassword));
    }

    @Test
    @DisplayName("测试 AES-GCM 密文篡改检测 (抗篡改性断言)")
    void testTamperedCiphertextThrows() {
        String plaintext = "my_token";
        String password = "test_password";

        String ciphertext = AesGcmConfigDecryptor.encrypt(plaintext, password);
        // 篡改 Base64 最后一个字符
        char lastChar = ciphertext.charAt(ciphertext.length() - 2);
        char replacedChar = (lastChar == 'A') ? 'B' : 'A';
        String tampered = ciphertext.substring(0, ciphertext.length() - 2) + replacedChar + "=";

        assertThrows(Exception.class, () -> decryptor.decrypt(tampered, password));
    }

    @Test
    @DisplayName("测试 AES-GCM 畸形短密文拒绝")
    void testInvalidShortCiphertextThrows() {
        assertThrows(IllegalArgumentException.class, () -> decryptor.decrypt("YWJj", "password"));
    }
}
