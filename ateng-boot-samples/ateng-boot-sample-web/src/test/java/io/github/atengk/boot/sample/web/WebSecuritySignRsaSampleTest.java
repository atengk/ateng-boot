package io.github.atengk.boot.sample.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.sample.web.dto.OrderSignDTO;
import io.github.atengk.boot.web.sign.aspect.ApiSignAspect;
import io.github.atengk.boot.web.sign.signer.RsaSigner;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 双向数据加签验签与防重放集成测试（RSA 非对称算法）
 *
 * @author Ateng
 * @since 2026-10-19
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("API 双向数据加签验签与防重放集成测试 (RSA 非对称)")
class WebSecuritySignRsaSampleTest {

    private static final String CLIENT_PRIVATE_KEY_PEM;
    private static final String CLIENT_PUBLIC_KEY_PEM;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            CLIENT_PRIVATE_KEY_PEM = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----";
            CLIENT_PUBLIC_KEY_PEM = "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----";
        } catch (Exception e) {
            throw new IllegalStateException("生成测试 RSA 密钥失败", e);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("ateng.boot.web.sign.enabled", () -> "true");
        registry.add("ateng.boot.web.sign.algorithm", () -> "RSA");
        registry.add("ateng.boot.web.sign.public-key", () -> CLIENT_PUBLIC_KEY_PEM);
        registry.add("ateng.boot.web.sign.private-key", () -> CLIENT_PRIVATE_KEY_PEM);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final RsaSigner rsaSigner = new RsaSigner();

    @Test
    @DisplayName("1. 客户端私钥加签，服务端公钥验签通过，出站服务端私钥加签，客户端公钥验签回执")
    void shouldVerifyRsaSignAndReturnResponseSignature() throws Exception {
        OrderSignDTO dto = new OrderSignDTO();
        dto.setOrderNo("ORD_RSA_001");
        dto.setAmount(new BigDecimal("188.88"));
        String jsonBody = objectMapper.writeValueAsString(dto);

        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String bodySha256 = DigestUtil.sha256Hex(jsonBody);

        String canonical = SignCanonicalUtil.buildRequestCanonicalString(
                "POST",
                "/api/sample/sign/orders",
                timestamp,
                nonce,
                Map.of(),
                bodySha256
        );

        // 客户端使用自己的私钥对请求内容加签
        String clientSignature = rsaSigner.sign(canonical, CLIENT_PRIVATE_KEY_PEM);

        MvcResult result = mockMvc.perform(post("/api/sample/sign/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody)
                        .header(ApiSignAspect.HEADER_TIMESTAMP, timestamp)
                        .header(ApiSignAspect.HEADER_NONCE, nonce)
                        .header(ApiSignAspect.HEADER_SIGNATURE, clientSignature))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("ORD_SIGNED_ORD_RSA_001"))
                .andExpect(header().exists(ApiSignAspect.HEADER_SIGNATURE))
                .andReturn();

        // 验证服务端出站加签，客户端使用公钥验证响应
        String serverSignature = result.getResponse().getHeader(ApiSignAspect.HEADER_SIGNATURE);
        String respContent = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String respBodySha256 = DigestUtil.sha256Hex(respContent);
        String respCanonical = SignCanonicalUtil.buildResponseCanonicalString(timestamp, nonce, respBodySha256);
        assertThat(rsaSigner.verify(respCanonical, serverSignature, CLIENT_PUBLIC_KEY_PEM)).isTrue();
    }

    @Test
    @DisplayName("2. RSA 签名篡改阻断验证")
    void shouldRejectTamperedRsaRequest() throws Exception {
        OrderSignDTO dto = new OrderSignDTO();
        dto.setOrderNo("ORD_RSA_TAMPER");
        dto.setAmount(new BigDecimal("500.00"));
        String jsonBody = objectMapper.writeValueAsString(dto);

        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String bodySha256 = DigestUtil.sha256Hex(jsonBody);

        String canonical = SignCanonicalUtil.buildRequestCanonicalString(
                "POST",
                "/api/sample/sign/orders",
                timestamp,
                nonce,
                Map.of(),
                bodySha256
        );
        String signature = rsaSigner.sign(canonical, CLIENT_PRIVATE_KEY_PEM);

        // 篡改金额为 0.01 发送
        String tampered = jsonBody.replace("500.00", "0.01");

        mockMvc.perform(post("/api/sample/sign/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tampered)
                        .header(ApiSignAspect.HEADER_TIMESTAMP, timestamp)
                        .header(ApiSignAspect.HEADER_NONCE, nonce)
                        .header(ApiSignAspect.HEADER_SIGNATURE, signature))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("数据验签失败"));
    }
}
