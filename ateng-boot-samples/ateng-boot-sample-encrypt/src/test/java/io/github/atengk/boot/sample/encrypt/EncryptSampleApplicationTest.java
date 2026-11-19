package io.github.atengk.boot.sample.encrypt;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.decryptor.AbstractKmsConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.ConfigEncryptor;
import io.github.atengk.boot.encrypt.exception.ConfigDecryptionException;
import io.github.atengk.boot.encrypt.exception.ConfigSecurityException;
import io.github.atengk.boot.encrypt.guard.PlaintextIngressGuard;
import io.github.atengk.boot.encrypt.guard.SanitizingGuard;
import io.github.atengk.boot.encrypt.key.MultiKeyRegistry;
import io.github.atengk.boot.encrypt.template.EncryptTemplate;
import io.github.atengk.boot.encrypt.wrapper.EncryptPropertySourceWrapper;
import io.github.atengk.boot.sample.encrypt.config.SampleSecretProperties;
import io.github.atengk.boot.sample.encrypt.service.ConfigDecryptionScenarioService;
import io.github.atengk.boot.sample.encrypt.service.IngressGuardScenarioService;
import io.github.atengk.boot.sample.encrypt.service.InPlaceRewriteScenarioService;
import io.github.atengk.boot.sample.encrypt.service.MultiKeyRoutingScenarioService;
import io.github.atengk.boot.sample.encrypt.service.ProgrammaticCryptoScenarioService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.SanitizableData;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.crypto.BadPaddingException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置加解密全场景样例端到端自验集成测试套件
 * 纯内存自洽验证：5 大专职场景服务实测、Actuator 物理脱敏及生产强制门禁阻断，零 Web / MockMvc 依赖
 *
 * @author Ateng
 * @since 2026-10-25
 */
@SpringBootTest(classes = EncryptSampleApplication.class, properties = {
        "ateng.boot.encrypt.password=SampleMasterSecretKey2026!"
})
class EncryptSampleApplicationTest {

    @Autowired
    private ConfigDecryptionScenarioService configDecryptionScenarioService;

    @Autowired
    private MultiKeyRoutingScenarioService multiKeyRoutingScenarioService;

    @Autowired
    private ProgrammaticCryptoScenarioService programmaticCryptoScenarioService;

    @Autowired
    private InPlaceRewriteScenarioService inPlaceRewriteScenarioService;

    @Autowired
    private IngressGuardScenarioService ingressGuardScenarioService;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("场景 1 实测: ConfigDecryptionScenarioService 配置属性与 @Value 透明懒解密")
    void testScenario1_ConfigDecryption() {
        assertNotNull(configDecryptionScenarioService, "ConfigDecryptionScenarioService 必须成功注入");

        // 1. 验证默认 AES-GCM 算法透明解密
        assertEquals("mysqlRootPass2026!", configDecryptionScenarioService.getDatabasePasswordFromProperties(),
                "数据库密码应成功解密为真实明文");

        // 2. 验证多 Key 别名路由 [redis-cluster] 透明解密
        assertEquals("redisAuthPass2026!", configDecryptionScenarioService.getRedisPasswordFromProperties(),
                "Redis密码应经由 redis-cluster 命名密钥成功解密");

        // 3. 验证显式国密 SM4-CBC 算法透明解密
        assertEquals("sm4ApiSecretToken888", configDecryptionScenarioService.getThirdPartyTokenFromProperties(),
                "第三方令牌应经由国密 SM4-CBC 成功解密");

        // 4. 验证 @Value 与 @ConfigurationProperties 注入值自洽
        assertTrue(configDecryptionScenarioService.verifyDecryptionConsistency(),
                "@ConfigurationProperties 与 @Value 注入的解密明文必须完全一致");
    }

