package io.github.atengk.boot.sample.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.sample.web.crypto.SampleCryptoKeyConfiguration;
import io.github.atengk.boot.web.crypto.cipher.RsaAesGcmApiCipher;
import io.github.atengk.boot.web.crypto.cipher.Sm2Sm4GcmApiCipher;
import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;
import io.github.atengk.boot.web.crypto.filter.ApiCryptoFilter;
import io.github.atengk.boot.web.crypto.model.ApiCipherInput;
import io.github.atengk.boot.web.crypto.model.ApiCipherResult;
import io.github.atengk.boot.web.crypto.model.ApiCryptoContext;
import io.github.atengk.boot.web.crypto.model.ApiEncryptedPayload;
import io.github.atengk.boot.web.crypto.spi.ApiCipher;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.sign.aspect.ApiSignAspect;
import io.github.atengk.boot.web.sign.signer.HmacSha256Signer;
import io.github.atengk.boot.web.sign.util.SignCanonicalUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sample Web API 传输层混合加解密与 {@code @ApiSign} Encrypt-then-Sign 全真场景集成测试
 *
 * @author Ateng
 * @since 2026-11-27
 */
@SpringBootTest(classes = SampleWebApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@DisplayName("Sample Web API 传输层混合加解密与组合加签端到端测试")
class WebSecurityCryptoSampleTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WebProperties webProperties;

    @Autowired
    private SampleCryptoKeyConfiguration sampleKeyConfig;

    private final RsaAesGcmApiCipher rsaCipher = new RsaAesGcmApiCipher();
    private final Sm2Sm4GcmApiCipher sm2Cipher = new Sm2Sm4GcmApiCipher();
    private final HmacSha256Signer hmacSigner = new HmacSha256Signer();

    @Test
    @DisplayName("1. 辅助信封准备端 + RSA/AES-GCM 双向支付加解密与默认 CORS 暴露头配置验证")
    void shouldExecuteRsaPaymentViaPrepareEnvelope() throws Exception {
        assertThat(webProperties.getCors().getExposedHeaders()).contains(
                ApiCryptoFilter.HEADER_ENCRYPT_KEY,
                ApiCryptoFilter.HEADER_ENCRYPT_IV,
                ApiCryptoFilter.HEADER_KEY_ID,
                ApiCryptoFilter.HEADER_ENCRYPT_ALG
        );

        String plainPayload = """
                {
                  "orderNo": "PAY-RSA-2026001",
                  "accountNo": "6222021000018899",
                  "amount": 256.80,
                  "remark": "<script>xss</script>商城结算"
                }
                """;
        String prepareReq = objectMapper.writeValueAsString(Map.of(
                "plainJson", plainPayload,
                "algorithm", "RSA_AES_GCM",
                "keyId", "v1",
                "withSign", false
        ));

        MvcResult prepRes = mockMvc.perform(post("/sample/web/crypto/prepare-envelope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(prepareReq))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode prepData = objectMapper.readTree(prepRes.getResponse().getContentAsByteArray()).get("data");
        String bodyJson = prepData.get("encryptedBodyJson").asText();
        JsonNode headersNode = prepData.get("headers");

        MockHttpServletRequestBuilder reqBuilder = post("/sample/web/crypto/rsa/payment")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson);
        headersNode.fields().forEachRemaining(e -> reqBuilder.header(e.getKey(), e.getValue().asText()));

        MvcResult payRes = mockMvc.perform(reqBuilder)
                .andExpect(status().isOk())
                .andExpect(header().string(ApiCryptoFilter.HEADER_ENCRYPT_ALG, "RSA_AES_GCM"))
                .andExpect(header().exists(ApiCryptoFilter.HEADER_ENCRYPT_KEY))
                .andExpect(header().exists(ApiCryptoFilter.HEADER_ENCRYPT_IV))
                .andReturn();

        JsonNode decResult = decryptResponse(payRes, CryptoAlgorithm.RSA_AES_GCM);
        assertThat(decResult.get("code").asInt()).isEqualTo(200);
        assertThat(decResult.get("data").get("orderNo").asText()).isEqualTo("PAY-RSA-2026001");
        assertThat(decResult.get("data").get("securityChannel").asText()).isEqualTo("RSA_AES_GCM");
    }

    @Test
    @DisplayName("2. 国密 SM2 + SM4-GCM 双向涉密归档与 GET 报表加密输出")
    void shouldExecuteSm2ArchiveAndGetEncryptedReport() throws Exception {
        String plainPayload = """
                {
                  "orderNo": "ARCHIVE-GM-001",
                  "accountNo": "GM-ACC-888",
                  "amount": 1024.00,
                  "remark": "国密传输存档"
                }
                """;
        String prepareReq = objectMapper.writeValueAsString(Map.of(
                "plainJson", plainPayload,
                "algorithm", "SM2_SM4_GCM",
                "withSign", false
        ));

        MvcResult prepRes = mockMvc.perform(post("/sample/web/crypto/prepare-envelope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(prepareReq))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode prepData = objectMapper.readTree(prepRes.getResponse().getContentAsByteArray()).get("data");
        MockHttpServletRequestBuilder reqBuilder = post("/sample/web/crypto/sm2/archive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(prepData.get("encryptedBodyJson").asText());
        prepData.get("headers").fields().forEachRemaining(e -> reqBuilder.header(e.getKey(), e.getValue().asText()));

        MvcResult sm2Res = mockMvc.perform(reqBuilder)
                .andExpect(status().isOk())
                .andExpect(header().string(ApiCryptoFilter.HEADER_ENCRYPT_ALG, "SM2_SM4_GCM"))
                .andReturn();

        JsonNode decSm2 = decryptResponse(sm2Res, CryptoAlgorithm.SM2_SM4_GCM);
        assertThat(decSm2.get("data").get("orderNo").asText()).isEqualTo("ARCHIVE-GM-001");
        assertThat(decSm2.get("data").get("securityChannel").asText()).isEqualTo("SM2_SM4_GCM");

        // GET 接口响应加密体验
        MvcResult reportRes = mockMvc.perform(get("/sample/web/crypto/report").param("bizNo", "BIZ-2026"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode decReport = decryptResponse(reportRes, CryptoAlgorithm.RSA_AES_GCM);
        assertThat(decReport.get("data").asText()).isEqualTo("CONFIDENTIAL_REPORT:BIZ-2026");
    }

    @Test
    @DisplayName("3. Encrypt-then-Sign 混合转账：验签防重放 + 解密 + SpEL 幂等 + 出站密文回签全链路闭环")
    void shouldExecuteMixedEncryptThenSignTransfer() throws Exception {
        String orderNo = "MIX-" + UUID.randomUUID().toString().substring(0, 8);
        String plainPayload = String.format("""
                {
                  "orderNo": "%s",
                  "accountNo": "6228480011223344",
                  "amount": 5000.00,
                  "remark": "混合加签加密转账"
                }
                """, orderNo);

        String prepareReq = objectMapper.writeValueAsString(Map.of(
                "plainJson", plainPayload,
                "algorithm", "RSA_AES_GCM",
                "requestUri", "/sample/web/crypto/mixed/transfer",
                "withSign", true
        ));

        MvcResult prepRes = mockMvc.perform(post("/sample/web/crypto/prepare-envelope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(prepareReq))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode prepData = objectMapper.readTree(prepRes.getResponse().getContentAsByteArray()).get("data");
        String bodyJson = prepData.get("encryptedBodyJson").asText();
        MockHttpServletRequestBuilder reqBuilder = post("/sample/web/crypto/mixed/transfer")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson);
        prepData.get("headers").fields().forEachRemaining(e -> reqBuilder.header(e.getKey(), e.getValue().asText()));

        MvcResult transferRes = mockMvc.perform(reqBuilder)
                .andExpect(status().isOk())
                .andReturn();

        // 校验出站 Encrypt-then-Sign 签名覆盖的是最终密文信封字节流
        String respTs = transferRes.getResponse().getHeader(ApiSignAspect.HEADER_TIMESTAMP);
        String respNonce = transferRes.getResponse().getHeader(ApiSignAspect.HEADER_NONCE);
        String respSig = transferRes.getResponse().getHeader(ApiSignAspect.HEADER_SIGNATURE);
        assertThat(respTs).isNotBlank();
        assertThat(respNonce).isNotBlank();
        assertThat(respSig).isNotBlank();

        byte[] rawEncryptedBytes = transferRes.getResponse().getContentAsByteArray();
        String respCanonical = SignCanonicalUtil.buildResponseCanonicalString(
                respTs, respNonce, DigestUtil.sha256Hex(rawEncryptedBytes));
        assertThat(hmacSigner.verify(respCanonical, respSig, webProperties.getSign().getSecretKey())).isTrue();

        JsonNode decTransfer = decryptResponse(transferRes, CryptoAlgorithm.RSA_AES_GCM);
        assertThat(decTransfer.get("data").get("orderNo").asText()).isEqualTo(orderNo);
        assertThat(decTransfer.get("data").get("securityChannel").asText()).isEqualTo("ENCRYPT_THEN_SIGN");

        // 重放同 Nonce 请求被 @ApiSign 拦截
        MockHttpServletRequestBuilder replayBuilder = post("/sample/web/crypto/mixed/transfer")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson);
        prepData.get("headers").fields().forEachRemaining(e -> replayBuilder.header(e.getKey(), e.getValue().asText()));
        mockMvc.perform(replayBuilder)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.REPEAT_SUBMIT.getCode()));
    }

    @Test
    @DisplayName("4. 灰度兼容、类级注解、方法级豁免及文件上传下载自动旁路验证")
    void shouldSupportGrayFallbackAndClassLevelVaultEndpoints() throws Exception {
        // 4.1 required=false 明文直接放行
        mockMvc.perform(post("/sample/web/crypto/gray/notice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"GRAY-PLAIN-01\",\"accountNo\":\"ACC-1\",\"amount\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("GRAY_ACCEPTED:GRAY-PLAIN-01"));

        // 4.2 类级 GET 响应加密
        MvcResult vaultSummary = mockMvc.perform(get("/sample/web/crypto/vault/summary"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode decSummary = decryptResponse(vaultSummary, CryptoAlgorithm.RSA_AES_GCM);
        assertThat(decSummary.get("data").asText()).isEqualTo("VAULT_CONFIDENTIAL_SUMMARY");

        // 4.3 方法级 enabled=false 明文交互
        mockMvc.perform(post("/sample/web/crypto/vault/plain-ping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"PING-1\",\"accountNo\":\"ACC-1\",\"amount\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("PLAIN_PING:PING-1"))
                .andExpect(res -> assertThat(res.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_KEY)).isNull());

        // 4.4 Multipart 与 Octet-Stream 自动旁路（Multipart 跳过入站解密但 JSON 出站遵守类级 @ApiEncrypt；Octet-Stream 透传）
        MockMultipartFile file = new MockMultipartFile("file", "contract.pdf", "application/pdf", new byte[]{1, 2, 3});
        MvcResult uploadRes = mockMvc.perform(multipart("/sample/web/crypto/vault/upload").file(file))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode decUpload = decryptResponse(uploadRes, CryptoAlgorithm.RSA_AES_GCM);
        assertThat(decUpload.get("data").asText()).isEqualTo("UPLOADED:contract.pdf");

        MvcResult dlRes = mockMvc.perform(get("/sample/web/crypto/vault/download"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(new String(dlRes.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
                .isEqualTo("RAW_BINARY_DATA");
        assertThat(dlRes.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_KEY)).isNull();
    }

    private JsonNode decryptResponse(MvcResult result, CryptoAlgorithm alg) throws Exception {
        String keyHeader = result.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_KEY);
        String ivHeader = result.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_IV);
        String keyIdHeader = result.getResponse().getHeader(ApiCryptoFilter.HEADER_KEY_ID);
        assertThat(keyHeader).isNotBlank();
        assertThat(ivHeader).isNotBlank();

        ApiEncryptedPayload payload = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(), ApiEncryptedPayload.class);
        ApiCipher cipher = alg == CryptoAlgorithm.SM2_SM4_GCM ? sm2Cipher : rsaCipher;
        String clientPrivKey = sampleKeyConfig.getEphemeralClientPrivateKey(alg);
        byte[] plainBytes = cipher.decrypt(
                new ApiCipherInput(keyHeader, ivHeader, payload.getCipherText()),
                new ApiCryptoContext(alg, webProperties.getCrypto().getAppId(), keyIdHeader, clientPrivKey, null)
        );
        return objectMapper.readTree(plainBytes);
    }
}
