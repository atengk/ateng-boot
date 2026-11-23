package io.github.atengk.boot.sample.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.sms.dto.DynamicChannelRegisterRequest;
import io.github.atengk.boot.sample.sms.dto.SendSmsRequest;
import io.github.atengk.boot.sms.model.SmsQuotaResetCycle;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工单 #1148 Sample 控制器（动态驱逐、渠道配额查询/重置、测试沙箱演练、通知频控）MockMvc 集成测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("工单 #1148 Sample 控制器端到端验证")
class SampleSmsTicket1148IntegrationTest {

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @DisplayName("渠道配额查询/重置与动态驱逐 MockMvc 验证")
    class ChannelQuotaAndEvictTests {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private MockSmsProvider mockSmsProvider;

        @BeforeEach
        void setUp() {
            mockSmsProvider.clear();
        }

        @Test
        @DisplayName("注册限额渠道 -> 超限容灾切换 -> 配额查询/重置 -> 热驱逐端点全链路")
        void testChannelQuotaFailoverResetAndEvictEndpoints() throws Exception {
            // 1. 注册带 TOTAL 配额上限 (maxQuota=1) 且备用指向 mock 的动态渠道
            DynamicChannelRegisterRequest regReq = DynamicChannelRegisterRequest.builder()
                    .name("quota-demo-ch")
                    .supplier(SmsSupplier.MOCK)
                    .signName("配额演练签名")
                    .failoverChannel("mock")
                    .maxQuota(1L)
                    .quotaResetCycle(SmsQuotaResetCycle.TOTAL)
                    .build();

            mockMvc.perform(post("/sample/sms/channel/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(regReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 2. 首次发信命中 quota-demo-ch（消耗 1/1）
            SendSmsRequest sendReq1 = SendSmsRequest.builder()
                    .phone("13800138111")
                    .templateId("TPL_QUOTA")
                    .channel("quota-demo-ch")
                    .bizId("QUOTA_BIZ_1")
                    .params(Map.of("code", "112233"))
                    .build();

            mockMvc.perform(post("/sample/sms/send/single")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sendReq1)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.channel").value("quota-demo-ch"));

            // 3. 查询配额状态 -> usedQuota=1, remainingQuota=0
            mockMvc.perform(get("/sample/sms/channel/quota").param("channelName", "quota-demo-ch"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.channelName").value("quota-demo-ch"))
                    .andExpect(jsonPath("$.data.maxQuota").value(1))
                    .andExpect(jsonPath("$.data.usedQuota").value(1))
                    .andExpect(jsonPath("$.data.remainingQuota").value(0));

            // 4. 再次发信触限 -> 自动 Failover 切换至 mock
            SendSmsRequest sendReq2 = SendSmsRequest.builder()
                    .phone("13800138111")
                    .templateId("TPL_QUOTA")
                    .channel("quota-demo-ch")
                    .bizId("QUOTA_BIZ_2")
                    .params(Map.of("code", "112233"))
                    .build();
            mockMvc.perform(post("/sample/sms/send/single")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sendReq2)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.channel").value("mock"));

            // 5. 重置配额后恢复可用
            mockMvc.perform(post("/sample/sms/channel/quota/reset").param("channelName", "quota-demo-ch"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(get("/sample/sms/channel/quota").param("channelName", "quota-demo-ch"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.usedQuota").value(0))
                    .andExpect(jsonPath("$.data.remainingQuota").value(1));

            // 6. 触发动态驱逐端点与注销清理
            mockMvc.perform(post("/sample/sms/channel/evict").param("channelName", "quota-demo-ch"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(post("/sample/sms/channel/unregister").param("channelName", "quota-demo-ch"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "ateng.boot.sms.sandbox.enabled=true",
            "ateng.boot.sms.sandbox.allowed-phones[0]=13800138*",
            "ateng.boot.sms.sandbox.redirect-to=13999998888",
            "ateng.boot.sms.guard.notify-rate-limit-enabled=true",
            "ateng.boot.sms.callback.token=SAMPLE_CB_SECRET"
    })
    @AutoConfigureMockMvc
    @DisplayName("沙箱重定向、通知频控与回调验签 MockMvc 端到端验证")
    class SandboxNotifyAndCallbackSecurityTests {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private MockSmsProvider mockSmsProvider;

        @BeforeEach
        void setUp() {
            mockSmsProvider.clear();
        }

        @Test
        @DisplayName("沙箱重定向与白名单放行、通知频控与 skipRateLimit、回调 Query Token 拦截")
        void testSandboxRedirectNotifyRateLimitAndCallbackToken() throws Exception {
            // 1. 沙箱：白名单号码穿透
            SendSmsRequest allowedReq = SendSmsRequest.builder()
                    .phone("13800138001")
                    .templateId("TPL_SANDBOX")
                    .build();
            mockMvc.perform(post("/sample/sms/guard/sandbox")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(allowedReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
            assertEquals("13800138001", mockSmsProvider.getLastRequest().getPhone());

            // 2. 沙箱：非白名单号码重定向至 13999998888
            SendSmsRequest nonAllowedReq = SendSmsRequest.builder()
                    .phone("13700001111")
                    .templateId("TPL_SANDBOX")
                    .build();
            mockMvc.perform(post("/sample/sms/guard/sandbox")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(nonAllowedReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
            assertEquals("13999998888", mockSmsProvider.getLastRequest().getPhone());

            // 3. 通知频控隔离与 skipRateLimit 豁免
            SendSmsRequest notifyReq1 = SendSmsRequest.builder()
                    .phone("13800138002")
                    .templateId("TPL_NOTIFY")
                    .bizId("NOTIFY_BIZ_1")
                    .build();
            mockMvc.perform(post("/sample/sms/guard/notify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(notifyReq1)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            SendSmsRequest notifyReq2 = SendSmsRequest.builder()
                    .phone("13800138002")
                    .templateId("TPL_NOTIFY")
                    .bizId("NOTIFY_BIZ_2")
                    .build();
            mockMvc.perform(post("/sample/sms/guard/notify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(notifyReq2)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(5802));

            SendSmsRequest skipReq = SendSmsRequest.builder()
                    .phone("13800138002")
                    .templateId("TPL_NOTIFY")
                    .bizId("NOTIFY_BIZ_SKIP")
                    .skipRateLimit(true)
                    .build();
            mockMvc.perform(post("/sample/sms/guard/notify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(skipReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 4. 回调安全：默认拒绝 Query Token (?token=...)，接受 Header X-Callback-Token
            String payload = "[{\"phone_number\":\"13800088888\",\"err_code\":\"DELIVRD\",\"success\":true}]";
            mockMvc.perform(post("/ateng-sms/callback/mock?token=SAMPLE_CB_SECRET")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(post("/ateng-sms/callback/mock")
                            .header("X-Callback-Token", "SAMPLE_CB_SECRET")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }
    }
}
