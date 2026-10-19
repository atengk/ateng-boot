package io.github.atengk.boot.sample.core;

import io.github.atengk.boot.core.model.Pair;
import io.github.atengk.boot.core.model.Triple;
import io.github.atengk.boot.sample.core.model.SampleUserDTO;
import io.github.atengk.boot.sample.core.service.CoreSecurityScenarioService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Core 安全、加解密与敏感脱敏场景端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-19
 */
@SpringBootTest
class CoreSecurityScenarioServiceTest {

    @Autowired
    private CoreSecurityScenarioService securityScenarioService;

    @Test
    @DisplayName("测试DesensitizeUtil敏感数据动态掩码")
    void testDesensitizePrimitives() {
        Triple<String, String, String> result = securityScenarioService.desensitizePrimitives();
        assertThat(result.left()).isEqualTo("138****5678");
        assertThat(result.middle()).isEqualTo("110101********2345");
        assertThat(result.right()).isEqualTo("诸**明");
    }

    @Test
    @DisplayName("测试@FieldSensitive序列化掩码与内存真明文不变量")
    void testVerifyInMemoryPlaintextAndJsonMasking() {
        Pair<String, SampleUserDTO> pair = securityScenarioService.verifyInMemoryPlaintextAndJsonMasking();
        String json = pair.left();
        SampleUserDTO user = pair.right();

        // 1. JSON 输出已完成脱敏掩码
        assertThat(json)
                .contains("\"phone\":\"138****5678\"")
                .contains("\"idCard\":\"110101********2345\"")
                .contains("\"name\":\"诸**明\"")
                .contains("\"email\":\"a****k@example.com\"")
                .contains("\"secretToken\":\"TO################56\"")
                .contains("\"customCode\":\"12###67890\"");

        // 2. 核心红线守护：内存实体中绝对保持 100% 原始真实明文，严禁污染业务实体
        assertThat(user.getName()).isEqualTo("诸葛孔明");
        assertThat(user.getPhone()).isEqualTo("13812345678");
        assertThat(user.getIdCard()).isEqualTo("110101199003072345");
        assertThat(user.getEmail()).isEqualTo("atengk@example.com");
        assertThat(user.getSecretToken()).isEqualTo("TOPSECRETTOKEN123456");
        assertThat(user.getCustomCode()).isEqualTo("1234567890");
    }

    @Test
    @DisplayName("测试CryptoUtil AES/GCM认证加解密")
    void testEncryptAndDecryptAesGcm() {
        String original = "ConfidentialCredential@2026";
        Pair<String, String> pair = securityScenarioService.encryptAndDecryptAesGcm(original);

        assertThat(pair.left()).isNotBlank().isNotEqualTo(original);
        assertThat(pair.right()).isEqualTo(original);
    }

    @Test
    @DisplayName("测试CryptoUtil AES/CBC对称加解密")
    void testEncryptAndDecryptAesCbc() {
        String original = "SecurePassword#9988";
        Pair<String, String> pair = securityScenarioService.encryptAndDecryptAesCbc(original);

        assertThat(pair.left()).isNotBlank().isNotEqualTo(original);
        assertThat(pair.right()).isEqualTo(original);
    }

    @Test
    @DisplayName("测试DigestUtil SHA-256与MD5哈希摘要计算")
    void testCalculateDigests() {
        String text = "HelloWorld";
        Pair<String, String> digests = securityScenarioService.calculateDigests(text);

        // SHA-256 64 位十六进制
        assertThat(digests.left()).hasSize(64);
        // MD5 32 位十六进制
        assertThat(digests.right()).hasSize(32);
    }
}
