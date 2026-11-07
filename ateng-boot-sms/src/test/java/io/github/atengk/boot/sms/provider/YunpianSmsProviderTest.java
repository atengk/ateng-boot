package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.sms.exception.SmsResultCode;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 云片网短信提供商驱动单元测试
 * 基于 MockWebServer 模拟云片网网关响应，测试 HTTP Form 协议、模板变量格式化与异常转译
 * 100% 离线自洽，无外部网络依赖
 *
 * @author Ateng
 * @since 2026-11-02
 */
@DisplayName("云片网短信原生驱动契约与离线自洽测试")
class YunpianSmsProviderTest {

    private MockWebServer mockWebServer;
    private YunpianSmsProvider yunpianSmsProvider;
    private String serverUrl;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        serverUrl = mockWebServer.url("/").toString();
        yunpianSmsProvider = new YunpianSmsProvider(RestClient.builder());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @Test
    @DisplayName("测试单条短信发送成功并强断言出站 Form 参数及字段")
    void testSendSingleSuccess() throws Exception {
        String mockResponse = """
                {
                  "code": 0,
                  "msg": "发送成功",
                  "count": 1,
                  "fee": 0.05,
                  "unit": "RMB",
                  "mobile": "13800138000",
                  "sid": 331022816
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(mockResponse));

        SmsChannel channel = SmsChannel.builder()
                .name("yunpian-test")
                .supplier(SmsSupplier.YUNPIAN)
                .accessKeyId("mock-yunpian-apikey")
                .endpoint(serverUrl)
                .statusCallback("https://example.com/callback")
                .build();

        SmsRequest request = SmsRequest.builder()
                .phone("13800138000")
                .templateId("1001")
                .params(SmsParam.of("code", "123456"))
                .bizId("BIZ_YP_001")
                .build();

        SmsResult result = yunpianSmsProvider.send(request, channel);

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("331022816", result.getBizId());
        assertEquals("331022816", result.getRequestId());
        assertEquals(1, result.getFee());
        assertEquals(SmsSupplier.YUNPIAN, result.getSupplier());

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertEquals("POST", recordedRequest.getMethod());
        assertTrue(recordedRequest.getPath().contains("tpl_single_send.json"));

        Map<String, String> formParams = parseFormBody(recordedRequest.getBody().readUtf8());
        assertEquals("mock-yunpian-apikey", formParams.get("apikey"));
        assertEquals("13800138000", formParams.get("mobile"));
        assertEquals("1001", formParams.get("tpl_id"));
        assertEquals("#code#=123456", formParams.get("tpl_value"));
        assertEquals("https://example.com/callback", formParams.get("callback_url"));
        assertEquals("BIZ_YP_001", formParams.get("uid"));
    }

    @Test
    @DisplayName("测试批量短信发送成功 (tpl_batch_send.json)")
    void testSendBatchSuccess() throws Exception {
        String mockResponse = """
                {
                  "total_count": 2,
                  "total_fee": "0.10",
                  "unit": "RMB",
                  "data": [
                    {
                      "code": 0,
                      "msg": "发送成功",
                      "count": 1,
                      "fee": 0.05,
                      "unit": "RMB",
                      "mobile": "13800138001",
                      "sid": 10001
                    },
                    {
                      "code": 0,
                      "msg": "发送成功",
                      "count": 1,
                      "fee": 0.05,
                      "unit": "RMB",
                      "mobile": "13800138002",
                      "sid": 10002
                    }
                  ]
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(mockResponse));

        SmsChannel channel = SmsChannel.builder()
                .name("yunpian-batch")
                .supplier(SmsSupplier.YUNPIAN)
                .accessKeyId("mock-yunpian-apikey")
                .endpoint(serverUrl)
                .build();

        SmsRequest request = SmsRequest.builder()
                .phones(List.of("13800138001", "13800138002"))
                .templateId("2002")
                .params(SmsParam.of("time", "10分钟"))
                .build();

        SmsResult result = yunpianSmsProvider.send(request, channel);

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("10001", result.getBizId());
        assertEquals(2, result.getFee());
        assertEquals(SmsSupplier.YUNPIAN, result.getSupplier());

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertTrue(recordedRequest.getPath().contains("tpl_batch_send.json"));

        Map<String, String> formParams = parseFormBody(recordedRequest.getBody().readUtf8());
        assertEquals("13800138001,13800138002", formParams.get("mobile"));
    }

