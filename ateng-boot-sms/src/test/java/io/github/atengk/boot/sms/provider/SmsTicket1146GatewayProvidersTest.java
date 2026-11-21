package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.core.util.HmacUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.model.BatchSmsRequest;
import io.github.atengk.boot.sms.model.BatchSmsResult;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsParam;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工单 #1146 五家企业级与运营商网关纯原生驱动（容联、创蓝、移动MAS、合一、助通）离线契约与鉴权测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("工单 #1146 五家企业与运营商网关纯原生驱动 MockWebServer 验证")
class SmsTicket1146GatewayProvidersTest {

    private MockWebServer mockWebServer;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        baseUrl = mockWebServer.url("/").toString();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @Test
    @DisplayName("容联云：MD5 sig + Base64 Authorization 鉴权、ordered datas 与 200 批限验证")
    @SuppressWarnings("unchecked")
    void shouldSignAndSendCloopenTemplateSms() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "statusCode": "000000",
                          "templateSMS": {
                            "smsMessageSid": "cl-sid-9001",
                            "dateCreated": "20261121154500"
                          }
                        }
                        """));

        SmsChannel channel = SmsChannel.builder()
                .name("cloopen-main")
                .supplier(SmsSupplier.CLOOPEN)
                .accessKeyId("CLOOPEN_SID")
                .accessKeySecret("CLOOPEN_TOKEN")
                .appId("CLOOPEN_APP")
                .endpoint(baseUrl)
                .build();

        CloopenSmsProvider provider = new CloopenSmsProvider(RestClient.builder());
        assertThat(provider.getBatchLimit()).isEqualTo(200);

        SmsResult result = provider.send(SmsRequest.builder()
                .phones(List.of("13800138001", "13800138002"))
                .templateId("tpl_cl_1")
                .params(SmsParam.of("code", "5566").add("expire", "5"))
                .build(), channel);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSupplier()).isEqualTo(SmsSupplier.CLOOPEN);
        assertThat(result.getBizId()).isEqualTo("cl-sid-9001");

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).startsWith("/Accounts/CLOOPEN_SID/SMS/TemplateSMS?sig=");

        String authPlain = new String(Base64.getDecoder().decode(recorded.getHeader("Authorization")), StandardCharsets.UTF_8);
        String[] parts = authPlain.split(":");
        assertThat(parts[0]).isEqualTo("CLOOPEN_SID");
        String timestamp = parts[1];
        String expectedSig = DigestUtil.md5Hex("CLOOPEN_SID" + "CLOOPEN_TOKEN" + timestamp).toUpperCase();
        assertThat(recorded.getRequestUrl().queryParameter("sig")).isEqualTo(expectedSig);

        Map<String, Object> body = JsonUtil.parseObject(recorded.getBody().readUtf8(), Map.class);
        assertThat(body.get("to")).isEqualTo("13800138001,13800138002");
        assertThat(body.get("appId")).isEqualTo("CLOOPEN_APP");
        assertThat(body.get("templateId")).isEqualTo("tpl_cl_1");
        assertThat((List<String>) body.get("datas")).containsExactly("5566", "5");

        // 业务错误转译
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"statusCode\":\"160014\",\"statusMsg\":\"账户余额不足\"}"));
        assertThatThrownBy(() -> provider.send(SmsRequest.of("13800138001", "tpl_cl_1"), channel))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("160014");
    }

    @Test
    @DisplayName("创蓝 253：变量模板 (/msg/variable/json) 与无模板纯文本 (/msg/v1/send/json) 双模直发")
    @SuppressWarnings("unchecked")
    void shouldSendChuanglanBothTemplateAndPlainTextModes() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"0\",\"errorMsg\":\"\",\"msgId\":\"cl253-tpl-1\"}"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"0\",\"errorMsg\":\"\",\"msgId\":\"cl253-plain-2\"}"));

        SmsChannel channel = SmsChannel.builder()
                .name("chuanglan-main")
                .supplier(SmsSupplier.CHUANGLAN)
                .accessKeyId("CL_USER")
                .accessKeySecret("CL_PWD")
                .signName("创蓝测试")
                .extCode("88")
                .endpoint(baseUrl)
                .build();

        ChuanglanSmsProvider provider = new ChuanglanSmsProvider(RestClient.builder());
        assertThat(provider.supportsPlainText()).isTrue();

        // 1. 变量模板模式 -> /msg/variable/json
        SmsResult tplRes = provider.send(SmsRequest.builder()
                .phones(List.of("13800138001", "13800138002"))
                .templateId("验证码：{$var}，有效期{$var}分钟")
                .params(SmsParam.ofArray("1234", "5"))
                .build(), channel);
        assertThat(tplRes.isSuccess()).isTrue();
        assertThat(tplRes.getSupplier()).isEqualTo(SmsSupplier.CHUANGLAN);
        assertThat(tplRes.getBizId()).isEqualTo("cl253-tpl-1");

        RecordedRequest tplReq = mockWebServer.takeRequest();
        assertThat(tplReq.getPath()).isEqualTo("/msg/variable/json");
        Map<String, Object> tplBody = JsonUtil.parseObject(tplReq.getBody().readUtf8(), Map.class);
        assertThat(tplBody.get("account")).isEqualTo("CL_USER");
        assertThat(tplBody.get("password")).isEqualTo("CL_PWD");
        assertThat(tplBody.get("msg")).isEqualTo("【创蓝测试】验证码：{$var}，有效期{$var}分钟");
        assertThat(tplBody.get("params")).isEqualTo("13800138001,1234,5;13800138002,1234,5");

        // 2. 纯文本直发模式 -> /msg/v1/send/json
        SmsResult plainRes = provider.send(SmsRequest.builder()
                .phones(List.of("13800138003", "13800138004"))
                .content("您的订单已发货，请注意查收")
                .bizId("BIZ_CL_02")
                .build(), channel);
        assertThat(plainRes.isSuccess()).isTrue();
        assertThat(plainRes.getBizId()).isEqualTo("cl253-plain-2");

        RecordedRequest plainReq = mockWebServer.takeRequest();
        assertThat(plainReq.getPath()).isEqualTo("/msg/v1/send/json");
        Map<String, Object> plainBody = JsonUtil.parseObject(plainReq.getBody().readUtf8(), Map.class);
        assertThat(plainBody.get("phone")).isEqualTo("13800138003,13800138004");
        assertThat(plainBody.get("msg")).isEqualTo("【创蓝测试】您的订单已发货，请注意查收");
        assertThat(plainBody.get("extend")).isEqualTo("88");
        assertThat(plainBody.get("uid")).isEqualTo("BIZ_CL_02");

        // 错误码拦截
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"108\",\"errorMsg\":\"手机号码格式错误\"}"));
        assertThatThrownBy(() -> provider.send(SmsRequest.builder().phone("13800138003").content("【创蓝】测试").build(), channel))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("108");
    }

    @Test
    @DisplayName("中国移动云 MAS：Base64+MD5 MAC 签名，支持 tmpsubmit 模板与 norsubmit/submit 纯文本切换")
    @SuppressWarnings("unchecked")
    void shouldSignAndSendChinaMobileMasTemplateAndPlainTextModes() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"rspcod\":\"success\",\"msgGroup\":\"mas-grp-001\",\"success\":true}"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"rspcod\":\"success\",\"msgGroup\":\"mas-grp-002\",\"success\":true}"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"rspcod\":\"success\",\"msgGroup\":\"mas-grp-003\",\"success\":true}"));

        SmsChannel baseChannel = SmsChannel.builder()
                .name("mas-main")
                .supplier(SmsSupplier.MAS)
                .appId("ap_test")
                .accessKeySecret("secret_mas")
                .signId("SIGN_CODE_1")
                .extCode("06")
                .endpoint(baseUrl + "sms/")
                .extras(Map.of("ecName", "阿腾集团"))
                .build();

        MasSmsProvider provider = new MasSmsProvider(RestClient.builder());
        assertThat(provider.supportsPlainText()).isTrue();

        // 1. tmpsubmit 模板模式
        SmsResult tmpRes = provider.send(SmsRequest.of("13800138000", "TMP_001", SmsParam.ofArray("8899")), baseChannel);
        assertThat(tmpRes.isSuccess()).isTrue();
        assertThat(tmpRes.getBizId()).isEqualTo("mas-grp-001");

        RecordedRequest tmpReq = mockWebServer.takeRequest();
        assertThat(tmpReq.getPath()).isEqualTo("/sms/tmpsubmit");
        String tmpJsonStr = new String(Base64.getDecoder().decode(tmpReq.getBody().readUtf8()), StandardCharsets.UTF_8);
        Map<String, Object> tmpBody = JsonUtil.parseObject(tmpJsonStr, Map.class);
        String expectedTmpMac = DigestUtil.md5Hex("阿腾集团ap_testsecret_masTMP_00113800138000[\"8899\"]SIGN_CODE_106");
        assertThat(tmpBody.get("mac")).isEqualTo(expectedTmpMac);
        assertThat(tmpBody.get("addSerial")).isEqualTo("06");

        // 2. 纯文本默认 norsubmit 模式
        SmsResult norRes = provider.send(SmsRequest.builder()
                .phone("13800138000")
                .content("告警纯文本内容")
                .build(), baseChannel);
        assertThat(norRes.isSuccess()).isTrue();

        RecordedRequest norReq = mockWebServer.takeRequest();
        assertThat(norReq.getPath()).isEqualTo("/sms/norsubmit");
        Map<String, Object> norBody = JsonUtil.parseObject(
                new String(Base64.getDecoder().decode(norReq.getBody().readUtf8()), StandardCharsets.UTF_8), Map.class);
        String expectedNorMac = DigestUtil.md5Hex("阿腾集团ap_testsecret_mas13800138000告警纯文本内容SIGN_CODE_106");
        assertThat(norBody.get("mac")).isEqualTo(expectedNorMac);

        // 3. extras.action=submit 显式切换路径 /sms/submit
        SmsChannel submitChannel = SmsChannel.builder()
                .name("mas-submit")
                .supplier(SmsSupplier.MAS)
                .appId("ap_test")
                .accessKeySecret("secret_mas")
                .signId("SIGN_CODE_1")
                .extCode("06")
                .endpoint(baseUrl + "sms/")
                .extras(Map.of("ecName", "阿腾集团", "action", "submit"))
                .build();
        provider.send(SmsRequest.builder().phone("13800138000").content("精确通道消息").build(), submitChannel);
        assertThat(mockWebServer.takeRequest().getPath()).isEqualTo("/sms/submit");

        // 缺失 extras.ecName 时 Fail-Fast
        SmsChannel noEcChannel = SmsChannel.builder()
                .name("mas-bad")
                .supplier(SmsSupplier.MAS)
                .appId("ap_test")
                .accessKeySecret("secret_mas")
                .signId("SIGN_CODE_1")
                .endpoint(baseUrl)
                .build();
        assertThatThrownBy(() -> provider.send(SmsRequest.of("13800138000", "TMP_1"), noEcChannel))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("合一短信 (UniSMS)：支持无状态 HMAC-SHA256 签名与 extras.simpleMode 简易模式")
    @SuppressWarnings("unchecked")
    void shouldSignAndSendUniSmsInHmacAndSimpleModesStatelessly() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"0\",\"message\":\"Success\",\"data\":{\"recipients\":1,\"messages\":[{\"id\":\"uni-msg-1\"}]}}"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"0\",\"message\":\"Success\",\"data\":{\"recipients\":1,\"messages\":[{\"id\":\"uni-msg-2\"}]}}"));

        UnismsSmsProvider provider = new UnismsSmsProvider(RestClient.builder());

        // 1. 标准 HMAC-SHA256 签名模式
        SmsChannel hmacChannel = SmsChannel.builder()
                .name("uni-hmac")
                .supplier(SmsSupplier.UNISMS)
                .accessKeyId("UNI_AK")
                .accessKeySecret("UNI_SK")
                .signName("合一签名")
                .endpoint(baseUrl)
                .build();

        SmsResult hmacRes = provider.send(SmsRequest.of("13800138000", "uni_tpl_1", SmsParam.of("code", "4321")), hmacChannel);
        assertThat(hmacRes.isSuccess()).isTrue();
        assertThat(hmacRes.getSupplier()).isEqualTo(SmsSupplier.UNISMS);
        assertThat(hmacRes.getBizId()).isEqualTo("uni-msg-1");

        RecordedRequest hmacReq = mockWebServer.takeRequest();
        assertThat(hmacReq.getRequestUrl().queryParameter("action")).isEqualTo("sms.message.send");
        assertThat(hmacReq.getRequestUrl().queryParameter("accessKeyId")).isEqualTo("UNI_AK");
        assertThat(hmacReq.getRequestUrl().queryParameter("algorithm")).isEqualTo("hmac-sha256");

        // 独立按字典序重排 Query 参数验签
        TreeMap<String, String> sorted = new TreeMap<>();
        for (String name : hmacReq.getRequestUrl().queryParameterNames()) {
            if (!"signature".equals(name)) {
                sorted.put(name, hmacReq.getRequestUrl().queryParameter(name));
            }
        }
        StringBuilder signSource = new StringBuilder();
        sorted.forEach((k, v) -> {
            if (signSource.length() > 0) {
                signSource.append('&');
            }
            signSource.append(k).append('=').append(v);
        });
        String expectedUniSig = HmacUtil.hmacSha256Base64(signSource.toString(), "UNI_SK");
        assertThat(hmacReq.getRequestUrl().queryParameter("signature")).isEqualTo(expectedUniSig);

        Map<String, Object> hmacBody = JsonUtil.parseObject(hmacReq.getBody().readUtf8(), Map.class);
        assertThat(hmacBody.get("signature")).isEqualTo("合一签名");
        assertThat(hmacBody.get("templateId")).isEqualTo("uni_tpl_1");
        assertThat((List<String>) hmacBody.get("to")).containsExactly("+8613800138000");

        // 2. extras.simpleMode=true 模式无需 signature 且无静态状态干扰
        SmsChannel simpleChannel = SmsChannel.builder()
                .name("uni-simple")
                .supplier(SmsSupplier.UNISMS)
                .accessKeyId("UNI_SIMPLE_AK")
                .signName("简易签名")
                .endpoint(baseUrl)
                .extras(Map.of("simpleMode", "true"))
                .build();

        SmsResult simpleRes = provider.send(SmsRequest.of("13800138009", "uni_tpl_2"), simpleChannel);
        assertThat(simpleRes.isSuccess()).isTrue();
        assertThat(simpleRes.getBizId()).isEqualTo("uni-msg-2");

        RecordedRequest simpleReq = mockWebServer.takeRequest();
        assertThat(simpleReq.getRequestUrl().queryParameter("signature")).isNull();
        assertThat(simpleReq.getRequestUrl().queryParameter("accessKeyId")).isEqualTo("UNI_SIMPLE_AK");
    }

    @Test
    @DisplayName("助通科技：双重 MD5 鉴权，v2/sendSmsTp 模板（含 extCode 与个性化批量）及 v2/sendSms 纯文本直发")
    @SuppressWarnings("unchecked")
    void shouldSignAndSendZhutongTemplatePlainAndPersonalizedBatch() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":200,\"msg\":\"success\",\"msgId\":\"zt-tpl-01\"}"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":200,\"msg\":\"success\",\"msgId\":\"zt-plain-02\"}"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":200,\"msg\":\"success\",\"msgId\":\"zt-pbatch-03\"}"));

        SmsChannel channel = SmsChannel.builder()
                .name("zhutong-main")
                .supplier(SmsSupplier.ZHUTONG)
                .accessKeyId("ZT_USER")
                .accessKeySecret("ZT_PLAIN_PWD")
                .signName("助通科技")
                .extCode("106")
                .endpoint(baseUrl)
                .build();

        ZhutongSmsProvider provider = new ZhutongSmsProvider(RestClient.builder());
        assertThat(provider.supportsPlainText()).isTrue();

        // 1. 模板短信 v2/sendSmsTp
        SmsResult tplRes = provider.send(SmsRequest.builder()
                .phone("13800138001")
                .templateId("tp_999")
                .params(SmsParam.of("code", "7788"))
                .bizId("EXTEND_1")
                .build(), channel);
        assertThat(tplRes.isSuccess()).isTrue();
        assertThat(tplRes.getSupplier()).isEqualTo(SmsSupplier.ZHUTONG);
        assertThat(tplRes.getBizId()).isEqualTo("zt-tpl-01");

        RecordedRequest tplReq = mockWebServer.takeRequest();
        assertThat(tplReq.getPath()).isEqualTo("/v2/sendSmsTp");
        Map<String, Object> tplBody = JsonUtil.parseObject(tplReq.getBody().readUtf8(), Map.class);
        String tKey = String.valueOf(tplBody.get("tKey"));
        String expectedPwd = DigestUtil.md5Hex(DigestUtil.md5Hex("ZT_PLAIN_PWD") + tKey);
        assertThat(tplBody.get("password")).isEqualTo(expectedPwd);
        assertThat(tplBody.get("tpId")).isEqualTo("tp_999");
        assertThat(tplBody.get("signature")).isEqualTo("【助通科技】");
        assertThat(tplBody.get("ext")).isEqualTo("106");
        assertThat(tplBody.get("extend")).isEqualTo("EXTEND_1");

        // 2. 纯文本直发 v2/sendSms
        SmsResult plainRes = provider.send(SmsRequest.builder()
                .phones(List.of("13800138002", "13800138003"))
                .content("您的订单已发货")
                .build(), channel);
        assertThat(plainRes.isSuccess()).isTrue();
        assertThat(plainRes.getBizId()).isEqualTo("zt-plain-02");

        RecordedRequest plainReq = mockWebServer.takeRequest();
        assertThat(plainReq.getPath()).isEqualTo("/v2/sendSms");
        Map<String, Object> plainBody = JsonUtil.parseObject(plainReq.getBody().readUtf8(), Map.class);
        assertThat(plainBody.get("mobile")).isEqualTo("13800138002,13800138003");
        assertThat(plainBody.get("content")).isEqualTo("【助通科技】您的订单已发货");

        // 3. 原生个性化批量直发 v2/sendSmsTp records
        Map<String, SmsParam> phoneParams = new LinkedHashMap<>();
        phoneParams.put("13800138010", SmsParam.of("name", "张三"));
        phoneParams.put("13800138011", SmsParam.of("name", "李四"));
        BatchSmsResult pBatchRes = provider.sendPersonalizedBatch(BatchSmsRequest.builder()
                .phones(List.of("13800138010", "13800138011"))
                .templateId("tp_pbatch")
                .phoneParams(phoneParams)
                .build(), channel);
        assertThat(pBatchRes.getSuccessCount()).isEqualTo(2);
        RecordedRequest pBatchReq = mockWebServer.takeRequest();
        Map<String, Object> pBatchBody = JsonUtil.parseObject(pBatchReq.getBody().readUtf8(), Map.class);
        List<Map<String, Object>> records = (List<Map<String, Object>>) pBatchBody.get("records");
        assertThat(records).hasSize(2);
        assertThat(((Map<String, Object>) records.get(1).get("tpContent"))).containsEntry("name", "李四");

        // 4. HTTP 500 与超时均转译保留 cause 的 SmsSendException
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("server error"));
        mockWebServer.enqueue(new MockResponse().setBodyDelay(1500, TimeUnit.MILLISECONDS).setResponseCode(200).setBody("{}"));
        SmsChannel shortTimeoutCh = SmsChannel.builder()
                .name("zt-err")
                .supplier(SmsSupplier.ZHUTONG)
                .accessKeyId("ZT_USER")
                .accessKeySecret("ZT_PLAIN_PWD")
                .signName("助通科技")
                .endpoint(baseUrl)
                .timeout(Duration.ofMillis(200))
                .build();
        assertThatThrownBy(() -> provider.send(SmsRequest.of("13800138000", "tp_1"), shortTimeoutCh))
                .isInstanceOf(SmsSendException.class)
                .hasCauseInstanceOf(Exception.class);
        assertThatThrownBy(() -> provider.send(SmsRequest.of("13800138000", "tp_1"), shortTimeoutCh))
                .isInstanceOf(SmsSendException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);
    }

    @Test
    @DisplayName("五网关补全：MAS 字符串 false/异常超时、UniSMS Fail-Fast 与数组参数推导、容联/创蓝 HTTP 错误及助通 Fail-Fast")
    @SuppressWarnings("unchecked")
    void shouldVerifyRemainingGatewayErrorTranslationTimeoutsAndFailFastContracts() throws Exception {
        // 1. MAS: success="false" 字符串拦截 + HTTP 500 JSON + 超时
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"rspcod\":\"success\",\"success\":\"false\"}"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setHeader("Content-Type", "application/json")
                .setBody("{\"rspcod\":\"InvalidMessage\",\"message\":\"bad base64\"}"));
        mockWebServer.enqueue(new MockResponse().setBodyDelay(1200, TimeUnit.MILLISECONDS).setResponseCode(200).setBody("{}"));

        SmsChannel masErrCh = SmsChannel.builder()
                .name("mas-err")
                .supplier(SmsSupplier.MAS)
                .appId("ap_1")
                .accessKeySecret("sk_1")
                .signId("sign_1")
                .endpoint(baseUrl + "sms/")
                .timeout(Duration.ofMillis(200))
                .extras(Map.of("ecName", "集团"))
                .build();
        MasSmsProvider masProvider = new MasSmsProvider(RestClient.builder());
        assertThatThrownBy(() -> masProvider.send(SmsRequest.builder().phone("13800138000").content("测试").build(), masErrCh))
                .isInstanceOf(SmsSendException.class);
        assertThatThrownBy(() -> masProvider.send(SmsRequest.builder().phone("13800138000").content("测试").build(), masErrCh))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("InvalidMessage")
                .hasCauseInstanceOf(Exception.class);
        assertThatThrownBy(() -> masProvider.send(SmsRequest.builder().phone("13800138000").content("测试").build(), masErrCh))
                .isInstanceOf(SmsSendException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);
        for (int i = 0; i < 3; i++) {
            mockWebServer.takeRequest();
        }

        // 2. UniSMS: buildSignedUrl Fail-Fast + 数组 paramOrder 推导 + 网关 error / HTTP 400 / 超时
        assertThatThrownBy(() -> UnismsSmsProvider.buildSignedUrl(baseUrl, "ak", "", false))
                .isInstanceOf(BusinessException.class);

        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"0\",\"message\":\"Success\",\"data\":{\"messages\":[{\"id\":\"u-arr\"}]}}"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"104111\",\"message\":\"invalid sig\"}"));
        mockWebServer.enqueue(new MockResponse().setBodyDelay(1200, TimeUnit.MILLISECONDS).setResponseCode(200).setBody("{}"));

        io.github.atengk.boot.sms.properties.SmsTemplateProperties uniTpl = new io.github.atengk.boot.sms.properties.SmsTemplateProperties();
        uniTpl.setTemplateId("uni_arr_tpl");
        uniTpl.setParamOrder(List.of("code", "ttl"));
        SmsChannel uniCh = SmsChannel.builder()
                .name("uni-arr")
                .supplier(SmsSupplier.UNISMS)
                .accessKeyId("UNI_AK")
                .accessKeySecret("UNI_SK")
                .signName("合一")
                .endpoint(baseUrl)
                .timeout(Duration.ofMillis(200))
                .templates(Map.of("uni_arr_tpl", uniTpl))
                .build();
        UnismsSmsProvider unismsProvider = new UnismsSmsProvider(RestClient.builder());
        unismsProvider.send(SmsRequest.of("13800138000", "uni_arr_tpl", SmsParam.ofArray("6677", "5")), uniCh);
        Map<String, Object> uniBody = JsonUtil.parseObject(mockWebServer.takeRequest().getBody().readUtf8(), Map.class);
        assertThat((Map<String, Object>) uniBody.get("templateData")).containsEntry("code", "6677").containsEntry("ttl", "5");

        assertThatThrownBy(() -> unismsProvider.send(SmsRequest.of("13800138000", "uni_arr_tpl"), uniCh))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("104111")
                .hasCauseInstanceOf(Exception.class);
        assertThatThrownBy(() -> unismsProvider.send(SmsRequest.of("13800138000", "uni_arr_tpl"), uniCh))
                .isInstanceOf(SmsSendException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);

        // 3. 容联 & 创蓝：HTTP 错误体与超时保留 cause
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setHeader("Content-Type", "application/json")
                .setBody("{\"statusCode\":\"111111\",\"statusMsg\":\"cloopen server fail\"}"));
        mockWebServer.enqueue(new MockResponse().setBodyDelay(1200, TimeUnit.MILLISECONDS).setResponseCode(200).setBody("{}"));
        SmsChannel clCh = SmsChannel.builder()
                .name("cl-err").supplier(SmsSupplier.CLOOPEN).accessKeyId("SID").accessKeySecret("TK")
                .appId("APP").endpoint(baseUrl).timeout(Duration.ofMillis(200)).build();
        CloopenSmsProvider clProvider = new CloopenSmsProvider(RestClient.builder());
        assertThatThrownBy(() -> clProvider.send(SmsRequest.of("13800138000", "t1"), clCh))
                .isInstanceOf(SmsSendException.class).hasMessageContaining("111111").hasCauseInstanceOf(Exception.class);
        assertThatThrownBy(() -> clProvider.send(SmsRequest.of("13800138000", "t1"), clCh))
                .isInstanceOf(SmsSendException.class).hasRootCauseInstanceOf(SocketTimeoutException.class);

        mockWebServer.enqueue(new MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"101\",\"errorMsg\":\"bad chuanglan req\"}"));
        mockWebServer.enqueue(new MockResponse().setBodyDelay(1200, TimeUnit.MILLISECONDS).setResponseCode(200).setBody("{}"));
        SmsChannel chuanCh = SmsChannel.builder()
                .name("chuan-err").supplier(SmsSupplier.CHUANGLAN).accessKeyId("U").accessKeySecret("P")
                .signName("创蓝").endpoint(baseUrl).timeout(Duration.ofMillis(200)).build();
        ChuanglanSmsProvider chuanProvider = new ChuanglanSmsProvider(RestClient.builder());
        assertThatThrownBy(() -> chuanProvider.send(SmsRequest.builder().phone("13800138000").content("测试").build(), chuanCh))
                .isInstanceOf(SmsSendException.class).hasMessageContaining("101").hasCauseInstanceOf(Exception.class);
        assertThatThrownBy(() -> chuanProvider.send(SmsRequest.builder().phone("13800138000").content("测试").build(), chuanCh))
                .isInstanceOf(SmsSendException.class).hasRootCauseInstanceOf(SocketTimeoutException.class);

        // 4. 助通：sendPersonalizedBatch 缺 signName 直接 Fail-Fast 抛 BusinessException（不被吞为切片失败）
        ZhutongSmsProvider ztProvider = new ZhutongSmsProvider(RestClient.builder());
        SmsChannel ztNoSign = SmsChannel.builder()
                .name("zt-nosign").supplier(SmsSupplier.ZHUTONG).accessKeyId("U").accessKeySecret("P").endpoint(baseUrl).build();
        BatchSmsRequest pReq = BatchSmsRequest.builder()
                .phones(List.of("13800138001", "13800138002"))
                .templateId("tp_x")
                .phoneParams(Map.of("13800138001", SmsParam.of("code", "1"), "13800138002", SmsParam.of("code", "2")))
                .build();
        assertThatThrownBy(() -> ztProvider.sendPersonalizedBatch(pReq, ztNoSign))
                .isInstanceOf(BusinessException.class);
    }
}
