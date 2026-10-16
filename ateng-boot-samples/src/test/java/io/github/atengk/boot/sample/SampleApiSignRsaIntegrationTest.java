package io.github.atengk.boot.sample;

import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.web.sign.aspect.ApiSignAspect;
import io.github.atengk.boot.web.sign.signer.RsaSigner;
import io.github.atengk.boot.web.sign.util.SignCanonicalUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 样例工程 API 非对称 RSA 数据加签验签端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("样例工程 API 非对称 RSA 数据加签验签端到端集成测试")
class SampleApiSignRsaIntegrationTest {

    private static final String privateKeyBase64;
    private static final String publicKeyBase64;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
            publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("ateng.boot.web.sign.enabled", () -> "true");
        registry.add("ateng.boot.web.sign.algorithm", () -> "RSA");
        registry.add("ateng.boot.web.sign.public-key", () -> publicKeyBase64);
        registry.add("ateng.boot.web.sign.private-key", () -> privateKeyBase64);
    }

    @Autowired
    private MockMvc mockMvc;

    private final RsaSigner rsaSigner = new RsaSigner();

    @Test
    @DisplayName("端到端验证：RSA 私钥加签、服务端公钥验签与出站私钥加签全链路")
    void shouldVerifyWithRsaPublicKeyAndSignWithPrivateKey() throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String queryCode = "RSA_TEST_888";
        String bodySha256 = DigestUtil.sha256Hex("");

        // 客户端用私钥对入站请求生成签名
        String canonical = SignCanonicalUtil.buildRequestCanonicalString(
                "GET",
                "/api/samples/sign/query",
                timestamp,
                nonce,
                Map.of("code", new String[]{queryCode}),
                bodySha256
        );
        String clientSignature = rsaSigner.sign(canonical, privateKeyBase64);

        // 发送请求，服务端用公钥验签通过
        MvcResult mvcResult = mockMvc.perform(get("/api/samples/sign/query")
                        .param("code", queryCode)
                        .header(ApiSignAspect.HEADER_TIMESTAMP, timestamp)
                        .header(ApiSignAspect.HEADER_NONCE, nonce)
                        .header(ApiSignAspect.HEADER_SIGNATURE, clientSignature))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("DATA_" + queryCode))
                .andExpect(header().exists(ApiSignAspect.HEADER_SIGNATURE))
                .andReturn();

        // 客户端用服务端公钥验证出站响应签名
        String serverSignature = mvcResult.getResponse().getHeader(ApiSignAspect.HEADER_SIGNATURE);
        String respContent = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String respBodySha256 = DigestUtil.sha256Hex(respContent);
        String respCanonical = SignCanonicalUtil.buildResponseCanonicalString(timestamp, nonce, respBodySha256);

        assertThat(rsaSigner.verify(respCanonical, serverSignature, publicKeyBase64)).isTrue();
    }
}
