package io.github.atengk.boot.encrypt.wrapper;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.decryptor.AesGcmConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.ConfigEncryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm4ConfigDecryptor;
import io.github.atengk.boot.encrypt.detector.KeyDetector;
import io.github.atengk.boot.encrypt.exception.ConfigDecryptionException;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 加密属性源动态装饰器单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class EncryptPropertySourceWrapperTest {

    private final String masterPassword = "TestMasterPassword@2026";

    @Test
    @DisplayName("测试包装属性源正常透明解密与明文保真放行")
    void testWrapperTransparentDecryption() {
        String dbPassPlain = "my_mysql_pass_999";
        String redisPassPlain = "redis_secret_888";

        String dbCipher = ConfigEncryptor.encrypt(dbPassPlain, masterPassword, EncryptAlgorithm.AES_GCM);
        String redisCipher = ConfigEncryptor.encrypt(redisPassPlain, masterPassword, EncryptAlgorithm.SM4_CBC);

        Map<String, Object> map = new HashMap<>();
        map.put("spring.datasource.password", "ENC(" + dbCipher + ")");
        map.put("spring.redis.password", "ENC(SM4@" + redisCipher + ")");
        map.put("server.port", 8080);
        map.put("app.name", "ateng-service");
        map.put("spring.datasource.url", "jdbc:mysql://localhost:3306/db?user=root&password=ENC(" + dbCipher + ")&ssl=false");

        MapPropertySource rawSource = new MapPropertySource("testSource", map);
        KeyDetector detector = new KeyDetector(masterPassword, Map.of());

        EncryptPropertySourceWrapper wrapper = new EncryptPropertySourceWrapper(
                rawSource,
                new CiphertextParser(),
                detector,
                List.of(new AesGcmConfigDecryptor(), new Sm4ConfigDecryptor()),
                EncryptAlgorithm.AES_GCM,
                true
        );

        // 1. 验证整字段 AES-GCM 解密
        assertEquals(dbPassPlain, wrapper.getProperty("spring.datasource.password"));

        // 2. 验证显式 SM4 国密解密
        assertEquals(redisPassPlain, wrapper.getProperty("spring.redis.password"));

        // 3. 验证非字符串与未加密字符串原样放行
        assertEquals(8080, wrapper.getProperty("server.port"));
        assertEquals("ateng-service", wrapper.getProperty("app.name"));

        // 4. 验证内嵌复合字符串解密替换
        assertEquals("jdbc:mysql://localhost:3306/db?user=root&password=" + dbPassPlain + "&ssl=false",
                wrapper.getProperty("spring.datasource.url"));
    }

    @Test
    @DisplayName("测试解密缓存命中与清空机制")
    void testCacheMechanism() {
        String plain = "cached_secret";
        String cipher = ConfigEncryptor.encrypt(plain, masterPassword, EncryptAlgorithm.AES_GCM);

        MapPropertySource rawSource = new MapPropertySource("cacheSource", Map.of("my.secret", "ENC(" + cipher + ")"));
        KeyDetector detector = new KeyDetector(masterPassword, Map.of());

        EncryptPropertySourceWrapper wrapper = new EncryptPropertySourceWrapper(
                rawSource,
                new CiphertextParser(),
                detector,
                List.of(new AesGcmConfigDecryptor()),
                EncryptAlgorithm.AES_GCM,
                true
        );

        // 首次读取触发解密
        assertEquals(plain, wrapper.getProperty("my.secret"));
        // 再次读取走缓存
        assertEquals(plain, wrapper.getProperty("my.secret"));

        // 清空缓存
        wrapper.clearCache();
        assertEquals(plain, wrapper.getProperty("my.secret"));
    }

    @Test
    @DisplayName("测试解密失败时抛出专职异常并精准指明出错属性 Key")
    void testDecryptionFailurePinpointsPropertyKey() {
        // 使用一个被篡改或错误密码加密的密文
        String invalidCipher = "ENC(InvalidCiphertextThatCannotBeDecrypted123)";
        MapPropertySource rawSource = new MapPropertySource("errSource", Map.of(
                "spring.datasource.password", invalidCipher
        ));
        KeyDetector detector = new KeyDetector("WrongPassword", Map.of());

        EncryptPropertySourceWrapper wrapper = new EncryptPropertySourceWrapper(
                rawSource,
                new CiphertextParser(),
                detector,
                List.of(new AesGcmConfigDecryptor()),
                EncryptAlgorithm.AES_GCM,
                true
        );

        ConfigDecryptionException ex = assertThrows(ConfigDecryptionException.class, () ->
                wrapper.getProperty("spring.datasource.password")
        );

        // 断言精准指示属性 Key 名称
        assertEquals("spring.datasource.password", ex.getPropertyKey());
        assertTrue(ex.getMessage().contains("spring.datasource.password"));
        // 断言严禁在异常中泄露原始密文
        assertFalse(ex.getMessage().contains(invalidCipher));
    }
}
