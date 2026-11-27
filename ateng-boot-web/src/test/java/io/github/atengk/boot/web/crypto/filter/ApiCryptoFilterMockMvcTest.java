package io.github.atengk.boot.web.crypto.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.web.crypto.annotation.ApiDecrypt;
import io.github.atengk.boot.web.crypto.annotation.ApiEncrypt;
import io.github.atengk.boot.web.crypto.cipher.RsaAesGcmApiCipher;
import io.github.atengk.boot.web.crypto.cipher.Sm2Sm4GcmApiCipher;
import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;
import io.github.atengk.boot.web.crypto.model.ApiCipherInput;
import io.github.atengk.boot.web.crypto.model.ApiCipherResult;
import io.github.atengk.boot.web.crypto.model.ApiCryptoContext;
import io.github.atengk.boot.web.crypto.model.ApiEncryptedPayload;
import io.github.atengk.boot.web.crypto.provider.DefaultApiCryptoKeyProvider;
import io.github.atengk.boot.web.crypto.spi.ApiCipher;
import io.github.atengk.boot.web.crypto.spi.ApiCryptoKeyProvider;
import io.github.atengk.boot.web.filter.PayloadLoggingFilter;
import io.github.atengk.boot.web.handler.GlobalExceptionHandler;
import io.github.atengk.boot.web.idempotent.annotation.Idempotent;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.sign.annotation.ApiSign;
import io.github.atengk.boot.web.sign.aspect.ApiSignAspect;
import io.github.atengk.boot.web.sign.signer.HmacSha256Signer;
import io.github.atengk.boot.web.sign.signer.Sm2Signer;
import io.github.atengk.boot.web.sign.util.SignCanonicalUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ApiCryptoFilter} 与 Spring MVC 全链路过滤器/切面 MockMvc 端到端集成测试
 * 完整验证 RSA/AES-GCM 与 SM2/SM4-GCM 独立加解密、{@code @ApiSign} Encrypt-then-Sign 混合协同、
 * {@code @Idempotent} 幂等 SpEL、XSS 清洗、PayloadLogging 密文日志脱敏、AAD 绑定、多版本轮换与异常边界
 *
 * @author Ateng
 * @since 2026-11-27
 */
