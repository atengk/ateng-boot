package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.sms.exception.SmsResultCode;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.model.SmsChannel;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阿里云短信提供商驱动单元测试
 * 基于 MockWebServer 模拟阿里云网关响应，测试 POP RPC 协议、HMAC-SHA1 签名与异常转译
 * 100% 离线自洽，无外部网络依赖
 *
 * @author Ateng
 * @since 2026-11-02
 */
@DisplayName("阿里云短信原生驱动契约与离线自洽测试")
class AliyunSmsProviderTest {

    private MockWebServer mockWebServer;
    private AliyunSmsProvider aliyunSmsProvider;
    private String serverUrl;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        serverUrl = mockWebServer.url("/").toString();
        aliyunSmsProvider = new AliyunSmsProvider(RestClient.builder());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @Test
    @DisplayName("测试发送短信成功 (Happy Path) 并强断言出站请求报文与 POP RPC 签名")
    void testSendSuccess_WithMockWebServer_HappyPath() throws Exception {
        // 1. 模拟阿里云 POP RPC 成功响应
        String jsonResponse = """
                {
                  "BizId": "123456789012345678^0",
                  "Code": "OK",
                  "Message": "OK",
                  "RequestId": "F655A8D5-B967-440B-8683-CAD6FF8D28E0"
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(jsonResponse));

        // 2. 构造渠道配置（endpoint 重写指向 MockWebServer）
        SmsChannel channel = SmsChannel.builder()
                .name("aliyun-main")
                .supplier(SmsSupplier.ALIYUN)
                .accessKeyId("LTAI4FTESTKEY0001")
                .accessKeySecret("testSecretKey9999")
                .signName("阿腾科技")
                .endpoint(serverUrl)
                .build();

        // 3. 构造发信请求
        SmsRequest request = SmsRequest.newBuilder("13800138000", "SMS_TEST_8888")
                .param("code", "654321")
                .param("userName", "张三")
                .bizId("BIZ_OUT_1001")
                .build();

        // 4. 执行发信
        SmsResult result = aliyunSmsProvider.send(request, channel);

        // 5. 校验发信领域结果
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("aliyun-main", result.getChannel());
        assertEquals(SmsSupplier.ALIYUN, result.getSupplier());
        assertEquals("123456789012345678^0", result.getBizId());
        assertEquals("F655A8D5-B967-440B-8683-CAD6FF8D28E0", result.getRequestId());
        assertEquals(1, result.getFee());

        // 6. 校验出站 HTTP 请求
        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertEquals("POST", recordedRequest.getMethod());
        assertTrue(recordedRequest.getHeader("Content-Type").contains("application/x-www-form-urlencoded"));

        String requestBody = recordedRequest.getBody().readUtf8();
        assertNotNull(requestBody);
        assertTrue(requestBody.contains("Signature="));
        assertTrue(requestBody.contains("Action=SendSms"));
        assertTrue(requestBody.contains("Version=2017-05-25"));
        assertTrue(requestBody.contains("Format=JSON"));
        assertTrue(requestBody.contains("SignatureMethod=HMAC-SHA1"));
        assertTrue(requestBody.contains("PhoneNumbers=13800138000"));
        assertTrue(requestBody.contains("TemplateCode=SMS_TEST_8888"));
        assertTrue(requestBody.contains("OutId=BIZ_OUT_1001"));
        assertTrue(requestBody.contains("AccessKeyId=LTAI4FTESTKEY0001"));

        // 验证签名参数是否已做 RFC 3986 编码，解码后能正确读取
        Map<String, String> bodyParams = parseFormBody(requestBody);
        assertEquals("SendSms", bodyParams.get("Action"));
        assertEquals("阿腾科技", bodyParams.get("SignName"));
        assertTrue(bodyParams.get("TemplateParam").contains("654321"));
        assertTrue(bodyParams.get("TemplateParam").contains("张三"));
        assertNotNull(bodyParams.get("Signature"));
    }

    @Test
    @DisplayName("测试阿里云短信携带数组参数时自动降级序列化为数字键 JSON 字符串")
    void testSendSuccess_WithArrayParams_SerializesToNumberedKeyJson() throws Exception {
        String jsonResponse = """
                {
                  "BizId": "123456789012345678^1",
                  "Code": "OK",
                  "Message": "OK",
                  "RequestId": "F655A8D5-B967-440B-8683-CAD6FF8D28E1"
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(jsonResponse));

        SmsChannel channel = SmsChannel.builder()
                .name("aliyun-main")
                .supplier(SmsSupplier.ALIYUN)
                .accessKeyId("LTAI4FTESTKEY0001")
                .accessKeySecret("testSecretKey9999")
                .signName("阿腾科技")
                .endpoint(serverUrl)
                .build();

        SmsRequest request = SmsRequest.newBuilder("13800138000", "SMS_TEST_8888")
                .params(io.github.atengk.boot.sms.model.SmsParam.ofArray("888888", "10"))
                .bizId("BIZ_OUT_1002")
                .build();

        SmsResult result = aliyunSmsProvider.send(request, channel);

        assertNotNull(result);
        assertTrue(result.isSuccess());

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        String requestBody = recordedRequest.getBody().readUtf8();
        Map<String, String> bodyParams = parseFormBody(requestBody);
        assertEquals("SendSms", bodyParams.get("Action"));
        assertNotNull(bodyParams.get("TemplateParam"));
        assertTrue(bodyParams.get("TemplateParam").contains("\"1\":\"888888\"") || bodyParams.get("TemplateParam").contains("\"1\": \"888888\""));
        assertTrue(bodyParams.get("TemplateParam").contains("\"2\":\"10\"") || bodyParams.get("TemplateParam").contains("\"2\": \"10\""));
    }

