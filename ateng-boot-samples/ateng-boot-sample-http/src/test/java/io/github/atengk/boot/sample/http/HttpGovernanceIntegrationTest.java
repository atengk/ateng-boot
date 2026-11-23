package io.github.atengk.boot.sample.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.http.dto.WechatNativeOrderRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HttpGovernanceController 端到端回环集成场景测试
 * 验证外部非信任域敏感头剥离、幂等指数退避重试与出站 cURL 脱敏日志
 *
 * @author Ateng
 * @since 2026-11-09
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureMockMvc
public class HttpGovernanceIntegrationTest {

    static int port = TestSocketUtils.findAvailableTcpPort();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> port);
        registry.add("sample.user-service.url", () -> "http://localhost:" + port + "/mock/provider");
        registry.add("sample.order-service.url", () -> "http://localhost:" + port + "/mock/provider");
        registry.add("sample.wechat-pay.url", () -> "http://localhost:" + port + "/mock/provider/wechat");
        registry.add("sample.flaky-service.url", () -> "http://localhost:" + port);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("场景 1: 调用外部微信支付接口，物理剥离内网私有敏感头并保留链路追踪")
    void testWechatPayContextStripped() throws Exception {
        WechatNativeOrderRequest request = WechatNativeOrderRequest.builder()
                .outTradeNo("PAY_WX_20261109_001")
                .description("Sample 会员充值订单")
                .totalFee(9900)
                .build();

        mockMvc.perform(post("/sample/http/governance/wechat/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.outTradeNo").value("PAY_WX_20261109_001"))
                .andExpect(jsonPath("$.data.codeUrl", containsString("weixin://wxpay/bizpayurl")))
                .andExpect(jsonPath("$.data.contextStripped").value(true))
                .andExpect(jsonPath("$.data.traceId", notNullValue()));
    }

    @Test
    @DisplayName("场景 2: 瞬态 503 故障触发指数退避重试与 Retry-After 自愈恢复")
    void testRetryFlaky() throws Exception {
        mockMvc.perform(get("/sample/http/governance/retry/flaky")
                        .param("failTimes", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("RECOVERED"))
                .andExpect(jsonPath("$.data.totalAttempts").value(3))
                .andExpect(jsonPath("$.data.message", containsString("自愈恢复")));
    }

    @Test
    @DisplayName("场景 3: 出站请求日志敏感凭据自动掩码脱敏")
    void testLoggingCurlDemo() throws Exception {
        mockMvc.perform(get("/sample/http/governance/logging/curl-demo")
                        .param("token", "sk-topsecret-bearer-token")
                        .param("password", "superSecret123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.maskedNotice", containsString("自动掩码脱敏")));
    }

    @Test
    @DisplayName("场景 4: 下游结构化异常响应反序列化与 HttpResultCode 因果映射")
    void testErrorTranslation() throws Exception {
        mockMvc.perform(get("/sample/http/governance/error-translate")
                        .param("code", "422"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.mappedResultCode").value(40001))
                .andExpect(jsonPath("$.data.httpStatusCode").value(422))
                .andExpect(jsonPath("$.data.remoteErrorCode").value("DOWNSTREAM_422"))
                .andExpect(jsonPath("$.data.responseBodyTranslated").value(true));

        mockMvc.perform(get("/sample/http/governance/error-translate")
                        .param("code", "503"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.mappedResultCode").value(50003))
                .andExpect(jsonPath("$.data.httpStatusCode").value(503))
                .andExpect(jsonPath("$.data.remoteErrorCode").value("DOWNSTREAM_503"))
                .andExpect(jsonPath("$.data.responseBodyTranslated").value(true));
    }

    @Test
    @DisplayName("场景 5: RestClientRegistry 多实例、mTLS 与实例级代理治理元数据探针")
    void testRegistryInspect() throws Exception {
        mockMvc.perform(get("/sample/http/governance/registry-inspect"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.registeredCount", greaterThanOrEqualTo(5)))
                .andExpect(jsonPath("$.data.registeredClientNames", hasItems(
                        "scoped-proxy-client", "mtls-service", "user-service", "order-service", "wechat-pay")))
                .andExpect(jsonPath("$.data.clients[?(@.clientName=='scoped-proxy-client')].proxyEnabled", hasItem(true)))
                .andExpect(jsonPath("$.data.clients[?(@.clientName=='scoped-proxy-client')].proxyEndpoint", hasItem("127.0.0.1:8888")))
                .andExpect(jsonPath("$.data.clients[?(@.clientName=='mtls-service')].sslEnabled", hasItem(true)))
                .andExpect(jsonPath("$.data.clients[?(@.clientName=='mtls-service')].keyStoreType", hasItem("PKCS12")));
    }
}