@SpringBootTest(classes = ApiCryptoFilterMockMvcTest.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@DisplayName("ApiCryptoFilter 全链路 MockMvc 端到端集成测试")
class ApiCryptoFilterMockMvcTest {

    private static final String APP_ID = "app-crypto-1";
    private static final String SIGN_SECRET = "TestSignSecretKeyForCrypto123456";

    private static final KeyPair RSA_SERVER_KP = generateRsaKeyPair();
    private static final KeyPair RSA_CLIENT_KP = generateRsaKeyPair();
    private static final KeyPair RSA_SERVER_V2_KP = generateRsaKeyPair();
    private static final KeyPair RSA_CLIENT_V2_KP = generateRsaKeyPair();
    private static final Sm2Signer.Sm2KeyPair SM2_SERVER_KP = Sm2Signer.generateKeyPair();
    private static final Sm2Signer.Sm2KeyPair SM2_CLIENT_KP = Sm2Signer.generateKeyPair();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final RsaAesGcmApiCipher rsaCipher = new RsaAesGcmApiCipher();
    private final Sm2Sm4GcmApiCipher sm2Cipher = new Sm2Sm4GcmApiCipher();
    private final HmacSha256Signer hmacSigner = new HmacSha256Signer();

    private Logger payloadLogger;
    private ListAppender<ILoggingEvent> payloadLogAppender;

    @DynamicPropertySource
    static void registerCryptoProperties(DynamicPropertyRegistry registry) {
        registry.add("ateng.boot.web.crypto.enabled", () -> "true");
        registry.add("ateng.boot.web.crypto.algorithm", () -> "RSA_AES_GCM");
        registry.add("ateng.boot.web.crypto.app-id", () -> APP_ID);
        registry.add("ateng.boot.web.crypto.active-key-id", () -> "v1");
        registry.add("ateng.boot.web.crypto.max-body-size", () -> "2MB");
        registry.add("ateng.boot.web.crypto.rsa-server-private-key", () -> encodePrivateKey(RSA_SERVER_KP));
        registry.add("ateng.boot.web.crypto.rsa-client-public-key", () -> encodePublicKey(RSA_CLIENT_KP));
        registry.add("ateng.boot.web.crypto.sm2-server-private-key", SM2_SERVER_KP::privateKeyHex);
        registry.add("ateng.boot.web.crypto.sm2-client-public-key", SM2_CLIENT_KP::publicKeyHex);

        registry.add("ateng.boot.web.sign.enabled", () -> "true");
        registry.add("ateng.boot.web.sign.app-id", () -> APP_ID);
        registry.add("ateng.boot.web.sign.secret-key", () -> SIGN_SECRET);
        registry.add("ateng.boot.web.payload-log.enabled", () -> "true");
        registry.add("ateng.boot.web.xss.enabled", () -> "true");
        registry.add("ateng.boot.web.xss.mode", () -> "ESCAPE");
        registry.add("ateng.boot.web.idempotent.enabled", () -> "true");
        registry.add("ateng.boot.web.ready-card.enabled", () -> "false");
        registry.add("ateng.boot.web.sanity-check.enabled", () -> "false");
    }

    @BeforeEach
    void attachLogAppender() {
        payloadLogger = (Logger) LoggerFactory.getLogger(PayloadLoggingFilter.class);
        payloadLogAppender = new ListAppender<>();
        payloadLogAppender.start();
        payloadLogger.addAppender(payloadLogAppender);
    }

    @AfterEach
    void detachLogAppender() {
        if (payloadLogger != null && payloadLogAppender != null) {
            payloadLogger.detachAppender(payloadLogAppender);
            payloadLogAppender.stop();
        }
    }

    @Test
    @DisplayName("1. RSA_AES_GCM 独立双向加解密、XSS 清洗、@Valid 校验与密文日志隔离")
    void shouldHandleIndependentRsaCryptoWithXssAndValidation() throws Exception {
        String plainJson = """
                {
                  "orderNo": "ORD-RSA-001",
                  "amount": 188.50,
                  "remark": "<script>alert(1)</script>备注",
                  "secretCard": "SECRET_CARD_9988"
                }
                """;
        PreparedEnvelope reqEnv = encryptEnvelope(
                CryptoAlgorithm.RSA_AES_GCM,
                encodePublicKey(RSA_SERVER_KP),
                "v1",
                null,
                plainJson.getBytes(StandardCharsets.UTF_8)
        );

        MvcResult result = mockMvc.perform(post("/test/crypto/rsa-both")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_KEY, reqEnv.encryptedKey())
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_IV, reqEnv.iv())
                        .header(ApiCryptoFilter.HEADER_KEY_ID, "v1")
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_ALG, "RSA_AES_GCM")
                        .content(reqEnv.envelopeBytes()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode decryptedJson = decryptResponseEnvelope(
                result, CryptoAlgorithm.RSA_AES_GCM, encodePrivateKey(RSA_CLIENT_KP), null);
        assertThat(decryptedJson.get("code").asInt()).isEqualTo(200);
        assertThat(decryptedJson.get("data").get("orderNo").asText()).isEqualTo("ORD-RSA-001");
        assertThat(decryptedJson.get("data").get("remark").asText()).isEqualTo("&lt;script&gt;alert(1)&lt;/script&gt;备注");

        // 验证 PayloadLoggingFilter 仅采样密文，严禁打印业务敏感明文
        String allLogs = payloadLogAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(allLogs).contains("cipherText");
        assertThat(allLogs).doesNotContain("SECRET_CARD_9988");
        assertThat(allLogs).doesNotContain("ORD-RSA-001");

        // @Valid 校验失败且默认 encryptError=false 时，返回明文业务错误且不带加密响应头
        String invalidJson = "{\"orderNo\":\"\",\"amount\":10}";
        PreparedEnvelope invalidEnv = encryptEnvelope(
                CryptoAlgorithm.RSA_AES_GCM, encodePublicKey(RSA_SERVER_KP), "v1", null, invalidJson.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/test/crypto/rsa-both")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_KEY, invalidEnv.encryptedKey())
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_IV, invalidEnv.iv())
                        .content(invalidEnv.envelopeBytes()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(res -> assertThat(res.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_KEY)).isNull());
    }

    @Test
    @DisplayName("2. encryptError=true 时 GlobalExceptionHandler 异常响应同样走密文加密输出")
    void shouldEncryptErrorResponseWhenEncryptErrorTrue() throws Exception {
        String plainJson = "{\"orderNo\":\"ORD-ERR-1\",\"amount\":99}";
        PreparedEnvelope reqEnv = encryptEnvelope(
                CryptoAlgorithm.RSA_AES_GCM, encodePublicKey(RSA_SERVER_KP), "v1", null, plainJson.getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(post("/test/crypto/encrypt-error")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_KEY, reqEnv.encryptedKey())
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_IV, reqEnv.iv())
                        .content(reqEnv.envelopeBytes()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode decryptedError = decryptResponseEnvelope(
                result, CryptoAlgorithm.RSA_AES_GCM, encodePrivateKey(RSA_CLIENT_KP), null);
        assertThat(decryptedError.get("code").asInt()).isEqualTo(400);
        assertThat(decryptedError.get("msg").asText()).isEqualTo("触发加密错误响应测试");
    }

    @Test
    @DisplayName("3. 国密 SM2_SM4_GCM 双向加解密与 GET @ApiEncrypt 单独加密输出")
    void shouldHandleSm2Sm4GcmAndGetOnlyEncrypt() throws Exception {
        String plainJson = "{\"orderNo\":\"ORD-GM-001\",\"amount\":520,\"remark\":\"国密报文\"}";
        PreparedEnvelope reqEnv = encryptEnvelope(
                CryptoAlgorithm.SM2_SM4_GCM,
                SM2_SERVER_KP.publicKeyHex(),
                "v1",
                null,
                plainJson.getBytes(StandardCharsets.UTF_8)
        );

        MvcResult result = mockMvc.perform(post("/test/crypto/sm2-both")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_KEY, reqEnv.encryptedKey())
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_IV, reqEnv.iv())
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_ALG, "SM2_SM4_GCM")
                        .content(reqEnv.envelopeBytes()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode decryptedJson = decryptResponseEnvelope(
                result, CryptoAlgorithm.SM2_SM4_GCM, SM2_CLIENT_KP.privateKeyHex(), null);
        assertThat(decryptedJson.get("code").asInt()).isEqualTo(200);
        assertThat(decryptedJson.get("data").get("orderNo").asText()).isEqualTo("ORD-GM-001");
        assertThat(decryptedJson.get("data").get("remark").asText()).isEqualTo("国密报文");

        // GET 接口仅标注 @ApiEncrypt，无请求加密头依然放行并加密出站响应
        MvcResult getResult = mockMvc.perform(get("/test/crypto/get-encrypt").param("item", "report-9"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode getDecrypted = decryptResponseEnvelope(
                getResult, CryptoAlgorithm.RSA_AES_GCM, encodePrivateKey(RSA_CLIENT_KP), null);
        assertThat(getDecrypted.get("data").asText()).isEqualTo("REPORT:report-9");
    }

    @Test
    @DisplayName("4. @ApiSign + @ApiDecrypt + @ApiEncrypt + @Idempotent 物理级 Encrypt-then-Sign 闭环")
    void shouldEnforceEncryptThenSignAndAntiReplayAndIdempotent() throws Exception {
        String plainJson = "{\"orderNo\":\"ORD-MIX-001\",\"amount\":999,\"remark\":\"混合安全请求\"}";
        PreparedEnvelope reqEnv = encryptEnvelope(
                CryptoAlgorithm.RSA_AES_GCM, encodePublicKey(RSA_SERVER_KP), "v1", null, plainJson.getBytes(StandardCharsets.UTF_8));

        String nonce = UUID.randomUUID().toString().replace("-", "");
        String timestamp = String.valueOf(System.currentTimeMillis());

        // 4.1 正常组合请求：对最终密文信封字节流计算签名
        MvcResult result = mockMvc.perform(signedPost("/test/crypto/mixed", reqEnv, timestamp, nonce))
                .andExpect(status().isOk())
                .andReturn();

        // 出站 Encrypt-then-Sign 验证：先验证响应签名覆盖的是最终密文信封字节流，再解密
        verifyResponseSignature(result);
        JsonNode decrypted = decryptResponseEnvelope(
                result, CryptoAlgorithm.RSA_AES_GCM, encodePrivateKey(RSA_CLIENT_KP), null);
        assertThat(decrypted.get("code").asInt()).isEqualTo(200);
        assertThat(decrypted.get("data").get("orderNo").asText()).isEqualTo("ORD-MIX-001");

        // 4.2 原 Nonce 重放攻击：即使密文合法也在入站解密前被 @ApiSign 防重放拦截（40003）
        mockMvc.perform(signedPost("/test/crypto/mixed", reqEnv, timestamp, nonce))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.REPEAT_SUBMIT.getCode()));

        // 4.3 伪造非法签名且同时携带非法 Base64 密钥头：应先命中 @ApiSign 验签失败，证明验签在解密前执行（防 DoS）
        String badSigNonce = UUID.randomUUID().toString().replace("-", "");
        mockMvc.perform(post("/test/crypto/mixed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiSignAspect.HEADER_APP_ID, APP_ID)
                        .header(ApiSignAspect.HEADER_TIMESTAMP, String.valueOf(System.currentTimeMillis()))
                        .header(ApiSignAspect.HEADER_NONCE, badSigNonce)
                        .header(ApiSignAspect.HEADER_SIGNATURE, "FORGED_SIGNATURE")
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_KEY, "NOT_VALID_BASE64!!!")
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_IV, "NOT_VALID_IV!!!")
                        .content(reqEnv.envelopeBytes()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("签名校验未通过"));

        // 4.4 新 Nonce + 新有效签名 + 相同 orderNo：验证解密后 @Idempotent SpEL("#dto.orderNo") 生效触发幂等拦截
        String dupOrderNonce = UUID.randomUUID().toString().replace("-", "");
        String dupOrderTs = String.valueOf(System.currentTimeMillis());
        mockMvc.perform(signedPost("/test/crypto/mixed", reqEnv, dupOrderTs, dupOrderNonce))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.REPEAT_SUBMIT.getCode()));
    }

    @Test
    @DisplayName("5. @ApiSign + @ApiDecrypt（明文回包）与 GET @ApiSign + @ApiEncrypt（密文+延迟回签）组合")
    void shouldSupportSignPlusDecryptOnlyAndSignPlusEncryptOnly() throws Exception {
        String plainJson = "{\"orderNo\":\"ORD-SIGN-DEC\",\"amount\":42}";
        PreparedEnvelope reqEnv = encryptEnvelope(
                CryptoAlgorithm.RSA_AES_GCM, encodePublicKey(RSA_SERVER_KP), "v1", null, plainJson.getBytes(StandardCharsets.UTF_8));
        String nonce1 = UUID.randomUUID().toString().replace("-", "");
        String ts1 = String.valueOf(System.currentTimeMillis());

        // @ApiSign + @ApiDecrypt：响应未加密，明文 JSON + 明文签名响应头
        MvcResult decOnlyRes = mockMvc.perform(signedPost("/test/crypto/sign-decrypt", reqEnv, ts1, nonce1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("DEC_SIGNED:ORD-SIGN-DEC"))
                .andReturn();
        assertThat(decOnlyRes.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_KEY)).isNull();
        verifyResponseSignature(decOnlyRes);

        // GET @ApiSign + @ApiEncrypt：出站延后至密文信封生成后签名
        String nonce2 = UUID.randomUUID().toString().replace("-", "");
        String ts2 = String.valueOf(System.currentTimeMillis());
        String canonical = SignCanonicalUtil.buildRequestCanonicalString(
                "GET", "/test/crypto/sign-encrypt", ts2, nonce2, Map.of("name", new String[]{"ateng"}), DigestUtil.sha256Hex(""));
        String sig2 = hmacSigner.sign(canonical, SIGN_SECRET);

        MvcResult encOnlyRes = mockMvc.perform(get("/test/crypto/sign-encrypt")
                        .param("name", "ateng")
                        .header(ApiSignAspect.HEADER_APP_ID, APP_ID)
                        .header(ApiSignAspect.HEADER_TIMESTAMP, ts2)
                        .header(ApiSignAspect.HEADER_NONCE, nonce2)
                        .header(ApiSignAspect.HEADER_SIGNATURE, sig2))
                .andExpect(status().isOk())
                .andReturn();
        verifyResponseSignature(encOnlyRes);
        JsonNode decGet = decryptResponseEnvelope(encOnlyRes, CryptoAlgorithm.RSA_AES_GCM, encodePrivateKey(RSA_CLIENT_KP), null);
        assertThat(decGet.get("data").asText()).isEqualTo("HELLO:ateng");
    }

    @Test
    @DisplayName("6. 类级注解与方法覆盖、required=false 灰度兼容、GET/DELETE 及二进制/SSE 旁路")
    void shouldHandleClassLevelAnnotationsGrayFallbackAndBinaryBypass() throws Exception {
        // GET / HEAD / OPTIONS / TRACE 在类级 @ApiDecrypt + @ApiEncrypt 下均跳过入站解密
        MvcResult classGet = mockMvc.perform(get("/test/crypto/class-level/info"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode decClassGet = decryptResponseEnvelope(classGet, CryptoAlgorithm.RSA_AES_GCM, encodePrivateKey(RSA_CLIENT_KP), null);
        assertThat(decClassGet.get("data").asText()).isEqualTo("CLASS_INFO");

        mockMvc.perform(head("/test/crypto/class-level/info"))
                .andExpect(status().isOk());
        mockMvc.perform(options("/test/crypto/class-level/info"))
                .andExpect(status().isOk());

        // 无请求体 DELETE 在类级注解下跳过入站解密；携带请求体 DELETE 正常执行入站解密
        MvcResult classDelete = mockMvc.perform(delete("/test/crypto/class-level/item/77"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode decDelete = decryptResponseEnvelope(classDelete, CryptoAlgorithm.RSA_AES_GCM, encodePrivateKey(RSA_CLIENT_KP), null);
        assertThat(decDelete.get("data").asText()).isEqualTo("DELETED:77");

        PreparedEnvelope delBodyEnv = encryptEnvelope(
                CryptoAlgorithm.RSA_AES_GCM, encodePublicKey(RSA_SERVER_KP), "v1", null,
                "{\"orderNo\":\"DEL-BODY-9\",\"amount\":12}".getBytes(StandardCharsets.UTF_8));
        MvcResult classDeleteBody = mockMvc.perform(delete("/test/crypto/class-level/item-with-body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_KEY, delBodyEnv.encryptedKey())
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_IV, delBodyEnv.iv())
                        .content(delBodyEnv.envelopeBytes()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode decDelBody = decryptResponseEnvelope(classDeleteBody, CryptoAlgorithm.RSA_AES_GCM, encodePrivateKey(RSA_CLIENT_KP), null);
        assertThat(decDelBody.get("data").asText()).isEqualTo("DELETED_BODY:DEL-BODY-9");

        // 方法级 enabled=false 彻底豁免类级加解密
        mockMvc.perform(post("/test/crypto/class-level/plain-override")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"ORD-PLAIN\",\"amount\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("PLAIN:ORD-PLAIN"))
                .andExpect(res -> assertThat(res.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_KEY)).isNull());

        // required=false 灰度放行明文
        mockMvc.perform(post("/test/crypto/gray-notice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"ORD-GRAY-1\",\"amount\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("GRAY:ORD-GRAY-1"));

        // required=false 同端点同样支持密文
        PreparedEnvelope grayEnv = encryptEnvelope(
                CryptoAlgorithm.RSA_AES_GCM, encodePublicKey(RSA_SERVER_KP), "v1", null,
                "{\"orderNo\":\"ORD-GRAY-2\",\"amount\":6}".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/test/crypto/gray-notice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_KEY, grayEnv.encryptedKey())
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_IV, grayEnv.iv())
                        .content(grayEnv.envelopeBytes()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("GRAY:ORD-GRAY-2"));

        // multipart 上传跳过解密，JSON 出站响应遵循类级 @ApiEncrypt；octet-stream 下载与 SSE 流透传不被包装为密文信封
        MockMultipartFile file = new MockMultipartFile("file", "demo.txt", "text/plain", "binary".getBytes(StandardCharsets.UTF_8));
        MvcResult uploadRes = mockMvc.perform(multipart("/test/crypto/class-level/upload").file(file))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode decUpload = decryptResponseEnvelope(uploadRes, CryptoAlgorithm.RSA_AES_GCM, encodePrivateKey(RSA_CLIENT_KP), null);
        assertThat(decUpload.get("data").asText()).isEqualTo("UPLOADED:demo.txt");

        MvcResult downloadRes = mockMvc.perform(get("/test/crypto/class-level/download"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(downloadRes.getResponse().getContentAsByteArray()).containsExactly(1, 2, 3, 4);
        assertThat(downloadRes.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_KEY)).isNull();

        MvcResult sseRes = mockMvc.perform(get("/test/crypto/class-level/sse"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(sseRes.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_KEY)).isNull();
    }

    @Test
    @DisplayName("7. AAD 绑定防头部替换、多版本密钥轮换与停用拒绝、maxBodySize 413 与 useHttpStatus 开关")
    void shouldEnforceBindAadKeyRotationAndErrorBoundaries() throws Exception {
        WebProperties props = new WebProperties();
        props.getCrypto().setEnabled(true);
        props.getCrypto().setAppId(APP_ID);
        props.getCrypto().setActiveKeyId("v2");
        props.getCrypto().setBindAad(true);
        props.getCrypto().setUseHttpStatus(true);
        props.getCrypto().setMaxBodySize(DataSize.ofBytes(256));

        WebProperties.CryptoKeyVersionProperties v1Props = new WebProperties.CryptoKeyVersionProperties();
        v1Props.setEnabled(true);
        v1Props.setRsaServerPrivateKey(encodePrivateKey(RSA_SERVER_KP));
        v1Props.setRsaClientPublicKey(encodePublicKey(RSA_CLIENT_KP));

        WebProperties.CryptoKeyVersionProperties v2Props = new WebProperties.CryptoKeyVersionProperties();
        v2Props.setEnabled(true);
        v2Props.setRsaServerPrivateKey(encodePrivateKey(RSA_SERVER_V2_KP));
        v2Props.setRsaClientPublicKey(encodePublicKey(RSA_CLIENT_V2_KP));

        WebProperties.CryptoKeyVersionProperties v0Disabled = new WebProperties.CryptoKeyVersionProperties();
        v0Disabled.setEnabled(false);
        v0Disabled.setRsaServerPrivateKey(encodePrivateKey(RSA_SERVER_KP));

        props.getCrypto().getKeys().put("v0", v0Disabled);
        props.getCrypto().getKeys().put("v1", v1Props);
        props.getCrypto().getKeys().put("v2", v2Props);

        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        ObjectProvider<ApiSignAspect> emptySignProvider = bf.getBeanProvider(ApiSignAspect.class);

        ApiCryptoKeyProvider keyProvider = new DefaultApiCryptoKeyProvider(props.getCrypto());
        List<ApiCipher> ciphers = List.of(rsaCipher, sm2Cipher);

        // 通过带真实 ContentCachingFilter + RequestMappingHandlerMapping 的 MockMvc 执行 AAD、轮换与 413 精准边界校验
        org.springframework.web.context.support.GenericWebApplicationContext wac =
                new org.springframework.web.context.support.GenericWebApplicationContext();
        wac.registerBean(TestCryptoController.class, TestCryptoController::new);
        wac.registerBean(TestClassLevelCryptoController.class, TestClassLevelCryptoController::new);
        wac.registerBean(GlobalExceptionHandler.class, () -> new GlobalExceptionHandler());
        wac.registerBean(RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new);
        wac.refresh();
        RequestMappingHandlerMapping realMapping = wac.getBean(RequestMappingHandlerMapping.class);

        io.github.atengk.boot.web.filter.ContentCachingFilter upstreamCachingFilter =
                new io.github.atengk.boot.web.filter.ContentCachingFilter(DataSize.ofMegabytes(2));
        ApiCryptoFilter customFilter = new ApiCryptoFilter(
                props, keyProvider, ciphers, objectMapper, List.of(realMapping), emptySignProvider);
        MockMvc boundaryMvc = MockMvcBuilders.standaloneSetup(new TestCryptoController(), new TestClassLevelCryptoController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(upstreamCachingFilter, customFilter)
                .build();

        // 验证类级 TRACE 请求同样跳过入站解密（直接验证 Filter 层对 TRACE 的豁免语义）
        org.springframework.mock.web.MockHttpServletRequest traceReq =
                new org.springframework.mock.web.MockHttpServletRequest("TRACE", "/test/crypto/class-level/info");
        org.springframework.mock.web.MockHttpServletResponse traceResp =
                new org.springframework.mock.web.MockHttpServletResponse();
        java.util.concurrent.atomic.AtomicBoolean traceReachedPlainBody = new java.util.concurrent.atomic.AtomicBoolean(false);
        customFilter.doFilter(traceReq, traceResp, (req, res) -> {
            traceReachedPlainBody.set(!(req instanceof DecryptedBodyHttpServletRequest));
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"ok\":true}");
        });
        assertThat(traceReachedPlainBody.get()).isTrue();
        assertThat(traceResp.getHeader(ApiCryptoFilter.HEADER_ENCRYPT_KEY)).isNotBlank();

        // 7.1 bind-aad=true：使用历史密钥 v1 加密入站，出站使用 activeKeyId=v2 密钥加密（平滑双轨轮换）
        byte[] validAadV1 = ApiCryptoContext.buildStandardAad(APP_ID, "v1", CryptoAlgorithm.RSA_AES_GCM);
        PreparedEnvelope v1Env = encryptEnvelope(
                CryptoAlgorithm.RSA_AES_GCM, encodePublicKey(RSA_SERVER_KP), "v1", validAadV1,
                "{\"orderNo\":\"ORD-ROTATE-1\",\"amount\":88}".getBytes(StandardCharsets.UTF_8));

        MvcResult rotateRes = boundaryMvc.perform(post("/test/crypto/rsa-both")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiCryptoFilter.HEADER_APP_ID, APP_ID)
                        .header(ApiCryptoFilter.HEADER_KEY_ID, "v1")
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_KEY, v1Env.encryptedKey())
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_IV, v1Env.iv())
                        .content(v1Env.envelopeBytes()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(rotateRes.getResponse().getHeader(ApiCryptoFilter.HEADER_KEY_ID)).isEqualTo("v2");
        byte[] expectedOutAadV2 = ApiCryptoContext.buildStandardAad(APP_ID, "v2", CryptoAlgorithm.RSA_AES_GCM);
        JsonNode decRotate = decryptResponseEnvelope(rotateRes, CryptoAlgorithm.RSA_AES_GCM, encodePrivateKey(RSA_CLIENT_V2_KP), expectedOutAadV2);
        assertThat(decRotate.get("data").get("orderNo").asText()).isEqualTo("ORD-ROTATE-1");

        // 7.2 bind-aad=true 下篡改 X-App-Id 头部（替换攻击）-> useHttpStatus=true 返回 HTTP 400
        boundaryMvc.perform(post("/test/crypto/rsa-both")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiCryptoFilter.HEADER_APP_ID, "forged-app")
                        .header(ApiCryptoFilter.HEADER_KEY_ID, "v1")
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_KEY, v1Env.encryptedKey())
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_IV, v1Env.iv())
                        .content(v1Env.envelopeBytes()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        // 7.3 已吊销密钥版本 v0 -> HTTP 400 拒绝
        boundaryMvc.perform(post("/test/crypto/rsa-both")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiCryptoFilter.HEADER_KEY_ID, "v0")
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_KEY, v1Env.encryptedKey())
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_IV, v1Env.iv())
                        .content(v1Env.envelopeBytes()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        // 7.4 缺失加密头 -> HTTP 400 拒绝
        boundaryMvc.perform(post("/test/crypto/rsa-both")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cipherText\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        // 7.5 入站超出 maxBodySize (256B) -> HTTP 413 拒绝
        String oversizedCipher = "A".repeat(400);
        boundaryMvc.perform(post("/test/crypto/rsa-both")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_KEY, v1Env.encryptedKey())
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_IV, v1Env.iv())
                        .content("{\"cipherText\":\"" + oversizedCipher + "\"}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(CommonResultCode.PAYLOAD_TOO_LARGE.getCode()));

        // 7.6 出站 @ApiEncrypt 响应体超出 maxBodySize (256B) -> 严禁静默透传明文，返回 413 且响应不泄露明文片段
        MvcResult outboundOverflow = boundaryMvc.perform(get("/test/crypto/get-encrypt")
                        .param("item", "OVERFLOW_SECRET_TOKEN_" + "X".repeat(320)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(CommonResultCode.PAYLOAD_TOO_LARGE.getCode()))
                .andReturn();
        assertThat(outboundOverflow.getResponse().getContentAsString()).doesNotContain("OVERFLOW_SECRET_TOKEN");
        assertThat(outboundOverflow.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_KEY)).isNull();
    }

    private MockHttpServletRequestBuilder signedPost(String uri, PreparedEnvelope env, String ts, String nonce) {
        String bodySha256 = DigestUtil.sha256Hex(env.envelopeBytes());
        String canonical = SignCanonicalUtil.buildRequestCanonicalString("POST", uri, ts, nonce, Collections.emptyMap(), bodySha256);
        String sig = hmacSigner.sign(canonical, SIGN_SECRET);
        return post(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .header(ApiSignAspect.HEADER_APP_ID, APP_ID)
                .header(ApiSignAspect.HEADER_TIMESTAMP, ts)
                .header(ApiSignAspect.HEADER_NONCE, nonce)
                .header(ApiSignAspect.HEADER_SIGNATURE, sig)
                .header(ApiCryptoFilter.HEADER_KEY_ID, env.keyId())
                .header(ApiCryptoFilter.HEADER_ENCRYPT_ALG, env.algorithm().name())
                .header(ApiCryptoFilter.HEADER_ENCRYPT_KEY, env.encryptedKey())
                .header(ApiCryptoFilter.HEADER_ENCRYPT_IV, env.iv())
                .content(env.envelopeBytes());
    }

    private void verifyResponseSignature(MvcResult result) {
        String ts = result.getResponse().getHeader(ApiSignAspect.HEADER_TIMESTAMP);
        String nonce = result.getResponse().getHeader(ApiSignAspect.HEADER_NONCE);
        String sig = result.getResponse().getHeader(ApiSignAspect.HEADER_SIGNATURE);
        assertThat(ts).isNotBlank();
        assertThat(nonce).isNotBlank();
        assertThat(sig).isNotBlank();

        byte[] rawRespBytes = result.getResponse().getContentAsByteArray();
        String respCanonical = SignCanonicalUtil.buildResponseCanonicalString(ts, nonce, DigestUtil.sha256Hex(rawRespBytes));
        assertThat(hmacSigner.verify(respCanonical, sig, SIGN_SECRET)).isTrue();
    }

    private PreparedEnvelope encryptEnvelope(CryptoAlgorithm algorithm,
                                             String publicKey,
                                             String keyId,
                                             byte[] aad,
                                             byte[] plainBytes) throws Exception {
        ApiCipher cipher = algorithm == CryptoAlgorithm.SM2_SM4_GCM ? sm2Cipher : rsaCipher;
        ApiCipherResult enc = cipher.encrypt(plainBytes, new ApiCryptoContext(algorithm, APP_ID, keyId, publicKey, aad));
        byte[] envelopeBytes = objectMapper.writeValueAsBytes(new ApiEncryptedPayload(enc.cipherText()));
        return new PreparedEnvelope(algorithm, keyId, enc.encryptedKey(), enc.iv(), envelopeBytes);
    }

    private JsonNode decryptResponseEnvelope(MvcResult result,
                                             CryptoAlgorithm expectedAlg,
                                             String clientPrivateKey,
                                             byte[] aad) throws Exception {
        String keyHeader = result.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_KEY);
        String ivHeader = result.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_IV);
        String algHeader = result.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_ALG);
        assertThat(keyHeader).isNotBlank();
        assertThat(ivHeader).isNotBlank();
        assertThat(algHeader).isEqualTo(expectedAlg.name());

        ApiEncryptedPayload envelope = objectMapper.readValue(result.getResponse().getContentAsByteArray(), ApiEncryptedPayload.class);
        assertThat(envelope.getCipherText()).isNotBlank();

        ApiCipher cipher = expectedAlg == CryptoAlgorithm.SM2_SM4_GCM ? sm2Cipher : rsaCipher;
        byte[] decrypted = cipher.decrypt(
                new ApiCipherInput(keyHeader, ivHeader, envelope.getCipherText()),
                new ApiCryptoContext(expectedAlg, APP_ID, result.getResponse().getHeader(ApiCryptoFilter.HEADER_KEY_ID), clientPrivateKey, aad)
        );
        return objectMapper.readTree(decrypted);
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("生成测试 RSA 密钥对失败", e);
        }
    }

    private static String encodePublicKey(KeyPair keyPair) {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    private static String encodePrivateKey(KeyPair keyPair) {
        return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

    private record PreparedEnvelope(CryptoAlgorithm algorithm, String keyId, String encryptedKey, String iv, byte[] envelopeBytes) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({TestCryptoController.class, TestClassLevelCryptoController.class})
    static class TestApplication {

        @org.springframework.context.annotation.Bean
        io.github.atengk.boot.web.idempotent.store.IdempotentKeyStore idempotentKeyStore() {
            return new io.github.atengk.boot.web.idempotent.store.InMemoryIdempotentKeyStore();
        }
    }

    @Data
    static class CryptoOrderDTO {
        @NotBlank(message = "orderNo 不能为空")
        private String orderNo;
        @NotNull
        @Positive
        private BigDecimal amount;
        private String remark;
        private String secretCard;
    }

    @RestController
    @RequestMapping("/test/crypto")
    static class TestCryptoController {

        @ApiDecrypt(algorithm = CryptoAlgorithm.RSA_AES_GCM)
        @ApiEncrypt(algorithm = CryptoAlgorithm.RSA_AES_GCM)
        @PostMapping("/rsa-both")
        public Result<CryptoOrderDTO> rsaBoth(@RequestBody @Valid CryptoOrderDTO dto) {
            return Result.success(dto);
        }

        @ApiDecrypt
        @ApiEncrypt(encryptError = true)
        @PostMapping("/encrypt-error")
        public Result<Void> encryptError(@RequestBody CryptoOrderDTO dto) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "触发加密错误响应测试");
        }

        @ApiDecrypt(algorithm = CryptoAlgorithm.SM2_SM4_GCM)
        @ApiEncrypt(algorithm = CryptoAlgorithm.SM2_SM4_GCM)
        @PostMapping("/sm2-both")
        public Result<CryptoOrderDTO> sm2Both(@RequestBody @Valid CryptoOrderDTO dto) {
            return Result.success(dto);
        }

        @ApiEncrypt
        @GetMapping("/get-encrypt")
        public Result<String> getEncrypt(@RequestParam("item") String item) {
            return Result.success("REPORT:" + item);
        }

        @ApiSign(message = "签名校验未通过")
        @ApiDecrypt
        @ApiEncrypt
        @Idempotent(key = "#dto.orderNo", timeout = 30)
        @PostMapping("/mixed")
        public Result<CryptoOrderDTO> mixed(@RequestBody @Valid CryptoOrderDTO dto) {
            return Result.success(dto);
        }

        @ApiSign(message = "签名校验未通过")
        @ApiDecrypt
        @PostMapping("/sign-decrypt")
        public Result<String> signDecrypt(@RequestBody @Valid CryptoOrderDTO dto) {
            return Result.success("DEC_SIGNED:" + dto.getOrderNo());
        }

        @ApiSign(message = "签名校验未通过")
        @ApiEncrypt
        @GetMapping("/sign-encrypt")
        public Result<String> signEncrypt(@RequestParam("name") String name) {
            return Result.success("HELLO:" + name);
        }

        @ApiDecrypt(required = false)
        @PostMapping("/gray-notice")
        public Result<String> grayNotice(@RequestBody @Valid CryptoOrderDTO dto) {
            return Result.success("GRAY:" + dto.getOrderNo());
        }
    }

    @ApiDecrypt
    @ApiEncrypt
    @RestController
    @RequestMapping("/test/crypto/class-level")
    static class TestClassLevelCryptoController {

        @RequestMapping(value = "/info", method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.OPTIONS, RequestMethod.TRACE})
        public Result<String> info() {
            return Result.success("CLASS_INFO");
        }

        @DeleteMapping("/item/{id}")
        public Result<String> deleteItem(@PathVariable("id") Long id) {
            return Result.success("DELETED:" + id);
        }

        @DeleteMapping("/item-with-body")
        public Result<String> deleteItemWithBody(@RequestBody @Valid CryptoOrderDTO dto) {
            return Result.success("DELETED_BODY:" + dto.getOrderNo());
        }

        @ApiDecrypt(enabled = false)
        @ApiEncrypt(enabled = false)
        @PostMapping("/plain-override")
        public Result<String> plainOverride(@RequestBody CryptoOrderDTO dto) {
            return Result.success("PLAIN:" + dto.getOrderNo());
        }

        @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        public Result<String> upload(@RequestParam("file") MultipartFile file) {
            return Result.success("UPLOADED:" + file.getOriginalFilename());
        }

        @GetMapping(value = "/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public byte[] download() {
            return new byte[]{1, 2, 3, 4};
        }

        @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter sse() {
            SseEmitter emitter = new SseEmitter(1000L);
            try {
                emitter.send("ping");
                emitter.complete();
            } catch (Exception ignored) {
            }
            return emitter;
        }
    }
}
