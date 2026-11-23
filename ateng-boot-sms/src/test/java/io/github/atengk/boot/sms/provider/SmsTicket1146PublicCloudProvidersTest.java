package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.core.util.HmacUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsParam;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsTemplateProperties;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工单 #1146 四家公有云纯原生驱动（百度、京东、七牛、网易）离线契约与鉴权测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("工单 #1146 四家公有云纯原生驱动 MockWebServer 验证")
class SmsTicket1146PublicCloudProvidersTest {

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
    @DisplayName("百度智能云：bce-auth-v1 签名、signId/extCode/extras.custom 与有序数组参数映射")
    @SuppressWarnings("unchecked")
    void shouldSignAndSendBaiduSmsWithBceAuthV1() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "requestId": "req-baidu-001",
                          "code": "1000",
                          "message": "成功",
                          "data": [
                            {
                              "code": "1000",
                              "message": "成功",
                              "mobile": "13800138000",
                              "messageId": "msg-baidu-1001"
                            }
                          ]
                        }
                        """));

        SmsTemplateProperties tpl = new SmsTemplateProperties();
        tpl.setTemplateId("sms-tmpl-baidu");
        tpl.setParamOrder(List.of("code", "minute"));

        SmsChannel channel = SmsChannel.builder()
                .name("baidu-main")
                .supplier(SmsSupplier.BAIDU)
                .accessKeyId("BAIDU_AK_123")
                .accessKeySecret("BAIDU_SK_456")
                .signId("sms-sign-888")
                .extCode("09")
                .endpoint(baseUrl)
                .extras(Map.of("custom", "trace-biz-01"))
                .templates(Map.of("LOGIN", tpl))
                .build();

        BaiduSmsProvider provider = new BaiduSmsProvider(RestClient.builder());
        assertThat(provider.getBatchLimit()).isEqualTo(200);
        SmsRequest req = SmsRequest.builder()
                .phone("13800138000")
                .templateId("sms-tmpl-baidu")
                .params(SmsParam.ofArray("654321", "5"))
                .build();

        SmsResult result = provider.send(req, channel);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSupplier()).isEqualTo(SmsSupplier.BAIDU);
        assertThat(result.getBizId()).isEqualTo("msg-baidu-1001");
        assertThat(result.getRequestId()).isEqualTo("req-baidu-001");

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/api/v3/sendSms");

        String bceDate = recorded.getHeader("x-bce-date");
        String hostHeader = recorded.getHeader("Host");
        String authHeader = recorded.getHeader("Authorization");
        assertThat(bceDate).isNotBlank();
        assertThat(authHeader).startsWith("bce-auth-v1/BAIDU_AK_123/" + bceDate + "/1800/host;x-bce-date/");

        // 独立按 BCE Auth v1 规范复算签名
        String prefix = "bce-auth-v1/BAIDU_AK_123/" + bceDate + "/1800";
        String signingKey = HmacUtil.hmacSha256Hex(prefix, "BAIDU_SK_456");
        String canonicalHeaders = "host:" + hostHeader.replace(":", "%3A")
                + "\nx-bce-date:" + bceDate.replace(":", "%3A");
        String canonicalRequest = "POST\n/api/v3/sendSms\n\n" + canonicalHeaders;
        String expectedSignature = HmacUtil.hmacSha256Hex(canonicalRequest, signingKey);
        assertThat(authHeader).isEqualTo(prefix + "/host;x-bce-date/" + expectedSignature);

        Map<String, Object> body = JsonUtil.parseObject(recorded.getBody().readUtf8(), Map.class);
        assertThat(body.get("mobile")).isEqualTo("13800138000");
        assertThat(body.get("template")).isEqualTo("sms-tmpl-baidu");
        assertThat(body.get("signatureId")).isEqualTo("sms-sign-888");
        assertThat(body.get("custom")).isEqualTo("trace-biz-01");
        assertThat(body.get("userExtId")).isEqualTo("09");
        Map<String, Object> contentVar = (Map<String, Object>) body.get("contentVar");
        assertThat(contentVar).containsEntry("code", "654321").containsEntry("minute", "5");

        // 缺失 signatureId/signName 时 Fail-Fast 且不发起网络请求
        SmsChannel invalidChannel = SmsChannel.builder()
                .name("baidu-bad")
                .supplier(SmsSupplier.BAIDU)
                .accessKeyId("BAIDU_AK_123")
                .accessKeySecret("BAIDU_SK_456")
                .endpoint(baseUrl)
                .build();
        assertThatThrownBy(() -> provider.send(SmsRequest.of("13800138000", "TPL_1"), invalidChannel))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("百度智能云：网关错误码与超时异常均转译为保留 cause 的 SmsSendException")
    void shouldTranslateBaiduGatewayAndTimeoutErrorWithCause() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"requestId\":\"req-err-1\",\"code\":\"6002\",\"message\":\"签名未审核\"}"));
        mockWebServer.enqueue(new MockResponse()
                .setBodyDelay(1500, TimeUnit.MILLISECONDS)
                .setResponseCode(200)
                .setBody("{}"));

        SmsChannel channel = SmsChannel.builder()
                .name("baidu-err")
                .supplier(SmsSupplier.BAIDU)
                .accessKeyId("AK")
                .accessKeySecret("SK")
                .signId("sign-1")
                .endpoint(baseUrl)
                .timeout(Duration.ofMillis(200))
                .build();
        BaiduSmsProvider provider = new BaiduSmsProvider(RestClient.builder());

        assertThatThrownBy(() -> provider.send(SmsRequest.of("13800138000", "TPL"), channel))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("6002")
                .hasCauseInstanceOf(Exception.class);

        assertThatThrownBy(() -> provider.send(SmsRequest.of("13800138000", "TPL"), channel))
                .isInstanceOf(SmsSendException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);
    }

    @Test
    @DisplayName("京东云：JDCLOUD2-HMAC-SHA256 规范请求与派生密钥链签名及 batchSend")
    @SuppressWarnings("unchecked")
    void shouldSignAndSendJdCloudBatchSms() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "requestId": "jd-req-999",
                          "result": {
                            "status": true,
                            "code": 200,
                            "message": "发送成功",
                            "data": {
                              "sequenceNumber": "seq-jd-2026"
                            }
                          }
                        }
                        """));

        SmsChannel channel = SmsChannel.builder()
                .name("jd-main")
                .supplier(SmsSupplier.JDCLOUD)
                .accessKeyId("JD_AK")
                .accessKeySecret("JD_SK")
                .signId("jd-sign-01")
                .region("cn-north-1")
                .endpoint(baseUrl)
                .build();

        JdCloudSmsProvider provider = new JdCloudSmsProvider(RestClient.builder());
        assertThat(provider.getBatchLimit()).isEqualTo(100);

        SmsRequest req = SmsRequest.builder()
                .phones(List.of("13800138001", "+8613800138002"))
                .templateId("jd-tpl-01")
                .params(SmsParam.of("code", "1122").add("ttl", "10"))
                .build();

        SmsResult result = provider.send(req, channel);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSupplier()).isEqualTo(SmsSupplier.JDCLOUD);
        assertThat(result.getBizId()).isEqualTo("seq-jd-2026");
        assertThat(result.getRequestId()).isEqualTo("jd-req-999");

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/v1/regions/cn-north-1/batchSend");

        String jdDate = recorded.getHeader("x-jdcloud-date");
        String jdNonce = recorded.getHeader("x-jdcloud-nonce");
        String host = recorded.getHeader("Host");
        String rawBody = recorded.getBody().readUtf8();
        String dateShort = jdDate.substring(0, 8);
        String scope = dateShort + "/cn-north-1/sms/jdcloud2_request";

        // 独立复算 JDCLOUD2-HMAC-SHA256 签名
        String canonicalRequest = "POST\n/v1/regions/cn-north-1/batchSend\n\n"
                + "content-type:application/json\n"
                + "host:" + host.toLowerCase() + "\n"
                + "x-jdcloud-date:" + jdDate + "\n"
                + "x-jdcloud-nonce:" + jdNonce + "\n\n"
                + "content-type;host;x-jdcloud-date;x-jdcloud-nonce\n"
                + DigestUtil.sha256Hex(rawBody);
        String stringToSign = "JDCLOUD2-HMAC-SHA256\n" + jdDate + "\n" + scope + "\n" + DigestUtil.sha256Hex(canonicalRequest);
        byte[] kDate = HmacUtil.hmacSha256(dateShort.getBytes(StandardCharsets.UTF_8), ("JDCLOUD2JD_SK").getBytes(StandardCharsets.UTF_8));
        byte[] kRegion = HmacUtil.hmacSha256("cn-north-1".getBytes(StandardCharsets.UTF_8), kDate);
        byte[] kService = HmacUtil.hmacSha256("sms".getBytes(StandardCharsets.UTF_8), kRegion);
        byte[] kSigning = HmacUtil.hmacSha256("jdcloud2_request".getBytes(StandardCharsets.UTF_8), kService);
        String expectedSig = HmacUtil.hmacSha256Hex(stringToSign.getBytes(StandardCharsets.UTF_8), kSigning);

        assertThat(recorded.getHeader("Authorization"))
                .isEqualTo("JDCLOUD2-HMAC-SHA256 Credential=JD_AK/" + scope
                        + ", SignedHeaders=content-type;host;x-jdcloud-date;x-jdcloud-nonce, Signature=" + expectedSig);

        Map<String, Object> bodyMap = JsonUtil.parseObject(rawBody, Map.class);
        assertThat(bodyMap.get("templateId")).isEqualTo("jd-tpl-01");
        assertThat(bodyMap.get("signId")).isEqualTo("jd-sign-01");
        assertThat((List<String>) bodyMap.get("phoneList")).containsExactly("13800138001", "13800138002");
        assertThat((List<String>) bodyMap.get("params")).containsExactly("1122", "10");

        // 业务错误返回 status=false 抛出 SmsSendException
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"requestId\":\"jd-err\",\"result\":{\"status\":false,\"code\":-10246,\"message\":\"参数数量不匹配\"}}"));
        assertThatThrownBy(() -> provider.send(req, channel))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("-10246");
    }

    @Test
    @DisplayName("七牛云：Qiniu HMAC-SHA1 URLSafe Base64 鉴权，自动区分单条 (/v1/message/single) 与批量 (/v1/message)")
    @SuppressWarnings("unchecked")
    void shouldSignAndSendQiniuSingleAndBatchSms() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"message_id\":\"qn-single-100\"}"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"job_id\":\"qn-job-200\"}"));

        SmsChannel channel = SmsChannel.builder()
                .name("qiniu-main")
                .supplier(SmsSupplier.QINIU)
                .accessKeyId("QN_AK")
                .accessKeySecret("QN_SK")
                .endpoint(baseUrl)
                .build();

        QiniuSmsProvider provider = new QiniuSmsProvider(RestClient.builder());

        // 1. 单条发送
        SmsResult singleRes = provider.send(
                SmsRequest.of("13800138000", "qn-tpl-1", SmsParam.of("code", "3344")), channel);
        assertThat(singleRes.isSuccess()).isTrue();
        assertThat(singleRes.getSupplier()).isEqualTo(SmsSupplier.QINIU);
        assertThat(singleRes.getBizId()).isEqualTo("qn-single-100");

        RecordedRequest singleReq = mockWebServer.takeRequest();
        assertThat(singleReq.getPath()).isEqualTo("/v1/message/single");
        String qDate = singleReq.getHeader("X-Qiniu-Date");
        String singleBody = singleReq.getBody().readUtf8();
        String expectedSingleToSign = "POST /v1/message/single\nHost: " + singleReq.getHeader("Host")
                + "\nContent-Type: application/json\nX-Qiniu-Date: " + qDate + "\n\n" + singleBody;
        String expectedSingleAuth = "Qiniu QN_AK:" + Base64.getUrlEncoder()
                .encodeToString(HmacUtil.hmacSha1(expectedSingleToSign.getBytes(StandardCharsets.UTF_8),
                        "QN_SK".getBytes(StandardCharsets.UTF_8)));
        assertThat(singleReq.getHeader("Authorization")).isEqualTo(expectedSingleAuth);

        Map<String, Object> singleMap = JsonUtil.parseObject(singleBody, Map.class);
        assertThat(singleMap.get("mobile")).isEqualTo("13800138000");
        assertThat(singleMap.get("template_id")).isEqualTo("qn-tpl-1");

        // 2. 批量发送
        SmsResult batchRes = provider.send(SmsRequest.builder()
                .phones(List.of("13800138001", "13800138002"))
                .templateId("qn-tpl-2")
                .params(SmsParam.of("name", "Ateng"))
                .build(), channel);
        assertThat(batchRes.isSuccess()).isTrue();
        assertThat(batchRes.getBizId()).isEqualTo("qn-job-200");

        RecordedRequest batchReq = mockWebServer.takeRequest();
        assertThat(batchReq.getPath()).isEqualTo("/v1/message");
        Map<String, Object> batchMap = JsonUtil.parseObject(batchReq.getBody().readUtf8(), Map.class);
        assertThat((List<String>) batchMap.get("mobiles")).containsExactly("13800138001", "13800138002");

        // 3. HTTP 400 错误体转译与 HTTP 401 异常均保留原始 cause
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Bad token\"}"));
        assertThatThrownBy(() -> provider.send(SmsRequest.of("13800138000", "qn-tpl-1"), channel))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("Bad token")
                .hasCauseInstanceOf(Exception.class);

        mockWebServer.enqueue(new MockResponse().setResponseCode(401));
        assertThatThrownBy(() -> provider.send(SmsRequest.of("13800138000", "qn-tpl-1"), channel))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("401")
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("网易云信：CheckSum SHA1 请求头、Form 参数、extras.needUp 及 100 号码批限校验")
    @SuppressWarnings("unchecked")
    void shouldSignAndSendNeteaseSmsWithCheckSumAndFormParams() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":200,\"msg\":\"sendid\",\"obj\":987654321}"));

        SmsChannel channel = SmsChannel.builder()
                .name("netease-main")
                .supplier(SmsSupplier.NETEASE)
                .accessKeyId("NE_APP_KEY")
                .accessKeySecret("NE_APP_SECRET")
                .endpoint(baseUrl)
                .extras(Map.of("needUp", "true"))
                .build();

        NeteaseSmsProvider provider = new NeteaseSmsProvider(RestClient.builder());
        assertThat(provider.getBatchLimit()).isEqualTo(100);

        SmsRequest req = SmsRequest.builder()
                .phones(List.of("13800138000", "13800138001"))
                .templateId("3051001")
                .params(SmsParam.ofArray("1234", "15"))
                .build();

        SmsResult res = provider.send(req, channel);

        assertThat(res.isSuccess()).isTrue();
        assertThat(res.getSupplier()).isEqualTo(SmsSupplier.NETEASE);
        assertThat(res.getBizId()).isEqualTo("987654321");

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/sms/sendtemplate.action");
        assertThat(recorded.getHeader("Content-Type")).contains("application/x-www-form-urlencoded");
        assertThat(recorded.getHeader("AppKey")).isEqualTo("NE_APP_KEY");
        String nonce = recorded.getHeader("Nonce");
        String curTime = recorded.getHeader("CurTime");
        assertThat(recorded.getHeader("CheckSum"))
                .isEqualTo(DigestUtil.sha1Hex("NE_APP_SECRET" + nonce + curTime));

        Map<String, String> form = parseFormBody(recorded.getBody().readUtf8());
        assertThat(form.get("templateid")).isEqualTo("3051001");
        assertThat(form.get("needUp")).isEqualTo("true");
        List<String> mobiles = JsonUtil.parseObject(form.get("mobiles"), List.class);
        List<String> params = JsonUtil.parseObject(form.get("params"), List.class);
        assertThat(mobiles).containsExactly("13800138000", "13800138001");
        assertThat(params).containsExactly("1234", "15");

        // 业务错误码抛出 SmsSendException
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":413,\"msg\":\"verify failed\"}"));
        assertThatThrownBy(() -> provider.send(req, channel))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("413");
    }

    @Test
    @DisplayName("四公有云补全：静态加签 Fail-Fast、反向参数推导及京东/网易 HTTP 错误与超时异常保留 cause")
    @SuppressWarnings("unchecked")
    void shouldVerifyStaticSignFailFastAdaptiveParamsAndHttpErrorsAcrossPublicClouds() throws Exception {
        // 1. 静态加签方法前置判空 Fail-Fast
        assertThatThrownBy(() -> BaiduSmsProvider.generateAuthorization("", "sk", "host", "/p", "2026-11-21T00:00:00Z"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> JdCloudSmsProvider.generateAuthorization("ak", "", "cn-north-1", "host", "/p", "{}", "20261121T000000Z", "n"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> QiniuSmsProvider.generateAuthorization("ak", "sk", "", "/p", "20261121T000000Z", "{}"))
                .isInstanceOf(BusinessException.class);

        // 2. 七牛支持 ofArray + paramOrder 自适应推导为 JSON parameters Map
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"message_id\":\"qn-arr-1\"}"));
        SmsTemplateProperties qnTpl = new SmsTemplateProperties();
        qnTpl.setTemplateId("qn_tpl_arr");
        qnTpl.setParamOrder(List.of("code", "ttl"));
        SmsChannel qnChannel = SmsChannel.builder()
                .name("qiniu-arr")
                .supplier(SmsSupplier.QINIU)
                .accessKeyId("QN_AK")
                .accessKeySecret("QN_SK")
                .endpoint(baseUrl)
                .templates(Map.of("qn_tpl_arr", qnTpl))
                .build();
        new QiniuSmsProvider(RestClient.builder()).send(
                SmsRequest.of("13800138000", "qn_tpl_arr", SmsParam.ofArray("8888", "5")), qnChannel);
        Map<String, Object> qnBody = JsonUtil.parseObject(mockWebServer.takeRequest().getBody().readUtf8(), Map.class);
        assertThat((Map<String, Object>) qnBody.get("parameters")).containsEntry("code", "8888").containsEntry("ttl", "5");

        // 3. 网易支持 ofMap + paramOrder 推导及 HTTP 500 JSON/超时保留 cause
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"code\":200,\"obj\":111}"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setHeader("Content-Type", "application/json")
                .setBody("{\"code\":500,\"msg\":\"internal gateway fail\"}"));
        mockWebServer.enqueue(new MockResponse().setBodyDelay(1200, TimeUnit.MILLISECONDS).setResponseCode(200).setBody("{}"));

        SmsTemplateProperties neTpl = new SmsTemplateProperties();
        neTpl.setTemplateId("3051999");
        neTpl.setParamOrder(List.of("code", "ttl"));
        SmsChannel neChannel = SmsChannel.builder()
                .name("netease-map")
                .supplier(SmsSupplier.NETEASE)
                .accessKeyId("NE_AK")
                .accessKeySecret("NE_SK")
                .endpoint(baseUrl)
                .timeout(Duration.ofMillis(200))
                .templates(Map.of("3051999", neTpl))
                .build();
        NeteaseSmsProvider neteaseProvider = new NeteaseSmsProvider(RestClient.builder());
        neteaseProvider.send(SmsRequest.of("13800138000", "3051999",
                SmsParam.of("ttl", "10").add("code", "9900")), neChannel);
        Map<String, String> neForm = parseFormBody(mockWebServer.takeRequest().getBody().readUtf8());
        assertThat(JsonUtil.parseObject(neForm.get("params"), List.class)).containsExactly("9900", "10");

        assertThatThrownBy(() -> neteaseProvider.send(SmsRequest.of("13800138000", "3051999"), neChannel))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("internal gateway fail")
                .hasCauseInstanceOf(Exception.class);
        assertThatThrownBy(() -> neteaseProvider.send(SmsRequest.of("13800138000", "3051999"), neChannel))
                .isInstanceOf(SmsSendException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);

        // 4. 京东云 HTTP 400 与超时均保留 cause
        mockWebServer.enqueue(new MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"code\":\"INVALID_SIGN\",\"message\":\"bad signature\"}}"));
        mockWebServer.enqueue(new MockResponse().setBodyDelay(1200, TimeUnit.MILLISECONDS).setResponseCode(200).setBody("{}"));
        SmsChannel jdChannel = SmsChannel.builder()
                .name("jd-err")
                .supplier(SmsSupplier.JDCLOUD)
                .accessKeyId("JD_AK")
                .accessKeySecret("JD_SK")
                .signId("jd_sign")
                .endpoint(baseUrl)
                .timeout(Duration.ofMillis(200))
                .build();
        JdCloudSmsProvider jdProvider = new JdCloudSmsProvider(RestClient.builder());
        assertThatThrownBy(() -> jdProvider.send(SmsRequest.of("13800138000", "jd_tpl"), jdChannel))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("INVALID_SIGN")
                .hasCauseInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdProvider.send(SmsRequest.of("13800138000", "jd_tpl"), jdChannel))
                .isInstanceOf(SmsSendException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);
    }

    private static Map<String, String> parseFormBody(String body) {
        Map<String, String> map = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return map;
    }
}