    @Test
    @DisplayName("场景 2 实测: MultiKeyRoutingScenarioService 多 Key 别名路由与动态密钥轮换")
    void testScenario2_MultiKeyRouting() {
        assertNotNull(multiKeyRoutingScenarioService, "MultiKeyRoutingScenarioService 必须成功注入");

        // 1. 验证配置文件预置的 redis-cluster 密钥存在
        assertTrue(multiKeyRoutingScenarioService.containsKey("redis-cluster"));
        assertEquals("RedisSpecialClusterKey2026!", multiKeyRoutingScenarioService.getKeyOrDefault("redis-cluster"));

        // 2. 验证使用带有别名标签的密文执行解密
        String token = "ENC[redis-cluster](YbIg1S6F2964mdnyLgtvn1D4SjJ2QqxN8M45CBCnWs0VpgEJ4b5wEZyEKOJyDA==)";
        String decrypted = multiKeyRoutingScenarioService.decryptRoutedToken(token);
        assertEquals("redisAuthPass2026!", decrypted, "多 Key 别名路由密文应成功解密");

        // 3. 验证动态注册与轮换新密钥
        String newKeyId = "payment-shard-key";
        String newKeyPassword = "NewPaymentShardPassword2026!";
        multiKeyRoutingScenarioService.rotateKey(newKeyId, newKeyPassword);

        assertTrue(multiKeyRoutingScenarioService.containsKey(newKeyId), "轮换后密钥应存在于多密钥注册表中");
        assertEquals(newKeyPassword, multiKeyRoutingScenarioService.getKeyOrDefault(newKeyId));
    }

    @Test
    @DisplayName("场景 3 实测: ProgrammaticCryptoScenarioService 编程式门面与多算法对称加解密")
    void testScenario3_ProgrammaticCrypto() {
        assertNotNull(programmaticCryptoScenarioService, "ProgrammaticCryptoScenarioService 必须成功注入");

        // 1. 默认 AES-GCM 编程式加解密闭环
        String plainText = "MySuperSecretDatabasePassword999";
        String encToken = programmaticCryptoScenarioService.encryptDefault(plainText);
        assertNotNull(encToken);
        assertTrue(encToken.startsWith("ENC("));
        assertTrue(encToken.endsWith(")"));
        assertEquals(plainText, programmaticCryptoScenarioService.decrypt(encToken));

        // 2. 指定算法与别名加解密
        String sm4Cipher = programmaticCryptoScenarioService.encryptWithKeyAndAlgorithm(
                "Sm4PaymentSecretToken", "redis-cluster", EncryptAlgorithm.SM4_CBC);
        assertTrue(sm4Cipher.contains("SM4-CBC@"));
        assertEquals("Sm4PaymentSecretToken", programmaticCryptoScenarioService.decrypt(sm4Cipher));

        // 3. 裸密文加密
        String rawCipher = programmaticCryptoScenarioService.encryptRaw("RawSecretValue");
        assertFalse(rawCipher.startsWith("ENC("));
        assertEquals("RawSecretValue", programmaticCryptoScenarioService.decrypt(rawCipher));

        // 4. 按命名密钥沿用环境默认算法快捷加解密
        String keyToken = programmaticCryptoScenarioService.encryptWithKey("NamedKeySecret", "redis-cluster");
        assertTrue(keyToken.startsWith("ENC[redis-cluster]("));
        assertEquals("NamedKeySecret", programmaticCryptoScenarioService.decryptWithKey(keyToken, "redis-cluster"));

        String rawKeyCipher = programmaticCryptoScenarioService.encryptRawWithKey("NamedKeyRawSecret", "redis-cluster");
        assertFalse(rawKeyCipher.startsWith("ENC("));
        assertEquals("NamedKeyRawSecret", programmaticCryptoScenarioService.decryptWithKey(rawKeyCipher, "redis-cluster"));

        // 5. 容错逃生舱解密
        assertEquals("plainTextOriginal", programmaticCryptoScenarioService.decryptOrOriginal("plainTextOriginal"));
        assertEquals("fallbackDefaultValue", programmaticCryptoScenarioService.decryptOrDefault("not_a_valid_cipher", "fallbackDefaultValue"));

        // 5. 底层 ConfigEncryptor 纯原生静态门面
        String staticResult = programmaticCryptoScenarioService.executeDirectStaticCrypto(
                "StaticCryptoTest", "MyStaticKey2026!Master", EncryptAlgorithm.AES_GCM);
        assertEquals("StaticCryptoTest", staticResult);
    }

