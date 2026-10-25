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
}
