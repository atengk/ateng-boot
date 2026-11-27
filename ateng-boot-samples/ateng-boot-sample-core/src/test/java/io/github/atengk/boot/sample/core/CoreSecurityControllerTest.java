package io.github.atengk.boot.sample.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CoreSecurityController 端到端 Web 集成测试
 * 验证分布式发号与短码、客户隐私数据出界动态脱敏、原生密码学套件与 HMAC 验签接口
 *
 * @author Ateng
 * @since 2026-11-07
 */
@SpringBootTest
@AutoConfigureMockMvc
class CoreSecurityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("测试生成分布式业务 ID 与 Base62 短码")
    void testGenerateDistributedId() throws Exception {
        mockMvc.perform(get("/sample/core/security/ids/generate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.id", notNullValue()))
                .andExpect(jsonPath("$.data.idStr", notNullValue()))
                .andExpect(jsonPath("$.data.base62", notNullValue()))
                .andExpect(jsonPath("$.data.restoredId", notNullValue()))
                .andExpect(jsonPath("$.data.workerId", is("1")))
                .andExpect(jsonPath("$.data.datacenterId", is("1")))
                .andExpect(jsonPath("$.data.simpleUuid", hasLength(32)))
                .andExpect(jsonPath("$.data.uuid", hasLength(36)))
                .andExpect(jsonPath("$.data.nanoId", hasLength(21)))
                .andExpect(jsonPath("$.data.dateTime", notNullValue()));

        mockMvc.perform(get("/sample/core/security/distributed-id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.workerId", is("1")))
                .andExpect(jsonPath("$.data.datacenterId", is("1")))
                .andExpect(jsonPath("$.data.restoredId", notNullValue()))
                .andExpect(jsonPath("$.data.base62", notNullValue()));
    }

    @Test
    @DisplayName("测试客户档案出界动态脱敏全场景验证")
    void testGetCustomerMaskedProfile() throws Exception {
        mockMvc.perform(get("/sample/core/security/customer/masked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.realName", is("诸**明")))
                .andExpect(jsonPath("$.data.phone", is("138****5678")))
                .andExpect(jsonPath("$.data.fixedPhone", is("010-******78")))
                .andExpect(jsonPath("$.data.idCard", is("110101********2345")))
                .andExpect(jsonPath("$.data.email", is("a****k@example.com")))
                .andExpect(jsonPath("$.data.bankCard", is("622202*********4012")))
                .andExpect(jsonPath("$.data.password", is("******")))
                .andExpect(jsonPath("$.data.address", is("北京市海淀区*********")))
                .andExpect(jsonPath("$.data.carLicense", is("京A***88")))
                .andExpect(jsonPath("$.data.clientIpv4", is("192.168.1.*")))
                .andExpect(jsonPath("$.data.clientIpv6", is("2001:****:7334")))
                .andExpect(jsonPath("$.data.creditCode", is("91************000X")))
                .andExpect(jsonPath("$.data.secretKey", is("sk-p****************cdef")))
                .andExpect(jsonPath("$.data.passport", is("G1****678")))
                .andExpect(jsonPath("$.data.customToken", is("AB######CD")))
                .andExpect(jsonPath("$.data.internalCode", is("PR****56US")));
    }

    @Test
    @DisplayName("测试支付报文加解密、HMAC 验签与数据校验和成功场景")
    void testVerifyPaymentCrypto_Success() throws Exception {
        String jsonBody = """
                {
                  "tradeNo": "TX-20261107-8899",
                  "amount": 299.90,
                  "channel": "WXPAY",
                  "secretKey": "mySecretApiKey2026",
                  "rawPayload": "orderId=10086&currency=CNY&amount=299.90"
                }
                """;

        mockMvc.perform(post("/sample/core/security/crypto/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.tradeNo", is("TX-20261107-8899")))
                .andExpect(jsonPath("$.data.gcmCiphertext", notNullValue()))
                .andExpect(jsonPath("$.data.gcmDecrypted", is("orderId=10086&currency=CNY&amount=299.90")))
                .andExpect(jsonPath("$.data.cbcCiphertext", notNullValue()))
                .andExpect(jsonPath("$.data.cbcDecrypted", is("orderId=10086&currency=CNY&amount=299.90")))
                .andExpect(jsonPath("$.data.sm4CbcCiphertext", notNullValue()))
                .andExpect(jsonPath("$.data.sm4CbcCiphertext", not("orderId=10086&currency=CNY&amount=299.90")))
                .andExpect(jsonPath("$.data.sm4CbcDecrypted", is("orderId=10086&currency=CNY&amount=299.90")))
                .andExpect(jsonPath("$.data.hmacSha256Hex", hasLength(64)))
                .andExpect(jsonPath("$.data.signatureVerified", is(true)))
                .andExpect(jsonPath("$.data.sha256Hex", hasLength(64)))
                .andExpect(jsonPath("$.data.sm3Hex", matchesPattern("^[0-9a-f]{64}$")))
                .andExpect(jsonPath("$.data.md5Hex", hasLength(32)))
                .andExpect(jsonPath("$.data.encodedHex", notNullValue()))
                .andExpect(jsonPath("$.data.crc32Hex", notNullValue()))
                .andExpect(jsonPath("$.data.crc32cHex", notNullValue()))
                .andExpect(jsonPath("$.data.adler32Hex", notNullValue()));
    }

    @Test
    @DisplayName("测试支付加密入参 JSR-303 格式校验拦截（非法负金额与空流水号）")
    void testVerifyPaymentCrypto_ValidationFail() throws Exception {
        String invalidJson = """
                {
                  "tradeNo": "",
                  "amount": -10.00,
                  "channel": "",
                  "secretKey": "",
                  "rawPayload": ""
                }
                """;

        mockMvc.perform(post("/sample/core/security/crypto/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.success", is(false)));
    }

    @Test
    @DisplayName("POST /sample/core/security/sign-and-verify - 国密 HmacSM3 签名与恒定时间验签端到端验证")
    void testSignAndVerify_Success() throws Exception {
        String jsonBody = """
                {
                  "payload": "orderNo=ORD-20261113-001&currency=CNY",
                  "secretKey": "AtengSm3SecretKey2026"
                }
                """;

        mockMvc.perform(post("/sample/core/security/sign-and-verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.hmacSm3Hex", matchesPattern("^[0-9a-f]{64}$")))
                .andExpect(jsonPath("$.data.hmacSm3Base64", notNullValue()))
                .andExpect(jsonPath("$.data.sm3HexVerified", is(true)))
                .andExpect(jsonPath("$.data.sm3Base64Verified", is(true)))
                .andExpect(jsonPath("$.data.sm3TamperBlocked", is(true)))
                .andExpect(jsonPath("$.data.hmacSha256Hex", matchesPattern("^[0-9a-f]{64}$")))
                .andExpect(jsonPath("$.data.sm3DigestHex", matchesPattern("^[0-9a-f]{64}$")));
    }

    @Test
    @DisplayName("POST /sample/core/security/sign-and-verify - 空载荷或空密钥触发 JSR-303 校验拦截")
    void testSignAndVerify_ValidationFail() throws Exception {
        String invalidJson = """
                {
                  "payload": "",
                  "secretKey": ""
                }
                """;

        mockMvc.perform(post("/sample/core/security/sign-and-verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.success", is(false)));
    }

    @Test
    @DisplayName("POST /sample/core/security/crypto - 国密 SM4 密钥生成与带 AAD 的 AES-GCM 加解密闭环")
    void testProcessSymmetricCrypto_Success() throws Exception {
        String jsonBody = """
                {
                  "plaintext": "CardToken-99887766",
                  "aad": "tenant=t001;scene=pay"
                }
                """;

        mockMvc.perform(post("/sample/core/security/crypto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.sm4KeyByteLength", is(16)))
                .andExpect(jsonPath("$.data.sm4CbcCiphertext", notNullValue()))
                .andExpect(jsonPath("$.data.sm4CbcDecrypted", is("CardToken-99887766")))
                .andExpect(jsonPath("$.data.gcmCiphertextWithAad", notNullValue()))
                .andExpect(jsonPath("$.data.gcmDecryptedWithAad", is("CardToken-99887766")))
                .andExpect(jsonPath("$.data.aadTamperBlocked", is(true)))
                .andExpect(jsonPath("$.data.sm4GcmCiphertextWithAad", notNullValue()))
                .andExpect(jsonPath("$.data.sm4GcmDecryptedWithAad", is("CardToken-99887766")))
                .andExpect(jsonPath("$.data.sm4GcmAadTamperBlocked", is(true)));
    }

    @Test
    @DisplayName("POST /sample/core/security/crypto - 空明文或空 AAD 触发 JSR-303 校验拦截")
    void testProcessSymmetricCrypto_ValidationFail() throws Exception {
        String invalidJson = """
                {
                  "plaintext": "",
                  "aad": ""
                }
                """;

        mockMvc.perform(post("/sample/core/security/crypto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.success", is(false)));
    }

    @Test
    @DisplayName("POST /sample/core/security/password - PBKDF2 口令慢哈希、恒定时间校验与升级闭环")
    void testVerifyPasswordLifecycle_Success() throws Exception {
        String jsonBody = """
                {
                  "username": "alice.chen",
                  "rawPassword": "Ateng#Login2026!",
                  "legacyIterations": 20000
                }
                """;

        mockMvc.perform(post("/sample/core/security/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.username", is("alice.chen")))
                .andExpect(jsonPath("$.data.encodedPassword", startsWith("$pbkdf2-sha256$310000$")))
                .andExpect(jsonPath("$.data.secondEncodedPassword", startsWith("$pbkdf2-sha256$310000$")))
                .andExpect(jsonPath("$.data.saltUnique", is(true)))
                .andExpect(jsonPath("$.data.matched", is(true)))
                .andExpect(jsonPath("$.data.wrongPasswordRejected", is(true)))
                .andExpect(jsonPath("$.data.legacyNeedsUpgrade", is(true)))
                .andExpect(jsonPath("$.data.upgradedPassword", startsWith("$pbkdf2-sha256$310000$")))
                .andExpect(jsonPath("$.data.currentNeedsUpgrade", is(false)))
                .andExpect(jsonPath("$.data.dummyCheckRejected", is(true)));
    }

    @Test
    @DisplayName("POST /sample/core/security/password - 空账号或越界迭代次数触发 JSR-303 校验拦截")
    void testVerifyPasswordLifecycle_ValidationFail() throws Exception {
        String invalidJson = """
                {
                  "username": "",
                  "rawPassword": "",
                  "legacyIterations": 100
                }
                """;

        mockMvc.perform(post("/sample/core/security/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.success", is(false)));
    }

    @Test
    @DisplayName("POST /sample/core/security/asymmetric-crypto - RSA-OAEP/PSS 与国密 SM2 非对称闭环")
    void testVerifyAsymmetricCrypto_Success() throws Exception {
        String jsonBody = """
                {
                  "plaintext": "aesSessionKey=k-20261127-prod",
                  "signPayload": "POST\\n/api/settlement\\n1794052800000\\nnonce-9001",
                  "sm2UserId": "MERCHANT_APP_01"
                }
                """;

        mockMvc.perform(post("/sample/core/security/asymmetric-crypto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.rsaKeyPemRoundTripValid", is(true)))
                .andExpect(jsonPath("$.data.rsaOaepDecrypted", is("aesSessionKey=k-20261127-prod")))
                .andExpect(jsonPath("$.data.rsaSha256Verified", is(true)))
                .andExpect(jsonPath("$.data.rsaPssVerified", is(true)))
                .andExpect(jsonPath("$.data.rsaTamperBlocked", is(true)))
                .andExpect(jsonPath("$.data.sm2PublicKeyHex", matchesPattern("^04[0-9a-f]{128}$")))
                .andExpect(jsonPath("$.data.sm2C1c3c2Decrypted", is("aesSessionKey=k-20261127-prod")))
                .andExpect(jsonPath("$.data.sm2DerDecrypted", is("aesSessionKey=k-20261127-prod")))
                .andExpect(jsonPath("$.data.sm2DerVerified", is(true)))
                .andExpect(jsonPath("$.data.sm2RawVerified", is(true)))
                .andExpect(jsonPath("$.data.sm2UserIdIsolated", is(true)))
                .andExpect(jsonPath("$.data.sm2TamperBlocked", is(true)));
    }

    @Test
    @DisplayName("POST /sample/core/security/asymmetric-crypto - 空明文或空签名载荷触发 JSR-303 校验拦截")
    void testVerifyAsymmetricCrypto_ValidationFail() throws Exception {
        String invalidJson = """
                {
                  "plaintext": "",
                  "signPayload": ""
                }
                """;

        mockMvc.perform(post("/sample/core/security/asymmetric-crypto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.success", is(false)));
    }

    @Test
    @DisplayName("POST /sample/core/security/kdf-and-stream - HKDF、HMAC-SHA512、流式摘要与 Packed AEAD")
    void testVerifyKdfAndStream_Success() throws Exception {
        String jsonBody = """
                {
                  "masterSecret": "ateng-tenant-master-key-2026",
                  "salt": "ateng-salt-v1",
                  "info": "tenant=t001:payload-enc",
                  "payload": "OrderNo=ORD20261127001&Amount=188.00",
                  "aad": "tenant=t001;scene=settlement"
                }
                """;

        mockMvc.perform(post("/sample/core/security/kdf-and-stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.hkdfSha256KeyHex", hasLength(64)))
                .andExpect(jsonPath("$.data.hkdfSm3KeyHex", hasLength(64)))
                .andExpect(jsonPath("$.data.hkdfDomainIsolated", is(true)))
                .andExpect(jsonPath("$.data.hmacSha512Hex", hasLength(128)))
                .andExpect(jsonPath("$.data.hmacSha512Verified", is(true)))
                .andExpect(jsonPath("$.data.sha256StreamHex", hasLength(64)))
                .andExpect(jsonPath("$.data.sha512StreamHex", hasLength(128)))
                .andExpect(jsonPath("$.data.sm3StreamHex", hasLength(64)))
                .andExpect(jsonPath("$.data.aesGcmPackedDecrypted", is("OrderNo=ORD20261127001&Amount=188.00")))
                .andExpect(jsonPath("$.data.sm4GcmPackedDecrypted", is("OrderNo=ORD20261127001&Amount=188.00")))
                .andExpect(jsonPath("$.data.base64UrlDecoded", is("OrderNo=ORD20261127001&Amount=188.00")))
                .andExpect(jsonPath("$.data.constantTimeEqualsValid", is(true)));
    }

    @Test
    @DisplayName("POST /sample/core/security/kdf-and-stream - 空字段触发 JSR-303 校验拦截")
    void testVerifyKdfAndStream_ValidationFail() throws Exception {
        String invalidJson = """
                {
                  "masterSecret": "",
                  "info": "",
                  "payload": "",
                  "aad": ""
                }
                """;

        mockMvc.perform(post("/sample/core/security/kdf-and-stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.success", is(false)));
    }
}
