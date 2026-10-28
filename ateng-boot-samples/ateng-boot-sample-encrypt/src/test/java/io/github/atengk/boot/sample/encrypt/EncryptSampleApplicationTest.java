package io.github.atengk.boot.sample.encrypt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.tool.InPlaceRewriter;
import io.github.atengk.boot.encrypt.guard.PlaintextIngressGuard;
import io.github.atengk.boot.sample.encrypt.config.SampleSecretProperties;
import io.github.atengk.boot.sample.encrypt.model.DecryptRequest;
import io.github.atengk.boot.sample.encrypt.model.EncryptRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 配置加解密全场景样例端到端自验集成测试套件
 * 离线自洽验证：透明懒解密、多Key动态路由、Web 契约层交互、Actuator 运行时物理脱敏及就地回写
 *
 * @author Ateng
 * @since 2026-10-25
 */
@SpringBootTest(properties = {
        "ateng.boot.encrypt.password=SampleMasterSecretKey2026!"
})
@AutoConfigureMockMvc
class EncryptSampleApplicationTest {

    @Autowired
    private SampleSecretProperties secretProperties;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("端到端验证配置属性透明解密与多Key别名路由")
    void testPropertiesTransparentDecryption() {
        assertNotNull(secretProperties, "配置属性类必须成功注入");

        // 1. 验证默认 AES-GCM 算法透明解密
        assertEquals("mysqlRootPass2026!", secretProperties.getDatabasePassword(),
                "数据库密码应成功解密为真实明文");

        // 2. 验证多 Key 别名路由 [redis-cluster] 透明解密
        assertEquals("redisAuthPass2026!", secretProperties.getRedisPassword(),
                "Redis密码应经由 redis-cluster 命名密钥成功解密");

        // 3. 验证显式国密 SM4-CBC 算法透明解密
        assertEquals("sm4ApiSecretToken888", secretProperties.getThirdPartyToken(),
                "第三方令牌应经由国密 SM4-CBC 成功解密");
    }

    @Test
    @DisplayName("端到端验证 Web Controller 获取解密后数据")
    void testWebEndpointSecrets() throws Exception {
        mockMvc.perform(get("/api/encrypt/secrets")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.databasePassword").value("mysqlRootPass2026!"))
                .andExpect(jsonPath("$.data.redisPassword").value("redisAuthPass2026!"))
                .andExpect(jsonPath("$.data.thirdPartyToken").value("sm4ApiSecretToken888"));
    }

    @Test
    @DisplayName("端到端验证编程式加密与解密门面接口闭环")
    void testWebEndpointEncryptAndDecryptTools() throws Exception {
        EncryptRequest encReq = new EncryptRequest();
        encReq.setPlaintext("MySuperSecretDatabasePassword999");
        encReq.setPassword("SampleMasterSecretKey2026!");
        encReq.setAlgorithm(EncryptAlgorithm.AES_GCM);

        // 1. 发起加密请求
        String encResponse = mockMvc.perform(post("/api/encrypt/tool/encrypt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(encReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode encJson = objectMapper.readTree(encResponse);
        String generatedCipher = encJson.get("data").asText();
        assertTrue(generatedCipher.startsWith("ENC("));
        assertTrue(generatedCipher.endsWith(")"));

        // 2. 发起解密请求还原明文
        DecryptRequest decReq = new DecryptRequest();
        decReq.setCiphertext(generatedCipher);
        decReq.setPassword("SampleMasterSecretKey2026!");
        decReq.setAlgorithm(EncryptAlgorithm.AES_GCM);

        mockMvc.perform(post("/api/encrypt/tool/decrypt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(decReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("MySuperSecretDatabasePassword999"));
    }

    @Test
    @DisplayName("端到端验证 Actuator 运行时端点物理掩码脱敏防泄露机制")
    void testActuatorEnvEndpointMasking() throws Exception {
        String response = mockMvc.perform(get("/actuator/env")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode root = objectMapper.readTree(response);
        JsonNode propertySources = root.get("propertySources");
        assertNotNull(propertySources, "propertySources 节点必须存在");

        boolean verifiedMasking = false;
        for (JsonNode source : propertySources) {
            JsonNode properties = source.get("properties");
            if (properties != null && properties.has("sample.secret.database-password")) {
                JsonNode dbNode = properties.get("sample.secret.database-password");
                String value = dbNode.get("value").asText();
                // 验证 Actuator 输出强制为物理掩码 ******，坚决不泄露内存真实明文
                assertEquals("******", value, "带有 ENC 标记的敏感属性在 Actuator 监控端点必须物理掩码为 ******");
                verifiedMasking = true;
                break;
            }
        }
        assertTrue(verifiedMasking, "必须在 propertySources 中检测到 sample.secret.database-password 的脱敏输出");
    }

    @Test
    @DisplayName("端到端验证 InPlaceRewriter 对样例 YAML 就地加密与还原")
    void testInPlaceRewriterOnSampleYaml(@TempDir Path tempDir) throws Exception {
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

        // 1. 执行就地加密
        InPlaceRewriter rewriter = new InPlaceRewriter("SampleMasterSecretKey2026!", EncryptAlgorithm.AES_GCM);
        int encCount = rewriter.rewriteEncrypt(yamlFile);
        assertEquals(1, encCount);

        // 验证备份文件存在
        assertTrue(Files.exists(tempDir.resolve("sample-app.yml.bak")));

        // 验证密文格式并保留注释与缩进
        String encrypted = Files.readString(yamlFile, StandardCharsets.UTF_8);
        assertTrue(encrypted.contains("    password: ENC("));
        assertTrue(encrypted.contains("# @encrypt 数据库明文密码"));
        assertFalse(encrypted.contains("myPlaintextRootPass123"));

        // 2. 执行就地逆向解密还原
        int decCount = rewriter.rewriteDecrypt(yamlFile);
        assertEquals(1, decCount);
        String restored = Files.readString(yamlFile, StandardCharsets.UTF_8);
        assertTrue(restored.contains("myPlaintextRootPass123"));
        assertTrue(restored.contains("# @encrypt 数据库明文密码"));
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
                        java.util.Map<String, Object> map = new java.util.HashMap<>();
                        map.put("spring.datasource.password", "nakedPlaintextSecret999");
                        context.getEnvironment().getPropertySources().addFirst(
                                new MapPropertySource("misconfigured-external-source", map));
                    })
                    .run();
        }, "存在裸明文敏感配置且开启 enforce-encrypted=true 时必须阻断启动");
    }

    @Test
    @DisplayName("端到端验证配置中心热更新守卫 PlaintextIngressGuard 响应刷新事件与缓存原子置换")
    void testPlaintextIngressGuardRefreshEvent() {
        PlaintextIngressGuard guard = applicationContext.getBean(PlaintextIngressGuard.class);
        assertNotNull(guard, "PlaintextIngressGuard 必须作为受管 Bean 成功装配");

        // 模拟触发配置中心环境变更全量刷新
        ConfigurableEnvironment env = (ConfigurableEnvironment) applicationContext.getEnvironment();
        assertDoesNotThrow(() -> guard.onEnvironmentChange(null, env),
                "在合规配置下触发环境变更刷新不应抛出异常");

        // 验证热更新后配置属性依然能够正常解密且服务可用
        assertEquals("mysqlRootPass2026!", secretProperties.getDatabasePassword());
    }
}