    @Test
    @DisplayName("测试业务状态码非 OK 响应转译 (如业务限流 isv.BUSINESS_LIMIT_CONTROL)")
    void testSendFailure_BusinessLimitControl_ThrowsSmsSendException() {
        String jsonResponse = """
                {
                  "Code": "isv.BUSINESS_LIMIT_CONTROL",
                  "Message": "业务限流，同一个手机号发送频率超出限制",
                  "RequestId": "LIMIT_REQ_001"
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(jsonResponse));

        SmsChannel channel = SmsChannel.builder()
                .name("aliyun-prod")
                .supplier(SmsSupplier.ALIYUN)
                .accessKeyId("LTAI4FTESTKEY0001")
                .accessKeySecret("testSecretKey9999")
                .signName("阿腾科技")
                .endpoint(serverUrl)
                .build();

        SmsRequest request = SmsRequest.of("13900000000", "SMS_CODE_123");

        SmsSendException exception = assertThrows(SmsSendException.class, () -> aliyunSmsProvider.send(request, channel));
        assertEquals("aliyun-prod", exception.getChannel());
        assertEquals("isv.BUSINESS_LIMIT_CONTROL", exception.getRawCode());
        assertTrue(exception.getRawMessage().contains("业务限流"));
        assertEquals(SmsResultCode.SMS_SEND_ERROR, exception.getResultCode());
    }

    @Test
    @DisplayName("测试网关鉴权失败 HTTP 400 响应与原始因果链保留")
    void testSendFailure_Http400GatewayError_ThrowsSmsSendException() {
        String errorJson = """
                {
                  "Code": "InvalidAccessKeyId.NotFound",
                  "Message": "Specified access key is not found.",
                  "RequestId": "AUTH_REQ_99999"
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(errorJson));

        SmsChannel channel = SmsChannel.builder()
                .name("aliyun-prod")
                .supplier(SmsSupplier.ALIYUN)
                .accessKeyId("INVALID_KEY")
                .accessKeySecret("INVALID_SECRET")
                .signName("阿腾科技")
                .endpoint(serverUrl)
                .build();

        SmsRequest request = SmsRequest.of("13900000000", "SMS_CODE_123");

        SmsSendException exception = assertThrows(SmsSendException.class, () -> aliyunSmsProvider.send(request, channel));
        assertEquals("aliyun-prod", exception.getChannel());
        assertEquals("InvalidAccessKeyId.NotFound", exception.getRawCode());
        assertNotNull(exception.getCause());
    }

    @Test
    @DisplayName("测试 RFC 3986 百分号编码算法规范性")
    void testPercentEncode_StandardCompliance() {
        // 空值保底
        assertEquals("", AliyunSmsProvider.percentEncode(null));

        // 无需转义字符
        assertEquals("abc-123_.~", AliyunSmsProvider.percentEncode("abc-123_.~"));

        // 空格编码为 %20 (严禁编码为 +)
        assertEquals("hello%20world", AliyunSmsProvider.percentEncode("hello world"));

        // 星号编码为 %2A
        assertEquals("foo%2Abar", AliyunSmsProvider.percentEncode("foo*bar"));

        // 波浪号保持原样 ~ (RFC 3986)
        assertEquals("foo~bar", AliyunSmsProvider.percentEncode("foo~bar"));

        // 斜杠编码为 %2F
        assertEquals("a%2Fb", AliyunSmsProvider.percentEncode("a/b"));

        // 中文 UTF-8 百分号大写
        assertEquals("%E9%98%BF%E8%85%BE", AliyunSmsProvider.percentEncode("阿腾"));
    }