    @Test
    @DisplayName("场景 4 实测: InPlaceRewriteScenarioService 配置文件就地加解密与格式保护")
    void testScenario4_InPlaceRewrite(@TempDir Path tempDir) throws Exception {
        assertNotNull(inPlaceRewriteScenarioService, "InPlaceRewriteScenarioService 必须成功注入");

        // 1. 测试 YAML 配置文件就地加解密
        String yamlContent = """
                server:
                  port: 9090
                
                # 生产环境数据源
                spring:
                  datasource:
                    password: myPlaintextRootPass123 # @encrypt 数据库明文密码
                """;

        Path yamlFile = tempDir.resolve("sample-app.yml");
        Files.writeString(yamlFile, yamlContent, StandardCharsets.UTF_8);

        int encYamlCount = inPlaceRewriteScenarioService.rewriteEncryptYaml(yamlFile, "SampleMasterSecretKey2026!");
        assertEquals(1, encYamlCount);
        assertTrue(Files.exists(tempDir.resolve("sample-app.yml.bak")), "加密时必须自动生成 .bak 备份");

        String encryptedYaml = Files.readString(yamlFile, StandardCharsets.UTF_8);
        assertTrue(encryptedYaml.contains("    password: ENC("));
        assertTrue(encryptedYaml.contains("# @encrypt 数据库明文密码"));
        assertFalse(encryptedYaml.contains("myPlaintextRootPass123"));

        int decYamlCount = inPlaceRewriteScenarioService.rewriteDecryptYaml(yamlFile, "SampleMasterSecretKey2026!");
        assertEquals(1, decYamlCount);
        String restoredYaml = Files.readString(yamlFile, StandardCharsets.UTF_8);
        assertTrue(restoredYaml.contains("myPlaintextRootPass123"));

        // 2. 测试 Properties 配置文件就地加解密
        String propContent = """
                # 生产环境配置
                spring.datasource.password=myPropertiesPassword888 # @encrypt 数据源密码
                server.port=8080
                """;

        Path propFile = tempDir.resolve("sample-app.properties");
        Files.writeString(propFile, propContent, StandardCharsets.UTF_8);

        int encPropCount = inPlaceRewriteScenarioService.rewriteEncryptProperties(propFile, "SampleMasterSecretKey2026!");
        assertEquals(1, encPropCount);
        assertTrue(Files.exists(tempDir.resolve("sample-app.properties.bak")));

        String encryptedProp = Files.readString(propFile, StandardCharsets.UTF_8);
        assertTrue(encryptedProp.contains("spring.datasource.password=ENC("));
        assertFalse(encryptedProp.contains("myPropertiesPassword888"));

        int decPropCount = inPlaceRewriteScenarioService.rewriteDecryptProperties(propFile, "SampleMasterSecretKey2026!");
        assertEquals(1, decPropCount);
        String restoredProp = Files.readString(propFile, StandardCharsets.UTF_8);
        assertTrue(restoredProp.contains("myPropertiesPassword888"));
    }

    @Test
    @DisplayName("场景 5 实测: IngressGuardScenarioService 配置动态刷新与缓存原子置换")
    void testScenario5_IngressGuard() {
        assertNotNull(ingressGuardScenarioService, "IngressGuardScenarioService 必须成功注入");

        // 1. 模拟全量环境刷新
        assertDoesNotThrow(() -> ingressGuardScenarioService.triggerFullRefresh(),
                "全量环境刷新不应抛出任何异常");

        // 2. 模拟针对特定敏感键名的局部热更新
        assertDoesNotThrow(() -> ingressGuardScenarioService.triggerKeyRefresh(Set.of("sample.secret.database-password")),
                "指定键局部刷新不应抛出异常");

        // 3. 验证刷新后环境属性依然保持可用且透明解密
        assertEquals("mysqlRootPass2026!", ingressGuardScenarioService.getEnvironmentProperty("sample.secret.database-password"));
    }

