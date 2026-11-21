package io.github.atengk.boot.sms.callback;

import io.github.atengk.boot.core.util.HmacUtil;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsCallbackProperties;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工单 #1148 Webhook 回调恒定时间令牌、禁用 Query 明文令牌、CIDR 白名单与签名验签安全测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("工单 #1148 Webhook 回调安全验签测试")
class SmsCallbackSecurityTest {

    private static final String VALID_PAYLOAD = "[{\"phone_number\":\"13800060001\",\"err_code\":\"DELIVRD\",\"success\":true}]";

    @Test
    @DisplayName("默认禁止 URL Query 参数明文传令牌，显式开启 allowQueryToken 后放行")
    void shouldRejectQueryTokenByDefaultAndAllowWhenExplicitlyEnabled() throws Exception {
        SmsChannelRegistry registry = new SmsChannelRegistry();
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        // 1. 默认 allowQueryToken=false
        SmsCallbackProperties defaultProps = new SmsCallbackProperties();
        defaultProps.setToken("CALLBACK_SECRET_123");
        MockMvc defaultMvc = MockMvcBuilders.standaloneSetup(
                new SmsCallbackController(registry, publisher, defaultProps)).build();

        defaultMvc.perform(post("/ateng-sms/callback/mock?token=CALLBACK_SECRET_123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        // Header 传正确令牌放行
        defaultMvc.perform(post("/ateng-sms/callback/mock")
                        .header("X-Callback-Token", "CALLBACK_SECRET_123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 2. 显式开启 allowQueryToken=true
        SmsCallbackProperties allowQueryProps = new SmsCallbackProperties();
        allowQueryProps.setToken("CALLBACK_SECRET_123");
        allowQueryProps.setAllowQueryToken(true);
        MockMvc allowQueryMvc = MockMvcBuilders.standaloneSetup(
                new SmsCallbackController(registry, publisher, allowQueryProps)).build();

        allowQueryMvc.perform(post("/ateng-sms/callback/mock?token=CALLBACK_SECRET_123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("CIDR IP 白名单拦截外部 IP 返回 403，放行白名单网段")
    void shouldEnforceCidrWhitelistReturning403ForUntrustedIp() throws Exception {
        SmsChannelRegistry registry = new SmsChannelRegistry();
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        SmsProperties props = new SmsProperties();
        props.getCallback().setAllowedCidrs(List.of("10.20.0.0/16", "192.168.100.5"));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new SmsCallbackController(registry, publisher, null, props)).build();

        // 外部 IP -> 403 Forbidden
        mvc.perform(post("/ateng-sms/callback/mock")
                        .with(req -> {
                            req.setRemoteAddr("203.0.113.88");
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        // 白名单网段 IP -> 200 OK
        mvc.perform(post("/ateng-sms/callback/mock")
                        .with(req -> {
                            req.setRemoteAddr("10.20.3.4");
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("强制签名验签与自定义 SmsCallbackVerifier SPI 拦截伪造签名")
    void shouldEnforceHmacSignatureAndCustomCallbackVerifier() throws Exception {
        SmsChannelRegistry registry = new SmsChannelRegistry();
        registry.register(SmsChannel.builder()
                .name("signed-ch")
                .supplier(SmsSupplier.MOCK)
                .accessKeySecret("HMAC_SK_999")
                .build());
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        // 1. 内置默认 HMAC-SHA256 签名验签
        SmsProperties props = new SmsProperties();
        props.getCallback().setSignatureRequired(true);

        MockMvc defaultSigMvc = MockMvcBuilders.standaloneSetup(
                new SmsCallbackController(registry, publisher, null, props)).build();

        defaultSigMvc.perform(post("/ateng-sms/callback/signed-ch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        defaultSigMvc.perform(post("/ateng-sms/callback/signed-ch")
                        .header("X-Callback-Signature", "deadbeef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isUnauthorized());

        String validSig = HmacUtil.hmacSha256Hex(VALID_PAYLOAD, "HMAC_SK_999");
        defaultSigMvc.perform(post("/ateng-sms/callback/signed-ch")
                        .header("X-Callback-Signature", validSig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 2. 自定义厂商签名 SPI 扩展点（同时保留 CIDR + Token 防卫）
        SmsProperties customProps = new SmsProperties();
        customProps.getCallback().setAllowedCidrs(List.of("127.0.0.1"));
        customProps.getCallback().setSignatureRequired(true);

        SmsCallbackVerifier vendorVerifier = ctx -> {
            String vendorSig = ctx.getHeaders().getFirst("X-Vendor-Signature");
            if (!"VENDOR_OK".equals(vendorSig)) {
                return SmsCallbackVerificationResult.unauthorized("Invalid vendor signature");
            }
            return SmsCallbackVerificationResult.ok();
        };

        MockMvc customMvc = MockMvcBuilders.standaloneSetup(
                new SmsCallbackController(registry, publisher, null, customProps, vendorVerifier)).build();

        customMvc.perform(post("/ateng-sms/callback/signed-ch")
                        .with(req -> {
                            req.setRemoteAddr("127.0.0.1");
                            return req;
                        })
                        .header("X-Vendor-Signature", "BAD_SIG")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid vendor signature"));

        customMvc.perform(post("/ateng-sms/callback/signed-ch")
                        .with(req -> {
                            req.setRemoteAddr("127.0.0.1");
                            return req;
                        })
                        .header("X-Vendor-Signature", "VENDOR_OK")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 3. 畸形百分比编码 Query String 快速失败返回 400 而非 500（通过 raw queryString 注入避免 UriComponentsBuilder 预转义）
        customMvc.perform(post("/ateng-sms/callback/signed-ch")
                        .with(req -> {
                            req.setRemoteAddr("127.0.0.1");
                            req.setQueryString("token=%ZZ");
                            return req;
                        })
                        .header("X-Vendor-Signature", "VENDOR_OK")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