    @Test
    @DisplayName("测试 POP RPC HMAC-SHA1 签名计算自洽性与确定性")
    void testGenerateSignature_DeterministicVerification() {
        Map<String, String> params = new HashMap<>();
        params.put("Action", "SendSms");
        params.put("Version", "2017-05-25");
        params.put("Format", "JSON");
        params.put("AccessKeyId", "testid");
        params.put("Timestamp", "2026-11-02T12:00:00Z");
        params.put("SignatureNonce", "nonce-123456");
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureVersion", "1.0");
        params.put("PhoneNumbers", "13800000000");
        params.put("SignName", "测试");

        String secret = "testsecret";

        // 连续两次计算必须完全确定且相等
        String sig1 = AliyunSmsProvider.generateSignature(params, "POST", secret);
        String sig2 = AliyunSmsProvider.generateSignature(params, "POST", secret);

        assertNotNull(sig1);
        assertEquals(sig1, sig2);
        assertTrue(sig1.length() > 10);
    }

    @Test
    @DisplayName("测试批量多手机号发送以逗号拼装出站")
    void testSendBatchPhones_CommaSeparated() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody("{\"Code\":\"OK\",\"BizId\":\"BIZ_BATCH_01\",\"RequestId\":\"REQ_01\"}"));

        SmsChannel channel = SmsChannel.builder()
                .name("aliyun-batch")
                .supplier(SmsSupplier.ALIYUN)
                .accessKeyId("TEST_AK")
                .accessKeySecret("TEST_SK")
                .signName("阿腾科技")
                .endpoint(serverUrl)
                .build();

        SmsRequest request = SmsRequest.builder()
                .phones(List.of("13800000001", "13800000002", "13800000003"))
                .templateId("SMS_BATCH_001")
                .build();

        SmsResult result = aliyunSmsProvider.send(request, channel);
        assertTrue(result.isSuccess());

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        String body = recordedRequest.getBody().readUtf8();
        Map<String, String> formParams = parseFormBody(body);
        assertEquals("13800000001,13800000002,13800000003", formParams.get("PhoneNumbers"));
    }

    @Test
    @DisplayName("测试前置防卫与参数卫语句快速失败")
    void testSend_DefenseInDepth_Validation() {
        SmsChannel validChannel = SmsChannel.builder()
                .name("aliyun-test")
                .supplier(SmsSupplier.ALIYUN)
                .accessKeyId("AK")
                .accessKeySecret("SK")
                .signName("阿腾科技")
                .build();

        SmsRequest validRequest = SmsRequest.of("13800000000", "SMS_001");

        // 1. request 为空
        assertThrows(BusinessException.class, () -> aliyunSmsProvider.send(null, validChannel));

        // 2. channel 为空
        assertThrows(BusinessException.class, () -> aliyunSmsProvider.send(validRequest, null));

        // 3. 缺少 AK
        SmsChannel noAkChannel = SmsChannel.builder().name("c").accessKeySecret("SK").signName("S").build();
        assertThrows(BusinessException.class, () -> aliyunSmsProvider.send(validRequest, noAkChannel));

        // 4. 缺少 SK
        SmsChannel noSkChannel = SmsChannel.builder().name("c").accessKeyId("AK").signName("S").build();
        assertThrows(BusinessException.class, () -> aliyunSmsProvider.send(validRequest, noSkChannel));

        // 5. 缺少签名 (request 和 channel 均无)
        SmsChannel noSignChannel = SmsChannel.builder().name("c").accessKeyId("AK").accessKeySecret("SK").build();
        assertThrows(BusinessException.class, () -> aliyunSmsProvider.send(validRequest, noSignChannel));
    }

    private Map<String, String> parseFormBody(String body) {
        Map<String, String> map = new HashMap<>();
        String[] pairs = body.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                map.put(key, val);
            }
        }
        return map;
    }
}