    @Test
    @DisplayName("端到端验证 Actuator SanitizingGuard 运行时端点物理掩码脱敏防泄露机制")
    void testActuatorSanitizingGuardMasking() {
        SanitizingGuard sanitizingGuard = applicationContext.getBean(SanitizingGuard.class);
        assertNotNull(sanitizingGuard, "SanitizingGuard 脱敏守卫必须由 Actuator 自动装配成功");

        // 1. 命中敏感属性正则的字段输出为物理掩码 ******
        SanitizableData sensitiveData = new SanitizableData(null, "sample.secret.database-password", "mysqlRootPass2026!");
        SanitizableData sanitized = sanitizingGuard.apply(sensitiveData);
        assertEquals("******", sanitized.getValue(), "敏感配置键名在 Actuator 端点必须物理脱敏为 ******");

        // 2. 包含 ENC(...) 密文标记的字段输出为物理掩码 ******
        SanitizableData encData = new SanitizableData(null, "custom.connection.token", "ENC(CZAQmB7bs...)");
        SanitizableData sanitizedEnc = sanitizingGuard.apply(encData);
        assertEquals("******", sanitizedEnc.getValue(), "带有密文标记的字段在 Actuator 端点必须物理脱敏为 ******");

        // 3. 主密钥与任意命名多密钥键前缀在 Actuator 端点必须无条件物理脱敏为 ******
        SanitizableData masterPasswordData = new SanitizableData(null, "ateng.boot.encrypt.password", "SampleMasterSecretKey2026!");
        assertEquals("******", sanitizingGuard.apply(masterPasswordData).getValue(),
                "主密钥配置在 Actuator 端点必须物理脱敏为 ******");

        SanitizableData redisKeyData = new SanitizableData(null, "ateng.boot.encrypt.keys.redis-cluster", "RedisSpecialClusterKey2026!");
        assertEquals("******", sanitizingGuard.apply(redisKeyData).getValue(),
                "命名多密钥配置在 Actuator 端点必须物理脱敏为 ******");

        SanitizableData plainNameKeyData = new SanitizableData(null, "ateng.boot.encrypt.keys.alpha", "PlainShardSecret2026!");
        assertEquals("******", sanitizingGuard.apply(plainNameKeyData).getValue(),
                "不含敏感词的命名多密钥在 Actuator 端点也必须无条件物理脱敏为 ******");

        // 4. 非敏感普通属性保真放行
        SanitizableData normalData = new SanitizableData(null, "server.port", 8080);
        SanitizableData passthrough = sanitizingGuard.apply(normalData);
        assertEquals(8080, passthrough.getValue(), "普通非敏感配置在 Actuator 端点必须原样保真输出");
    }

    @Test
    @DisplayName("端到端验证生产环境强制加密门禁 enforce-encrypted 阻断未加密敏感配置")
    void testEnforceEncryptedBlocksUnencryptedSecretsInApplication() {
        assertThrows(Exception.class, () -> {
            new SpringApplicationBuilder(EncryptSampleApplication.class)
                    .properties(
                            "spring.profiles.active=test",
                            "ateng.boot.encrypt.password=SampleMasterSecretKey2026!",
                            "ateng.boot.encrypt.enforce-encrypted=true"
                    )
                    .initializers(context -> {
                        // 注入携带裸明文敏感属性的非系统属性源（模拟运维误提交）
                        Map<String, Object> map = Map.of("spring.datasource.password", "nakedPlaintextSecret999");
                        context.getEnvironment().getPropertySources().addFirst(
                                new MapPropertySource("misconfigured-external-source", map));
                    })
                    .run();
        }, "存在裸明文敏感配置且开启 enforce-encrypted=true 时必须阻断启动");
    }

    @Test
    @DisplayName("端到端验证配置中心动态推送裸明文敏感属性时被 PlaintextIngressGuard 刚性拦截")
    void testPlaintextIngressGuardBlocksDynamicUnencryptedPush() {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(EncryptSampleApplication.class)
                .run(
                        "--spring.profiles.active=test",
                        "--ateng.boot.encrypt.password=SampleMasterSecretKey2026!",
                        "--ateng.boot.encrypt.enforce-encrypted=true"
                );
        try {
            IngressGuardScenarioService service = ctx.getBean(IngressGuardScenarioService.class);
            assertNotNull(service);

            // 1. 动态推送裸明文敏感字符串时必须抛出异常刚性拦截
            assertThrows(ConfigSecurityException.class, () -> {
                service.simulatePlaintextIngress("spring.datasource.password", "hotPushedPlaintext123");
            }, "热推送裸明文密码必须被 PlaintextIngressGuard 刚性拦截");

            // 2. 动态推送未加引号纯数字密码时亦必须刚性拦截
            assertThrows(ConfigSecurityException.class, () -> {
                service.simulatePlaintextIngress("spring.redis.password", 123456);
            }, "热推送未加引号纯数字密码必须被 PlaintextIngressGuard 刚性拦截");
        } finally {
            ctx.close();
        }
    }

    @EnableAutoConfiguration
    @EnableConfigurationProperties(SampleSecretProperties.class)
    static class MinimalEncryptBootApp {
    }

