package io.github.atengk.boot.sample.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.sample.web.dto.OrderSignDTO;
import io.github.atengk.boot.sample.web.dto.PrepareSignHeadersDTO;
import io.github.atengk.boot.web.sign.aspect.ApiSignAspect;
import io.github.atengk.boot.web.sign.signer.HmacSha256Signer;
import io.github.atengk.boot.web.sign.signer.RsaSigner;
import io.github.atengk.boot.web.sign.signer.Sm2Signer;
import io.github.atengk.boot.web.sign.spi.ApiSigner;
import io.github.atengk.boot.web.sign.util.SignCanonicalUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 传输安全防护网与 API 双向加签验签端到端集成测试（HMAC-SHA256 算法）
 * 覆盖合法请求加签放行、防重放阻断、篡改拦截、辅助头生成及安全接口
 *
 * @author Ateng
 * @since 2026-11-08
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("API 双向数据加签验签与防重放集成测试 (HMAC-SHA256 / 多算法注解路由)")
class WebSecurityAndSignControllerTest {

    private static final String SECRET_KEY = "TestSecretKey123";
    private static final String RSA_PUBLIC_KEY;
    private static final String RSA_PRIVATE_KEY;
    private static final Sm2Signer.Sm2KeyPair SM2_KEY_PAIR = Sm2Signer.generateKeyPair();

