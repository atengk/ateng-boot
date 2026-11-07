package io.github.atengk.boot.sample.core;

import io.github.atengk.boot.sample.core.dto.PaymentCryptoRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.is;
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
                .andExpect(jsonPath("$.data.simpleUuid", hasLength(32)))
                .andExpect(jsonPath("$.data.uuid", hasLength(36)))
                .andExpect(jsonPath("$.data.nanoId", hasLength(21)))
                .andExpect(jsonPath("$.data.dateTime", notNullValue()));
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
                .andExpect(jsonPath("$.data.hmacSha256Hex", hasLength(64)))
                .andExpect(jsonPath("$.data.signatureVerified", is(true)))
                .andExpect(jsonPath("$.data.sha256Hex", hasLength(64)))
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
}