    @Test
    @DisplayName("端到端验证全局开关 enabled=false 时全生命周期短路退出且保持属性源未包装")
    void testDisabledModeShortCircuitsEarlyLifecycleAndSkipsWrapping(@TempDir Path tempDir) throws Exception {
        Path disabledYaml = tempDir.resolve("disabled-app.yml");
        Files.writeString(disabledYaml, """
                ateng:
                  boot:
                    encrypt:
                      enabled: false
                      password: ignoredLocalPasswordWhenDisabled
                      enforce-encrypted: true
                sample:
                  secret:
                    database-password: plainDbPassword
                    redis-password: plainRedisPassword
                    third-party-token: plainThirdPartyToken
                """, StandardCharsets.UTF_8);

        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(MinimalEncryptBootApp.class)
                .run(
                        "--spring.main.web-application-type=none",
                        "--spring.config.location=" + disabledYaml.toUri()
                );
        try {
            boolean hasWrapper = false;
            for (PropertySource<?> ps : ctx.getEnvironment().getPropertySources()) {
                if (ps instanceof EncryptPropertySourceWrapper) {
                    hasWrapper = true;
                    break;
                }
            }
            assertFalse(hasWrapper, "enabled=false 时不应装配任何 EncryptPropertySourceWrapper");
            assertTrue(ctx.getBeansOfType(EncryptTemplate.class).isEmpty(), "enabled=false 时不应装配 EncryptTemplate");
            assertEquals("plainDbPassword", ctx.getBean(SampleSecretProperties.class).getDatabasePassword());
        } finally {
            ctx.close();
        }
    }

    @Test
    @DisplayName("端到端验证 fail-on-error=false 时密文解密失败不阻断启动并保底返回原值")
    void testFailOnErrorFalseAllowsStartupAndPreservesRawCiphertext() {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(EncryptSampleApplication.class)
                .run(
                        "--spring.main.web-application-type=none",
                        "--ateng.boot.encrypt.password=SampleMasterSecretKey2026!",
                        "--ateng.boot.encrypt.fail-on-error=false",
                        "--sample.secret.database-password=ENC(CorruptedNonDecryptableValue)"
                );
        try {
            SampleSecretProperties props = ctx.getBean(SampleSecretProperties.class);
            assertEquals("ENC(CorruptedNonDecryptableValue)", props.getDatabasePassword());
            assertEquals("redisAuthPass2026!", props.getRedisPassword());
        } finally {
            ctx.close();
        }
    }

    @Test
    @DisplayName("端到端验证 Two-Phase Wrapper Sync：ConfigData 导入后置覆盖算法、兼容开关与 fail-on-error")
    void testTwoPhaseConfigDataImportAndPostProcessorSync(@TempDir Path tempDir) throws Exception {
        String masterPass = "TwoPhaseSampleMaster2026!";
        String sm4Cipher = ConfigEncryptor.encrypt("twoPhaseSm4DbPass", masterPass, EncryptAlgorithm.SM4_CBC);
        String legacyCipher = ConfigEncryptor.encrypt("twoPhaseLegacyRedisPass", masterPass, EncryptAlgorithm.SM4_CBC);

        Path overrideYaml = tempDir.resolve("override.yml");
        Files.writeString(overrideYaml, """
                ateng:
                  boot:
                    encrypt:
                      algorithm: SM4-CBC
                      compatible-cipher-prefix: true
                      fail-on-error: false
                """, StandardCharsets.UTF_8);

        Path baseYaml = tempDir.resolve("base.yml");
        Files.writeString(baseYaml, String.format("""
                spring:
                  config:
                    import: "optional:%s"
                sample:
                  secret:
                    database-password: ENC(%s)
                    redis-password: "{cipher}%s"
                    third-party-token: ENC(BrokenTwoPhaseCipher)
                """, overrideYaml.toUri(), sm4Cipher, legacyCipher), StandardCharsets.UTF_8);

        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(MinimalEncryptBootApp.class)
                .run(
                        "--spring.main.web-application-type=none",
                        "--ateng.boot.encrypt.password=" + masterPass,
                        "--spring.config.location=" + baseYaml.toUri()
                );
        try {
            SampleSecretProperties props = ctx.getBean(SampleSecretProperties.class);
            assertEquals("twoPhaseSm4DbPass", props.getDatabasePassword());
            assertEquals("twoPhaseLegacyRedisPass", props.getRedisPassword());
            assertEquals("ENC(BrokenTwoPhaseCipher)", props.getThirdPartyToken());
        } finally {
            ctx.close();
        }
    }

    @TestConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties(KmsSampleProperties.class)
    static class KmsBridgeE2EApp {

