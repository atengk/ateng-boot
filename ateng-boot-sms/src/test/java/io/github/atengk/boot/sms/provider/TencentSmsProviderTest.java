package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sms.exception.SmsResultCode;
import io.github.atengk.boot.sms.exception.SmsSendException;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 腾讯云短信驱动单元测试 (SMS 3.0)
 * 基于 MockWebServer 模拟腾讯云 API 响应，全真校验 TC3-HMAC-SHA256 签名与有序参数推导
 * 100% 离线自洽，无外部物理网络依赖
 *
 * @author Ateng
 * @since 2026-11-02
 */
@DisplayName("腾讯云短信原生驱动契约与离线自洽测试")
class TencentSmsProviderTest {

    private MockWebServer mockWebServer;
    private TencentSmsProvider tencentSmsProvider;
    private String serverUrl;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        serverUrl = mockWebServer.url("/").toString();
        tencentSmsProvider = new TencentSmsProvider(RestClient.builder());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @Test
    @DisplayName("测试发送短信成功 (Happy Path) 并强断言出站请求头、TC3 签名与 JSON 请求体")
    void testSendSuccess_WithMockWebServer_HappyPath() throws Exception {
        // 1. 模拟腾讯云成功响应报文
        String responseJson = """
                {
                  "Response": {
                    "SendStatusSet": [
                      {
                        "SerialNo": "5000:10457106691570532457",
                        "PhoneNumber": "+8613800138000",
                        "Fee": 1,
                        "SessionContext": "BIZ_TX_001",
                        "Code": "Ok",
                        "Message": "send success",
                        "IsoCode": "CN"
                      }
                    ],
                    "RequestId": "a0aabda6-ac91-423e-8666-ba522ce1111a"
                  }
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(responseJson));

        // 2. 构造腾讯云渠道配置
        SmsChannel channel = SmsChannel.builder()
                .name("tencent-main")
                .supplier(SmsSupplier.TENCENT)
                .accessKeyId("AKIDz8kr61IfTxxxxxx")
                .accessKeySecret("Gu5t9xGARxxxxxx")
                .appId("1400000001")
                .signName("腾讯云")
                .endpoint(serverUrl)
                .build();

        // 3. 构造发信请求（国内普通号码自动补全 +86）
        SmsRequest request = SmsRequest.newBuilder("13800138000", "1234")
                .param("code", "999888")
                .param("minute", "5")
                .bizId("BIZ_TX_001")
                .build();

        // 4. 执行发信
        SmsResult result = tencentSmsProvider.send(request, channel);

        // 5. 校验发信领域结果
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("tencent-main", result.getChannel());
        assertEquals(SmsSupplier.TENCENT, result.getSupplier());
        assertEquals("5000:10457106691570532457", result.getBizId());
        assertEquals("a0aabda6-ac91-423e-8666-ba522ce1111a", result.getRequestId());
        assertEquals(1, result.getFee());

        // 6. 出站 HTTP 请求强断言
        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertEquals("POST", recordedRequest.getMethod());
        assertEquals("application/json", recordedRequest.getHeader("Content-Type"));
        assertEquals("SendSms", recordedRequest.getHeader("X-TC-Action"));
        assertEquals("2021-01-11", recordedRequest.getHeader("X-TC-Version"));
        assertNotNull(recordedRequest.getHeader("X-TC-Timestamp"));
        assertNotNull(recordedRequest.getHeader("X-TC-Region"));

        // 校验 Authorization 头部格式与 TC3 规范
        String authHeader = recordedRequest.getHeader("Authorization");
        assertNotNull(authHeader);
        assertTrue(authHeader.startsWith("TC3-HMAC-SHA256 Credential=AKIDz8kr61IfTxxxxxx/"));
        assertTrue(authHeader.contains("/sms/tc3_request"));
        assertTrue(authHeader.contains("SignedHeaders=content-type;host"));
        assertTrue(authHeader.contains("Signature="));

        // 校验 JSON 请求体结构
        String body = recordedRequest.getBody().readUtf8();
        @SuppressWarnings("unchecked")
        Map<String, Object> bodyMap = JsonUtil.parseObject(body, Map.class);
        assertNotNull(bodyMap);
        assertEquals("1400000001", bodyMap.get("SmsSdkAppId"));
        assertEquals("腾讯云", bodyMap.get("SignName"));
        assertEquals("1234", bodyMap.get("TemplateId"));
        assertEquals("BIZ_TX_001", bodyMap.get("SessionContext"));

        @SuppressWarnings("unchecked")
        List<String> phoneSet = (List<String>) bodyMap.get("PhoneNumberSet");
        assertEquals(1, phoneSet.size());
        assertEquals("+8613800138000", phoneSet.get(0));

        @SuppressWarnings("unchecked")
        List<String> paramSet = (List<String>) bodyMap.get("TemplateParamSet");
        assertNotNull(paramSet);
        assertTrue(paramSet.contains("999888"));
    }

    @Test
    @DisplayName("测试全局 Error 节点转译因果异常 (如含有敏感词 FailedOperation.ContainSensitiveWord)")
    void testSendFailure_ErrorNode_ThrowsSmsSendException() {
        String errorJson = """
                {
                  "Response": {
                    "Error": {
                      "Code": "FailedOperation.ContainSensitiveWord",
                      "Message": "短信内容中含有敏感词"
                    },
                    "RequestId": "err-sensitive-001"
                  }
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(errorJson));

        SmsChannel channel = SmsChannel.builder()
                .name("tencent-prod")
                .supplier(SmsSupplier.TENCENT)
                .accessKeyId("AKID_TEST")
                .accessKeySecret("SK_TEST")
                .appId("1400000002")
                .signName("腾讯云")
                .endpoint(serverUrl)
                .build();

        SmsRequest request = SmsRequest.of("13800000000", "9999");

        SmsSendException exception = assertThrows(SmsSendException.class, () -> tencentSmsProvider.send(request, channel));
        assertEquals("tencent-prod", exception.getChannel());
        assertEquals("FailedOperation.ContainSensitiveWord", exception.getRawCode());
        assertTrue(exception.getRawMessage().contains("敏感词"));
        assertEquals(SmsResultCode.SMS_SEND_ERROR, exception.getResultCode());
    }

