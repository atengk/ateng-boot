package io.github.atengk.boot.encrypt.decryptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 国密 SM4 核心解密器单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class Sm4ConfigDecryptorTest {

    private final Sm4ConfigDecryptor decryptor = new Sm4ConfigDecryptor();

    @Test
    @DisplayName("测试纯原生 SM4/CBC 加解密闭环")
    void testSm4CbcSuccess() throws Exception {
        String plaintext = "national_crypto_password_8888";
        String password = "Sm4MasterPassword123";

        String ciphertext = Sm4ConfigDecryptor.encryptCbc(plaintext, password);
        assertNotNull(ciphertext);
        assertNotEquals(plaintext, ciphertext);

        String decrypted = decryptor.decrypt(ciphertext, password);
        assertEquals(plaintext, decrypted);
    }

    @Test
    @DisplayName("测试纯原生 SM4/ECB 加解密闭环")
    void testSm4EcbSuccess() throws Exception {
        String plaintext = "ecb_secret_value";
        String password = "Sm4EcbPassword999";

        String ciphertext = Sm4ConfigDecryptor.encryptEcb(plaintext, password);
        assertNotNull(ciphertext);

        String decrypted = decryptor.decrypt(ciphertext, password);
        assertEquals(plaintext, decrypted);
    }

    @Test
    @DisplayName("测试 SM4 错误密码解密失败")
    void testSm4WrongPasswordThrows() {
        String plaintext = "test_data";
        String password = "password_a";
        String wrongPassword = "password_b";

        String ciphertext = Sm4ConfigDecryptor.encryptCbc(plaintext, password);
        assertThrows(Exception.class, () -> decryptor.decrypt(ciphertext, wrongPassword));
    }
}
