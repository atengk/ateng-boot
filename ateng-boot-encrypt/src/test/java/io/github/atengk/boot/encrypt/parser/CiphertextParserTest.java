package io.github.atengk.boot.encrypt.parser;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 密文解析器与正则提取单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class CiphertextParserTest {

    private final CiphertextParser parser = new CiphertextParser();

    @Test
    @DisplayName("测试标准 ENC(...) 语法解析")
    void testStandardEncSyntax() {
        String input = "ENC(my_ciphertext_123)";
        assertTrue(parser.hasEncrypted(input));

        String replaced = parser.replaceTokens(input, token -> {
            assertNull(token.getKeyId());
            assertNull(token.getAlgorithm());
            assertEquals("my_ciphertext_123", token.getCiphertext());
            return "decrypted_value";
        });
        assertEquals("decrypted_value", replaced);
    }

    @Test
    @DisplayName("测试复合连接串中的内嵌密文精准替换")
    void testEmbeddedCiphertextReplacement() {
        String input = "jdbc:mysql://localhost:3306/mydb?user=root&password=ENC(abc123xyz)&useSSL=false";
        assertTrue(parser.hasEncrypted(input));

        String replaced = parser.replaceTokens(input, token -> {
            assertEquals("abc123xyz", token.getCiphertext());
            return "plain_pass";
        });
        assertEquals("jdbc:mysql://localhost:3306/mydb?user=root&password=plain_pass&useSSL=false", replaced);
    }

    @Test
    @DisplayName("测试同一字符串中多个内嵌密文连续替换")
    void testMultipleEmbeddedTokens() {
        String input = "auth: user=ENC(user_cipher), pass=ENC(pass_cipher)";
        List<String> collected = new ArrayList<>();

        String replaced = parser.replaceTokens(input, token -> {
            collected.add(token.getCiphertext());
            return "decrypted_" + token.getCiphertext();
        });

        assertEquals(2, collected.size());
        assertEquals("auth: user=decrypted_user_cipher, pass=decrypted_pass_cipher", replaced);
    }

    @Test
    @DisplayName("测试显式算法声明 ENC(SM4@ciphertext)")
    void testAlgorithmPrefixSyntax() {
        String input = "ENC(SM4@sm4_cipher_content)";
        assertTrue(parser.hasEncrypted(input));

        String replaced = parser.replaceTokens(input, token -> {
            assertEquals(EncryptAlgorithm.SM4_CBC, token.getAlgorithm());
            assertEquals("sm4_cipher_content", token.getCiphertext());
            return "sm4_plain";
        });
        assertEquals("sm4_plain", replaced);
    }

    @Test
    @DisplayName("测试多 Key 别名 ENC[finance-key](ciphertext)")
    void testKeyIdPrefixSyntax() {
        String input = "ENC[finance-key](finance_cipher)";
        assertTrue(parser.hasEncrypted(input));

        String replaced = parser.replaceTokens(input, token -> {
            assertEquals("finance-key", token.getKeyId());
            assertNull(token.getAlgorithm());
            assertEquals("finance_cipher", token.getCiphertext());
            return "finance_plain";
        });
        assertEquals("finance_plain", replaced);
    }

    @Test
    @DisplayName("测试多 Key 与算法复合声明 ENC[finance-key](SM4@ciphertext)")
    void testKeyIdAndAlgorithmCompositeSyntax() {
        String input = "ENC[finance-key](SM4@composite_cipher)";
        assertTrue(parser.hasEncrypted(input));

        String replaced = parser.replaceTokens(input, token -> {
            assertEquals("finance-key", token.getKeyId());
            assertEquals(EncryptAlgorithm.SM4_CBC, token.getAlgorithm());
            assertEquals("composite_cipher", token.getCiphertext());
            return "composite_plain";
        });
        assertEquals("composite_plain", replaced);
    }

    @Test
    @DisplayName("测试自定义前后缀模式 (如 CRYPT[...])")
    void testCustomPrefixAndSuffix() {
        CiphertextParser customParser = new CiphertextParser("CRYPT[", "]");
        String input = "token: CRYPT[custom_cipher_value]";
        assertTrue(customParser.hasEncrypted(input));

        String replaced = customParser.replaceTokens(input, token -> {
            assertEquals("custom_cipher_value", token.getCiphertext());
            return "custom_plain";
        });
        assertEquals("token: custom_plain", replaced);
    }

    @Test
    @DisplayName("测试无密文普通字符串原样保真放行")
    void testNonEncryptedStringPassthrough() {
        String input = "regular_plaintext_string_without_enc";
        assertFalse(parser.hasEncrypted(input));
        assertEquals(input, parser.replaceTokens(input, token -> "not_called"));
    }

    @Test
    @DisplayName("测试多行折行与缩进空白 YAML 密文自动清洗")
    void testMultilineYamlCiphertextCleaning() {
        String input = "ENC(\n"
                + "  RSA@MIIEvgIBADANBgkqhkiG9w0BAQEFAASC\r\n"
                + "  BKgwggSkAgEAAoIBAQC7+abcdef\t\n"
                + "  1234567890==\n"
                + ")";
        assertTrue(parser.hasEncrypted(input));

        String replaced = parser.replaceTokens(input, token -> {
            assertEquals(EncryptAlgorithm.RSA, token.getAlgorithm());
            assertEquals("MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC7+abcdef1234567890==", token.getCiphertext());
            return "cleaned_rsa_plain";
        });
        assertEquals("cleaned_rsa_plain", replaced);
    }

    @Test
    @DisplayName("测试 formatToken 静态门面格式化生成 ENC 标记")
    void testFormatToken() {
        // 默认 AES-GCM 算法且无 keyId，省略算法与 keyId
        assertEquals("ENC(my_cipher_123)",
                CiphertextParser.formatToken(null, EncryptAlgorithm.AES_GCM, "my_cipher_123"));
        assertEquals("ENC(my_cipher_123)",
                CiphertextParser.formatToken("default", EncryptAlgorithm.AES_GCM, "my_cipher_123"));
        assertEquals("ENC(my_cipher_123)",
                CiphertextParser.formatToken("", null, "my_cipher_123"));

        // 显式非默认算法（如 SM4-CBC）
        assertEquals("ENC(SM4-CBC@my_sm4_cipher)",
                CiphertextParser.formatToken(null, EncryptAlgorithm.SM4_CBC, "my_sm4_cipher"));

        // 指定 KeyId
        assertEquals("ENC[db-key](my_cipher)",
                CiphertextParser.formatToken("db-key", EncryptAlgorithm.AES_GCM, "my_cipher"));

        // 指定 KeyId 与 非默认算法
        assertEquals("ENC[finance-key](RSA@rsa_cipher)",
                CiphertextParser.formatToken("finance-key", EncryptAlgorithm.RSA, "rsa_cipher"));

        // 自定义默认算法基准
        assertEquals("ENC(sm4_cipher)",
                CiphertextParser.formatToken(null, EncryptAlgorithm.SM4_CBC, EncryptAlgorithm.SM4_CBC, "sm4_cipher"));
        assertEquals("ENC[redis](AES-GCM@gcm_cipher)",
                CiphertextParser.formatToken("redis", EncryptAlgorithm.AES_GCM, EncryptAlgorithm.SM4_CBC, "gcm_cipher"));

        // 空密文断言防御
        assertThrows(IllegalArgumentException.class, () ->
                CiphertextParser.formatToken("db", EncryptAlgorithm.AES_GCM, null));
    }
}
