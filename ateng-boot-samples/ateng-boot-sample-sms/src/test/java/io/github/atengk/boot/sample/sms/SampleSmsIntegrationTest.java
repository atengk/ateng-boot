package io.github.atengk.boot.sample.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.sms.dto.BatchSmsSendRequest;
import io.github.atengk.boot.sample.sms.dto.DynamicChannelRegisterRequest;
import io.github.atengk.boot.sample.sms.dto.SendSmsRequest;
import io.github.atengk.boot.sample.sms.dto.SendVerifyCodeRequest;
import io.github.atengk.boot.sample.sms.dto.VerifyCodeCheckRequest;
import io.github.atengk.boot.sample.sms.handler.SampleSmsFailureHandler;
import io.github.atengk.boot.sample.sms.listener.SampleSmsEventListener;
import io.github.atengk.boot.sample.sms.service.SmsScenarioService;
import io.github.atengk.boot.sms.event.SmsDeliveryReportEvent;
import io.github.atengk.boot.sms.event.SmsMoMessageEvent;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 短信多场景端到端真实集成自验测试
 *
 * @author Ateng
 * @since 2026-11-02
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("短信基础设施场景端到端集成测试")
class SampleSmsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockSmsProvider mockSmsProvider;

    @Autowired
    private SmsChannelRegistry channelRegistry;

    @Autowired
    private SampleSmsFailureHandler failureHandler;

    @Autowired
    private SampleSmsEventListener eventListener;

    @Autowired
    private SmsScenarioService smsScenarioService;

    @Autowired
    private Environment environment;

    @BeforeEach
    void setUp() {
        boolean isLocal = Arrays.asList(environment.getActiveProfiles()).contains("local");
        if (!isLocal && mockSmsProvider != null) {
            mockSmsProvider.clear();
        }
        failureHandler.clear();
        eventListener.clear();
    }

    @Test
    @DisplayName("场景 1: 单条短信同步发信端到端测试")
    void testSend_Success() throws Exception {
        SendSmsRequest req = SendSmsRequest.builder()
                .phone("13812345678")
                .templateId("SMS_TPL_001")
                .params(Map.of("code", "123456"))
                .build();

        mockMvc.perform(post("/api/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.channel").value("mock"));

        assertEquals(1, mockSmsProvider.getSentCount());
        assertEquals("13812345678", mockSmsProvider.getLastRequest().getPhone());
    }

    @Test
    @DisplayName("场景 2: JSR-303 格式校验与 Fail-Fast 拦截测试")
    void testSend_ValidationError() throws Exception {
        SendSmsRequest req = SendSmsRequest.builder()
                .phone("12345") // 非法手机号
                .templateId("SMS_TPL_001")
                .build();

        mockMvc.perform(post("/api/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isNumber())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));

        assertEquals(0, mockSmsProvider.getSentCount());
    }

    @Test
    @DisplayName("场景 3: 受管异步非阻塞发信与四维上下文透传测试")
    void testSendAsync_Success() throws Exception {
        SendSmsRequest req = SendSmsRequest.builder()
                .phone("13866668888")
                .templateId("SMS_ASYNC_001")
                .build();

        MvcResult mvcResult = mockMvc.perform(post("/api/sms/send-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        assertEquals(1, mockSmsProvider.getSentCount());
        assertEquals("13866668888", mockSmsProvider.getLastRequest().getPhone());
    }

    @Test
    @DisplayName("场景 4: 本地物理事务感知发信 (COMMIT 成功触发真实投递)")
    void testSendTransactional_SuccessOnCommit() throws Exception {
        SendSmsRequest req = SendSmsRequest.builder()
                .phone("13899990001")
                .templateId("SMS_TX_COMMIT")
                .build();

        mockMvc.perform(post("/api/sms/send-tx")
                        .param("shouldRollback", "false")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 事务提交后，底层应真实收到发信
        assertEquals(1, mockSmsProvider.getSentCount());
        assertEquals("13899990001", mockSmsProvider.getLastRequest().getPhone());
    }

    @Test
    @DisplayName("场景 5: 本地物理事务感知发信 (ROLLBACK 自动取消，防幽灵短信)")
    void testSendTransactional_CancelledOnRollback() throws Exception {
        SendSmsRequest req = SendSmsRequest.builder()
                .phone("13899990002")
                .templateId("SMS_TX_ROLLBACK")
                .build();

        mockMvc.perform(post("/api/sms/send-tx")
                        .param("shouldRollback", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));

        // 事务回滚后，底层绝对不得收到发信
        assertEquals(0, mockSmsProvider.getSentCount(), "事务回滚时应绝对防幽灵短信");
    }

    @Test
    @DisplayName("场景 6: 批量营销短信海量切片与聚合投递")
    void testBatchSend_TransparentSlice() throws Exception {
        List<String> phones = List.of("13800010001", "13800010002", "13800010003", "13800010004");
        BatchSmsSendRequest req = BatchSmsSendRequest.builder()
                .phones(phones)
                .templateId("SMS_BATCH_MARKETING")
                .params(Map.of("discount", "80%"))
                .build();

        mockMvc.perform(post("/api/sms/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalCount").value(4))
                .andExpect(jsonPath("$.data.successCount").value(4));

        assertEquals(1, mockSmsProvider.getSentCount());
        assertEquals(4, mockSmsProvider.getLastRequest().getPhones().size());
    }

    @Test
    @DisplayName("场景 7: 验证码全生命周期治理 (获取 -> 错误核验 -> 正确核销 -> 自毁防重放)")
    void testVerifyCode_LifecycleAndDestruction() throws Exception {
        String phone = "13800139001";
        String bizType = "login";

        // 1. 发送验证码
        SendVerifyCodeRequest sendReq = SendVerifyCodeRequest.builder()
                .phone(phone)
                .bizType(bizType)
                .build();

        MvcResult sendResult = mockMvc.perform(post("/api/sms/verify-code/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sendReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isString())
                .andReturn();

        String responseJson = sendResult.getResponse().getContentAsString();
        String generatedCode = objectMapper.readTree(responseJson).get("data").asText();
        assertNotNull(generatedCode);
        assertEquals(6, generatedCode.length());

        // 2. 故意传入错误验证码核验 -> 预期触发 SmsVerifyCodeException
        VerifyCodeCheckRequest wrongReq = VerifyCodeCheckRequest.builder()
                .phone(phone)
                .bizType(bizType)
                .code("000000")
                .build();

        mockMvc.perform(post("/api/sms/verify-code/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));

        // 3. 传入正确验证码核验 -> 预期通过
        VerifyCodeCheckRequest correctReq = VerifyCodeCheckRequest.builder()
                .phone(phone)
                .bizType(bizType)
                .code(generatedCode)
                .build();

        mockMvc.perform(post("/api/sms/verify-code/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correctReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 4. 二次核验相同验证码 -> 预期自毁防重放生效，核验失败抛出异常
        mockMvc.perform(post("/api/sms/verify-code/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correctReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));
    }

    @Test
    @DisplayName("场景 8: 验证码滑动窗口频控与防刷限流")
    void testVerifyCode_RateLimit() throws Exception {
        String phone = "13800139002";
        String bizType = "register";

        SendVerifyCodeRequest req = SendVerifyCodeRequest.builder()
                .phone(phone)
                .bizType(bizType)
                .build();

        // 第一次发信成功 (消耗 1 次/分钟 配额)
        mockMvc.perform(post("/api/sms/verify-code/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 连续第二次立即发信 -> 预期触发 1 分钟频控限流熔断
        mockMvc.perform(post("/api/sms/verify-code/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));
    }

    @Test
    @DisplayName("场景 9: 出站黑名单静默熔断拦截与后置补偿 SPI")
    void testBlacklist_Blocked() throws Exception {
        // 13800000000 在 application.yml 中配置为黑名单号码
        SendSmsRequest req = SendSmsRequest.builder()
                .phone("13800000000")
                .templateId("SMS_BLACK")
                .build();

        mockMvc.perform(post("/api/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.rawCode").value("BLOCKED"));

        assertEquals(0, mockSmsProvider.getSentCount(), "命中出站黑名单应静默拦截，不得触发底层发信");
    }

    @Test
    @DisplayName("场景 10: 统一入站 Webhook 回调转译与领域事件广播")
    void testCallbackGateway_Events() throws Exception {
        // 1. 模拟阿里云送达回执回调
        String aliyunDeliveryReportJson = """
                [
                  {
                    "phone_number": "13800088888",
                    "send_time": "2026-11-02 21:00:00",
                    "report_time": "2026-11-02 21:00:02",
                    "success": true,
                    "err_code": "DELIVRD",
                    "err_msg": "用户接收成功",
                    "sms_size": "1",
                    "biz_id": "ALI_CALLBACK_001"
                  }
                ]
                """;

        mockMvc.perform(post("/ateng-sms/callback/mock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aliyunDeliveryReportJson))
                .andExpect(status().isOk());

        assertEquals(1, eventListener.getDeliveryReportEvents().size());
        SmsDeliveryReportEvent reportEvent = eventListener.getDeliveryReportEvents().get(0);
        assertEquals("13800088888", reportEvent.getPhone());
        assertTrue(reportEvent.isSuccess());
        assertEquals("DELIVRD", reportEvent.getResultCode());

        // 2. 模拟用户上行回复短信 (TD 退订)
        String aliyunMoJson = """
                [
                  {
                    "phone_number": "13800088888",
                    "send_time": "2026-11-02 21:05:00",
                    "content": "TD",
                    "sign_name": "官方测试"
                  }
                ]
                """;

        mockMvc.perform(post("/ateng-sms/callback/mock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aliyunMoJson))
                .andExpect(status().isOk());

        assertEquals(1, eventListener.getMoMessageEvents().size());
        SmsMoMessageEvent moEvent = eventListener.getMoMessageEvents().get(0);
        assertEquals("13800088888", moEvent.getPhone());
        assertEquals("TD", moEvent.getContent());
    }

    @Test
    @DisplayName("场景 11: 渠道列表查询与动态渠道注册 (安全脱敏 VO & 凭据隔离)")
    void testChannel_ListAndDynamicRegister() throws Exception {
        // 1. 查询所有已注册渠道，断言返回脱敏 VO 且绝对不泄露 accessKeySecret
        mockMvc.perform(get("/api/sms/channel/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].name").exists())
                .andExpect(jsonPath("$.data[0].accessKeySecret").doesNotExist())
                .andExpect(jsonPath("$.data[0].accessKeyId").doesNotExist());

        // 2. 动态注册新渠道
        DynamicChannelRegisterRequest req = DynamicChannelRegisterRequest.builder()
                .name("custom-dynamic-test")
                .supplier(SmsSupplier.MOCK)
                .signName("动态测试签名")
                .accessKeyId("mock-ak")
                .accessKeySecret("mock-sk")
                .build();

        mockMvc.perform(post("/api/sms/channel/dynamic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 3. 再次查询，验证动态渠道已成功注册
        assertTrue(channelRegistry.getChannels().containsKey("custom-dynamic-test"), "动态注册渠道应存在于注册中心");
    }

    @Test
    @DisplayName("场景 12: 动态注册渠道 JSR-303 参数校验 Fail-Fast")
    void testChannel_DynamicRegisterValidationError() throws Exception {
        DynamicChannelRegisterRequest badReq = DynamicChannelRegisterRequest.builder()
                .name("") // 空名称
                .supplier(null) // 空服务商
                .build();

        mockMvc.perform(post("/api/sms/channel/dynamic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));
    }

    @Test
    @DisplayName("场景 13: 批量发信 JSR-303 集合元素级手机号格式防卫测试")
    void testBatchSend_ElementValidationError() throws Exception {
        // 集合内包含非法手机号 "12345"，验证集合元素校验生效
        BatchSmsSendRequest badBatch = BatchSmsSendRequest.builder()
                .phones(List.of("13800010001", "12345"))
                .templateId("SMS_BATCH_TPL")
                .build();

        mockMvc.perform(post("/api/sms/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badBatch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));
    }

    @Test
    @DisplayName("场景 14: 双模自适应本地真实公有云发信联调 (单测试套件双模)")
    void testDualMode_RealCloudSend() {
        boolean isLocal = Arrays.asList(environment.getActiveProfiles()).contains("local");
        if (!isLocal) {
            // 默认 / CI 模式下保证离线自洽，验证 Mock 渠道正常就绪
            assertTrue(channelRegistry.getChannels().containsKey("mock"), "离线模式应包含 Mock 渠道");
            return;
        }

        // 激活 local Profile 时全真执行发信
        SendSmsRequest req = SendSmsRequest.builder()
                .phone("13800138000")
                .templateId("SMS_REAL_TEST")
                .params(Map.of("code", "888888"))
                .build();
        SmsResult result = smsScenarioService.sendSingleSms(req);
        assertNotNull(result);
        assertTrue(result.isSuccess(), "本地真实公有云发信联调应投递成功");
    }
}
