package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.model.*;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 天翼云短信提供商驱动单元测试
 * 基于 MockWebServer 模拟天翼云网关响应，测试 CT EOP 协议签名、E.164 号码规范化与异常转译
 * 100% 离线自洽，无外部网络依赖
 *
 * @author Ateng
 * @since 2026-11-02
 */
@DisplayName("天翼云短信原生驱动契约与离线自洽测试")
class CtyunSmsProviderTest {

    private MockWebServer mockWebServer;
    private CtyunSmsProvider ctyunSmsProvider;
    private String serverUrl;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        serverUrl = mockWebServer.url("/").toString();
        ctyunSmsProvider = new CtyunSmsProvider(RestClient.builder());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @Test
    @DisplayName("测试单条发信成功 (Happy Path) 并校验 EOP 鉴权头与 JSON 报文")
    void testSendSuccess_HappyPath() throws Exception {
        String mockResponse = """
                {
                  "code": "OK",
                  "message": "发送成功",
                  "bizId": "CT_BIZ_987654",
                  "requestId": "REQ_CT_001"
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(mockResponse));

        SmsChannel channel = SmsChannel.builder()
                .name("ctyun-main")
                .supplier(SmsSupplier.CTYUN)
                .accessKeyId("mock-ctyun-ak")
                .accessKeySecret("mock-ctyun-sk")
                .signName("天翼测试")
                .endpoint(serverUrl)
                .build();

        SmsRequest request = SmsRequest.builder()
                .phone("13800138000")
                .templateId("SMS_1002")
                .params(SmsParam.of("code", "654321"))
                .build();

        SmsResult result = ctyunSmsProvider.send(request, channel);

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("CT_BIZ_987654", result.getBizId());
        assertEquals("REQ_CT_001", result.getRequestId());
        assertEquals(1, result.getFee());
        assertEquals(SmsSupplier.CTYUN, result.getSupplier());

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertEquals("POST", recordedRequest.getMethod());

        // 强断言 CT EOP 协议三道鉴权请求头
        String eopReqId = recordedRequest.getHeader("ctyun-eop-request-id");
        String eopDate = recordedRequest.getHeader("Eop-date");
        String eopAuth = recordedRequest.getHeader("Eop-Authorization");

        assertNotNull(eopReqId);
        assertNotNull(eopDate);
        assertNotNull(eopAuth);

        assertTrue(eopAuth.startsWith("mock-ctyun-ak Headers=ctyun-eop-request-id;eop-date Signature="));
        assertTrue(eopDate.endsWith("Z"));

        // 强断言 JSON 请求体参数
        Map<String, Object> bodyMap = JsonUtil.parseMap(recordedRequest.getBody().readUtf8());
        assertEquals("SendSms", bodyMap.get("action"));
        assertEquals("+8613800138000", bodyMap.get("phoneNumber"));
        assertEquals("天翼测试", bodyMap.get("signName"));
        assertEquals("SMS_1002", bodyMap.get("templateCode"));
        assertTrue(String.valueOf(bodyMap.get("templateParam")).contains("654321"));
    }

    @Test
    @DisplayName("测试批量发信多手机号格式化与逗号分隔拼接")
    void testSendBatchPhones() throws Exception {
        String mockResponse = """
                {
                  "code": "OK",
                  "message": "发送成功",
                  "bizId": "CT_BATCH_001",
                  "requestId": "REQ_002"
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(mockResponse));

        SmsChannel channel = SmsChannel.builder()
                .name("ctyun-batch")
                .supplier(SmsSupplier.CTYUN)
                .accessKeyId("mock-ak")
                .accessKeySecret("mock-sk")
                .signName("天翼批次")
                .endpoint(serverUrl)
                .build();

        SmsRequest request = SmsRequest.builder()
                .phones(List.of("13800138001", "+8613800138002", "0085298765432"))
                .templateId("SMS_BATCH_TPL")
                .build();

        SmsResult result = ctyunSmsProvider.send(request, channel);

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals(3, result.getFee());

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        Map<String, Object> bodyMap = JsonUtil.parseMap(recordedRequest.getBody().readUtf8());
        assertEquals("+8613800138001,+8613800138002,+85298765432", bodyMap.get("phoneNumber"));
    }

