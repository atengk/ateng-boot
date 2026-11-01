package io.github.atengk.boot.encrypt.template;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.config.EncryptProperties;
import io.github.atengk.boot.encrypt.exception.ConfigDecryptionException;
import io.github.atengk.boot.encrypt.key.MultiKeyRegistry;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EncryptTemplate 统一编程式加解密受管门面单元测试
 * 验证加解密往返、Token 组装、多 Key 别名路由、Fail-Fast 异常抛出与 decryptOrOriginal / decryptOrDefault 容错降级
 *
 * @author Ateng
 * @since 2026-11-01
 */
@DisplayName("EncryptTemplate 统一受管门面单元测试")
class EncryptTemplateTest {

    private static final String DEFAULT_PASSWORD = "MasterSecretPassword2026!";
    private static final String DB_PASSWORD = "DbSpecialSecretKey2026!";
    private static final String PLAINTEXT = "SuperSecretDatabasePassword123";

    private EncryptProperties properties;
    private MultiKeyRegistry keyRegistry;
    private CiphertextParser parser;
    private EncryptTemplate template;

    @BeforeEach
    void setUp() {
        properties = new EncryptProperties();
        properties.setPassword(DEFAULT_PASSWORD);
        properties.setAlgorithm(EncryptAlgorithm.AES_GCM);
        properties.setPrefix("ENC(");
        properties.setSuffix(")");
        properties.setKeys(Map.of("dbKey", DB_PASSWORD));

        keyRegistry = MultiKeyRegistry.fromProperties(properties);
        parser = new CiphertextParser(properties.getPrefix(), properties.getSuffix());
        template = new EncryptTemplate(properties, keyRegistry, parser);
    }

    @Test
    @DisplayName("验证默认算法与主密钥的 encrypt 与 decrypt 闭环")
    void testDefaultEncryptAndDecrypt() {
        // 1. 加密生成标准 ENC(...) 标记
        String token = template.encrypt(PLAINTEXT);
        assertNotNull(token);
        assertTrue(token.startsWith("ENC("));
        assertTrue(token.endsWith(")"));
        assertTrue(template.isEncrypted(token));

        // 2. 解密还原为明文
        String decrypted = template.decrypt(token);
        assertEquals(PLAINTEXT, decrypted);
    }

    @Test
    @DisplayName("验证 encryptRaw 生成裸密文并成功 decrypt 还原")
    void testRawEncryptAndDecrypt() {
        String rawCipher = template.encryptRaw(PLAINTEXT);
        assertNotNull(rawCipher);
        assertFalse(template.isEncrypted(rawCipher));

        // 直接解密裸密文
        String decrypted = template.decrypt(rawCipher);
        assertEquals(PLAINTEXT, decrypted);
    }

    @Test
    @DisplayName("验证多 Key 别名 [dbKey] 与国密 SM4-CBC 算法的端到端加解密闭环")
    void testMultiKeyAndSm4Encryption() {
        String token = template.encrypt(PLAINTEXT, "dbKey", EncryptAlgorithm.SM4_CBC);
        assertNotNull(token);
        assertTrue(token.startsWith("ENC[dbKey](SM4-CBC@"));
        assertTrue(token.endsWith(")"));

        // 解密自动识别 KeyId: dbKey 并使用其独立密钥解密
        String decrypted = template.decrypt(token);
        assertEquals(PLAINTEXT, decrypted);
    }

    @Test
    @DisplayName("验证内嵌 ENC 标记的复合字符串替换解密")
    void testEmbeddedTokenReplacement() {
        String token = template.encrypt("mysql123");
        String connectionString = "jdbc:mysql://localhost:3306/db?user=root&password=" + token;

        String decryptedConnection = template.decrypt(connectionString);
        assertEquals("jdbc:mysql://localhost:3306/db?user=root&password=mysql123", decryptedConnection);
    }

    @Test
    @DisplayName("验证解密失败时严格遵循 Fail-Fast 抛出 ConfigDecryptionException")
    void testDecryptionFailFast() {
        // 伪造损坏的密文
        String badToken = "ENC(CorruptedCiphertextTagMismatch1234567890==)";
        assertThrows(ConfigDecryptionException.class, () -> template.decrypt(badToken));

        // 裸密文且密码错误
        String rawCipher = template.encryptRaw(PLAINTEXT);
        template.getKeyRegistry().setDefaultKey("WrongKey999!");
        assertThrows(ConfigDecryptionException.class, () -> template.decrypt(rawCipher));
    }

    @Test
    @DisplayName("验证 decryptOrOriginal 容错逃生舱：解密失败或非密文时返回原始文本")
    void testDecryptOrOriginal() {
        // 1. 正常密文解密
        String token = template.encrypt(PLAINTEXT);
        assertEquals(PLAINTEXT, template.decryptOrOriginal(token));

        // 2. 损坏密文容错返回原值
        String badToken = "ENC(CorruptedCiphertextTagMismatch1234567890==)";
        assertEquals(badToken, template.decryptOrOriginal(badToken));

        // 3. 普通明文直接返回原值
        String plain = "purePlaintextWithoutEncryption";
        assertEquals(plain, template.decryptOrOriginal(plain));

        // 4. Null 入参返回 Null
        assertNull(template.decryptOrOriginal(null));
    }

    @Test
    @DisplayName("验证 decryptOrDefault 容错逃生舱：解密失败时返回指定默认值")
    void testDecryptOrDefault() {
        // 1. 正常解密
        String token = template.encrypt(PLAINTEXT);
        assertEquals(PLAINTEXT, template.decryptOrDefault(token, "fallback"));

        // 2. 损坏密文回退默认值
        String badToken = "ENC(CorruptedCiphertextTagMismatch1234567890==)";
        assertEquals("defaultFallbackPassword", template.decryptOrDefault(badToken, "defaultFallbackPassword"));

        // 3. Null 入参返回默认值
        assertEquals("defaultVal", template.decryptOrDefault(null, "defaultVal"));
    }

    @Test
    @DisplayName("验证 Null 安全性")
    void testNullHandling() {
        assertNull(template.encrypt(null));
        assertNull(template.encryptRaw(null));
        assertNull(template.encrypt(null, "dbKey", EncryptAlgorithm.AES_GCM));
        assertNull(template.encryptRaw(null, "dbKey", EncryptAlgorithm.AES_GCM));
        assertNull(template.decrypt(null));
        assertEquals("", template.decrypt(""));
        assertEquals("   ", template.decrypt("   "));
    }

    @Test
    @DisplayName("验证 formatToken 辅助方法")
    void testFormatToken() {
        String tokenDefault = template.formatToken(null, EncryptAlgorithm.AES_GCM, "myCipher");
        assertEquals("ENC(myCipher)", tokenDefault);

        String tokenCustom = template.formatToken("db", EncryptAlgorithm.SM4_CBC, "myCipher");
        assertEquals("ENC[db](SM4-CBC@myCipher)", tokenCustom);
    }
}