    @Test
    @DisplayName("测试明细 SendStatusSet 状态失败转译 (如手机号在黑名单中)")
    void testSendFailure_SendStatusSetItemFailed_ThrowsSmsSendException() {
        String responseJson = """
                {
                  "Response": {
                    "SendStatusSet": [
                      {
                        "SerialNo": "",
                        "PhoneNumber": "+8613800138000",
                        "Fee": 0,
                        "Code": "FailedOperation.PhoneNumberInBlacklist",
                        "Message": "手机号在黑名单中"
                      }
                    ],
                    "RequestId": "err-blacklist-002"
                  }
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(responseJson));

        SmsChannel channel = SmsChannel.builder()
                .name("tencent-prod")
                .supplier(SmsSupplier.TENCENT)
                .accessKeyId("AKID_TEST")
                .accessKeySecret("SK_TEST")
                .appId("1400000002")
                .signName("腾讯云")
                .endpoint(serverUrl)
                .build();

        SmsRequest request = SmsRequest.of("13800000000", "9999");

        SmsSendException exception = assertThrows(SmsSendException.class, () -> tencentSmsProvider.send(request, channel));
        assertEquals("tencent-prod", exception.getChannel());
        assertEquals("FailedOperation.PhoneNumberInBlacklist", exception.getRawCode());
        assertTrue(exception.getRawMessage().contains("黑名单"));
    }

    @Test
    @DisplayName("测试网关鉴权失败 HTTP 400 响应与原始因果链保留")
    void testSendFailure_Http400GatewayError_ThrowsSmsSendException() {
        String errorJson = """
                {
                  "Response": {
                    "Error": {
                      "Code": "AuthFailure.SecretIdNotFound",
                      "Message": "The SecretId is not found."
                    },
                    "RequestId": "auth-fail-003"
                  }
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(errorJson));

        SmsChannel channel = SmsChannel.builder()
                .name("tencent-prod")
                .supplier(SmsSupplier.TENCENT)
                .accessKeyId("INVALID_ID")
                .accessKeySecret("INVALID_KEY")
                .appId("1400000003")
                .signName("腾讯云")
                .endpoint(serverUrl)
                .build();

        SmsRequest request = SmsRequest.of("13800000000", "9999");

        SmsSendException exception = assertThrows(SmsSendException.class, () -> tencentSmsProvider.send(request, channel));
        assertEquals("tencent-prod", exception.getChannel());
        assertEquals("AuthFailure.SecretIdNotFound", exception.getRawCode());
        assertNotNull(exception.getCause());
    }