    static {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            RSA_PUBLIC_KEY = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            RSA_PRIVATE_KEY = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("初始化测试 RSA 密钥对失败", e);
        }
    }

    @DynamicPropertySource
    static void registerMultiAlgorithmKeys(DynamicPropertyRegistry registry) {
        registry.add("ateng.boot.web.sign.rsa-public-key", () -> RSA_PUBLIC_KEY);
        registry.add("ateng.boot.web.sign.rsa-private-key", () -> RSA_PRIVATE_KEY);
        registry.add("ateng.boot.web.sign.sm2-public-key", SM2_KEY_PAIR::publicKeyHex);
        registry.add("ateng.boot.web.sign.sm2-private-key", SM2_KEY_PAIR::privateKeyHex);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final HmacSha256Signer signer = new HmacSha256Signer();

    @Test
    @DisplayName("1. 合法 HMAC 签名 POST 请求放行且响应头正确回写 X-Signature")
    void shouldAllowValidHmacSignedPostRequestAndReturnSignature() throws Exception {
        OrderSignDTO dto = new OrderSignDTO();
        dto.setOrderNo("ORD_SIGN_001");
        dto.setAmount(new BigDecimal("99.99"));
        String jsonBody = objectMapper.writeValueAsString(dto);

        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String bodySha256 = DigestUtil.sha256Hex(jsonBody);

        String canonical = SignCanonicalUtil.buildRequestCanonicalString(
                "POST",
                "/sample/web/security/sign/orders",
                timestamp,
                nonce,
                Map.of(),
                bodySha256
        );
        String signature = signer.sign(canonical, SECRET_KEY);

        MvcResult result = mockMvc.perform(post("/sample/web/security/sign/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody)
                        .header(ApiSignAspect.HEADER_TIMESTAMP, timestamp)
                        .header(ApiSignAspect.HEADER_NONCE, nonce)
                        .header(ApiSignAspect.HEADER_SIGNATURE, signature))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderNo").value("ORD_SIGN_001"))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(header().exists(ApiSignAspect.HEADER_SIGNATURE))
                .andExpect(header().string(ApiSignAspect.HEADER_NONCE, nonce))
                .andExpect(header().string(ApiSignAspect.HEADER_TIMESTAMP, timestamp))
                .andReturn();

        // 校验响应头签名的合法性
        String respSignature = result.getResponse().getHeader(ApiSignAspect.HEADER_SIGNATURE);
        String respContent = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String respBodySha256 = DigestUtil.sha256Hex(respContent);
        String respCanonical = SignCanonicalUtil.buildResponseCanonicalString(timestamp, nonce, respBodySha256);
        assertThat(signer.verify(respCanonical, respSignature, SECRET_KEY)).isTrue();
    }

    @Test
    @DisplayName("2. 时间戳超时请求被安全拦截 (400 签名已过期)")
    void shouldRejectExpiredTimestamp() throws Exception {
        OrderSignDTO dto = new OrderSignDTO();
        dto.setOrderNo("ORD_EXPIRED");
        dto.setAmount(new BigDecimal("10.00"));
        String jsonBody = objectMapper.writeValueAsString(dto);

        // 构造 120 秒前的时间戳（超出 60 秒窗口）
        String expiredTimestamp = String.valueOf(System.currentTimeMillis() - 120_000L);
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String bodySha256 = DigestUtil.sha256Hex(jsonBody);

        String canonical = SignCanonicalUtil.buildRequestCanonicalString(
                "POST",
                "/sample/web/security/sign/orders",
                expiredTimestamp,
                nonce,
                Map.of(),
                bodySha256
        );
        String signature = signer.sign(canonical, SECRET_KEY);

        mockMvc.perform(post("/sample/web/security/sign/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody)
                        .header(ApiSignAspect.HEADER_TIMESTAMP, expiredTimestamp)
                        .header(ApiSignAspect.HEADER_NONCE, nonce)
                        .header(ApiSignAspect.HEADER_SIGNATURE, signature))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("请求签名已过期"));
    }

    @Test
    @DisplayName("3. 时效窗口内重复使用 Nonce 触发防重放阻断 (40003)")
    void shouldRejectReplayedNonceWithinExpireWindow() throws Exception {
        OrderSignDTO dto = new OrderSignDTO();
        dto.setOrderNo("ORD_REPLAY_TEST");
        dto.setAmount(new BigDecimal("50.00"));
        String jsonBody = objectMapper.writeValueAsString(dto);

        String timestamp = String.valueOf(System.currentTimeMillis());
        String sharedNonce = "nonce_replay_" + System.currentTimeMillis();
        String bodySha256 = DigestUtil.sha256Hex(jsonBody);

        String canonical = SignCanonicalUtil.buildRequestCanonicalString(
                "POST",
                "/sample/web/security/sign/orders",
                timestamp,
                sharedNonce,
                Map.of(),
                bodySha256
        );
        String signature = signer.sign(canonical, SECRET_KEY);

        // 首次请求成功
        mockMvc.perform(post("/sample/web/security/sign/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody)
                        .header(ApiSignAspect.HEADER_TIMESTAMP, timestamp)
                        .header(ApiSignAspect.HEADER_NONCE, sharedNonce)
                        .header(ApiSignAspect.HEADER_SIGNATURE, signature))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 第二次携带相同 Nonce 重放被阻断
        mockMvc.perform(post("/sample/web/security/sign/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody)
                        .header(ApiSignAspect.HEADER_TIMESTAMP, timestamp)
                        .header(ApiSignAspect.HEADER_NONCE, sharedNonce)
                        .header(ApiSignAspect.HEADER_SIGNATURE, signature))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40003))
                .andExpect(jsonPath("$.msg").value("请求已被处理或存在重放风险"));
    }

    @Test
    @DisplayName("4. 请求体篡改导致验签失败阻断 (400 数据验签失败)")
    void shouldRejectTamperedBody() throws Exception {
        OrderSignDTO dto = new OrderSignDTO();
        dto.setOrderNo("ORD_ORIGINAL");
        dto.setAmount(new BigDecimal("100.00"));
        String originalJson = objectMapper.writeValueAsString(dto);

        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String bodySha256 = DigestUtil.sha256Hex(originalJson);

        String canonical = SignCanonicalUtil.buildRequestCanonicalString(
                "POST",
                "/sample/web/security/sign/orders",
                timestamp,
                nonce,
                Map.of(),
                bodySha256
        );
        String signature = signer.sign(canonical, SECRET_KEY);

        // 篡改请求体内容
        String tamperedJson = originalJson.replace("100.00", "0.01");

        mockMvc.perform(post("/sample/web/security/sign/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tamperedJson)
                        .header(ApiSignAspect.HEADER_TIMESTAMP, timestamp)
                        .header(ApiSignAspect.HEADER_NONCE, nonce)
                        .header(ApiSignAspect.HEADER_SIGNATURE, signature))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("数据验签失败"));
    }

    @Test
    @DisplayName("5. 缺少签名必要请求头被拦截 (400)")
    void shouldRejectMissingSignHeaders() throws Exception {
        OrderSignDTO dto = new OrderSignDTO();
        dto.setOrderNo("ORD_NOHDR");
        dto.setAmount(new BigDecimal("10.00"));
        String jsonBody = objectMapper.writeValueAsString(dto);

        mockMvc.perform(post("/sample/web/security/sign/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("缺少签名必要请求头 (X-Timestamp, X-Nonce, X-Signature)"));
    }

    @Test
    @DisplayName("6. GET Query 参数加签验签通过")
    void shouldVerifyQueryParametersSuccessfully() throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String bodySha256 = DigestUtil.sha256Hex("");

        String canonical = SignCanonicalUtil.buildRequestCanonicalString(
                "GET",
                "/sample/web/security/sign/query",
                timestamp,
                nonce,
                Map.of("code", new String[]{"VIP001"}),
                bodySha256
        );
        String signature = signer.sign(canonical, SECRET_KEY);

        mockMvc.perform(get("/sample/web/security/sign/query")
                        .param("code", "VIP001")
                        .header(ApiSignAspect.HEADER_TIMESTAMP, timestamp)
                        .header(ApiSignAspect.HEADER_NONCE, nonce)
                        .header(ApiSignAspect.HEADER_SIGNATURE, signature))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.merchantCode").value("VIP001"))
                .andExpect(header().exists(ApiSignAspect.HEADER_SIGNATURE));
    }

    @Test
    @DisplayName("7. 仅开启入站验签（signResponse=false）出站不回写签名头")
    void shouldVerifyInboundOnlyWithoutOutboundSignature() throws Exception {
        OrderSignDTO dto = new OrderSignDTO();
        dto.setOrderNo("ORD_INBOUND_ONLY");
        dto.setAmount(new BigDecimal("1.00"));
        String jsonBody = objectMapper.writeValueAsString(dto);

        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String bodySha256 = DigestUtil.sha256Hex(jsonBody);

        String canonical = SignCanonicalUtil.buildRequestCanonicalString(
                "POST",
                "/sample/web/security/sign/inbound",
                timestamp,
                nonce,
                Map.of(),
                bodySha256
        );
        String signature = signer.sign(canonical, SECRET_KEY);

        mockMvc.perform(post("/sample/web/security/sign/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody)
                        .header(ApiSignAspect.HEADER_TIMESTAMP, timestamp)
                        .header(ApiSignAspect.HEADER_NONCE, nonce)
                        .header(ApiSignAspect.HEADER_SIGNATURE, signature))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderNo").value("ORD_INBOUND_ONLY"))
                .andExpect(header().doesNotExist(ApiSignAspect.HEADER_SIGNATURE));
    }

    @Test
    @DisplayName("8. 仅开启出站加签（verifyRequest=false）入站免签，出站强制加签")
    void shouldSignOutboundOnlyWithoutInboundVerification() throws Exception {
        mockMvc.perform(get("/sample/web/security/sign/outbound"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.reportTitle").value("2026年第四季度结算报表"))
                .andExpect(header().exists(ApiSignAspect.HEADER_SIGNATURE))
                .andExpect(header().exists(ApiSignAspect.HEADER_TIMESTAMP))
                .andExpect(header().exists(ApiSignAspect.HEADER_NONCE));
    }

    @Test
    @DisplayName("9. 辅助接口：POST /sign/prepare-headers 动态计算签名头与 cURL 体验命令")
    void shouldPrepareSignHeadersSuccessfully() throws Exception {
        PrepareSignHeadersDTO dto = PrepareSignHeadersDTO.builder()
                .method("POST")
                .uri("/sample/web/security/sign/orders")
                .body("{\"orderNo\":\"ORD_PREPARE_001\",\"amount\":66.66}")
                .algorithm("HMAC")
                .secretOrPrivateKey(SECRET_KEY)
                .appId("test-app")
                .build();

        MvcResult result = mockMvc.perform(post("/sample/web/security/sign/prepare-headers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.X-Timestamp").isNotEmpty())
                .andExpect(jsonPath("$.data.X-Nonce").isNotEmpty())
                .andExpect(jsonPath("$.data.X-Signature").isNotEmpty())
                .andExpect(jsonPath("$.data.curlSample").isNotEmpty())
                .andReturn();

        // 提取计算出的签名头，全真请求受保护端点
        String respJson = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> respMap = (Map<String, Object>) objectMapper.readValue(respJson, Map.class).get("data");

        String timestamp = (String) respMap.get("X-Timestamp");
        String nonce = (String) respMap.get("X-Nonce");
        String signature = (String) respMap.get("X-Signature");

        mockMvc.perform(post("/sample/web/security/sign/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dto.getBody())
                        .header("X-Timestamp", timestamp)
                        .header("X-Nonce", nonce)
                        .header("X-Signature", signature))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderNo").value("ORD_PREPARE_001"));
    }

    @Test
    @DisplayName("10. 基础端点直接响应成功：CORS 预留端点探测")
    void shouldAccessCorsPingDirectly() throws Exception {
        mockMvc.perform(get("/sample/web/security/cors/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("CORS 动态跨域凭证校验成功"));
    }

    @Test
    @DisplayName("11. ContentCachingFilter 请求体超限防爆屏障：写出 200 + Result 413 JSON 与 X-Trace-Id 响应头")
    void shouldReturn413WhenContentLengthExceedsLimit() throws Exception {
        // 请求体大小 3MB，超出默认 2MB 上限
        byte[] largeBody = new byte[3 * 1024 * 1024];
        java.util.Arrays.fill(largeBody, (byte) ' ');

        mockMvc.perform(post("/sample/web/security/xss/article")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(largeBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(413))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("请求体大小超过允许的最大限制")))
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    @DisplayName("12. 单应用多算法动态共存：默认 HMAC 下通过 @ApiSign(algorithm) 动态路由 RSA 与 SM2 双向加验签")
    void shouldSupportMultiAlgorithmCoexistenceViaAnnotationRouting() throws Exception {
        OrderSignDTO dto = new OrderSignDTO();
        dto.setOrderNo("ORD-MULTI-20261114");
        dto.setAmount(new BigDecimal("199.00"));
        String bodyJson = objectMapper.writeValueAsString(dto);
        String bodySha256 = DigestUtil.sha256Hex(bodyJson);

        // 1. 验证 RSA 注解动态路由双向加验签
        assertAsymmetricBidirectionalSign(
                "/sample/web/security/sign/rsa",
                bodyJson,
                bodySha256,
                "RSA",
                new RsaSigner(),
                RSA_PRIVATE_KEY,
                RSA_PUBLIC_KEY
        );

        // 2. 验证 SM2 注解动态路由双向加验签
        assertAsymmetricBidirectionalSign(
                "/sample/web/security/sign/sm2",
                bodyJson,
                bodySha256,
                "SM2",
                new Sm2Signer(),
                SM2_KEY_PAIR.privateKeyHex(),
                SM2_KEY_PAIR.publicKeyHex()
        );
    }

    private void assertAsymmetricBidirectionalSign(String uri,
                                                   String bodyJson,
                                                   String bodySha256,
                                                   String expectedAlgorithm,
                                                   ApiSigner signer,
                                                   String privateKey,
                                                   String publicKey) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String canonical = SignCanonicalUtil.buildRequestCanonicalString(
                "POST", uri, timestamp, nonce, Map.of(), bodySha256);
        String signature = signer.sign(canonical, privateKey);

        MvcResult result = mockMvc.perform(post(uri)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson)
                        .header(ApiSignAspect.HEADER_TIMESTAMP, timestamp)
                        .header(ApiSignAspect.HEADER_NONCE, nonce)
                        .header(ApiSignAspect.HEADER_SIGNATURE, signature)
                        .header(ApiSignAspect.HEADER_APP_ID, "test-app"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.algorithm").value(expectedAlgorithm))
                .andExpect(header().exists(ApiSignAspect.HEADER_SIGNATURE))
                .andReturn();

        String respTimestamp = result.getResponse().getHeader(ApiSignAspect.HEADER_TIMESTAMP);
        String respNonce = result.getResponse().getHeader(ApiSignAspect.HEADER_NONCE);
        String respSign = result.getResponse().getHeader(ApiSignAspect.HEADER_SIGNATURE);
        String respBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String respCanonical = SignCanonicalUtil.buildResponseCanonicalString(
                respTimestamp, respNonce, DigestUtil.sha256Hex(respBody));
        assertThat(signer.verify(respCanonical, respSign, publicKey)).isTrue();
    }

    @Test
    @DisplayName("13. prepare-headers 指定 SM2 算法生成签名头并成功调用 /sign/sm2")
    void shouldPrepareSm2HeadersAndPassVerification() throws Exception {
        OrderSignDTO orderDto = new OrderSignDTO();
        orderDto.setOrderNo("ORD-SM2-PREP-001");
        orderDto.setAmount(new BigDecimal("88.88"));
        String orderBodyJson = objectMapper.writeValueAsString(orderDto);

        PrepareSignHeadersDTO prepareDto = new PrepareSignHeadersDTO();
        prepareDto.setMethod("POST");
        prepareDto.setUri("/sample/web/security/sign/sm2");
        prepareDto.setBody(orderBodyJson);
        prepareDto.setAlgorithm("SM2");
        prepareDto.setSecretOrPrivateKey(SM2_KEY_PAIR.privateKeyHex());
        prepareDto.setAppId("test-app");

        MvcResult prepResult = mockMvc.perform(post("/sample/web/security/sign/prepare-headers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(prepareDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        Map<?, ?> prepMap = objectMapper.readValue(prepResult.getResponse().getContentAsString(StandardCharsets.UTF_8), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> prepData = (Map<String, Object>) prepMap.get("data");

        mockMvc.perform(post("/sample/web/security/sign/sm2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBodyJson)
                        .header(ApiSignAspect.HEADER_TIMESTAMP, String.valueOf(prepData.get(ApiSignAspect.HEADER_TIMESTAMP)))
                        .header(ApiSignAspect.HEADER_NONCE, String.valueOf(prepData.get(ApiSignAspect.HEADER_NONCE)))
                        .header(ApiSignAspect.HEADER_SIGNATURE, String.valueOf(prepData.get(ApiSignAspect.HEADER_SIGNATURE)))
                        .header(ApiSignAspect.HEADER_APP_ID, String.valueOf(prepData.get(ApiSignAspect.HEADER_APP_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.algorithm").value("SM2"));
    }
}