        @Bean
        AbstractKmsConfigDecryptor sampleKmsDecryptor(KmsInvocationTracker tracker) {
            return new AbstractKmsConfigDecryptor() {
                @Override
                protected String doDecrypt(String ciphertext, String password) throws Exception {
                    if ("bad-padding".equals(ciphertext)) {
                        tracker.badPaddingAttempts.incrementAndGet();
                        throw new BadPaddingException("sample bad padding");
                    }
                    int attempt = tracker.transientAttempts.incrementAndGet();
                    if (attempt < 4) {
                        throw new IOException("sample transient kms failure #" + attempt);
                    }
                    return "e2e-kms:" + ciphertext;
                }
            };
        }

        @Bean
        KmsInvocationTracker kmsInvocationTracker() {
            return new KmsInvocationTracker();
        }

        @Bean
        EarlyKmsConsumerService earlyKmsConsumerService(
                @Value("${sample.kms.value-secret}") String valueSecret,
                KmsSampleProperties kmsSampleProperties) {
            return new EarlyKmsConsumerService(valueSecret, kmsSampleProperties);
        }
    }

    static class KmsInvocationTracker {
        final AtomicInteger transientAttempts = new AtomicInteger();
        final AtomicInteger badPaddingAttempts = new AtomicInteger();
    }

    @ConfigurationProperties(prefix = "sample.kms")
    static class KmsSampleProperties {
        private String configSecret;

        public String getConfigSecret() {
            return configSecret;
        }

        public void setConfigSecret(String configSecret) {
            this.configSecret = configSecret;
        }
    }

    static class EarlyKmsConsumerService {
        private final String valueSecret;
        private final KmsSampleProperties kmsSampleProperties;

        EarlyKmsConsumerService(String valueSecret, KmsSampleProperties kmsSampleProperties) {
            this.valueSecret = valueSecret;
            this.kmsSampleProperties = kmsSampleProperties;
        }

        String getValueSecret() {
            return valueSecret;
        }

        String getConfigSecret() {
            return kmsSampleProperties.getConfigSecret();
        }
    }

    @Test
    @DisplayName("端到端验证容器期 KMS Bean 反向桥接至 @Value / @ConfigurationProperties / EncryptTemplate，且不触发双重重试")
    void testContainerKmsBridgeWithValueAndConfigurationPropertiesAndNoDoubleRetry() {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(KmsBridgeE2EApp.class)
                .run(
                        "--spring.main.web-application-type=none",
                        "--ateng.boot.encrypt.password=SampleMasterSecretKey2026!",
                        "--ateng.boot.encrypt.retry.max-attempts=4",
                        "--ateng.boot.encrypt.retry.delay=5ms",
                        "--sample.kms.value-secret=ENC(KMS@value-payload)",
                        "--sample.kms.config-secret=ENC(KMS@config-payload)",
                        "--sample.kms.bad-padding=ENC(KMS@bad-padding)"
                );
        try {
            KmsInvocationTracker tracker = ctx.getBean(KmsInvocationTracker.class);
            EarlyKmsConsumerService consumer = ctx.getBean(EarlyKmsConsumerService.class);
            assertEquals("e2e-kms:value-payload", consumer.getValueSecret());
            assertEquals("e2e-kms:config-payload", consumer.getConfigSecret());
            // @Value(4次) + @ConfigurationProperties(每次doDecrypt到4才成功) => 4 + 1 = 5（注意 attempt>=4 时不再失败，说明首次第4次成功、第二次直接1次成功）
            assertEquals(5, tracker.transientAttempts.get());

            tracker.transientAttempts.set(0);
            EncryptTemplate template = ctx.getBean(EncryptTemplate.class);
            // 验证 BadPaddingException 快速失败（仅尝试 1 次，触发条件为 EncryptPropertySourceWrapper 与 EncryptTemplate）
            PropertySource<?> commandLineSource = ctx.getEnvironment().getPropertySources().get("commandLineArgs");
            assertInstanceOf(EncryptPropertySourceWrapper.class, commandLineSource);
            assertThrows(ConfigDecryptionException.class,
                    () -> commandLineSource.getProperty("sample.kms.bad-padding"));
            assertEquals(1, tracker.badPaddingAttempts.get());

            tracker.badPaddingAttempts.set(0);
            assertThrows(ConfigDecryptionException.class,
                    () -> template.decrypt("ENC(KMS@bad-padding)"));
            assertEquals(1, tracker.badPaddingAttempts.get());
            assertEquals("e2e-kms:template-payload", template.decrypt("ENC(KMS@template-payload)"));
            assertEquals(4, tracker.transientAttempts.get());
        } finally {
            ctx.close();
        }
    }

