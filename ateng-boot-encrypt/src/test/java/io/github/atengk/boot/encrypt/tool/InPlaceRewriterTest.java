package io.github.atengk.boot.encrypt.tool;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.key.MultiKeyRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置文件整文件就地加解密回写器单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class InPlaceRewriterTest {

    @TempDir
    Path tempDir;

    private static final String SAMPLE_YAML = """
            # 基础服务配置
            server:
              port: 8080
            
            # 数据源敏感配置
            spring:
              datasource:
                url: jdbc:mysql://localhost:3306/mydb
                username: root
                password: secretPassword123  # 数据库生产密码
              redis:
                host: 127.0.0.1
                # @encrypt[redis-key]
                password: redisAuthToken456  # 缓存访问令牌
              custom:
                api-token: myCustomApiToken789 # @encrypt
            """;

    @Test
    @DisplayName("测试 YAML 就地加密重写：缩进注释完整保持并生成 .bak 备份")
    void testRewriteEncryptKeepsIndentsAndComments() throws IOException {
        Path yamlFile = tempDir.resolve("application.yml");
        Files.writeString(yamlFile, SAMPLE_YAML, StandardCharsets.UTF_8);

        MultiKeyRegistry registry = new MultiKeyRegistry("GlobalMasterKey!");
        registry.registerKey("redis-key", "RedisSpecialKey2026!");

        InPlaceRewriter rewriter = new InPlaceRewriter(registry, EncryptAlgorithm.AES_GCM,
                InPlaceRewriter.DEFAULT_SENSITIVE_KEY_PATTERN, true);

        int count = rewriter.rewriteEncrypt(yamlFile);
        assertEquals(3, count, "应该加密 3 个字段：datasource.password, redis.password, custom.api-token");

        // 1. 验证 .bak 备份文件完整生成且内容为原始明文
        Path bakFile = tempDir.resolve("application.yml.bak");
        assertTrue(Files.exists(bakFile), ".bak 备份文件必须存在");
        assertEquals(SAMPLE_YAML, Files.readString(bakFile, StandardCharsets.UTF_8));

        // 2. 验证加密后文件的内容与格式
        List<String> encryptedLines = Files.readAllLines(yamlFile, StandardCharsets.UTF_8);
        boolean foundDatasourcePassword = false;
        boolean foundRedisPassword = false;
        boolean foundApiToken = false;

        for (String line : encryptedLines) {
            if (line.contains("password:") && line.contains("数据库生产密码")) {
                foundDatasourcePassword = true;
                assertTrue(line.startsWith("    password: ENC("), "必须严格保持 4 个空格缩进与 ENC( 前缀");
                assertTrue(line.endsWith("  # 数据库生产密码"), "必须严格保留原有行尾注释");
                assertFalse(line.contains("secretPassword123"), "明文密码必须已被密文替换");
            }
            if (line.contains("password:") && line.contains("缓存访问令牌")) {
                foundRedisPassword = true;
                assertTrue(line.startsWith("    password: ENC[redis-key]("), "必须支持显式 [redis-key] 路由");
                assertTrue(line.endsWith("  # 缓存访问令牌"));
                assertFalse(line.contains("redisAuthToken456"));
            }
            if (line.contains("api-token:") && line.contains("# @encrypt")) {
                foundApiToken = true;
                assertTrue(line.startsWith("    api-token: ENC("));
                assertTrue(line.endsWith(" # @encrypt"));
                assertFalse(line.contains("myCustomApiToken789"));
            }
        }

        assertTrue(foundDatasourcePassword);
        assertTrue(foundRedisPassword);
        assertTrue(foundApiToken);

        // 3. 幂等性验证：二次加密应跳过已加密属性
        int secondCount = rewriter.rewriteEncrypt(yamlFile);
        assertEquals(0, secondCount, "二次执行不应对已是 ENC 密文的字段重复加密");
    }

    @Test
    @DisplayName("测试对称逆向解密回写：完整还原为明文且格式保真")
    void testRewriteDecryptRestoresPlaintext() throws IOException {
        Path yamlFile = tempDir.resolve("application-encrypted.yml");
        Files.writeString(yamlFile, SAMPLE_YAML, StandardCharsets.UTF_8);

        MultiKeyRegistry registry = new MultiKeyRegistry("GlobalMasterKey!");
        registry.registerKey("redis-key", "RedisSpecialKey2026!");

        InPlaceRewriter rewriter = new InPlaceRewriter(registry, EncryptAlgorithm.AES_GCM,
                InPlaceRewriter.DEFAULT_SENSITIVE_KEY_PATTERN, true);

        // 先加密
        rewriter.rewriteEncrypt(yamlFile);

        // 再逆向解密还原
        int decryptedCount = rewriter.rewriteDecrypt(yamlFile);
        assertEquals(3, decryptedCount, "应该解密还原 3 行密文字段");

        String restoredYaml = Files.readString(yamlFile, StandardCharsets.UTF_8);
        assertTrue(restoredYaml.contains("password: secretPassword123  # 数据库生产密码"));
        assertTrue(restoredYaml.contains("password: redisAuthToken456  # 缓存访问令牌"));
        assertTrue(restoredYaml.contains("api-token: myCustomApiToken789 # @encrypt"));
    }

    @Test
    @DisplayName("测试 EncryptFileRewriter 语义别名与静态方法")
    void testEncryptFileRewriterAlias() throws IOException {
        Path yamlFile = tempDir.resolve("sample.yml");
        Files.writeString(yamlFile, "secret.key: mySecretValue\n", StandardCharsets.UTF_8);

        int encCount = EncryptFileRewriter.encryptFile(yamlFile.toFile(), "pass123");
        assertEquals(1, encCount);
        String encContent = Files.readString(yamlFile, StandardCharsets.UTF_8);
        assertTrue(encContent.contains("secret.key: ENC("));

        int decCount = EncryptFileRewriter.decryptFile(yamlFile.toFile(), "pass123");
        assertEquals(1, decCount);
        String decContent = Files.readString(yamlFile, StandardCharsets.UTF_8);
        assertEquals("secret.key: mySecretValue\n", decContent);
    }

    private static final String SAMPLE_PROPERTIES = """
            # 基础服务端口
            server.port=8080
            
            # 数据源敏感信息 (无空格等号)
            spring.datasource.url=jdbc:mysql://localhost:3306/mydb?useSSL=false
            spring.datasource.username=root
            spring.datasource.password=secretPassword123 # 数据库密码
            
            ! 缓存服务信息 (带空格等号与感叹号注释)
            spring.redis.host = 127.0.0.1
            spring.redis.password = redisAuthToken456 ! 缓存访问令牌
            
            # 自定义令牌 (行内指令)
            custom.api-token=myCustomApiToken789 # @encrypt
            
            ! 国密 SM4 定向加密 (前置感叹号指令)
            ! @encrypt[sm4-key](SM4_CBC)
            custom.sm4.secret = sm4PlainTextValue ! 国密私有敏感值
            
            # 数组格式键名
            servers[0].password=serverPassword001
            """;

    @Test
    @DisplayName("测试 Properties 就地加解密重写：缩进、分隔符与注释保真")
    void testRewritePropertiesKeepsIndentsSeparatorsAndComments() throws IOException {
        Path propFile = tempDir.resolve("application.properties");
        Files.writeString(propFile, SAMPLE_PROPERTIES, StandardCharsets.UTF_8);

        MultiKeyRegistry registry = new MultiKeyRegistry("GlobalMasterKey!");
        registry.registerKey("sm4-key", "1234567890123456");

        InPlaceRewriter rewriter = new InPlaceRewriter(registry, EncryptAlgorithm.AES_GCM,
                InPlaceRewriter.DEFAULT_SENSITIVE_KEY_PATTERN, true);

        int count = rewriter.rewriteEncrypt(propFile);
        assertEquals(5, count, "应该加密 5 个字段：datasource.password, redis.password, custom.api-token, custom.sm4.secret, servers[0].password");

        // 1. 验证 .properties.bak 备份文件存在且与原始内容逐字一致
        Path bakFile = tempDir.resolve("application.properties.bak");
        assertTrue(Files.exists(bakFile), ".properties.bak 备份文件必须存在");
        assertEquals(SAMPLE_PROPERTIES, Files.readString(bakFile, StandardCharsets.UTF_8));

        // 2. 验证加密后文件的分隔符、格式与注释保真
        List<String> encryptedLines = Files.readAllLines(propFile, StandardCharsets.UTF_8);
        boolean foundDatasourcePass = false;
        boolean foundRedisPass = false;
        boolean foundApiToken = false;
        boolean foundSm4Secret = false;
        boolean foundServersPass = false;
        boolean foundServerPort = false;

        for (String line : encryptedLines) {
            if (line.contains("spring.datasource.password=")) {
                foundDatasourcePass = true;
                assertTrue(line.startsWith("spring.datasource.password=ENC("), "无空格等号必须保持无空格");
                assertTrue(line.endsWith(" # 数据库密码"), "必须保留行尾 # 注释");
                assertFalse(line.contains("secretPassword123"));
            }
            if (line.contains("spring.redis.password = ")) {
                foundRedisPass = true;
                assertTrue(line.startsWith("spring.redis.password = ENC("), "带空格等号必须保持空格");
                assertTrue(line.endsWith(" ! 缓存访问令牌"), "必须保留行尾 ! 注释");
                assertFalse(line.contains("redisAuthToken456"));
            }
            if (line.contains("custom.api-token=")) {
                foundApiToken = true;
                assertTrue(line.startsWith("custom.api-token=ENC("));
                assertTrue(line.endsWith(" # @encrypt"));
                assertFalse(line.contains("myCustomApiToken789"));
            }
            if (line.contains("custom.sm4.secret = ")) {
                foundSm4Secret = true;
                assertTrue(line.startsWith("custom.sm4.secret = ENC[sm4-key](SM4-CBC@"), "支持感叹号指令与 SM4 算法路由");
                assertTrue(line.endsWith(" ! 国密私有敏感值"));
                assertFalse(line.contains("sm4PlainTextValue"));
            }
            if (line.contains("servers[0].password=")) {
                foundServersPass = true;
                assertTrue(line.startsWith("servers[0].password=ENC("), "支持数组下标格式属性键");
                assertFalse(line.contains("serverPassword001"));
            }
            if (line.contains("server.port=8080")) {
                foundServerPort = true;
            }
        }

        assertTrue(foundDatasourcePass);
        assertTrue(foundRedisPass);
        assertTrue(foundApiToken);
        assertTrue(foundSm4Secret);
        assertTrue(foundServersPass);
        assertTrue(foundServerPort);

        // 3. 幂等性验证
        int secondCount = rewriter.rewriteEncrypt(propFile);
        assertEquals(0, secondCount, "二次执行不应对已加密密文重复加密");

        // 4. 逆向对称解密还原
        int decryptedCount = rewriter.rewriteDecrypt(propFile);
        assertEquals(5, decryptedCount, "应该解密还原 5 行密文字段");
        assertEquals(SAMPLE_PROPERTIES, Files.readString(propFile, StandardCharsets.UTF_8), "解密后必须 100% 还原原始配置文本");
    }

    private static final String PLACEHOLDER_CONFIG = """
            # 环境变量占位符 (应自动豁免加密)
            spring.datasource.password=${DB_PASSWORD:defaultPassword}
            
            # SpEL 动态表达式占位符 (应自动豁免加密)
            spring.redis.password=#{systemProperties['redis.pass']}
            
            # 双引号包裹的占位符 (应自动豁免加密)
            custom.secret="${ENV_SECRET}"
            
            # 显式 @encrypt 行内指令标注的占位符 (应强制执行加密)
            custom.forced.token=${FORCED_TOKEN:123} # @encrypt
            
            # 显式 @encrypt 前置指令标注的占位符 (应强制执行加密)
            # @encrypt
            custom.forced.other=${OTHER_TOKEN}
            
            # 普通敏感明文 (应正常加密)
            app.secret.key=realPlainSecret
            """;

    @Test
    @DisplayName("测试占位符绝对免疫：${...} 与 #{...} 默认不被就地加密，显式 @encrypt 指令除外")
    void testPlaceholderImmunity() throws IOException {
        Path configFile = tempDir.resolve("placeholder-test.properties");
        Files.writeString(configFile, PLACEHOLDER_CONFIG, StandardCharsets.UTF_8);

        InPlaceRewriter rewriter = new InPlaceRewriter("GlobalKey123!");
        int count = rewriter.rewriteEncrypt(configFile);
        assertEquals(3, count, "仅强制指令与普通敏感字段被加密（共 3 个）");

        List<String> lines = Files.readAllLines(configFile, StandardCharsets.UTF_8);
        assertTrue(lines.contains("spring.datasource.password=${DB_PASSWORD:defaultPassword}"), "环境变量占位符保持明文");
        assertTrue(lines.contains("spring.redis.password=#{systemProperties['redis.pass']}"), "SpEL 表达式占位符保持明文");
        assertTrue(lines.contains("custom.secret=\"${ENV_SECRET}\""), "带双引号的占位符保持明文");

        boolean forcedTokenEncrypted = lines.stream().anyMatch(l -> l.startsWith("custom.forced.token=ENC(") && l.endsWith(" # @encrypt"));
        boolean forcedOtherEncrypted = lines.stream().anyMatch(l -> l.startsWith("custom.forced.other=ENC("));
        boolean appSecretEncrypted = lines.stream().anyMatch(l -> l.startsWith("app.secret.key=ENC("));

        assertTrue(forcedTokenEncrypted, "带显式 @encrypt 的占位符必须加密");
        assertTrue(forcedOtherEncrypted, "前置 @encrypt 的占位符必须加密");
        assertTrue(appSecretEncrypted, "普通敏感明文字段必须加密");

        // 逆向解密还原
        int decCount = rewriter.rewriteDecrypt(configFile);
        assertEquals(3, decCount);
        assertEquals(PLACEHOLDER_CONFIG, Files.readString(configFile, StandardCharsets.UTF_8));
    }

    private static final String MULTI_DOC_YAML = """
            # 第一段文档：dev 环境
            spring:
              config:
                activate:
                  on-profile: dev
              datasource:
                password: devPassword123 # dev环境密码
            ---
            # 第二段文档：test 环境
            spring:
              config:
                activate:
                  on-profile: test
              datasource:
                password: testPassword456
            ---
            # 第三段文档：prod 环境
            spring:
              config:
                activate:
                  on-profile: prod
              datasource:
                # @encrypt[prod-key]
                password: prodPassword789
            """;

    @Test
    @DisplayName("测试 YAML 多文档分隔符 '---' 独立分区与上下文重置")
    void testMultiDocumentYaml() throws IOException {
        Path multiDocFile = tempDir.resolve("multi-doc.yml");
        Files.writeString(multiDocFile, MULTI_DOC_YAML, StandardCharsets.UTF_8);

        MultiKeyRegistry registry = new MultiKeyRegistry("DefaultKey!");
        registry.registerKey("prod-key", "ProdSuperKey2026!");

        InPlaceRewriter rewriter = new InPlaceRewriter(registry, EncryptAlgorithm.AES_GCM,
                InPlaceRewriter.DEFAULT_SENSITIVE_KEY_PATTERN, true);

        int encCount = rewriter.rewriteEncrypt(multiDocFile);
        assertEquals(3, encCount, "跨多文档的 3 个 password 均应被加密");

        String encContent = Files.readString(multiDocFile, StandardCharsets.UTF_8);
        assertTrue(encContent.contains("---"), "文档分隔符 '---' 必须完整保留");
        assertTrue(encContent.contains("    password: ENC[prod-key]("), "第三段文档应使用 prod-key 路由");
        assertFalse(encContent.contains("devPassword123"));
        assertFalse(encContent.contains("testPassword456"));
        assertFalse(encContent.contains("prodPassword789"));

        int decCount = rewriter.rewriteDecrypt(multiDocFile);
        assertEquals(3, decCount);
        assertEquals(MULTI_DOC_YAML, Files.readString(multiDocFile, StandardCharsets.UTF_8));
    }

    private static final String BLOCK_SCALAR_YAML = """
            rsa:
              # 多行私钥证书（块标量 | 不应被误加密）
              private-key: |
                -----BEGIN PRIVATE KEY-----
                MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC7
                -----END PRIVATE KEY-----
              # 显式单行密码（应正常加密）
              password: secretRsaPassword
            """;

    @Test
    @DisplayName("测试 YAML 块标量 '|' 保护：避免误加密破坏多行文本排版")
    void testYamlBlockScalarPreserved() throws IOException {
        Path blockFile = tempDir.resolve("block-scalar.yml");
        Files.writeString(blockFile, BLOCK_SCALAR_YAML, StandardCharsets.UTF_8);

        InPlaceRewriter rewriter = new InPlaceRewriter("MasterKey!");
        int encCount = rewriter.rewriteEncrypt(blockFile);
        assertEquals(1, encCount, "仅单行 password 字段被加密，块标量指示符 '|' 严格保护");

        String encContent = Files.readString(blockFile, StandardCharsets.UTF_8);
        assertTrue(encContent.contains("private-key: |"), "块标量头必须原样保留");
        assertTrue(encContent.contains("-----BEGIN PRIVATE KEY-----"), "私钥内容排版必须原样保留");
        assertTrue(encContent.contains("password: ENC("));

        int decCount = rewriter.rewriteDecrypt(blockFile);
        assertEquals(1, decCount);
        assertEquals(BLOCK_SCALAR_YAML, Files.readString(blockFile, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("测试 YAML 块标量 '|' 携带显式 # @encrypt 指令时被提取并打平加密为标准单行密文")
    void testYamlBlockScalarEncryptedWithDirective() throws IOException {
        String yamlWithDirective = """
                rsa:
                  # @encrypt
                  private-key: |
                    -----BEGIN PRIVATE KEY-----
                    MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC7
                    -----END PRIVATE KEY-----
                  password: secretRsaPassword
                """;
        Path blockFile = tempDir.resolve("block-scalar-directive.yml");
        Files.writeString(blockFile, yamlWithDirective, StandardCharsets.UTF_8);

        InPlaceRewriter rewriter = new InPlaceRewriter("MasterKey!");
        int encCount = rewriter.rewriteEncrypt(blockFile);
        assertEquals(2, encCount, "带 @encrypt 指令的多行块标量与 password 均被就地加密");

        String encContent = Files.readString(blockFile, StandardCharsets.UTF_8);
        assertTrue(encContent.contains("  private-key: ENC("), "多行块标量已转换为单行 ENC 密文");
        assertFalse(encContent.contains("-----BEGIN PRIVATE KEY-----"), "原始明文块已被完全移除");
        assertTrue(encContent.contains("  password: ENC("));
    }

    @Test
    @DisplayName("测试带双引号且内含冒号、等号与注释字符的复杂密码加解密")
    void testQuotedValuesWithSpecialChars() throws IOException {
        String quotedConfig = """
                spring.datasource.password="p#ss:w=rd!2026" # 复杂特殊字符
                spring.redis.password='another#secret'
                """;
        Path file = tempDir.resolve("quoted.properties");
        Files.writeString(file, quotedConfig, StandardCharsets.UTF_8);

        InPlaceRewriter rewriter = new InPlaceRewriter("MasterKey!");
        int encCount = rewriter.rewriteEncrypt(file);
        assertEquals(2, encCount);

        String encContent = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(encContent.contains("spring.datasource.password=ENC("));
        assertTrue(encContent.contains(" # 复杂特殊字符"));
        assertTrue(encContent.contains("spring.redis.password=ENC("));

        int decCount = rewriter.rewriteDecrypt(file);
        assertEquals(2, decCount);
        String restored = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(restored.contains("p#ss:w=rd!2026"));
        assertTrue(restored.contains("another#secret"));
    }
}
