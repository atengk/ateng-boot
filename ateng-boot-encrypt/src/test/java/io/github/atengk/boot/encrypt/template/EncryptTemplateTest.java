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

    @Test
    @DisplayName("验证命名密钥缺省算法对称重载：encrypt(plain, keyId)、encryptRaw(plain, keyId) 与 decrypt(cipher, keyId)")
    void testNamedKeyDefaultAlgorithmOverloads() {
        // 1. Token 加解密闭环
        String token = template.encrypt(PLAINTEXT, "dbKey");
        assertNotNull(token);
        assertTrue(token.startsWith("ENC[dbKey]("));
        assertFalse(token.contains("AES-GCM@"));
        assertEquals(PLAINTEXT, template.decrypt(token));
        assertEquals(PLAINTEXT, template.decrypt(token, "dbKey"));

        // 2. 裸密文加解密闭环
        String rawCipher = template.encryptRaw(PLAINTEXT, "dbKey");
        assertNotNull(rawCipher);
        assertFalse(template.isEncrypted(rawCipher));
        assertEquals(PLAINTEXT, template.decrypt(rawCipher, "dbKey"));
        assertThrows(ConfigDecryptionException.class, () -> template.decrypt(rawCipher));

        // 3. 无 [keyId] 标签的 ENC(...) 密文显式指定 keyId 解密
        String tokenWithoutKeyId = template.formatToken(null, EncryptAlgorithm.AES_GCM, rawCipher);
        assertEquals(PLAINTEXT, template.decrypt(tokenWithoutKeyId, "dbKey"));

        // 4. Null / Blank 安全性
        assertNull(template.encrypt(null, "dbKey"));
        assertNull(template.encryptRaw(null, "dbKey"));
        assertNull(template.decrypt(null, "dbKey"));
        assertEquals("", template.decrypt("", "dbKey"));
        assertEquals("   ", template.decrypt("   ", "dbKey"));

        // 5. 缺失密钥（无默认回退密钥）与损坏密文 Fail-Fast 保留因果链
        EncryptProperties noDefaultProps = new EncryptProperties();
        noDefaultProps.setKeys(Map.of("dbKey", DB_PASSWORD));
        EncryptTemplate noDefaultTemplate = new EncryptTemplate(
                noDefaultProps, MultiKeyRegistry.fromProperties(noDefaultProps), parser);
        ConfigDecryptionException missingKeyEx = assertThrows(ConfigDecryptionException.class,
                () -> noDefaultTemplate.encrypt(PLAINTEXT, "missingKey"));
        assertNotNull(missingKeyEx.getCause());
        ConfigDecryptionException decryptMissingKeyEx = assertThrows(ConfigDecryptionException.class,
                () -> noDefaultTemplate.decrypt(rawCipher, "missingKey"));
        assertNotNull(decryptMissingKeyEx.getCause());
        ConfigDecryptionException corruptedEx = assertThrows(ConfigDecryptionException.class,
                () -> template.decrypt("ENC[dbKey](CorruptedCiphertext123456==)", "dbKey"));
        assertNotNull(corruptedEx.getCause());
    }

    @Test
    @DisplayName("验证自定义前后缀下 EncryptTemplate 的 formatToken、encrypt、isEncrypted 与 decrypt 100% 对称往返闭环")
    void testCustomPrefixAndSuffixRoundTrip() {
        // 1. 开圆括号结尾自定义前缀：CIPHER( ... )
        EncryptProperties parenProps = new EncryptProperties();
        parenProps.setPassword(DEFAULT_PASSWORD);
        parenProps.setAlgorithm(EncryptAlgorithm.AES_GCM);
        parenProps.setPrefix("CIPHER(");
        parenProps.setSuffix(")");
        parenProps.setKeys(Map.of("dbKey", DB_PASSWORD));
        EncryptTemplate parenTemplate = new EncryptTemplate(
                parenProps, MultiKeyRegistry.fromProperties(parenProps),
                new CiphertextParser(parenProps.getPrefix(), parenProps.getSuffix()));

        String defaultToken = parenTemplate.encrypt(PLAINTEXT);
        assertTrue(defaultToken.startsWith("CIPHER(") && defaultToken.endsWith(")"));
        assertTrue(parenTemplate.isEncrypted(defaultToken));
        assertEquals(PLAINTEXT, parenTemplate.decrypt(defaultToken));

        String keyToken = parenTemplate.encrypt(PLAINTEXT, "dbKey");
        assertTrue(keyToken.startsWith("CIPHER[dbKey](") && keyToken.endsWith(")"));
        assertTrue(parenTemplate.isEncrypted(keyToken));
        assertEquals(PLAINTEXT, parenTemplate.decrypt(keyToken));
        assertEquals(PLAINTEXT, parenTemplate.decrypt(keyToken, "dbKey"));

        String algoKeyToken = parenTemplate.encrypt(PLAINTEXT, "dbKey", EncryptAlgorithm.SM4_CBC);
        assertTrue(algoKeyToken.startsWith("CIPHER[dbKey](SM4-CBC@") && algoKeyToken.endsWith(")"));
        assertTrue(parenTemplate.isEncrypted(algoKeyToken));
        assertEquals(PLAINTEXT, parenTemplate.decrypt(algoKeyToken));
        assertEquals("CIPHER[dbKey](SM4-CBC@xyz)", parenTemplate.formatToken("dbKey", EncryptAlgorithm.SM4_CBC, "xyz"));

        // 2. 开方括号结尾自定义前缀：MY_ENC[ ... ]
        EncryptProperties squareProps = new EncryptProperties();
        squareProps.setPassword(DEFAULT_PASSWORD);
        squareProps.setAlgorithm(EncryptAlgorithm.AES_GCM);
        squareProps.setPrefix("MY_ENC[");
        squareProps.setSuffix("]");
        squareProps.setKeys(Map.of("dbKey", DB_PASSWORD));
        EncryptTemplate squareTemplate = new EncryptTemplate(
                squareProps, MultiKeyRegistry.fromProperties(squareProps),
                new CiphertextParser(squareProps.getPrefix(), squareProps.getSuffix()));

        String squareToken = squareTemplate.encrypt(PLAINTEXT, "dbKey", EncryptAlgorithm.SM4_CBC);
        assertTrue(squareToken.startsWith("MY_ENC[dbKey][SM4-CBC@") && squareToken.endsWith("]"));
        assertTrue(squareTemplate.isEncrypted(squareToken));
        assertEquals(PLAINTEXT, squareTemplate.decrypt(squareToken));

        // 3. 非括号结尾自定义前缀：CIPHER: ... ;
        EncryptProperties colonProps = new EncryptProperties();
        colonProps.setPassword(DEFAULT_PASSWORD);
        colonProps.setAlgorithm(EncryptAlgorithm.AES_GCM);
        colonProps.setPrefix("CIPHER:");
        colonProps.setSuffix(";");
        colonProps.setKeys(Map.of("dbKey", DB_PASSWORD));
        EncryptTemplate colonTemplate = new EncryptTemplate(
                colonProps, MultiKeyRegistry.fromProperties(colonProps),
                new CiphertextParser(colonProps.getPrefix(), colonProps.getSuffix()));

        String colonToken = colonTemplate.encrypt(PLAINTEXT, "dbKey", EncryptAlgorithm.SM4_CBC);
        assertTrue(colonToken.startsWith("CIPHER:[dbKey]SM4-CBC@") && colonToken.endsWith(";"));
        assertTrue(colonTemplate.isEncrypted(colonToken));
        assertEquals(PLAINTEXT, colonTemplate.decrypt(colonToken));

        // 4. 构造期显式传入 defaultAlgorithm=SM4_CBC 时，缺省算法重载自动沿用 SM4_CBC
        EncryptTemplate sm4DefaultTemplate = new EncryptTemplate(
                properties, EncryptAlgorithm.SM4_CBC, keyRegistry, parser, null);
        String sm4Token = sm4DefaultTemplate.encrypt(PLAINTEXT, "dbKey");
        assertTrue(sm4Token.startsWith("ENC[dbKey]("));
        assertFalse(sm4Token.contains("SM4-CBC@"));
        assertEquals(PLAINTEXT, sm4DefaultTemplate.decrypt(sm4Token, "dbKey"));
    }
}