    @Test
    @DisplayName("端到端验证热更新与 MultiKeyRegistry 单例联动：保留运行期轮换密钥、支持 KMS 与新属性源缓存失效")
    void testHotRefreshSharesManagedRegistryWithRotationAndKms() {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(KmsBridgeE2EApp.class)
                .run(
                        "--spring.main.web-application-type=none",
                        "--ateng.boot.encrypt.password=SampleMasterSecretKey2026!",
                        "--ateng.boot.encrypt.retry.max-attempts=4",
                        "--ateng.boot.encrypt.retry.delay=5ms",
                        "--sample.kms.value-secret=ENC(KMS@startup-ok)",
                        "--sample.kms.config-secret=ENC(KMS@startup-config)"
                );
        try {
            MultiKeyRegistry registry = ctx.getBean(MultiKeyRegistry.class);
            PlaintextIngressGuard guard = ctx.getBean(PlaintextIngressGuard.class);
            ConfigurableEnvironment env = ctx.getEnvironment();

            String rotatedKeyV1 = "E2eHotKeyV1@2026";
            String rotatedKeyV2 = "E2eHotKeyV2@2026";
            registry.registerKey("hot-e2e-key", rotatedKeyV1);

            Map<String, Object> hotMap = new HashMap<>();
            hotMap.put("sample.hot.rotated", "ENC[hot-e2e-key](" + ConfigEncryptor.encrypt("hot-v1", rotatedKeyV1, EncryptAlgorithm.AES_GCM) + ")");
            hotMap.put("sample.hot.kms", "ENC(KMS@hot-kms-payload)");
            env.getPropertySources().addFirst(new MapPropertySource("e2eNacosHotSource", hotMap));

            guard.onEnvironmentChange(Set.of("sample.hot.rotated", "sample.hot.kms"), env);

            assertEquals("hot-v1", env.getProperty("sample.hot.rotated"));
            assertEquals("e2e-kms:hot-kms-payload", env.getProperty("sample.hot.kms"));

            PropertySource<?> wrapped = env.getPropertySources().get("e2eNacosHotSource");
            assertInstanceOf(EncryptPropertySourceWrapper.class, wrapped);
            assertSame(registry, ((EncryptPropertySourceWrapper) wrapped).getKeyRegistry());

            // 再次轮换密钥验证新纳管属性源缓存同步失效
            hotMap.put("sample.hot.rotated", "ENC[hot-e2e-key](" + ConfigEncryptor.encrypt("hot-v2", rotatedKeyV2, EncryptAlgorithm.AES_GCM) + ")");
            registry.registerKey("hot-e2e-key", rotatedKeyV2);
            assertEquals("hot-v2", env.getProperty("sample.hot.rotated"));
        } finally {
            ctx.close();
        }
    }

    @Test
    @DisplayName("端到端验证 GET /sample/encrypt/guard/rules 与自定义 EncryptProperties 动态配置 100% 一致")
    void testGuardRulesEndpointReflectsBoundEncryptProperties() throws Exception {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(EncryptSampleApplication.class)
                .run(
                        "--server.port=0",
                        "--ateng.boot.encrypt.password=SampleMasterSecretKey2026!",
                        "--ateng.boot.encrypt.enforce-encrypted=true",
                        "--ateng.boot.encrypt.sensitive-key-pattern=.*custom-credential.*|.*password.*",
                        "--ateng.boot.encrypt.ignored-keys[0]=sample.secret.custom-whitelist",
                        "--ateng.boot.encrypt.ignored-keys[1]=sample.secret.secondary-whitelist"
                );
        try {
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) ctx).build();
            mockMvc.perform(get("/sample/encrypt/guard/rules"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.enforceEncrypted").value(true))
                    .andExpect(jsonPath("$.data.sensitivePattern").value(".*custom-credential.*|.*password.*"))
                    .andExpect(jsonPath("$.data.ignoredKeys",
                            containsInAnyOrder("sample.secret.custom-whitelist", "sample.secret.secondary-whitelist")));
        } finally {
            ctx.close();
        }
    }

