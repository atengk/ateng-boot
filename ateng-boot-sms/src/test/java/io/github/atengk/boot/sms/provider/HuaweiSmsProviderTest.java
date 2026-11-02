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
 * 华为云短信驱动单元测试 (MSGSMS)
 * 基于 MockWebServer 模拟华为云 API 响应，全真校验 WSSE UsernameToken 摘要鉴权与表单协议
 * 100% 离线自洽，无外部网络依赖
 *
 * @author Ateng
 * @since 2026-11-02
 */
@DisplayName("华为云短信原生驱动契约与离线自洽测试")
class HuaweiSmsProviderTest {

    private MockWebServer mockWebServer;
    private HuaweiSmsProvider huaweiSmsProvider;
    private String serverUrl;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        serverUrl = mockWebServer.url("/").toString();
        huaweiSmsProvider = new HuaweiSmsProvider(RestClient.builder());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @Test
    @DisplayName("测试发送短信成功 (Happy Path) 并强断言 WSSE 摘要头与表单参数")
    void testSendSuccess_WithMockWebServer_HappyPath() throws Exception {
        // 1. 模拟华为云成功响应
        String responseJson = """
                {
                  "result": [
                    {
                      "total": 1,
                      "originTo": "13800138000",
                      "createTime": "2026-11-02T12:00:00Z",
                      "from": "106900000001",
                      "smsMsgId": "2bff4b98-38bb-4a7b-a246-8e5e8e815e1f_13800138000",
                      "status": "000000",
                      "countryId": "CN"
                    }
                  ],
                  "code": "000000",
                  "description": "Success"
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(responseJson));

        // 2. 构造华为云渠道配置
        SmsChannel channel = SmsChannel.builder()
                .name("huawei-main")
                .supplier(SmsSupplier.HUAWEI)
                .accessKeyId("app_key_123456")
                .accessKeySecret("app_secret_999999")
                .appId("106900000001")
                .signName("华为云短信")
                .endpoint(serverUrl)
                .build();

        // 3. 构造发信请求
        SmsRequest request = SmsRequest.newBuilder("13800138000", "HW_TPL_001")
                .param("code", "123456")
                .bizId("CALLBACK_001")
                .build();

        // 4. 执行发信
        SmsResult result = huaweiSmsProvider.send(request, channel);

        // 5. 校验发信领域模型
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("huawei-main", result.getChannel());
        assertEquals(SmsSupplier.HUAWEI, result.getSupplier());
        assertEquals("2bff4b98-38bb-4a7b-a246-8e5e8e815e1f_13800138000", result.getBizId());
        assertEquals(1, result.getFee());

        // 6. 校验出站 HTTP 请求
        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertEquals("POST", recordedRequest.getMethod());
        assertTrue(recordedRequest.getHeader("Content-Type").contains("application/x-www-form-urlencoded"));
        assertTrue(recordedRequest.getPath().contains("/sms/batchSendSms/v1"));

        // 校验 WSSE Authorization 与 X-WSSE 请求头
        assertEquals("WSSE realm=\"SDP\",profile=\"UsernameToken\",type=\"Appkey\"", recordedRequest.getHeader("Authorization"));
        String wsseHeader = recordedRequest.getHeader("X-WSSE");
        assertNotNull(wsseHeader);
        assertTrue(wsseHeader.contains("UsernameToken Username=\"app_key_123456\""));
        assertTrue(wsseHeader.contains("PasswordDigest="));
        assertTrue(wsseHeader.contains("Nonce="));
        assertTrue(wsseHeader.contains("Created="));

        // 校验 Form 表单请求体
        String requestBody = recordedRequest.getBody().readUtf8();
        Map<String, String> formParams = parseFormBody(requestBody);
        assertEquals("106900000001", formParams.get("from"));
        assertEquals("13800138000", formParams.get("to"));
        assertEquals("HW_TPL_001", formParams.get("templateId"));
        assertEquals("华为云短信", formParams.get("signature"));
        assertEquals("CALLBACK_001", formParams.get("statusCallback"));
        assertNotNull(formParams.get("templateParas"));
        assertTrue(formParams.get("templateParas").contains("123456"));
    }

    @Test
    @DisplayName("测试全局非 000000 响应错误转译 (如鉴权失败 E200028)")
    void testSendFailure_GlobalErrorCode_ThrowsSmsSendException() {
        String errorJson = """
                {
                  "code": "E200028",
                  "description": "Authentication failed: invalid appKey or appSecret."
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(errorJson));

        SmsChannel channel = SmsChannel.builder()
                .name("huawei-prod")
                .supplier(SmsSupplier.HUAWEI)
                .accessKeyId("INVALID_KEY")
                .accessKeySecret("INVALID_SECRET")
                .appId("106900000001")
                .endpoint(serverUrl)
                .build();

        SmsRequest request = SmsRequest.of("13900000000", "HW_TPL_002");

        SmsSendException exception = assertThrows(SmsSendException.class, () -> huaweiSmsProvider.send(request, channel));
        assertEquals("huawei-prod", exception.getChannel());
        assertEquals("E200028", exception.getRawCode());
        assertTrue(exception.getRawMessage().contains("Authentication failed"));
        assertEquals(SmsResultCode.SMS_SEND_ERROR, exception.getResultCode());
    }

