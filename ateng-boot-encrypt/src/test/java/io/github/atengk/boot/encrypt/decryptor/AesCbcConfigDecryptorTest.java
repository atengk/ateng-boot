package io.github.atengk.boot.encrypt.decryptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AES-CBC 核心解密器单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class AesCbcConfigDecryptorTest {

    private final AesCbcConfigDecryptor decryptor = new AesCbcConfigDecryptor();

    @Test
    @DisplayName("测试 AES-CBC 正常加解密闭环")
    void testAesCbcEncryptDecryptSuccess() throws Exception {
        String plaintext = "my_cbc_password_666";
        String password = "CbcMasterPassword2026";

        String ciphertext = AesCbcConfigDecryptor.encrypt(plaintext, password);
        assertNotNull(ciphertext);
        assertNotEquals(plaintext, ciphertext);

        String decrypted = decryptor.decrypt(ciphertext, password);
        assertEquals(plaintext, decrypted);
    }

    @Test
    @DisplayName("测试 AES-CBC 错误密码解密失败")
    void testAesCbcWrongPasswordThrows() {
        String plaintext = "test_cbc_data";
        String password = "password_correct";
        String wrongPassword = "password_wrong";

        String ciphertext = AesCbcConfigDecryptor.encrypt(plaintext, password);
        assertThrows(Exception.class, () -> decryptor.decrypt(ciphertext, wrongPassword));
    }

    @Test
    @DisplayName("测试 AES-CBC 畸形短密文拒绝")
    void testInvalidShortCiphertextThrows() {
        assertThrows(IllegalArgumentException.class, () -> decryptor.decrypt("YWJj", "password"));
    }
}