    @Test
    @DisplayName("端到端验证 application-test.yml 本地文件声明主密钥无法绕过自加密防卫阻断")
    void testApplicationTestYamlCannotBypassSelfEncryptionGuard(@TempDir Path tempDir) throws Exception {
        Path testProfileYaml = tempDir.resolve("application-test.yml");
        Files.writeString(testProfileYaml, """
                ateng:
                  boot:
                    encrypt:
                      password: LeakedPasswordInApplicationTestYaml
                """, StandardCharsets.UTF_8);

        Throwable thrown = assertThrows(Throwable.class, () -> {
            ConfigurableApplicationContext ctx = new SpringApplicationBuilder(MinimalEncryptBootApp.class)
                    .run(
                            "--spring.main.web-application-type=none",
                            "--spring.profiles.active=test",
                            "--spring.config.additional-location=" + testProfileYaml.toUri()
                    );
            ctx.close();
        });
        Throwable cursor = thrown;
        boolean foundConfigSecurityException = false;
        while (cursor != null) {
            if (cursor instanceof ConfigSecurityException) {
                foundConfigSecurityException = true;
                break;
            }
            cursor = cursor.getCause();
        }
        assertTrue(foundConfigSecurityException,
                "application-test.yml 中声明主密钥时必须触发 ConfigSecurityException 阻断启动");
    }

    @Test
    @DisplayName("端到端验证自定义前后缀 (CIPHER(...)) 在属性源包装器与 EncryptTemplate 中的对称往返闭环")
    void testCustomPrefixAndSuffixEndToEndWithNamedKeyAndWrapper(@TempDir Path tempDir) throws Exception {
        String masterPass = "CustomPrefixMaster2026!";
        String redisKey = "CustomPrefixRedis2026!";
        String dbCipher = ConfigEncryptor.encrypt("custom-db-pass", masterPass, EncryptAlgorithm.AES_GCM);
        String redisCipher = ConfigEncryptor.encrypt("custom-redis-pass", redisKey, EncryptAlgorithm.SM4_CBC);
        String thirdCipher = ConfigEncryptor.encrypt("custom-third-pass", masterPass, EncryptAlgorithm.AES_GCM);

        Path customYaml = tempDir.resolve("custom-prefix.yml");
        Files.writeString(customYaml, String.format("""
                ateng:
                  boot:
                    encrypt:
                      prefix: "CIPHER("
                      suffix: ")"
                      keys:
                        redis-cluster: %s
                sample:
                  secret:
                    database-password: CIPHER(%s)
                    redis-password: CIPHER[redis-cluster](SM4-CBC@%s)
                    third-party-token: CIPHER(%s)
                """, redisKey, dbCipher, redisCipher, thirdCipher), StandardCharsets.UTF_8);

        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(MinimalEncryptBootApp.class)
                .run(
                        "--spring.main.web-application-type=none",
                        "--ateng.boot.encrypt.password=" + masterPass,
                        "--spring.config.location=" + customYaml.toUri()
                );
        try {
            SampleSecretProperties props = ctx.getBean(SampleSecretProperties.class);
            assertEquals("custom-db-pass", props.getDatabasePassword());
            assertEquals("custom-redis-pass", props.getRedisPassword());
            assertEquals("custom-third-pass", props.getThirdPartyToken());

            EncryptTemplate template = ctx.getBean(EncryptTemplate.class);
            String defaultToken = template.encrypt("round-trip-default");
            assertTrue(defaultToken.startsWith("CIPHER(") && defaultToken.endsWith(")"));
            assertTrue(template.isEncrypted(defaultToken));
            assertEquals("round-trip-default", template.decrypt(defaultToken));

            String routedToken = template.encrypt("round-trip-routed", "redis-cluster");
            assertTrue(routedToken.startsWith("CIPHER[redis-cluster](") && routedToken.endsWith(")"));
            assertTrue(template.isEncrypted(routedToken));
            assertEquals("round-trip-routed", template.decrypt(routedToken));
            assertEquals("round-trip-routed", template.decrypt(routedToken, "redis-cluster"));

            String sm4RoutedToken = template.encrypt("round-trip-sm4", "redis-cluster", EncryptAlgorithm.SM4_CBC);
            assertTrue(sm4RoutedToken.startsWith("CIPHER[redis-cluster](SM4-CBC@") && sm4RoutedToken.endsWith(")"));
            assertTrue(template.isEncrypted(sm4RoutedToken));
            assertEquals("round-trip-sm4", template.decrypt(sm4RoutedToken));
        } finally {
            ctx.close();
        }
    }
}
