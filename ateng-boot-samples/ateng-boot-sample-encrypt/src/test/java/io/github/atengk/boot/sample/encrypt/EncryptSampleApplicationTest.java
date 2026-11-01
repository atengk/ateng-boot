package io.github.atengk.boot.sample.encrypt;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.exception.ConfigSecurityException;
import io.github.atengk.boot.encrypt.guard.SanitizingGuard;
import io.github.atengk.boot.sample.encrypt.service.ConfigDecryptionScenarioService;
import io.github.atengk.boot.sample.encrypt.service.IngressGuardScenarioService;
import io.github.atengk.boot.sample.encrypt.service.InPlaceRewriteScenarioService;
import io.github.atengk.boot.sample.encrypt.service.MultiKeyRoutingScenarioService;
import io.github.atengk.boot.sample.encrypt.service.ProgrammaticCryptoScenarioService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.SanitizableData;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置加解密全场景样例端到端自验集成测试套件
 * 纯内存自洽验证：5 大专职场景服务实测、Actuator 物理脱敏及生产强制门禁阻断，零 Web / MockMvc 依赖
 *
 * @author Ateng
 * @since 2026-10-25
 */
@SpringBootTest(properties = {
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

        // 4. 容错逃生舱解密
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

        // 3. 非敏感普通属性保真放行
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
}
