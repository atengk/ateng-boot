package io.github.atengk.boot.sample.encrypt;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.key.MultiKeyRegistry;
import io.github.atengk.boot.sample.encrypt.dto.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 配置加解密 5 大领域 Controller 端到端 MockMvc 集成测试套件
 * <p>
 * 遵循最高测试接缝（The Highest Seam）原则，全链路验证 Web 边界请求解析、
 * JSR-303 参数校验快速失败、透明懒解密、编程式加密、多 Key 路由轮换、动态防倒灌及沙箱就地回写。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@SpringBootTest(properties = {
        "ateng.boot.encrypt.password=SampleMasterSecretKey2026!"
})
@AutoConfigureMockMvc
class SampleEncryptWebControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MultiKeyRegistry multiKeyRegistry;

    @AfterEach
    void tearDown() {
        if (multiKeyRegistry != null) {
            multiKeyRegistry.unregisterKey("payment-shard");
        }
    }

    // =========================================================================
    // 1. EncryptDecryptionController 实测 (透明解密呈现与属性探查)
    // =========================================================================

    @Test
    @DisplayName("DecryptionController: GET /secrets 成功获取已透明解密绑定的业务机密属性")
    void testDecryptedSecrets() throws Exception {
        mockMvc.perform(get("/sample/encrypt/decrypt/secrets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.databasePassword").value("mysqlRootPass2026!"))
                .andExpect(jsonPath("$.data.redisPassword").value("redisAuthPass2026!"))
                .andExpect(jsonPath("$.data.thirdPartyToken").value("sm4ApiSecretToken888"))
                .andExpect(jsonPath("$.data.activeAlgorithm").value("AES-GCM"))
                .andExpect(jsonPath("$.data.decryptionStatus").value("VERIFIED_CONSISTENT"));
    }

    @Test
    @DisplayName("DecryptionController: GET /property?propertyName=... 成功查询单个解密环境属性")
    void testDecryptedProperty_Success() throws Exception {
        mockMvc.perform(get("/sample/encrypt/decrypt/property")
                        .param("propertyName", "sample.secret.database-password"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("mysqlRootPass2026!"));
    }

    @Test
    @DisplayName("DecryptionController: GET /property 参数缺失触发 JSR-303 快速失败")
    void testDecryptedProperty_ValidationFailure() throws Exception {
        mockMvc.perform(get("/sample/encrypt/decrypt/property")
                        .param("propertyName", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));
    }

    // =========================================================================
    // 2. EncryptCryptoController 实测 (编程式受管加解密与对称纯函数)
    // =========================================================================

    @Test
    @DisplayName("CryptoController: POST /encrypt 成功执行默认 AES-GCM 算法加密并生成 ENC(...) 标记")
    void testEncryptDefault_Success() throws Exception {
        CryptoEncryptRequest request = CryptoEncryptRequest.builder()
                .plaintext("HelloAtengBoot2026!")
                .build();

        mockMvc.perform(post("/sample/encrypt/crypto/encrypt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.plaintext").value("HelloAtengBoot2026!"))
                .andExpect(jsonPath("$.data.token").value(startsWith("ENC(")))
                .andExpect(jsonPath("$.data.algorithm").value("AES-GCM"));
    }

    @Test
    @DisplayName("CryptoController: POST /encrypt 空明文触发 JSR-303 快速失败")
    void testEncryptDefault_ValidationFailure() throws Exception {
        CryptoEncryptRequest request = CryptoEncryptRequest.builder()
                .plaintext("")
                .build();

        mockMvc.perform(post("/sample/encrypt/crypto/encrypt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("CryptoController: POST /decrypt 成功解密 ENC(...) 标记")
    void testDecrypt_Success() throws Exception {
        // 先生成密文
        CryptoEncryptRequest encryptRequest = CryptoEncryptRequest.builder()
                .plaintext("SecretToDecrypt999")
                .build();
        String responseStr = mockMvc.perform(post("/sample/encrypt/crypto/encrypt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(encryptRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String token = objectMapper.readTree(responseStr).path("data").path("token").asText();

        // 执行解密
        CryptoDecryptRequest decryptRequest = CryptoDecryptRequest.builder()
                .ciphertext(token)
                .build();

        mockMvc.perform(post("/sample/encrypt/crypto/decrypt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(decryptRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("SecretToDecrypt999"));
    }

    @Test
    @DisplayName("CryptoController: POST /encrypt-custom 指定 Key 与 SM4_CBC 算法加密成功")
    void testEncryptCustom_Sm4Cbc() throws Exception {
        CryptoCustomRequest request = CryptoCustomRequest.builder()
                .plaintext("Sm4SecretValue888")
                .keyId("redis-cluster")
                .algorithm(EncryptAlgorithm.SM4_CBC)
                .build();

        mockMvc.perform(post("/sample/encrypt/crypto/encrypt-custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.keyId").value("redis-cluster"))
                .andExpect(jsonPath("$.data.algorithm").value("SM4_CBC"))
                .andExpect(jsonPath("$.data.token").value(startsWith("ENC[redis-cluster](SM4-CBC@")));
    }

    @Test
    @DisplayName("CryptoController: POST /encrypt-custom 缺失必填字段触发 JSR-303 快速失败")
    void testEncryptCustom_ValidationFailure() throws Exception {
        CryptoCustomRequest request = CryptoCustomRequest.builder()
                .plaintext("SomeText")
                .keyId("") // 空 KeyId
                .build();

        mockMvc.perform(post("/sample/encrypt/crypto/encrypt-custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("CryptoController: POST /encrypt-raw 生成纯 Base64 裸密文")
    void testEncryptRaw() throws Exception {
        CryptoRawRequest request = CryptoRawRequest.builder()
                .plaintext("RawSecretPayload123")
                .build();

        mockMvc.perform(post("/sample/encrypt/crypto/encrypt-raw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(not(startsWith("ENC("))))
                .andExpect(jsonPath("$.data").value(not(emptyString())));
    }

    @Test
    @DisplayName("CryptoController: POST /decrypt-safe 容错逃生舱解密损坏密文返回保底默认值")
    void testDecryptSafe_Fallback() throws Exception {
        CryptoSafeDecryptRequest request = CryptoSafeDecryptRequest.builder()
                .ciphertext("corrupted_invalid_cipher_token_here")
                .defaultValue("safeFallbackDefaultValue")
                .build();

        mockMvc.perform(post("/sample/encrypt/crypto/decrypt-safe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("safeFallbackDefaultValue"));
    }

    // =========================================================================
    // 3. EncryptMultiKeyController 实测 (多 Key 别名路由与动态轮换)
    // =========================================================================

    @Test
    @DisplayName("MultiKeyController: GET /keys 探查多 Key 注册状态")
    void testGetKeyStatus() throws Exception {
        mockMvc.perform(get("/sample/encrypt/multi-key/keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.defaultKeyConfigured").value(true))
                .andExpect(jsonPath("$.data.registeredKeyIds", hasItem("redis-cluster")))
                .andExpect(jsonPath("$.data.totalKeys").value(greaterThanOrEqualTo(2)));
    }

    @Test
    @DisplayName("MultiKeyController: POST /decrypt-routed 使用多 Key 路由标签成功解密")
    void testDecryptRouted() throws Exception {
        RoutedDecryptRequest request = RoutedDecryptRequest.builder()
                .token("ENC[redis-cluster](YbIg1S6F2964mdnyLgtvn1D4SjJ2QqxN8M45CBCnWs0VpgEJ4b5wEZyEKOJyDA==)")
                .build();

        mockMvc.perform(post("/sample/encrypt/multi-key/decrypt-routed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("redisAuthPass2026!"));
    }

    @Test
    @DisplayName("MultiKeyController: POST /decrypt-routed 空 token 触发 JSR-303 快速失败")
    void testDecryptRouted_ValidationFailure() throws Exception {
        RoutedDecryptRequest request = RoutedDecryptRequest.builder()
                .token("")
                .build();

        mockMvc.perform(post("/sample/encrypt/multi-key/decrypt-routed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("MultiKeyController: POST /rotate 动态注册与平滑轮换新密钥成功")
    void testRotateKey() throws Exception {
        KeyRotateRequest request = KeyRotateRequest.builder()
                .keyId("payment-shard")
                .newPassword("NewPaymentPass2026!")
                .build();

        mockMvc.perform(post("/sample/encrypt/multi-key/rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 验证注册表已收录新 keyId
        mockMvc.perform(get("/sample/encrypt/multi-key/keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registeredKeyIds", hasItem("payment-shard")));
    }

    // =========================================================================
    // 4. EncryptGuardController 实测 (配置刷新、防明文倒灌与规则探查)
    // =========================================================================

    @Test
    @DisplayName("GuardController: POST /refresh 全量环境热更新刷新成功")
    void testTriggerRefresh_Full() throws Exception {
        mockMvc.perform(post("/sample/encrypt/guard/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("GuardController: POST /refresh 指定属性键局部热更新刷新成功")
    void testTriggerRefresh_SelectiveKeys() throws Exception {
        KeyRefreshRequest request = KeyRefreshRequest.builder()
                .changedKeys(Set.of("sample.secret.database-password"))
                .build();

        mockMvc.perform(post("/sample/encrypt/guard/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("GuardController: POST /simulate-ingress 模拟推送敏感明文配置端点正常响应")
    void testSimulateIngress() throws Exception {
        IngressSimulateRequest request = IngressSimulateRequest.builder()
                .sensitiveKey("custom.dynamic.password")
                .plaintextValue("plaintext123456")
                .build();

        mockMvc.perform(post("/sample/encrypt/guard/simulate-ingress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(not(emptyString())));
    }

    @Test
    @DisplayName("GuardController: GET /rules 探查防误伤过滤链规则与免疫特征")
    void testGetRules() throws Exception {
        mockMvc.perform(get("/sample/encrypt/guard/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.protocolImmunities", hasItem("http://")))
                .andExpect(jsonPath("$.data.logLevelImmunities", hasItem("DEBUG")));
    }

    // =========================================================================
    // 5. EncryptInPlaceRewriteController 实测 (沙箱就地加解密与 Diff)
    // =========================================================================

    @Test
    @DisplayName("RewriteController: POST /encrypt-yaml 沙箱执行 YAML 就地加密并生成 .bak 备份与 Diff")
    void testEncryptYaml() throws Exception {
        String yamlContent = "app:\n  datasource:\n    password: mysqlPass2026! # @encrypt\n";
        InPlaceRewriteRequest request = InPlaceRewriteRequest.builder()
                .content(yamlContent)
                .password("SampleMasterSecretKey2026!")
                .algorithm(EncryptAlgorithm.AES_GCM)
                .build();

        mockMvc.perform(post("/sample/encrypt/rewrite/encrypt-yaml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.modifiedCount").value(1))
                .andExpect(jsonPath("$.data.backupCreated").value(true))
                .andExpect(jsonPath("$.data.fileType").value("yml"))
                .andExpect(jsonPath("$.data.rewrittenContent").value(containsString("ENC(")));
    }

    @Test
    @DisplayName("RewriteController: POST /decrypt-yaml 沙箱执行已加密 YAML 就地解密还原")
    void testDecryptYaml() throws Exception {
        // 先加密
        String rawYaml = "app:\n  datasource:\n    password: mysqlPass2026! # @encrypt\n";
        InPlaceRewriteRequest encryptReq = InPlaceRewriteRequest.builder()
                .content(rawYaml)
                .password("SampleMasterSecretKey2026!")
                .algorithm(EncryptAlgorithm.AES_GCM)
                .build();

        String encResp = mockMvc.perform(post("/sample/encrypt/rewrite/encrypt-yaml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(encryptReq)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String encryptedYaml = objectMapper.readTree(encResp).path("data").path("rewrittenContent").asText();

        // 执行解密还原
        InPlaceRewriteRequest decryptReq = InPlaceRewriteRequest.builder()
                .content(encryptedYaml)
                .password("SampleMasterSecretKey2026!")
                .algorithm(EncryptAlgorithm.AES_GCM)
                .build();

        mockMvc.perform(post("/sample/encrypt/rewrite/decrypt-yaml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(decryptReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.modifiedCount").value(1))
                .andExpect(jsonPath("$.data.rewrittenContent").value(containsString("mysqlPass2026!")));
    }

    @Test
    @DisplayName("RewriteController: POST /encrypt-properties 沙箱执行 Properties 就地加密")
    void testEncryptProperties() throws Exception {
        String propContent = "app.datasource.password=mysqlPass2026! # @encrypt\n";
        InPlaceRewriteRequest request = InPlaceRewriteRequest.builder()
                .content(propContent)
                .password("SampleMasterSecretKey2026!")
                .algorithm(EncryptAlgorithm.AES_GCM)
                .build();

        mockMvc.perform(post("/sample/encrypt/rewrite/encrypt-properties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.modifiedCount").value(1))
                .andExpect(jsonPath("$.data.fileType").value("properties"))
                .andExpect(jsonPath("$.data.rewrittenContent").value(containsString("ENC(")));
    }

    @Test
    @DisplayName("RewriteController: POST /decrypt-properties 沙箱执行 Properties 就地解密还原")
    void testDecryptProperties() throws Exception {
        String propContent = "app.datasource.password=mysqlPass2026! # @encrypt\n";
        InPlaceRewriteRequest encryptReq = InPlaceRewriteRequest.builder()
                .content(propContent)
                .password("SampleMasterSecretKey2026!")
                .algorithm(EncryptAlgorithm.AES_GCM)
                .build();

        String encResp = mockMvc.perform(post("/sample/encrypt/rewrite/encrypt-properties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(encryptReq)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String encryptedProps = objectMapper.readTree(encResp).path("data").path("rewrittenContent").asText();

        InPlaceRewriteRequest decryptReq = InPlaceRewriteRequest.builder()
                .content(encryptedProps)
                .password("SampleMasterSecretKey2026!")
                .algorithm(EncryptAlgorithm.AES_GCM)
                .build();

        mockMvc.perform(post("/sample/encrypt/rewrite/decrypt-properties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(decryptReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.modifiedCount").value(1))
                .andExpect(jsonPath("$.data.rewrittenContent").value(containsString("mysqlPass2026!")));
    }

    @Test
    @DisplayName("RewriteController: 空文本内容触发 JSR-303 快速失败")
    void testRewrite_ValidationFailure() throws Exception {
        InPlaceRewriteRequest request = InPlaceRewriteRequest.builder()
                .content("")
                .password("SampleMasterSecretKey2026!")
                .build();

        mockMvc.perform(post("/sample/encrypt/rewrite/encrypt-yaml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));
    }
}