    @Test
    @DisplayName("测试明细条目 status 非 000000 错误转译 (如非法手机号 E200024)")
    void testSendFailure_ItemStatusFailed_ThrowsSmsSendException() {
        String responseJson = """
                {
                  "result": [
                    {
                      "total": 0,
                      "originTo": "13800138000",
                      "status": "E200024",
                      "smsMsgId": ""
                    }
                  ],
                  "code": "000000",
                  "description": "Success"
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(responseJson));

        SmsChannel channel = SmsChannel.builder()
                .name("huawei-prod")
                .supplier(SmsSupplier.HUAWEI)
                .accessKeyId("app_key")
                .accessKeySecret("app_secret")
                .appId("106900000001")
                .endpoint(serverUrl)
                .build();

        SmsRequest request = SmsRequest.of("13800138000", "HW_TPL_003");

        SmsSendException exception = assertThrows(SmsSendException.class, () -> huaweiSmsProvider.send(request, channel));
        assertEquals("huawei-prod", exception.getChannel());
        assertEquals("E200024", exception.getRawCode());
    }

    @Test
    @DisplayName("测试网关鉴权失败 HTTP 400 响应与原始因果链保留")
    void testSendFailure_Http400GatewayError_ThrowsSmsSendException() {
        String errorJson = """
                {
                  "code": "E200015",
                  "description": "Request header is invalid: X-WSSE is required."
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(errorJson));

        SmsChannel channel = SmsChannel.builder()
                .name("huawei-prod")
                .supplier(SmsSupplier.HUAWEI)
                .accessKeyId("app_key")
                .accessKeySecret("app_secret")
                .appId("106900000001")
                .endpoint(serverUrl)
                .build();

        SmsRequest request = SmsRequest.of("13800138000", "HW_TPL_004");

        SmsSendException exception = assertThrows(SmsSendException.class, () -> huaweiSmsProvider.send(request, channel));
        assertEquals("huawei-prod", exception.getChannel());
        assertEquals("E200015", exception.getRawCode());
        assertNotNull(exception.getCause());
    }

    @Test
    @DisplayName("测试 WSSE UsernameToken 摘要计算确定性")
    void testBuildWsseHeader_DeterministicVerification() {
        String appKey = "test_key_001";
        String appSecret = "test_secret_001";
        String nonce = "c1a2b3d4e5f6";
        String created = "2026-11-02T12:00:00Z";

        String wsse1 = HuaweiSmsProvider.buildWsseHeader(appKey, appSecret, nonce, created);
        String wsse2 = HuaweiSmsProvider.buildWsseHeader(appKey, appSecret, nonce, created);

        assertNotNull(wsse1);
        assertEquals(wsse1, wsse2);
        assertTrue(wsse1.contains("Username=\"" + appKey + "\""));
        assertTrue(wsse1.contains("Nonce=\"" + nonce + "\""));
        assertTrue(wsse1.contains("Created=\"" + created + "\""));
        assertTrue(wsse1.contains("PasswordDigest="));
    }

    @Test
    @DisplayName("测试多手机号批量发送时以逗号分隔出站")
    void testSendBatchPhones_CommaSeparated() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody("""
                        {
                          "result": [
                            {"total": 1, "status": "000000", "smsMsgId": "msg_01"},
                            {"total": 1, "status": "000000", "smsMsgId": "msg_02"}
                          ],
                          "code": "000000",
                          "description": "Success"
                        }
                        """));

        SmsChannel channel = SmsChannel.builder()
                .name("huawei-batch")
                .supplier(SmsSupplier.HUAWEI)
                .accessKeyId("app_key")
                .accessKeySecret("app_secret")
                .appId("106900000001")
                .endpoint(serverUrl)
                .build();

        SmsRequest request = SmsRequest.builder()
                .phones(List.of("13800000001", "13800000002"))
                .templateId("HW_BATCH_TPL")
                .build();

        SmsResult result = huaweiSmsProvider.send(request, channel);
        assertTrue(result.isSuccess());
        assertEquals(2, result.getFee());

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        Map<String, String> formParams = parseFormBody(recordedRequest.getBody().readUtf8());
        assertEquals("13800000001,13800000002", formParams.get("to"));
    }

    @Test
    @DisplayName("测试前置防卫与参数卫语句快速失败")
    void testDefenseInDepth() {
        SmsChannel validChannel = SmsChannel.builder()
                .name("hw-channel")
                .supplier(SmsSupplier.HUAWEI)
                .accessKeyId("AK")
                .accessKeySecret("SK")
                .appId("106900001")
                .signName("华为云")
                .build();

        SmsRequest validRequest = SmsRequest.of("13800000000", "TPL_001");

        // 1. request 为空
        assertThrows(BusinessException.class, () -> huaweiSmsProvider.send(null, validChannel));

        // 2. channel 为空
        assertThrows(BusinessException.class, () -> huaweiSmsProvider.send(validRequest, null));

        // 3. 缺少 appId (from 通道号)
        SmsChannel noAppIdChannel = SmsChannel.builder().name("c").accessKeyId("AK").accessKeySecret("SK").build();
        assertThrows(BusinessException.class, () -> huaweiSmsProvider.send(validRequest, noAppIdChannel));

        // 4. 缺少 ak
        SmsChannel noAkChannel = SmsChannel.builder().name("c").accessKeySecret("SK").appId("APP").build();
        assertThrows(BusinessException.class, () -> huaweiSmsProvider.send(validRequest, noAkChannel));

        // 5. 缺少 sk
        SmsChannel noSkChannel = SmsChannel.builder().name("c").accessKeyId("AK").appId("APP").build();
        assertThrows(BusinessException.class, () -> huaweiSmsProvider.send(validRequest, noSkChannel));
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
