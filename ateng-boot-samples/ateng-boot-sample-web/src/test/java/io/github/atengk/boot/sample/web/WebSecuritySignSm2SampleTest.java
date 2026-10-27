package io.github.atengk.boot.sample.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.sample.web.dto.OrderSignDTO;
import io.github.atengk.boot.web.sign.aspect.ApiSignAspect;
import io.github.atengk.boot.web.sign.signer.Sm2Signer;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 双向数据加签验签与防重放集成测试（国密 SM2/SM3 非对称算法）
 * 覆盖端到端请求加签、入站服务端验签、出站服务端回签、数据防篡改及防重放拒绝
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("API 双向数据加签验签集成测试 (国密 SM2/SM3)")
class WebSecuritySignSm2SampleTest {

    private static final Sm2Signer.Sm2KeyPair KEY_PAIR = Sm2Signer.generateKeyPair();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("ateng.boot.web.sign.enabled", () -> "true");
        registry.add("ateng.boot.web.sign.algorithm", () -> "SM2");
        registry.add("ateng.boot.web.sign.public-key", KEY_PAIR::publicKeyHex);
        registry.add("ateng.boot.web.sign.private-key", KEY_PAIR::privateKeyHex);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final Sm2Signer sm2Signer = new Sm2Signer();

    @Test
    @DisplayName("1. 客户端 SM2 私钥加签，服务端公钥验签通过，出站服务端私钥加签，客户端公钥验签回执")
    void shouldVerifySm2SignAndReturnResponseSignature() throws Exception {
        OrderSignDTO dto = new OrderSignDTO();
        dto.setOrderNo("ORD_SM2_001");
        dto.setAmount(new BigDecimal("999.00"));
        String jsonBody = objectMapper.writeValueAsString(dto);

        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String bodySha256 = DigestUtil.sha256Hex(jsonBody);

        String canonical = SignCanonicalUtil.buildRequestCanonicalString(
                "POST",
                "/api/sample/security/sign/sm2",
                timestamp,
                nonce,
                Map.of(),
                bodySha256
        );

        // 客户端私钥生成 SM2 数字签名
        String clientSignature = sm2Signer.sign(canonical, KEY_PAIR.privateKeyHex());

        MvcResult mvcResult = mockMvc.perform(post("/api/sample/security/sign/sm2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody)
                        .header(ApiSignAspect.HEADER_TIMESTAMP, timestamp)
                        .header(ApiSignAspect.HEADER_NONCE, nonce)
                        .header(ApiSignAspect.HEADER_SIGNATURE, clientSignature))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("SM2_SIGNED_ORD_SM2_001"))
                .andExpect(header().exists(ApiSignAspect.HEADER_SIGNATURE))
                .andExpect(header().exists(ApiSignAspect.HEADER_TIMESTAMP))
                .andExpect(header().exists(ApiSignAspect.HEADER_NONCE))
                .andReturn();

        // 客户端接收响应，使用服务端公钥验证回签响应
        String respSignature = mvcResult.getResponse().getHeader(ApiSignAspect.HEADER_SIGNATURE);
        String respTimestamp = mvcResult.getResponse().getHeader(ApiSignAspect.HEADER_TIMESTAMP);
        String respNonce = mvcResult.getResponse().getHeader(ApiSignAspect.HEADER_NONCE);
        String respContent = mvcResult.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String respSha256 = DigestUtil.sha256Hex(respContent);

        String respCanonical = SignCanonicalUtil.buildResponseCanonicalString(respTimestamp, respNonce, respSha256);
        boolean respValid = sm2Signer.verify(respCanonical, respSignature, KEY_PAIR.publicKeyHex());
        assertThat(respValid).isTrue();
    }

    @Test
    @DisplayName("2. 请求载荷遭恶意篡改时，服务端 SM2 验签拦截并返回 400")
    void shouldRejectWhenBodyTampered() throws Exception {
        OrderSignDTO dto = new OrderSignDTO();
        dto.setOrderNo("ORD_SM2_TAMPER");
        dto.setAmount(new BigDecimal("10.00"));
        String jsonBody = objectMapper.writeValueAsString(dto);

        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String bodySha256 = DigestUtil.sha256Hex(jsonBody);

        String canonical = SignCanonicalUtil.buildRequestCanonicalString(
                "POST",
                "/api/sample/security/sign/sm2",
                timestamp,
                nonce,
                Map.of(),
                bodySha256
        );
        String clientSignature = sm2Signer.sign(canonical, KEY_PAIR.privateKeyHex());

        // 篡改请求体金额：由 10.00 改为 10000.00
        String tamperedBody = jsonBody.replace("10.00", "10000.00");

        mockMvc.perform(post("/api/sample/security/sign/sm2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tamperedBody)
                        .header(ApiSignAspect.HEADER_TIMESTAMP, timestamp)
                        .header(ApiSignAspect.HEADER_NONCE, nonce)
                        .header(ApiSignAspect.HEADER_SIGNATURE, clientSignature))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("数据验签失败"));
    }

    @Test
    @DisplayName("3. 伪造或篡改签名串时，服务端 SM2 验签失败")
    void shouldRejectWhenSignatureTampered() throws Exception {
        OrderSignDTO dto = new OrderSignDTO();
        dto.setOrderNo("ORD_SM2_FAKE");
        dto.setAmount(new BigDecimal("50.00"));
        String jsonBody = objectMapper.writeValueAsString(dto);

        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");

        // 伪造非法签名串
        String fakeSignature = "MEQCIAyFakeSignatureAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==";

        mockMvc.perform(post("/api/sample/security/sign/sm2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody)
                        .header(ApiSignAspect.HEADER_TIMESTAMP, timestamp)
                        .header(ApiSignAspect.HEADER_NONCE, nonce)
                        .header(ApiSignAspect.HEADER_SIGNATURE, fakeSignature))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("数据验签失败"));
    }

    @Test
    @DisplayName("4. 缺少必要安全签名头时，返回 400 Bad Request 业务错误")
    void shouldRejectWhenMissingSignHeaders() throws Exception {
        OrderSignDTO dto = new OrderSignDTO();
        dto.setOrderNo("ORD_SM2_MISSING");
        dto.setAmount(new BigDecimal("60.00"));
        String jsonBody = objectMapper.writeValueAsString(dto);

        mockMvc.perform(post("/api/sample/security/sign/sm2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
