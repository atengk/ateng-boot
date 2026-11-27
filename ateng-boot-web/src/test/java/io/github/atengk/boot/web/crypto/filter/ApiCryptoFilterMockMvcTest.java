package io.github.atengk.boot.web.crypto.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.web.crypto.annotation.ApiDecrypt;
import io.github.atengk.boot.web.crypto.annotation.ApiEncrypt;
import io.github.atengk.boot.web.crypto.cipher.RsaAesGcmApiCipher;
import io.github.atengk.boot.web.crypto.cipher.Sm2Sm4GcmApiCipher;
import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;
import io.github.atengk.boot.web.crypto.enums.CryptoResponseKeyMode;
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
import io.github.atengk.boot.web.xss.XssCleaner;
import io.github.atengk.boot.web.xss.XssFilter;
import io.github.atengk.boot.web.xss.XssMode;
import jakarta.servlet.http.HttpServletRequest;
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

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    @DisplayName("8. REQUEST_SESSION_KEY 模式：无静态 clientPublicKey 双向回包、GET 密钥握手、@ApiSign 防 DoS 与 @Idempotent 闭环")
    void shouldSupportRequestSessionKeyForSpaWithoutClientPublicKeyAndHandshake() throws Exception {
        // 8.1 构造完全不配置任何 clientPublicKey 且开启 xss.cleanHeaders=true 的全栈独立 MockMvc
        WebProperties spaProps = new WebProperties();
        spaProps.getCrypto().setEnabled(true);
        spaProps.getCrypto().setAppId(APP_ID);
        spaProps.getCrypto().setActiveKeyId("v1");
        spaProps.getCrypto().setRsaServerPrivateKey(encodePrivateKey(RSA_SERVER_KP));
        spaProps.getCrypto().setSm2ServerPrivateKey(SM2_SERVER_KP.privateKeyHex());
        spaProps.getXss().setEnabled(true);
        spaProps.getXss().setMode(XssMode.ESCAPE);
        spaProps.getXss().setCleanHeaders(true);

        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        ObjectProvider<ApiSignAspect> emptySignProvider = bf.getBeanProvider(ApiSignAspect.class);
        ApiCryptoKeyProvider spaKeyProvider = new DefaultApiCryptoKeyProvider(spaProps.getCrypto());

        AtomicInteger getExecutionCounter = new AtomicInteger(0);
        AtomicInteger grayFailCounter = new AtomicInteger(0);
        AtomicInteger conflictCounter = new AtomicInteger(0);
        TestSpaController spaController = new TestSpaController(getExecutionCounter, grayFailCounter, conflictCounter);

        org.springframework.web.context.support.GenericWebApplicationContext wac =
                new org.springframework.web.context.support.GenericWebApplicationContext();
        wac.registerBean(TestSpaController.class, () -> spaController);
        wac.registerBean(GlobalExceptionHandler.class, () -> new GlobalExceptionHandler());
        wac.registerBean(RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new);
        wac.refresh();
        RequestMappingHandlerMapping spaMapping = wac.getBean(RequestMappingHandlerMapping.class);

        XssFilter xssFilter = new XssFilter(spaProps.getXss(), new XssCleaner(spaProps.getXss().getMode()));
        ApiCryptoFilter spaCryptoFilter = new ApiCryptoFilter(
                spaProps, spaKeyProvider, List.of(rsaCipher, sm2Cipher), objectMapper, List.of(spaMapping), emptySignProvider);
        MockMvc spaMvc = MockMvcBuilders.standaloneSetup(spaController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(xssFilter, spaCryptoFilter)
                .build();

        // 8.2 RSA_AES_GCM POST 双向 SPA 回包（无任何 clientPublicKey 配置 + XssFilter cleanHeaders=true）
        byte[] rsaSessionKey = CryptoUtil.generateKey(256);
        String reqIv = Base64.getEncoder().encodeToString(CryptoUtil.generateGcmIv());
        String wrappedRsaKey = wrapRsaSessionKey(rsaSessionKey, RSA_SERVER_KP);
        byte[] rsaPlainBody = "{\"orderNo\":\"SPA-RSA-01\",\"amount\":66.6,\"remark\":\"<script>s</script>\"}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] rsaBodyCipher = CryptoUtil.encryptGcm(rsaPlainBody, rsaSessionKey, Base64.getDecoder().decode(reqIv), null);
        byte[] rsaEnvelope = objectMapper.writeValueAsBytes(
                new ApiEncryptedPayload(Base64.getEncoder().encodeToString(rsaBodyCipher)));

        MvcResult rsaSpaRes = spaMvc.perform(post("/test/spa/rsa-both")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_KEY, wrappedRsaKey)
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_IV, reqIv)
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_ALG, "RSA_AES_GCM")
                        .content(rsaEnvelope))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(rsaSpaRes.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_KEY)).isNull();
        String respIv = rsaSpaRes.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_IV);
        assertThat(respIv).isNotBlank().isNotEqualTo(reqIv);
        assertThat(rsaSpaRes.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_ALG)).isEqualTo("RSA_AES_GCM");
        JsonNode rsaDecNode = decryptSpaResponse(rsaSpaRes, CryptoAlgorithm.RSA_AES_GCM, rsaSessionKey, null);
        assertThat(rsaDecNode.get("code").asInt()).isEqualTo(200);
        assertThat(rsaDecNode.get("data").get("orderNo").asText()).isEqualTo("SPA-RSA-01");
        assertThat(rsaDecNode.get("data").get("hasSessionKeyAttribute").asBoolean()).isFalse();

        // 8.3 SM2_SM4_GCM POST 双向 SPA 回包（无任何 clientPublicKey）
        byte[] sm4PlainBody = "{\"orderNo\":\"SPA-SM2-01\",\"amount\":88.8}".getBytes(StandardCharsets.UTF_8);
        ApiCipherResult sm4ReqEnc = sm2Cipher.encrypt(sm4PlainBody,
                new ApiCryptoContext(CryptoAlgorithm.SM2_SM4_GCM, APP_ID, "v1", SM2_SERVER_KP.publicKeyHex(), null));
        byte[] sm4SessionKey = sm2Cipher.unwrapSessionKey(sm4ReqEnc.encryptedKey(),
                new ApiCryptoContext(CryptoAlgorithm.SM2_SM4_GCM, APP_ID, "v1", SM2_SERVER_KP.privateKeyHex(), null));
        byte[] sm4Envelope = objectMapper.writeValueAsBytes(new ApiEncryptedPayload(sm4ReqEnc.cipherText()));

        MvcResult sm2SpaRes = spaMvc.perform(post("/test/spa/sm2-both")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_KEY, sm4ReqEnc.encryptedKey())
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_IV, sm4ReqEnc.iv())
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_ALG, "SM2_SM4_GCM")
                        .content(sm4Envelope))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(sm2SpaRes.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_KEY)).isNull();
        assertThat(sm2SpaRes.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_IV)).isNotBlank().isNotEqualTo(sm4ReqEnc.iv());
        JsonNode sm2DecNode = decryptSpaResponse(sm2SpaRes, CryptoAlgorithm.SM2_SM4_GCM, sm4SessionKey, null);
        assertThat(sm2DecNode.get("data").get("orderNo").asText()).isEqualTo("SPA-SM2-01");

        // 8.4 GET @ApiEncrypt(keyMode = REQUEST_SESSION_KEY) 密钥握手成功 + 缺失 X-Encrypt-Key 前置 Fail-Fast 拦截
        byte[] getSessionKey = CryptoUtil.generateKey(256);
        String wrappedGetKey = wrapRsaSessionKey(getSessionKey, RSA_SERVER_KP);
        MvcResult getHandshakeRes = spaMvc.perform(get("/test/spa/get-handshake")
                        .param("item", "profile-1")
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_KEY, wrappedGetKey))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(getExecutionCounter.get()).isEqualTo(1);
        assertThat(getHandshakeRes.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_KEY)).isNull();
        JsonNode getDec = decryptSpaResponse(getHandshakeRes, CryptoAlgorithm.RSA_AES_GCM, getSessionKey, null);
        assertThat(getDec.get("data").asText()).isEqualTo("SPA_GET:profile-1");

        spaMvc.perform(get("/test/spa/get-handshake").param("item", "profile-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.BAD_REQUEST.getCode()));
        assertThat(getExecutionCounter.get()).isEqualTo(1);

        // 8.5 GET @ApiSign + @ApiEncrypt(keyMode = REQUEST_SESSION_KEY) 与 POST @ApiSign + @Idempotent 闭环（走主 MockMvc）
        byte[] signedGetKey = CryptoUtil.generateKey(256);
        String wrappedSignedGetKey = wrapRsaSessionKey(signedGetKey, RSA_SERVER_KP);
        String getNonce = UUID.randomUUID().toString().replace("-", "");
        String getTs = String.valueOf(System.currentTimeMillis());
        String getCanonical = SignCanonicalUtil.buildRequestCanonicalString(
                "GET", "/test/crypto/spa-sign-get", getTs, getNonce, Map.of("name", new String[]{"spa-user"}), DigestUtil.sha256Hex(""));
        String getSig = hmacSigner.sign(getCanonical, SIGN_SECRET);

        MvcResult signedGetRes = mockMvc.perform(get("/test/crypto/spa-sign-get")
                        .param("name", "spa-user")
                        .header(ApiSignAspect.HEADER_APP_ID, APP_ID)
                        .header(ApiSignAspect.HEADER_TIMESTAMP, getTs)
                        .header(ApiSignAspect.HEADER_NONCE, getNonce)
                        .header(ApiSignAspect.HEADER_SIGNATURE, getSig)
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_KEY, wrappedSignedGetKey))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(signedGetRes.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_KEY)).isNull();
        verifyResponseSignature(signedGetRes);
        JsonNode signedGetDec = decryptSpaResponse(signedGetRes, CryptoAlgorithm.RSA_AES_GCM, signedGetKey, null);
        assertThat(signedGetDec.get("data").asText()).isEqualTo("SPA_SIGNED_GET:spa-user");

        // 伪造签名 + 畸形 X-Encrypt-Key：优先命中 @ApiSign 验签失败（防 DoS）
        mockMvc.perform(get("/test/crypto/spa-sign-get")
                        .param("name", "spa-user")
                        .header(ApiSignAspect.HEADER_APP_ID, APP_ID)
                        .header(ApiSignAspect.HEADER_TIMESTAMP, String.valueOf(System.currentTimeMillis()))
                        .header(ApiSignAspect.HEADER_NONCE, UUID.randomUUID().toString().replace("-", ""))
                        .header(ApiSignAspect.HEADER_SIGNATURE, "BAD_SIGN")
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_KEY, "NOT_BASE64!!!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("签名校验未通过"));

        // POST @ApiSign + @ApiDecrypt + @ApiEncrypt(keyMode = REQUEST_SESSION_KEY) + @Idempotent
        byte[] postSignKey = CryptoUtil.generateKey(256);
        String postReqIv = Base64.getEncoder().encodeToString(CryptoUtil.generateGcmIv());
        String wrappedPostSignKey = wrapRsaSessionKey(postSignKey, RSA_SERVER_KP);
        String spaOrderNo = "SPA-MIX-" + UUID.randomUUID().toString().substring(0, 8);
        byte[] postSignPlain = String.format("{\"orderNo\":\"%s\",\"amount\":300}", spaOrderNo).getBytes(StandardCharsets.UTF_8);
        byte[] postSignCipher = CryptoUtil.encryptGcm(postSignPlain, postSignKey, Base64.getDecoder().decode(postReqIv), null);
        byte[] postSignEnvelope = objectMapper.writeValueAsBytes(
                new ApiEncryptedPayload(Base64.getEncoder().encodeToString(postSignCipher)));

        String postTs1 = String.valueOf(System.currentTimeMillis());
        String postNonce1 = UUID.randomUUID().toString().replace("-", "");
        PreparedEnvelope postPrep1 = new PreparedEnvelope(CryptoAlgorithm.RSA_AES_GCM, "v1", wrappedPostSignKey, postReqIv, postSignEnvelope);
        MvcResult postSignRes = mockMvc.perform(signedPost("/test/crypto/spa-mixed", postPrep1, postTs1, postNonce1))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(postSignRes.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_KEY)).isNull();
        verifyResponseSignature(postSignRes);
        JsonNode postSignDec = decryptSpaResponse(postSignRes, CryptoAlgorithm.RSA_AES_GCM, postSignKey, null);
        assertThat(postSignDec.get("data").get("orderNo").asText()).isEqualTo(spaOrderNo);

        // 同 orderNo + 新 Nonce 验证 @Idempotent 幂等拦截
        mockMvc.perform(signedPost("/test/crypto/spa-mixed", postPrep1,
                        String.valueOf(System.currentTimeMillis()), UUID.randomUUID().toString().replace("-", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.REPEAT_SUBMIT.getCode()));
    }

    @Test
    @DisplayName("9. REQUEST_SESSION_KEY 轮换期 X-Key-Id/AAD 一致性、全局继承与注解覆写及 Fail-Closed 冲突边界")
    void shouldEnforceRequestSessionKeyRotationAadGlobalOverrideAndFailClosed() throws Exception {
        WebProperties props = new WebProperties();
        props.getCrypto().setEnabled(true);
        props.getCrypto().setAppId(APP_ID);
        props.getCrypto().setActiveKeyId("v2");
        props.getCrypto().setBindAad(true);
        props.getCrypto().setUseHttpStatus(true);
        props.getCrypto().setResponseKeyMode(CryptoResponseKeyMode.REQUEST_SESSION_KEY);

        WebProperties.CryptoKeyVersionProperties v1Props = new WebProperties.CryptoKeyVersionProperties();
        v1Props.setEnabled(true);
        v1Props.setRsaServerPrivateKey(encodePrivateKey(RSA_SERVER_KP));
        v1Props.setRsaClientPublicKey(encodePublicKey(RSA_CLIENT_KP));

        WebProperties.CryptoKeyVersionProperties v2Props = new WebProperties.CryptoKeyVersionProperties();
        v2Props.setEnabled(true);
        v2Props.setRsaServerPrivateKey(encodePrivateKey(RSA_SERVER_V2_KP));
        v2Props.setRsaClientPublicKey(encodePublicKey(RSA_CLIENT_V2_KP));

        props.getCrypto().getKeys().put("v1", v1Props);
        props.getCrypto().getKeys().put("v2", v2Props);

        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        ObjectProvider<ApiSignAspect> emptySignProvider = bf.getBeanProvider(ApiSignAspect.class);
        ApiCryptoKeyProvider keyProvider = new DefaultApiCryptoKeyProvider(props.getCrypto());

        AtomicInteger getCounter = new AtomicInteger(0);
        AtomicInteger grayCounter = new AtomicInteger(0);
        AtomicInteger conflictCounter = new AtomicInteger(0);
        TestSpaController spaController = new TestSpaController(getCounter, grayCounter, conflictCounter);

        org.springframework.web.context.support.GenericWebApplicationContext wac =
                new org.springframework.web.context.support.GenericWebApplicationContext();
        wac.registerBean(TestSpaController.class, () -> spaController);
        wac.registerBean(GlobalExceptionHandler.class, () -> new GlobalExceptionHandler());
        wac.registerBean(RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new);
        wac.refresh();
        RequestMappingHandlerMapping mapping = wac.getBean(RequestMappingHandlerMapping.class);

        ApiCryptoFilter filter = new ApiCryptoFilter(
                props, keyProvider, List.of(rsaCipher, sm2Cipher), objectMapper, List.of(mapping), emptySignProvider);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(spaController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(filter)
                .build();

        // 9.1 轮换窗口（activeKeyId=v2）下客户端用 v1 发起请求，响应 X-Key-Id 回写 v1 且 AAD 对齐 v1
        byte[] v1SessionKey = CryptoUtil.generateKey(256);
        String v1ReqIv = Base64.getEncoder().encodeToString(CryptoUtil.generateGcmIv());
        String v1WrappedKey = wrapRsaSessionKey(v1SessionKey, RSA_SERVER_KP);
        byte[] v1Aad = ApiCryptoContext.buildStandardAad(APP_ID, "v1", CryptoAlgorithm.RSA_AES_GCM);
        byte[] v1Cipher = CryptoUtil.encryptGcm(
                "{\"orderNo\":\"ROT-SPA-1\",\"amount\":10}".getBytes(StandardCharsets.UTF_8),
                v1SessionKey, Base64.getDecoder().decode(v1ReqIv), v1Aad);
        byte[] v1Envelope = objectMapper.writeValueAsBytes(
                new ApiEncryptedPayload(Base64.getEncoder().encodeToString(v1Cipher)));

        MvcResult inheritRes = mvc.perform(post("/test/spa/inherit-global")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiCryptoFilter.HEADER_APP_ID, APP_ID)
                        .header(ApiCryptoFilter.HEADER_KEY_ID, "v1")
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_KEY, v1WrappedKey)
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_IV, v1ReqIv)
                        .content(v1Envelope))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(inheritRes.getResponse().getHeader(ApiCryptoFilter.HEADER_KEY_ID)).isEqualTo("v1");
        assertThat(inheritRes.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_KEY)).isNull();
        JsonNode inheritDec = decryptSpaResponse(inheritRes, CryptoAlgorithm.RSA_AES_GCM, v1SessionKey, v1Aad);
        assertThat(inheritDec.get("data").get("orderNo").asText()).isEqualTo("ROT-SPA-1");

        // 9.2 全局 responseKeyMode=REQUEST_SESSION_KEY 下方法级显式覆盖为 CLIENT_PUBLIC_KEY（使用 activeKeyId=v2 客户端公钥）
        MvcResult overrideRes = mvc.perform(get("/test/spa/override-client-pub"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(overrideRes.getResponse().getHeader(ApiCryptoFilter.HEADER_KEY_ID)).isEqualTo("v2");
        byte[] v2Aad = ApiCryptoContext.buildStandardAad(APP_ID, "v2", CryptoAlgorithm.RSA_AES_GCM);
        JsonNode overrideDec = decryptResponseEnvelope(overrideRes, CryptoAlgorithm.RSA_AES_GCM, encodePrivateKey(RSA_CLIENT_V2_KP), v2Aad);
        assertThat(overrideDec.get("data").asText()).isEqualTo("CLIENT_PUBLIC_KEY_OVERRIDE");

        // 9.3 Fail-Closed：@ApiDecrypt(required = false) + @ApiEncrypt(keyMode = REQUEST_SESSION_KEY) 明文请求严格拦截 400
        mvc.perform(post("/test/spa/gray-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"PLAIN\",\"amount\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(CommonResultCode.BAD_REQUEST.getCode()));
        assertThat(grayCounter.get()).isZero();

        // 9.4 Fail-Fast：入站与出站显式声明冲突算法直接拦截 500，不进入 Controller
        mvc.perform(post("/test/spa/alg-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_KEY, v1WrappedKey)
                        .header(ApiCryptoFilter.HEADER_ENCRYPT_IV, v1ReqIv)
                        .content(v1Envelope))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(CommonResultCode.INTERNAL_SERVER_ERROR.getCode()));
        assertThat(conflictCounter.get()).isZero();
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

    private JsonNode decryptSpaResponse(MvcResult result,
                                        CryptoAlgorithm expectedAlg,
                                        byte[] sessionKey,
                                        byte[] aad) throws Exception {
        assertThat(result.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_KEY)).isNull();
        String ivHeader = result.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_IV);
        String algHeader = result.getResponse().getHeader(ApiCryptoFilter.HEADER_ENCRYPT_ALG);
        assertThat(ivHeader).isNotBlank();
        assertThat(algHeader).isEqualTo(expectedAlg.name());

        ApiEncryptedPayload envelope = objectMapper.readValue(result.getResponse().getContentAsByteArray(), ApiEncryptedPayload.class);
        assertThat(envelope.getCipherText()).isNotBlank();

        ApiCipher cipher = expectedAlg == CryptoAlgorithm.SM2_SM4_GCM ? sm2Cipher : rsaCipher;
        byte[] decrypted = cipher.decryptWithSessionKey(
                sessionKey,
                ivHeader,
                envelope.getCipherText(),
                new ApiCryptoContext(expectedAlg, APP_ID, result.getResponse().getHeader(ApiCryptoFilter.HEADER_KEY_ID), null, aad)
        );
        return objectMapper.readTree(decrypted);
    }

    private static String wrapRsaSessionKey(byte[] sessionKey, KeyPair serverKp) throws Exception {
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, serverKp.getPublic(),
                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        return Base64.getEncoder().encodeToString(rsa.doFinal(sessionKey));
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

        @ApiSign(message = "签名校验未通过")
        @ApiEncrypt(keyMode = CryptoResponseKeyMode.REQUEST_SESSION_KEY)
        @GetMapping("/spa-sign-get")
        public Result<String> spaSignGet(@RequestParam("name") String name) {
            return Result.success("SPA_SIGNED_GET:" + name);
        }

        @ApiSign(message = "签名校验未通过")
        @ApiDecrypt
        @ApiEncrypt(keyMode = CryptoResponseKeyMode.REQUEST_SESSION_KEY)
        @Idempotent(key = "#dto.orderNo", timeout = 30)
        @PostMapping("/spa-mixed")
        public Result<CryptoOrderDTO> spaMixed(@RequestBody @Valid CryptoOrderDTO dto) {
            return Result.success(dto);
        }
    }

    @RestController
    @RequestMapping("/test/spa")
    static class TestSpaController {

        private final AtomicInteger getCounter;
        private final AtomicInteger grayCounter;
        private final AtomicInteger conflictCounter;

        TestSpaController(AtomicInteger getCounter, AtomicInteger grayCounter, AtomicInteger conflictCounter) {
            this.getCounter = getCounter;
            this.grayCounter = grayCounter;
            this.conflictCounter = conflictCounter;
        }

        @ApiDecrypt(algorithm = CryptoAlgorithm.RSA_AES_GCM)
        @ApiEncrypt(algorithm = CryptoAlgorithm.RSA_AES_GCM, keyMode = CryptoResponseKeyMode.REQUEST_SESSION_KEY)
        @PostMapping("/rsa-both")
        public Result<Map<String, Object>> rsaBoth(@RequestBody @Valid CryptoOrderDTO dto, HttpServletRequest request) {
            boolean leakedSessionAttr = false;
            Enumeration<String> names = request.getAttributeNames();
            while (names.hasMoreElements()) {
                String attrName = names.nextElement();
                Object val = request.getAttribute(attrName);
                if (val instanceof byte[] || attrName.toLowerCase().contains("sessionkey")) {
                    leakedSessionAttr = true;
                    break;
                }
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("orderNo", dto.getOrderNo());
            resp.put("hasSessionKeyAttribute", leakedSessionAttr);
            return Result.success(resp);
        }

        @ApiDecrypt(algorithm = CryptoAlgorithm.SM2_SM4_GCM)
        @ApiEncrypt(algorithm = CryptoAlgorithm.SM2_SM4_GCM, keyMode = CryptoResponseKeyMode.REQUEST_SESSION_KEY)
        @PostMapping("/sm2-both")
        public Result<CryptoOrderDTO> sm2Both(@RequestBody @Valid CryptoOrderDTO dto) {
            return Result.success(dto);
        }

        @ApiEncrypt(keyMode = CryptoResponseKeyMode.REQUEST_SESSION_KEY)
        @GetMapping("/get-handshake")
        public Result<String> getHandshake(@RequestParam("item") String item) {
            getCounter.incrementAndGet();
            return Result.success("SPA_GET:" + item);
        }

        @ApiDecrypt
        @ApiEncrypt
        @PostMapping("/inherit-global")
        public Result<CryptoOrderDTO> inheritGlobal(@RequestBody @Valid CryptoOrderDTO dto) {
            return Result.success(dto);
        }

        @ApiEncrypt(keyMode = CryptoResponseKeyMode.CLIENT_PUBLIC_KEY)
        @GetMapping("/override-client-pub")
        public Result<String> overrideClientPub() {
            return Result.success("CLIENT_PUBLIC_KEY_OVERRIDE");
        }

        @ApiDecrypt(required = false)
        @ApiEncrypt(keyMode = CryptoResponseKeyMode.REQUEST_SESSION_KEY)
        @PostMapping("/gray-conflict")
        public Result<String> grayConflict(@RequestBody CryptoOrderDTO dto) {
            grayCounter.incrementAndGet();
            return Result.success(dto.getOrderNo());
        }

        @ApiDecrypt(algorithm = CryptoAlgorithm.RSA_AES_GCM)
        @ApiEncrypt(algorithm = CryptoAlgorithm.SM2_SM4_GCM, keyMode = CryptoResponseKeyMode.REQUEST_SESSION_KEY)
        @PostMapping("/alg-conflict")
        public Result<String> algConflict(@RequestBody CryptoOrderDTO dto) {
            conflictCounter.incrementAndGet();
            return Result.success(dto.getOrderNo());
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