    @Test
    @DisplayName("测试业务状态码非0时抛出受管因果异常")
    void testSendFailure_BusinessCodeError() {
        String errorJson = """
                {
                  "code": 2,
                  "msg": "请求参数缺失",
                  "detail": "模板ID不能为空"
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(errorJson));

        SmsChannel channel = SmsChannel.builder()
                .name("yunpian-err")
                .supplier(SmsSupplier.YUNPIAN)
                .accessKeyId("mock-key")
                .endpoint(serverUrl)
                .build();

        SmsRequest request = SmsRequest.of("13800138000", "1001");

        SmsSendException ex = assertThrows(SmsSendException.class, () -> yunpianSmsProvider.send(request, channel));
        assertEquals("2", ex.getRawCode());
        assertEquals("请求参数缺失", ex.getRawMessage());
        assertTrue(ex.getMessage().contains("模板ID不能为空"));
    }

    @Test
    @DisplayName("测试网关返回 HTTP 400 时的异常转译与原因链条保留")
    void testHttp400GatewayError() {
        String errorJson = """
                {
                  "code": 1,
                  "msg": "请求过于频繁",
                  "detail": "超出频次限制"
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(errorJson));

        SmsChannel channel = SmsChannel.builder()
                .name("yunpian-400")
                .supplier(SmsSupplier.YUNPIAN)
                .accessKeyId("mock-key")
                .endpoint(serverUrl)
                .build();

        SmsRequest request = SmsRequest.of("13800138000", "1001");

        SmsSendException ex = assertThrows(SmsSendException.class, () -> yunpianSmsProvider.send(request, channel));
        assertEquals("1", ex.getRawCode());
        assertNotNull(ex.getCause());
    }

    @Test
    @DisplayName("测试前置参数防卫校验")
    void testPreconditionDefense() {
        SmsRequest request = SmsRequest.of("13800138000", "1001");

        // 缺少 apikey
        SmsChannel chNoKey = SmsChannel.builder().name("yp").supplier(SmsSupplier.YUNPIAN).endpoint(serverUrl).build();
        assertThrows(BusinessException.class, () -> yunpianSmsProvider.send(request, chNoKey));

        SmsChannel validChannel = SmsChannel.builder().name("yp").supplier(SmsSupplier.YUNPIAN).accessKeyId("key").endpoint(serverUrl).build();
        // 缺少 phone
        assertThrows(BusinessException.class, () -> yunpianSmsProvider.send(SmsRequest.builder().templateId("1").build(), validChannel));
        // 缺少 templateId
        assertThrows(BusinessException.class, () -> yunpianSmsProvider.send(SmsRequest.builder().phones(List.of("13800138000")).build(), validChannel));
    }

    @Test
    @DisplayName("测试 formatTplValue 格式化逻辑")
    void testFormatTplValue() {
        assertEquals("", YunpianSmsProvider.formatTplValue(null));
        assertEquals("", YunpianSmsProvider.formatTplValue(SmsParam.create()));

        SmsParam param = SmsParam.of("code", "123456").add("#name#", "张三");
        String formatted = YunpianSmsProvider.formatTplValue(param);

        assertTrue(formatted.contains("#code#=123456"));
        assertTrue(formatted.contains("#name#=%E5%BC%A0%E4%B8%89"));
        assertFalse(formatted.contains("##name##"));
    }

    private static Map<String, String> parseFormBody(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isBlank()) {
            return map;
        }
        String[] pairs = body.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String v = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            map.put(k, v);
        }
        return map;
    }
}