    @Test
    @DisplayName("测试天翼云业务错误码返回时转译为受管因果异常")
    void testBusinessCodeError() {
        String mockError = """
                {
                  "code": "isv.BUSINESS_LIMIT_CONTROL",
                  "message": "短信发送频率超限"
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(mockError));

        SmsChannel channel = SmsChannel.builder()
                .name("ctyun-err")
                .supplier(SmsSupplier.CTYUN)
                .accessKeyId("mock-ak")
                .accessKeySecret("mock-sk")
                .signName("测试签名")
                .endpoint(serverUrl)
                .build();

        SmsRequest request = SmsRequest.of("13800138000", "SMS_TPL");

        SmsSendException ex = assertThrows(SmsSendException.class, () -> ctyunSmsProvider.send(request, channel));
        assertEquals("isv.BUSINESS_LIMIT_CONTROL", ex.getRawCode());
        assertTrue(ex.getMessage().contains("短信发送频率超限"));
    }

    @Test
    @DisplayName("测试 HTTP 500 网关故障异常捕获与因果保留")
    void testHttp500GatewayError() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"));

        SmsChannel channel = SmsChannel.builder()
                .name("ctyun-500")
                .supplier(SmsSupplier.CTYUN)
                .accessKeyId("mock-ak")
                .accessKeySecret("mock-sk")
                .signName("测试签名")
                .endpoint(serverUrl)
                .build();

        SmsRequest request = SmsRequest.of("13800138000", "SMS_TPL");

        SmsSendException ex = assertThrows(SmsSendException.class, () -> ctyunSmsProvider.send(request, channel));
        assertEquals("HTTP_500", ex.getRawCode());
        assertNotNull(ex.getCause());
    }

    @Test
    @DisplayName("测试前置非空防御卫语句")
    void testPreconditions() {
        SmsRequest request = SmsRequest.of("13800138000", "SMS_TPL");

        // 缺 AK
        SmsChannel chNoAk = SmsChannel.builder().name("c").supplier(SmsSupplier.CTYUN).accessKeySecret("sk").signName("sign").build();
        assertThrows(BusinessException.class, () -> ctyunSmsProvider.send(request, chNoAk));

        // 缺 SK
        SmsChannel chNoSk = SmsChannel.builder().name("c").supplier(SmsSupplier.CTYUN).accessKeyId("ak").signName("sign").build();
        assertThrows(BusinessException.class, () -> ctyunSmsProvider.send(request, chNoSk));

        // 缺签名
        SmsChannel chNoSign = SmsChannel.builder().name("c").supplier(SmsSupplier.CTYUN).accessKeyId("ak").accessKeySecret("sk").build();
        assertThrows(BusinessException.class, () -> ctyunSmsProvider.send(request, chNoSign));

        // 缺模板
        SmsChannel validChannel = SmsChannel.builder().name("c").supplier(SmsSupplier.CTYUN).accessKeyId("ak").accessKeySecret("sk").signName("sign").build();
        assertThrows(BusinessException.class, () -> ctyunSmsProvider.send(SmsRequest.builder().phones(List.of("13800138000")).build(), validChannel));
    }

    @Test
    @DisplayName("测试 formatPhone 号码清洗格式化")
    void testFormatPhone() {
        assertEquals("", CtyunSmsProvider.formatPhone(null));
        assertEquals("+8613800138000", CtyunSmsProvider.formatPhone("13800138000"));
        assertEquals("+8613800138000", CtyunSmsProvider.formatPhone(" 138-0013-8000 "));
        assertEquals("+8613800138000", CtyunSmsProvider.formatPhone("8613800138000"));
        assertEquals("+85212345678", CtyunSmsProvider.formatPhone("+85212345678"));
        assertEquals("+85212345678", CtyunSmsProvider.formatPhone("0085212345678"));
    }

    @Test
    @DisplayName("测试 generateAuthHeaders 签名生成算法完整性")
    void testGenerateAuthHeaders() {
        Map<String, String> headers = CtyunSmsProvider.generateAuthHeaders(
                "test-ak",
                "test-sk",
                "{\"action\":\"SendSms\"}",
                "uuid-1234",
                "20261102T120000Z",
                "20261102"
        );

        assertNotNull(headers);
        assertEquals("uuid-1234", headers.get("ctyun-eop-request-id"));
        assertEquals("20261102T120000Z", headers.get("Eop-date"));
        String auth = headers.get("Eop-Authorization");
        assertNotNull(auth);
        assertTrue(auth.startsWith("test-ak Headers=ctyun-eop-request-id;eop-date Signature="));
    }
}
