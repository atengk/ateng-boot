package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.model.SmsBalanceResult;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsDeliveryQueryResult;
import io.github.atengk.boot.sms.model.SmsDeliveryStatus;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import io.github.atengk.boot.sms.template.SmsTemplate;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工单 #1149 主动发送状态回查 (querySendStatus) 与账户余额探查 (queryBalance) MockWebServer 契约测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("工单 #1149 主动回执查询与账户余额探查测试")
class SmsQueryAndBalanceTest {

    private MockWebServer mockWebServer;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    @DisplayName("阿里云 QuerySendDetails 签名校验与发送状态 (DELIVERED/UNKNOWN/异常保留 cause) 解析")
    void shouldQueryAliyunSendStatusCorrectly() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {
                          "Code": "OK",
                          "Message": "OK",
                          "TotalCount": 1,
                          "SmsSendDetailDTOs": {
                            "SmsSendDetailDTO": [
                              {
                                "PhoneNum": "13800138000",
                                "SendStatus": 3,
                                "ErrCode": "DELIVERED",
                                "Content": "【官方】您的验证码是123456",
                                "SendDate": "2026-11-21 10:00:00",
                                "ReceiveDate": "2026-11-21 10:00:02",
                                "OutId": "BIZ-ALI-001"
                              }
                            ]
                          }
                        }
                        """));

        AliyunSmsProvider provider = new AliyunSmsProvider();
        SmsChannel channel = SmsChannel.builder()
                .name("ali-ch")
                .supplier(SmsSupplier.ALIYUN)
                .accessKeyId("LTAI5tTest")
                .accessKeySecret("AliSecret123")
                .endpoint(mockWebServer.url("/").toString())
                .build();

        SmsDeliveryQueryResult result = provider.querySendStatus("13800138000", "BIZ-ALI-001", LocalDate.of(2026, 11, 21), channel);
        assertThat(result.isSupported()).isTrue();
        assertThat(result.getSupplier()).isEqualTo(SmsSupplier.ALIYUN);
        assertThat(result.getDeliveryStatus()).isEqualTo(SmsDeliveryStatus.DELIVERED);
        assertThat(result.getRawStatusCode()).isEqualTo("DELIVERED");
        assertThat(result.getBizId()).isEqualTo("BIZ-ALI-001");
        assertThat(result.getDetails()).hasSize(1);

        RecordedRequest req = mockWebServer.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("Action=QuerySendDetails");
        assertThat(body).contains("PhoneNumber=13800138000");
        assertThat(body).contains("SendDate=20261121");
        assertThat(body).contains("BizId=BIZ-ALI-001");
        assertThat(body).contains("Signature=");
    }

    @Test
    @DisplayName("腾讯云 PullSmsSendStatus TC3-HMAC-SHA256 签名校验与 SerialNo 匹配转译")
    void shouldQueryTencentSendStatusCorrectly() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {
                          "Response": {
                            "RequestId": "req-tx-1",
                            "PullSmsSendStatusSet": [
                              {
                                "UserReceiveTime": 1763712000,
                                "SerialNo": "SER-TX-999",
                                "PhoneNumber": "+8613800138001",
                                "SubscriberNumber": "13800138001",
                                "ReportStatus": "SUCCESS",
                                "Description": "用户接收成功"
                              }
                            ]
                          }
                        }
                        """));

        TencentSmsProvider provider = new TencentSmsProvider();
        SmsChannel channel = SmsChannel.builder()
                .name("tx-ch")
                .supplier(SmsSupplier.TENCENT)
                .accessKeyId("AKID_TX_123")
                .accessKeySecret("SK_TX_456")
                .appId("1400001234")
                .endpoint(mockWebServer.url("/").toString())
                .build();

        SmsDeliveryQueryResult result = provider.querySendStatus("13800138001", "SER-TX-999", null, channel);
        assertThat(result.isSupported()).isTrue();
        assertThat(result.getDeliveryStatus()).isEqualTo(SmsDeliveryStatus.DELIVERED);
        assertThat(result.getRawStatusCode()).isEqualTo("SUCCESS");
        assertThat(result.getRawStatusDesc()).isEqualTo("用户接收成功");

        RecordedRequest req = mockWebServer.takeRequest();
        assertThat(req.getHeader("X-TC-Action")).isEqualTo("PullSmsSendStatus");
        assertThat(req.getHeader("Authorization")).startsWith("TC3-HMAC-SHA256 Credential=AKID_TX_123/");
    }

    @Test
    @DisplayName("云片与助通科技账户余额只读探查契约验证（含异常保留 cause）")
    void shouldQueryBalanceForYunpianAndZhutong() throws Exception {
        // 1. 云片 /v2/user/get.json
        mockWebServer.enqueue(new MockResponse()
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"nick\":\"test\",\"balance\":368.50}"));
        // 2. 助通 /v2/balance
        mockWebServer.enqueue(new MockResponse()
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"code\":200,\"msg\":\"success\",\"sumSms\":12500}"));
        // 3. 助通失败
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(401)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"code\":4001,\"msg\":\"invalid username or password\"}"));

        YunpianSmsProvider yunpian = new YunpianSmsProvider();
        SmsChannel ypCh = SmsChannel.builder()
                .name("yp-ch")
                .supplier(SmsSupplier.YUNPIAN)
                .accessKeyId("YP_APIKEY_ABC")
                .endpoint(mockWebServer.url("/v2").toString())
                .build();
        SmsBalanceResult ypBalance = yunpian.queryBalance(ypCh);
        assertThat(ypBalance.isSupported()).isTrue();
        assertThat(ypBalance.getBalance()).isEqualByComparingTo(new BigDecimal("368.50"));
        assertThat(ypBalance.getUnit()).isEqualTo("CNY");

        RecordedRequest ypReq = mockWebServer.takeRequest();
        assertThat(ypReq.getPath()).isEqualTo("/v2/user/get.json");
        assertThat(ypReq.getBody().readUtf8()).contains("apikey=YP_APIKEY_ABC");

        ZhutongSmsProvider zhutong = new ZhutongSmsProvider();
        SmsChannel ztCh = SmsChannel.builder()
                .name("zt-ch")
                .supplier(SmsSupplier.ZHUTONG)
                .accessKeyId("zt_user")
                .accessKeySecret("zt_pass")
                .endpoint(mockWebServer.url("/").toString())
                .build();
        SmsBalanceResult ztBalance = zhutong.queryBalance(ztCh);
        assertThat(ztBalance.isSupported()).isTrue();
        assertThat(ztBalance.getRemainingCount()).isEqualTo(12500L);
        assertThat(ztBalance.getUnit()).isEqualTo("COUNT");

        RecordedRequest ztReq = mockWebServer.takeRequest();
        assertThat(ztReq.getPath()).isEqualTo("/v2/balance");
        String ztPayload = ztReq.getBody().readUtf8();
        assertThat(ztPayload).contains("\"username\":\"zt_user\"").contains("\"tKey\"").contains("\"password\"");

        assertThatThrownBy(() -> zhutong.queryBalance(ztCh))
                .isInstanceOf(SmsSendException.class)
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("不支持主动查询或余额探查的厂商返回 supported=false 明确语义契约，MockSmsProvider 支持双查")
    void shouldReturnExplicitUnsupportedContractWithoutNpe() {
        SmsProperties props = new SmsProperties();
        SmsChannelRegistry registry = new SmsChannelRegistry(props);
        registry.register(SmsChannel.builder()
                .name("baidu-ch")
                .supplier(SmsSupplier.BAIDU)
                .accessKeyId("AK")
                .accessKeySecret("SK")
                .build());
        registry.register(SmsChannel.builder()
                .name("ali-ch")
                .supplier(SmsSupplier.ALIYUN)
                .accessKeyId("AK")
                .accessKeySecret("SK")
                .build());
        registry.register(SmsChannel.builder()
                .name("mock-ch")
                .supplier(SmsSupplier.MOCK)
                .defaultTemplateId("TPL_1")
                .build());

        MockSmsProvider mockProvider = new MockSmsProvider(props.getGuard());
        SmsTemplate template = new SmsTemplate(
                props,
                List.of(mockProvider, new BaiduSmsProvider(), new AliyunSmsProvider()),
                registry,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null
        );

        // 百度不支持回执与余额查询 -> 清晰返回 supported=false
        SmsDeliveryQueryResult bdStatus = template.querySendStatus("baidu-ch", "13800001234", "BIZ1", null);
        assertThat(bdStatus.isSupported()).isFalse();
        assertThat(bdStatus.getDeliveryStatus()).isEqualTo(SmsDeliveryStatus.UNKNOWN);
        assertThat(bdStatus.getMessage()).isNotBlank();

        SmsBalanceResult aliBalance = template.queryBalance("ali-ch");
        assertThat(aliBalance.isSupported()).isFalse();
        assertThat(aliBalance.getMessage()).contains("暂不支持");

        // MockSmsProvider 发信后可查送达及模拟余额
        SmsResult sent = template.send(SmsRequest.of("13800005555", "TPL_1").withChannel("mock-ch"));
        SmsDeliveryQueryResult mockQuery = template.querySendStatus("mock-ch", "13800005555", sent.getBizId(), null);
        assertThat(mockQuery.isSupported()).isTrue();
        assertThat(mockQuery.getDeliveryStatus()).isEqualTo(SmsDeliveryStatus.DELIVERED);
        assertThat(template.queryBalance("mock-ch").getBalance()).isEqualByComparingTo("1000.00");
    }
}
