package io.github.atengk.boot.sample.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.sample.web.dto.OrderSignDTO;
import io.github.atengk.boot.sample.web.dto.PrepareSignHeadersDTO;
import io.github.atengk.boot.web.sign.aspect.ApiSignAspect;
import io.github.atengk.boot.web.sign.signer.HmacSha256Signer;
import io.github.atengk.boot.web.sign.util.SignCanonicalUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
@DisplayName("API 双向数据加签验签与防重放集成测试 (HMAC-SHA256)")
class WebSecurityAndSignControllerTest {

    private static final String SECRET_KEY = "TestSecretKey123";

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
}
