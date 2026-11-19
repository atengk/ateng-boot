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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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

    @Test
    @DisplayName("测试包装属性源对非对称 RSA 与国密 SM2 透明解密")
    void testAsymmetricRsaAndSm2TransparentDecryption() throws Exception {
        // 1. RSA
        java.security.KeyPair rsaKeyPair = io.github.atengk.boot.encrypt.decryptor.RsaConfigDecryptor.generateKeyPair(2048);
        String rsaPub = io.github.atengk.boot.encrypt.decryptor.RsaConfigDecryptor.encodePublicKey(rsaKeyPair.getPublic());
        String rsaPriv = io.github.atengk.boot.encrypt.decryptor.RsaConfigDecryptor.encodePrivateKey(rsaKeyPair.getPrivate());
        String rsaPlain = "rsa_confidential_db_secret";
        String rsaCipher = io.github.atengk.boot.encrypt.decryptor.RsaConfigDecryptor.encrypt(rsaPlain, rsaPub);

        // 2. SM2
        io.github.atengk.boot.encrypt.decryptor.Sm2Engine.Sm2KeyPair sm2KeyPair = io.github.atengk.boot.encrypt.decryptor.Sm2ConfigDecryptor.generateKeyPair();
        String sm2Pub = sm2KeyPair.getPublicKeyHex();
        String sm2Priv = sm2KeyPair.getPrivateKeyHex();
        String sm2Plain = "sm2_wechat_payment_secret";
        String sm2Cipher = io.github.atengk.boot.encrypt.decryptor.Sm2ConfigDecryptor.encrypt(sm2Plain, sm2Pub);

        // 多密钥映射：仅注入私钥解密
        KeyDetector detector = new KeyDetector(masterPassword, Map.of(
                "rsa-prod", rsaPriv,
                "sm2-prod", sm2Priv
        ));

        Map<String, Object> map = new HashMap<>();
        map.put("custom.rsa.password", "ENC[rsa-prod](RSA@" + rsaCipher + ")");
        map.put("custom.sm2.password", "ENC[sm2-prod](SM2@" + sm2Cipher + ")");
        MapPropertySource rawSource = new MapPropertySource("asymSource", map);

        EncryptPropertySourceWrapper wrapper = new EncryptPropertySourceWrapper(
                rawSource,
                new CiphertextParser(),
                detector,
                null, // 默认自动装配所有解密器
                EncryptAlgorithm.AES_GCM,
                true
        );

        assertEquals(rsaPlain, wrapper.getProperty("custom.rsa.password"));
        assertEquals(sm2Plain, wrapper.getProperty("custom.sm2.password"));
    }

    @Test
    @DisplayName("测试 Spring Cloud {cipher} 模式受控兼容解密")
    void testSpringCloudCipherCompatibility() {
        String secretPlain = "spring_cloud_legacy_secret";
        String cipher = ConfigEncryptor.encrypt(secretPlain, masterPassword, EncryptAlgorithm.AES_GCM);

        Map<String, Object> map = new HashMap<>();
        map.put("legacy.cloud.secret", "{cipher}" + cipher);
        map.put("legacy.cloud.url", "jdbc:mysql://host:3306/db?password={cipher}" + cipher + "&ssl=false");

        MapPropertySource rawSource = new MapPropertySource("cloudSource", map);
        KeyDetector detector = new KeyDetector(masterPassword, Map.of());

        // 1. 当 compatibleCipherPrefix = false 时，视作普通字符串保真放行
        EncryptPropertySourceWrapper defaultWrapper = new EncryptPropertySourceWrapper(
                rawSource,
                new CiphertextParser(false),
                detector,
                null,
                EncryptAlgorithm.AES_GCM,
                true
        );
        assertEquals("{cipher}" + cipher, defaultWrapper.getProperty("legacy.cloud.secret"));
        assertEquals("jdbc:mysql://host:3306/db?password={cipher}" + cipher + "&ssl=false",
                defaultWrapper.getProperty("legacy.cloud.url"));

        // 2. 当 compatibleCipherPrefix = true 时，识别并成功解密
        EncryptPropertySourceWrapper compatibleWrapper = new EncryptPropertySourceWrapper(
                rawSource,
                new CiphertextParser(true),
                detector,
                null,
                EncryptAlgorithm.AES_GCM,
                true
        );
        assertEquals(secretPlain, compatibleWrapper.getProperty("legacy.cloud.secret"));
        assertEquals("jdbc:mysql://host:3306/db?password=" + secretPlain + "&ssl=false",
                compatibleWrapper.getProperty("legacy.cloud.url"));
    }

    @Test
    @DisplayName("测试 failOnError=false 时解密失败保底返回原始值且不污染缓存")
    void testFailOnErrorFalseReturnsRawValueWithoutPoisoningCache() {
        String plain = "recoverable_secret_2026";
        String cipher = ConfigEncryptor.encrypt(plain, masterPassword, EncryptAlgorithm.AES_GCM);
        String rawToken = "ENC(" + cipher + ")";

        MapPropertySource rawSource = new MapPropertySource("failSafeSource", Map.of(
                "app.recoverable.secret", rawToken
        ));
        // 初始使用错误密钥且 failOnError = false
        KeyDetector wrongDetector = new KeyDetector("WrongPassword", Map.of());
        EncryptPropertySourceWrapper wrapper = new EncryptPropertySourceWrapper(
                rawSource,
                new CiphertextParser(),
                wrongDetector,
                null,
                EncryptAlgorithm.AES_GCM,
                false
        );

        // 解密失败时不抛出异常，降级返回原始密文串
        assertEquals(rawToken, wrapper.getProperty("app.recoverable.secret"));

        // 修复密钥后再次读取，由于失败结果未污染缓存，应成功解密
        wrongDetector.setDefaultPassword(masterPassword);
        assertEquals(plain, wrapper.getProperty("app.recoverable.secret"));
    }

    @Test
    @DisplayName("测试 updateRuntimeConfig 运行时配置同步：对齐默认算法、解析器与容错策略并清空缓存")
    void testUpdateRuntimeConfigSynchronizesAllSettings() {
        String sm4Plain = "sm4_two_phase_secret";
        String sm4Cipher = ConfigEncryptor.encrypt(sm4Plain, masterPassword, EncryptAlgorithm.SM4_CBC);
        String legacyPlain = "legacy_cipher_secret";
        String legacyCipher = ConfigEncryptor.encrypt(legacyPlain, masterPassword, EncryptAlgorithm.SM4_CBC);

        MapPropertySource rawSource = new MapPropertySource("twoPhaseSource", Map.of(
                "app.sm4.secret", "ENC(" + sm4Cipher + ")",
                "app.legacy.secret", "{cipher}" + legacyCipher,
                "app.broken.secret", "ENC(CorruptedCipherValue)"
        ));

        // 模拟阶段一默认配置：AES_GCM、不兼容 {cipher}、failOnError=true
        KeyDetector detector = new KeyDetector(masterPassword, Map.of());
        EncryptPropertySourceWrapper wrapper = new EncryptPropertySourceWrapper(
                rawSource,
                new CiphertextParser(false),
                detector,
                null,
                EncryptAlgorithm.AES_GCM,
                true
        );

        // 同步阶段二最终配置：SM4_CBC、兼容 {cipher}、failOnError=false
        wrapper.updateRuntimeConfig(
                new CiphertextParser(true),
                detector,
                null,
                EncryptAlgorithm.SM4_CBC,
                false
        );

        assertEquals(sm4Plain, wrapper.getProperty("app.sm4.secret"));
        assertEquals(legacyPlain, wrapper.getProperty("app.legacy.secret"));
        assertEquals("ENC(CorruptedCipherValue)", wrapper.getProperty("app.broken.secret"));
    }

    @Test
    @DisplayName("测试多线程并发更新运行配置与密钥轮换时无死锁且状态一致")
    void testConcurrentUpdateRuntimeConfigAndRead() throws Exception {
        String plain = "concurrent_secret_value";
        String cipher = ConfigEncryptor.encrypt(plain, masterPassword, EncryptAlgorithm.AES_GCM);
        MapPropertySource rawSource = new MapPropertySource("concurrentSource", Map.of(
                "app.concurrent.secret", "ENC(" + cipher + ")"
        ));
        KeyDetector detector = new KeyDetector(masterPassword, Map.of());
        EncryptPropertySourceWrapper wrapper = new EncryptPropertySourceWrapper(rawSource, detector);

        int threadCount = 8;
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threadCount);
        executor.setMaxPoolSize(threadCount);
        executor.setThreadNamePrefix("wrapper-sync-test-");
        executor.initialize();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.execute(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < 20; j++) {
                            if (idx % 2 == 0) {
                                KeyDetector nextDetector = new KeyDetector(masterPassword, Map.of());
                                wrapper.updateRuntimeConfig(new CiphertextParser(), nextDetector, null,
                                        EncryptAlgorithm.AES_GCM, true);
                            } else {
                                assertEquals(plain, wrapper.getProperty("app.concurrent.secret"));
                            }
                        }
                    } catch (Throwable ex) {
                        errors.add(ex);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "并发读写应在限定时间内完成");
            assertTrue(errors.isEmpty(), "并发同步与读取不应抛出异常: " + errors);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("测试自定义前后缀与 [keyId] 及算法声明在属性源包装器中的透明解密")
    void testCustomPrefixWithKeyIdInWrapper() {
        String dbKey = "CustomPrefixDbSecret2026!";
        String cipherGcm = ConfigEncryptor.encrypt("db-plain-gcm", dbKey, EncryptAlgorithm.AES_GCM);
        String cipherSm4 = ConfigEncryptor.encrypt("db-plain-sm4", dbKey, EncryptAlgorithm.SM4_CBC);

        CiphertextParser parenParser = new CiphertextParser("CIPHER(", ")");
        MapPropertySource parenSource = new MapPropertySource("customParenSource", Map.of(
                "db.pass", parenParser.formatToken(EncryptAlgorithm.AES_GCM, "dbKey", EncryptAlgorithm.AES_GCM, cipherGcm),
                "db.sm4", parenParser.formatToken(EncryptAlgorithm.AES_GCM, "dbKey", EncryptAlgorithm.SM4_CBC, cipherSm4)
        ));
        KeyDetector detector = new KeyDetector(masterPassword, Map.of("dbKey", dbKey));
        EncryptPropertySourceWrapper parenWrapper = new EncryptPropertySourceWrapper(
                parenSource, parenParser, detector, null, EncryptAlgorithm.AES_GCM, true);

        assertEquals("db-plain-gcm", parenWrapper.getProperty("db.pass"));
        assertEquals("db-plain-sm4", parenWrapper.getProperty("db.sm4"));
        assertTrue(parenWrapper.isEncryptedProperty("db.pass"));

        CiphertextParser bracketParser = new CiphertextParser("MY_ENC[", "]");
        MapPropertySource bracketSource = new MapPropertySource("customBracketSource", Map.of(
                "db.bracket", bracketParser.formatToken(EncryptAlgorithm.AES_GCM, "dbKey", EncryptAlgorithm.SM4_CBC, cipherSm4)
        ));
        EncryptPropertySourceWrapper bracketWrapper = new EncryptPropertySourceWrapper(
                bracketSource, bracketParser, detector, null, EncryptAlgorithm.AES_GCM, true);
        assertEquals("db-plain-sm4", bracketWrapper.getProperty("db.bracket"));
        assertTrue(bracketWrapper.isEncryptedProperty("db.bracket"));
    }
}
