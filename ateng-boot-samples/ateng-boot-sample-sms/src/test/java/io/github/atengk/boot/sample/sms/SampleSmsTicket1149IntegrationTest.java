package io.github.atengk.boot.sample.sms;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sample.sms.dto.DelaySmsRequest;
import io.github.atengk.boot.sample.sms.dto.PlainOrIntlSmsRequest;
import io.github.atengk.boot.sample.sms.dto.SendSmsRequest;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工单 #1149 Sample SmsDelayAndQueryController 端到端 MockMvc 验证
 *
 * @author Ateng
 * @since 2026-11-21
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("工单 #1149 Sample 延迟调度、纯文本/国际、主动查单及沙箱控制器验证")
class SampleSmsTicket1149IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MockSmsProvider mockSmsProvider;

    @BeforeEach
    void setUp() {
        mockSmsProvider.clear();
    }

    @Test
    @DisplayName("延迟发信预约、未到点取消、定时触发与主动状态回执/余额探查全闭环")
    @SuppressWarnings("unchecked")
    void shouldScheduleCancelDeliverAndQueryStatusAndBalance() throws Exception {
        // 1. 预约 10 秒延迟发信并立即撤销 -> 0 条真实发送
        DelaySmsRequest cancelReq = DelaySmsRequest.builder()
                .phone("13800090001")
                .templateId("TPL_ORDER_REMIND")
                .params(Map.of("orderNo", "ORD-CANCEL-1"))
                .bizId("SAMPLE-DELAY-CANCEL-1")
                .delaySeconds(10)
                .build();

        MvcResult cancelScheduleRes = mockMvc.perform(post("/sample/sms/delay/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(cancelReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskId").isNotEmpty())
                .andReturn();

        Map<String, Object> scheduleJson = JsonUtil.parseObject(cancelScheduleRes.getResponse().getContentAsString(), Map.class);
        Map<String, Object> scheduleData = (Map<String, Object>) scheduleJson.get("data");
        String cancelledTaskId = String.valueOf(scheduleData.get("taskId"));

        mockMvc.perform(post("/sample/sms/delay/cancel").param("taskId", cancelledTaskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 2. 预约 120ms 定时发信，等待到期发送后再主动查询送达回执（校验手机号脱敏 VO）
        DelaySmsRequest deliverReq = DelaySmsRequest.builder()
                .phone("13800090002")
                .templateId("TPL_ORDER_REMIND")
                .params(Map.of("orderNo", "ORD-AT-2"))
                .bizId("SAMPLE-DELAY-AT-2")
                .triggerAt(Instant.now().plusMillis(120))
                .build();

        mockMvc.perform(post("/sample/sms/delay/at")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(deliverReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskId").isNotEmpty());

        long deadline = System.currentTimeMillis() + 3000L;
        while (mockSmsProvider.getSentCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(30L);
        }
        assertThat(mockSmsProvider.getSentCount()).isEqualTo(1);

        // 3. 主动查询送达回执 -> 返回 DELIVERED 且 maskedPhone 为脱敏号码，严禁返回明文
        mockMvc.perform(get("/sample/sms/query/status")
                        .param("channel", "mock")
                        .param("phone", "13800090002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.supported").value(true))
                .andExpect(jsonPath("$.data.deliveryStatus").value("DELIVERED"))
                .andExpect(jsonPath("$.data.maskedPhone").value("138****0002"));

        // 4. 账户余额只读探查
        mockMvc.perform(get("/sample/sms/query/balance").param("channel", "mock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.supported").value(true))
                .andExpect(jsonPath("$.data.balance").value(1000.0))
                .andExpect(jsonPath("$.data.remainingCount").value(10000));
    }

    @Test
    @DisplayName("无模板纯文本直发、E.164 国际区号发送与非生产沙箱零外发模拟演练")
    void shouldSupportPlainTextInternationalAndSandboxDryRun() throws Exception {
        // 1. 无模板纯文本 + 香港 E.164 国际号发送
        PlainOrIntlSmsRequest plainIntlReq = PlainOrIntlSmsRequest.builder()
                .phone("+85291234567")
                .content("【跨境商城】您的国际订单 HK-8801 已清关放行")
                .channel("mock")
                .bizId("SAMPLE-INTL-1")
                .build();

        mockMvc.perform(post("/sample/sms/delay/plain-intl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(plainIntlReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        assertThat(mockSmsProvider.getSentCount()).isEqualTo(1);
        assertThat(mockSmsProvider.getLastRequest().getPhone()).isEqualTo("+85291234567");

        // 2. 沙箱零外发模拟演练（不会新增 MockSmsProvider 真实发送记录）
        SendSmsRequest sandboxReq = SendSmsRequest.builder()
                .phone("13800090088")
                .templateId("TPL_SANDBOX")
                .params(Map.of("code", "654321"))
                .bizId("SAMPLE-SB-DRY-1")
                .build();

        mockMvc.perform(post("/sample/sms/delay/sandbox-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(sandboxReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.deliveryStatus").value("MOCKED"));

        assertThat(mockSmsProvider.getSentCount()).isEqualTo(1);
    }
}
