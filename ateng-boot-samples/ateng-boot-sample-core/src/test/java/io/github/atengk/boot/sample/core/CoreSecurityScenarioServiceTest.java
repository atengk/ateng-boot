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

    @Test
    @DisplayName("测试DesensitizeUtil扩展敏感类型掩码")
    void testDesensitizeExtendedTypes() {
        Triple<String, String, String> result = securityScenarioService.desensitizeExtendedTypes();
        // 统一社会信用代码前2后4明文，中间12位掩码
        assertThat(result.left()).isEqualTo("91************000X");
        // 密钥/Token保留前4后4
        assertThat(result.middle()).isEqualTo("sk-p****************cdef");
        // 护照号保留前2后3
        assertThat(result.right()).isEqualTo("G1****678");
    }

    @Test
    @DisplayName("测试HexUtil十六进制编解码与对称还原")
    void testDemonstrateHexEncoding() {
        String raw = "AtengBoot2026";
        Pair<String, String> pair = securityScenarioService.demonstrateHexEncoding(raw);

        assertThat(pair.left()).matches("^[0-9a-f]+$");
        assertThat(pair.right()).isEqualTo(raw);
    }

    @Test
    @DisplayName("测试HmacUtil密钥加签与防侧信道比对")
    void testDemonstrateHmacSigning() {
        String payload = "orderId=10086&amount=88.88";
        String secretKey = "SecureSampleKey2026";

        Triple<String, String, Boolean> result = securityScenarioService.demonstrateHmacSigning(payload, secretKey);

        assertThat(result.left()).hasSize(64); // SHA-256 Hex 64 字符
        assertThat(result.middle()).isNotBlank();
        assertThat(result.right()).isTrue();
    }

    @Test
    @DisplayName("测试ChecksumUtil数据校验和计算")
    void testDemonstrateChecksum() {
        String data = "123456789";
        Triple<String, String, String> result = securityScenarioService.demonstrateChecksum(data);

        assertThat(result.left()).isEqualTo("cbf43926");
        assertThat(result.middle()).isEqualTo("e3069283");
        assertThat(result.right()).isEqualTo("091e01de");
    }
}