    @Test
    @DisplayName("测试 E.164 手机号格式化清洗算法")
    void testFormatE164() {
        assertEquals("", TencentSmsProvider.formatE164(null));
        assertEquals("", TencentSmsProvider.formatE164("   "));

        // 国内普通 11 位手机号自动添加 +86
        assertEquals("+8613800138000", TencentSmsProvider.formatE164("13800138000"));

        // 已经带有 + 开头的国际手机号保持原样
        assertEquals("+8613800138000", TencentSmsProvider.formatE164("+8613800138000"));
        assertEquals("+12025550123", TencentSmsProvider.formatE164("+12025550123"));

        // 带有 86 开头但未带 + 号
        assertEquals("+8613800138000", TencentSmsProvider.formatE164("8613800138000"));

        // 带有连字符与空格清洗
        assertEquals("+8613800138000", TencentSmsProvider.formatE164("86-138 0013-8000"));
        assertEquals("+12025550123", TencentSmsProvider.formatE164("+1-202-555-0123"));
    }

    @Test
    @DisplayName("测试模板参数智能推导：paramOrder 配置映射、数字键与原生数组")
    void testTemplateParamDeduction() {
        // 1. 原生数组模式直通
        SmsParam arrayParam = SmsParam.ofArray("VAL_A", "VAL_B", "VAL_C");
        List<String> arrayResult = arrayParam.toOrderedArray(null);
        assertEquals(List.of("VAL_A", "VAL_B", "VAL_C"), arrayResult);

        // 2. 声明式 paramOrder 顺序映射
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("time", "10分钟");
        map.put("code", "123456");
        SmsParam mapParam = SmsParam.ofMap(map);

        // 指定按照 ["code", "time"] 顺序提取
        List<String> orderedResult = mapParam.toOrderedArray(List.of("code", "time"));
        assertEquals(List.of("123456", "10分钟"), orderedResult);

        // 3. 数字键自然排序
        Map<String, Object> numericMap = new LinkedHashMap<>();
        numericMap.put("2", "第二项");
        numericMap.put("1", "第一项");
        SmsParam numericParam = SmsParam.ofMap(numericMap);
        List<String> numResult = numericParam.toOrderedArray(null);
        assertEquals(List.of("第一项", "第二项"), numResult);
    }

    @Test
    @DisplayName("测试 TC3 签名计算自洽性与确定性")
    void testTC3Signature_DeterministicVerification() {
        String secretId = "AKIDTESTID001";
        String secretKey = "testsecretkey";
        String host = "sms.tencentcloudapi.com";
        String payload = "{\"PhoneNumberSet\":[\"+8613800138000\"],\"SmsSdkAppId\":\"1400000000\"}";
        long timestamp = 1730592000L;
        String date = "2026-11-02";

        String auth1 = TencentSmsProvider.generateAuthorization(secretId, secretKey, host, payload, timestamp, date);
        String auth2 = TencentSmsProvider.generateAuthorization(secretId, secretKey, host, payload, timestamp, date);

        assertNotNull(auth1);
        assertEquals(auth1, auth2);
        assertTrue(auth1.contains("Credential=" + secretId + "/" + date + "/sms/tc3_request"));
        assertTrue(auth1.contains("SignedHeaders=content-type;host"));
    }

    @Test
    @DisplayName("测试前置防卫与参数卫语句快速失败")
    void testDefenseInDepth() {
        SmsChannel validChannel = SmsChannel.builder()
                .name("tc-channel")
                .supplier(SmsSupplier.TENCENT)
                .accessKeyId("AK")
                .accessKeySecret("SK")
                .appId("1400000000")
                .signName("腾讯云")
                .build();

        SmsRequest validRequest = SmsRequest.of("13800000000", "1234");

        // 1. request 为空
        assertThrows(BusinessException.class, () -> tencentSmsProvider.send(null, validChannel));

        // 2. channel 为空
        assertThrows(BusinessException.class, () -> tencentSmsProvider.send(validRequest, null));

        // 3. 缺少 appId
        SmsChannel noAppIdChannel = SmsChannel.builder().name("c").accessKeyId("AK").accessKeySecret("SK").signName("S").build();
        assertThrows(BusinessException.class, () -> tencentSmsProvider.send(validRequest, noAppIdChannel));

        // 4. 缺少 ak
        SmsChannel noAkChannel = SmsChannel.builder().name("c").accessKeySecret("SK").appId("APP").signName("S").build();
        assertThrows(BusinessException.class, () -> tencentSmsProvider.send(validRequest, noAkChannel));

        // 5. 缺少 sk
        SmsChannel noSkChannel = SmsChannel.builder().name("c").accessKeyId("AK").appId("APP").signName("S").build();
        assertThrows(BusinessException.class, () -> tencentSmsProvider.send(validRequest, noSkChannel));

        // 6. 缺少签名 (request 和 channel 均无)
        SmsChannel noSignChannel = SmsChannel.builder().name("c").accessKeyId("AK").accessKeySecret("SK").appId("APP").build();
        assertThrows(BusinessException.class, () -> tencentSmsProvider.send(validRequest, noSignChannel));
    }
}
