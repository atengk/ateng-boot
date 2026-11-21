package io.github.atengk.boot.sms;

import io.github.atengk.boot.sms.exception.SmsException;
import io.github.atengk.boot.sms.exception.SmsIdempotentException;
import io.github.atengk.boot.sms.exception.SmsResultCode;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.guard.InMemorySmsIdempotentGuard;
import io.github.atengk.boot.sms.model.BatchSmsRequest;
import io.github.atengk.boot.sms.model.BatchSmsResult;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsParam;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsChannelProperties;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.properties.SmsProxyProperties;
import io.github.atengk.boot.sms.provider.AbstractHttpSmsProvider;
import io.github.atengk.boot.sms.provider.AliyunSmsProvider;
import io.github.atengk.boot.sms.provider.CtyunSmsProvider;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import io.github.atengk.boot.sms.provider.SmsProvider;
import io.github.atengk.boot.sms.provider.TencentSmsProvider;
import io.github.atengk.boot.sms.provider.YunpianSmsProvider;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import io.github.atengk.boot.sms.template.SmsTemplate;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工单 #1145 多自定义 providerCode 路由、HTTP 代理、国际区号与纯文本直发集成契约测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("工单 #1145 providerCode 多驱动路由、代理、国际区号与纯文本直发验证")
class SmsTicket1145RoutingProxyPlainTextTest {

    private MockWebServer mockWebServer;

    @AfterEach
    void tearDown() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
            mockWebServer = null;
        }
    }

    @Test
    @DisplayName("多自定义驱动共存验证：同为 SmsSupplier.CUSTOM 但 providerCode 不同的多个驱动可精确独立路由与容灾")
    void shouldRouteMultipleCustomProvidersIndependentlyByProviderCode() {
        List<String> trace = new CopyOnWriteArrayList<>();

        SmsProvider gwAlpha = new SmsProvider() {
            @Override
            public SmsSupplier getSupplier() {
                return SmsSupplier.CUSTOM;
            }

            @Override
            public String getProviderCode() {
                return "gw-alpha";
            }

            @Override
            public boolean supportsPlainText() {
                return true;
            }

            @Override
            public SmsResult send(SmsRequest request, SmsChannel channel) {
                trace.add("alpha:" + channel.getName() + ":" + request.getContent());
                return SmsResult.success(channel.getName(), SmsSupplier.CUSTOM, "BIZ_ALPHA", "REQ_ALPHA", 1);
            }
        };

        SmsProvider gwBeta = new SmsProvider() {
            @Override
            public SmsSupplier getSupplier() {
                return SmsSupplier.CUSTOM;
            }

            @Override
            public String getProviderCode() {
                return "gw-beta";
            }

            @Override
            public SmsResult send(SmsRequest request, SmsChannel channel) {
                trace.add("beta:" + channel.getName() + ":" + request.getTemplateId());
                return SmsResult.success(channel.getName(), SmsSupplier.CUSTOM, "BIZ_BETA", "REQ_BETA", 1);
            }
        };

        SmsChannelRegistry registry = new SmsChannelRegistry();
        registry.register(SmsChannel.builder()
                .name("ch-alpha")
                .supplier(SmsSupplier.CUSTOM)
                .providerCode("gw-alpha")
                .signName("签名A")
                .build());
        registry.register(SmsChannel.builder()
                .name("ch-beta")
                .supplier(SmsSupplier.CUSTOM)
                .providerCode("GW-BETA") // 验证大小写不敏感匹配
                .signName("签名B")
                .build());

        SmsTemplate template = new SmsTemplate(
                new SmsProperties(), List.of(gwAlpha, gwBeta), registry,
                null, null, null, null, null, null, null, null, null, null
        );

        SmsResult resA = template.send(SmsRequest.builder()
                .phone("13800138000")
                .content("告警纯文本消息")
                .channel("ch-alpha")
                .build());
        SmsResult resB = template.send(SmsRequest.builder()
                .phone("13800138001")
                .templateId("TPL_BETA_01")
                .channel("ch-beta")
                .build());

        assertThat(resA.isSuccess()).isTrue();
        assertThat(resA.getBizId()).isEqualTo("BIZ_ALPHA");
        assertThat(resB.isSuccess()).isTrue();
        assertThat(resB.getBizId()).isEqualTo("BIZ_BETA");
        assertThat(trace).containsExactly("alpha:ch-alpha:告警纯文本消息", "beta:ch-beta:TPL_BETA_01");
    }

    @Test
    @DisplayName("HTTP 代理验证：配置渠道 proxy（含用户名密码）时请求经由指定代理转发且携带 Proxy-Authorization")
    void shouldForwardHttpRequestsThroughConfiguredProxyWithBasicAuth() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody("{\"Code\":\"OK\",\"Message\":\"OK\",\"BizId\":\"PROXY_BIZ_1\",\"RequestId\":\"PROXY_REQ_1\"}"));

        SmsProxyProperties proxy = new SmsProxyProperties();
        proxy.setEnabled(true);
        proxy.setHost(mockWebServer.getHostName());
        proxy.setPort(mockWebServer.getPort());
        proxy.setUsername("proxy-user");
        proxy.setPassword("proxy-pass");

        SmsChannel channel = SmsChannel.builder()
                .name("aliyun-proxied")
                .supplier(SmsSupplier.ALIYUN)
                .accessKeyId("LTAI_PROXY")
                .accessKeySecret("SK_PROXY")
                .signName("代理测试")
                .endpoint("http://sms-gateway.internal.test/send")
                .proxy(proxy)
                .build();

        AliyunSmsProvider provider = new AliyunSmsProvider(RestClient.builder());
        SmsResult result = provider.send(SmsRequest.of("13800138000", "SMS_PROXY_01"), channel);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getBizId()).isEqualTo("PROXY_BIZ_1");

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getRequestLine()).contains("sms-gateway.internal.test");
        assertThat(recorded.getHeader("Host")).isEqualTo("sms-gateway.internal.test");
        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString("proxy-user:proxy-pass".getBytes(StandardCharsets.UTF_8));
        assertThat(recorded.getHeader("Proxy-Authorization")).isEqualTo(expectedAuth);
    }

    @Test
    @DisplayName("国际区号自适应转换验证：阿里云去 + 传国际码，腾讯与天翼保留标准 E.164 前缀")
    void shouldNormalizeInternationalNumbersPerVendorProtocol() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        String endpoint = mockWebServer.url("/").toString();

        // 1. 阿里云：+852-9123-4567 -> 85291234567 (不带 +) ；默认香港区号通道无前缀 61234567 -> 85261234567
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"Code\":\"OK\",\"BizId\":\"ALI_HK_1\",\"RequestId\":\"R1\"}"));
        AliyunSmsProvider aliyunProvider = new AliyunSmsProvider(RestClient.builder());
        SmsChannel aliHkChannel = SmsChannel.builder()
                .name("ali-hk")
                .supplier(SmsSupplier.ALIYUN)
                .accessKeyId("AK")
                .accessKeySecret("SK")
                .signName("SignHK")
                .endpoint(endpoint)
                .internationalEnabled(true)
                .defaultRegionCode("+852")
                .build();
        aliyunProvider.send(SmsRequest.builder()
                .phones(List.of("+852-9123-4567", "61234567", "+8613800138000"))
                .templateId("SMS_INTL")
                .build(), aliHkChannel);
        Map<String, String> aliForm = parseFormBody(mockWebServer.takeRequest().getBody().readUtf8());
        assertThat(aliForm.get("PhoneNumbers")).isEqualTo("85291234567,85261234567,13800138000");

        // 2. 腾讯云：标准 E.164 (+85291234567, +14155550199)
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"Response\":{\"RequestId\":\"R2\",\"SendStatusSet\":[{\"Code\":\"Ok\",\"SerialNo\":\"S2\",\"Fee\":2}]}}"));
        TencentSmsProvider tencentProvider = new TencentSmsProvider(RestClient.builder());
        SmsChannel tcIntlChannel = SmsChannel.builder()
                .name("tc-intl")
                .supplier(SmsSupplier.TENCENT)
                .accessKeyId("AK")
                .accessKeySecret("SK")
                .appId("140001")
                .signName("SignIntl")
                .endpoint(endpoint)
                .internationalEnabled(true)
                .defaultRegionCode("1")
                .build();
        tencentProvider.send(SmsRequest.builder()
                .phones(List.of("4155550199", "0085291234567"))
                .templateId("12345")
                .build(), tcIntlChannel);
        String tcBody = mockWebServer.takeRequest().getBody().readUtf8();
        assertThat(tcBody).contains("\"+14155550199\"").contains("\"+85291234567\"");

        // 3. 天翼云：标准 E.164
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"OK\",\"bizId\":\"CT_INTL_1\",\"requestId\":\"R3\"}"));
        CtyunSmsProvider ctyunProvider = new CtyunSmsProvider(RestClient.builder());
        SmsChannel ctIntlChannel = SmsChannel.builder()
                .name("ct-intl")
                .supplier(SmsSupplier.CTYUN)
                .accessKeyId("AK")
                .accessKeySecret("SK")
                .signName("SignCT")
                .endpoint(endpoint)
                .internationalEnabled(true)
                .defaultRegionCode("+65")
                .build();
        ctyunProvider.send(SmsRequest.of("91234567", "TPL_CT"), ctIntlChannel);
        String ctBody = mockWebServer.takeRequest().getBody().readUtf8();
        assertThat(ctBody).contains("\"+6591234567\"");
    }

    @Test
    @DisplayName("纯文本直发验证：云片/Mock 支持无模板 content 直发（含批量），仅支持模板厂商前置 Fail-Fast 拒绝且零网络调用")
    void shouldSupportPlainTextOnYunpianAndMockAndFailFastOnTemplateOnlyProviders() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        String endpoint = mockWebServer.url("/").toString();

        // MockWebServer 首包模拟云片纯文本单发成功，第二包模拟云片纯文本群发成功
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"msg\":\"发送成功\",\"count\":1,\"sid\":9001}"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"total_count\":2,\"data\":[{\"code\":0,\"msg\":\"发送成功\",\"count\":1,\"sid\":9002},{\"code\":0,\"msg\":\"发送成功\",\"count\":1,\"sid\":9003}]}"));

        MockSmsProvider mockProvider = new MockSmsProvider();
        YunpianSmsProvider yunpianProvider = new YunpianSmsProvider(RestClient.builder());
        AliyunSmsProvider aliyunProvider = new AliyunSmsProvider(RestClient.builder());

        SmsChannelRegistry registry = new SmsChannelRegistry();
        registry.register(SmsChannel.builder()
                .name("mock-plain")
                .supplier(SmsSupplier.MOCK)
                .signName("官方测试")
                .defaultTemplateId("SHOULD_NOT_OVERRIDE_PLAIN")
                .build());
        registry.register(SmsChannel.builder()
                .name("yp-plain")
                .supplier(SmsSupplier.YUNPIAN)
                .accessKeyId("YP_KEY")
                .signName("云片通知")
                .endpoint(endpoint)
                .build());
        registry.register(SmsChannel.builder()
                .name("ali-template-only")
                .supplier(SmsSupplier.ALIYUN)
                .accessKeyId("ALI_AK")
                .accessKeySecret("ALI_SK")
                .signName("阿里签名")
                .endpoint(endpoint)
                .build());

        SmsTemplate template = new SmsTemplate(
                new SmsProperties(), List.of(mockProvider, yunpianProvider, aliyunProvider), registry,
                null, null, null, null, null, null, null, null, null, null
        );

        // 1. Mock 单发 + 批量纯文本直发
        SmsResult mockRes = template.send(SmsRequest.ofContent("13800138000", "系统告警：CPU 使用率超过 90%").withChannel("mock-plain"));
        assertThat(mockRes.isSuccess()).isTrue();
        assertThat(mockProvider.getLastRequest().getContent()).isEqualTo("系统告警：CPU 使用率超过 90%");
        assertThat(mockProvider.getLastRequest().getTemplateId()).isNull();

        BatchSmsResult mockBatch = template.sendBatch(BatchSmsRequest.builder()
                .phones(List.of("13800138001", "13800138002"))
                .content("停机维护通知：今晚 02:00 升级")
                .channel("mock-plain")
                .build());
        assertThat(mockBatch.isSuccess()).isTrue();
        assertThat(mockBatch.getSuccessCount()).isEqualTo(2);

        // 2. 云片单发纯文本直发 -> /sms/single_send.json + text 含签名
        SmsResult ypSingle = template.send(SmsRequest.ofContent("13800138003", "您的订单已签收").withChannel("yp-plain"));
        assertThat(ypSingle.isSuccess()).isTrue();
        assertThat(ypSingle.getBizId()).isEqualTo("9001");
        RecordedRequest ypReq1 = mockWebServer.takeRequest();
        assertThat(ypReq1.getPath()).contains("/sms/single_send.json");
        Map<String, String> ypForm1 = parseFormBody(ypReq1.getBody().readUtf8());
        assertThat(ypForm1.get("tpl_id")).isNull();
        assertThat(ypForm1.get("text")).isEqualTo("【云片通知】您的订单已签收");

        // 3. 云片批量纯文本直发 -> /sms/batch_send.json
        BatchSmsResult ypBatch = template.sendBatch(BatchSmsRequest.builder()
                .phones(List.of("13800138004", "13800138005"))
                .content("【云片通知】批量活动提醒")
                .channel("yp-plain")
                .build());
        assertThat(ypBatch.isSuccess()).isTrue();
        RecordedRequest ypReq2 = mockWebServer.takeRequest();
        assertThat(ypReq2.getPath()).contains("/sms/batch_send.json");
        Map<String, String> ypForm2 = parseFormBody(ypReq2.getBody().readUtf8());
        assertThat(ypForm2.get("text")).isEqualTo("【云片通知】批量活动提醒");

        // 4. 仅支持模板的阿里云通道前置 Fail-Fast 拒绝纯文本，绝对零网络请求
        int requestsBefore = mockWebServer.getRequestCount();
        assertThatThrownBy(() -> template.send(SmsRequest.ofContent("13800138006", "纯文本非法请求").withChannel("ali-template-only")))
                .isInstanceOf(SmsException.class)
                .extracting(e -> ((SmsException) e).getResultCode())
                .isEqualTo(SmsResultCode.SMS_PARAM_INVALID);
        assertThatThrownBy(() -> template.sendBatch(BatchSmsRequest.builder()
                .phones(List.of("13800138006"))
                .content("纯文本批量非法请求")
                .channel("ali-template-only")
                .build()))
                .isInstanceOf(SmsException.class)
                .extracting(e -> ((SmsException) e).getResultCode())
                .isEqualTo(SmsResultCode.SMS_PARAM_INVALID);
        assertThat(mockWebServer.getRequestCount()).isEqualTo(requestsBefore);
    }

    @Test
    @DisplayName("端点协议头防御补全与 RestClient 有界 LRU 缓存淘汰验证")
    void shouldNormalizeEndpointSchemeAndEvictRestClientCacheAboveLimit() {
        ProbeHttpSmsProvider provider = new ProbeHttpSmsProvider(2);

        assertThat(provider.normalize("dysmsapi.aliyuncs.com", AliyunSmsProvider.DEFAULT_ENDPOINT))
                .isEqualTo("https://dysmsapi.aliyuncs.com");
        assertThat(provider.normalize("https://custom.gateway.com/sms", AliyunSmsProvider.DEFAULT_ENDPOINT))
                .isEqualTo("https://custom.gateway.com/sms");
        assertThat(provider.normalize("http://127.0.0.1:8080/sms", AliyunSmsProvider.DEFAULT_ENDPOINT))
                .isEqualTo("http://127.0.0.1:8080/sms");
        assertThat(provider.normalize("  ", AliyunSmsProvider.DEFAULT_ENDPOINT))
                .isEqualTo(AliyunSmsProvider.DEFAULT_ENDPOINT);

        provider.getClient(Duration.ofSeconds(1));
        provider.getClient(Duration.ofSeconds(2));
        provider.getClient(Duration.ofSeconds(3));
        assertThat(provider.getCachedRestClientCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("路由防伪成功验证：显式配置未注册 providerCode 时严禁静默回退默认 MOCK 驱动")
    void shouldFailFastWhenExplicitProviderCodeIsUnregisteredInsteadOfFallingBackToMock() {
        SmsProperties props = new SmsProperties();
        SmsChannelProperties ch = new SmsChannelProperties();
        // SmsChannelProperties.supplier 默认值为 MOCK；显式声明未注册 providerCode 时应按自定义编码失败而非走 Mock
        ch.setProviderCode("missing-custom-gw");
        ch.setDefaultTemplateId("TPL_001");
        props.getChannels().put("broken-custom", ch);

        MockSmsProvider mockProvider = new MockSmsProvider();
        SmsTemplate template = new SmsTemplate(props, List.of(mockProvider));

        assertThatThrownBy(() -> template.send(SmsRequest.builder()
                .phone("13800138000")
                .templateId("TPL_001")
                .channel("broken-custom")
                .build()))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("missing-custom-gw");
        assertThat(mockProvider.getSentCount()).isZero();
    }

    @Test
    @DisplayName("纯文本幂等指纹验证：无 bizId 时不同 content 生成不同幂等键且同号连发不被误拦截，相同 content 正常拦截")
    void shouldIncludePlainTextContentInIdempotentKeyDigest() {
        SmsProperties props = new SmsProperties();
        props.getGuard().setIdempotentEnabled(true);
        props.getGuard().setIdempotentWindow(Duration.ofMinutes(3));

        SmsChannelProperties ch = new SmsChannelProperties();
        ch.setSupplier(SmsSupplier.MOCK);
        props.getChannels().put("mock-plain-idem", ch);

        MockSmsProvider mockProvider = new MockSmsProvider();
        InMemorySmsIdempotentGuard idempotentGuard = new InMemorySmsIdempotentGuard();
        SmsTemplate template = new SmsTemplate(
                props, List.of(mockProvider), new SmsChannelRegistry(props),
                null, null, null, null, null, null, idempotentGuard, null, null
        );

        SmsRequest first = SmsRequest.builder()
                .phone("13800138009")
                .content("您的订单 A001 已发货")
                .signName("官方测试")
                .channel("mock-plain-idem")
                .build();
        SmsRequest secondDifferentContent = SmsRequest.builder()
                .phone("13800138009")
                .content("您的订单 A002 已签收")
                .signName("官方测试")
                .channel("mock-plain-idem")
                .build();

        assertThat(idempotentGuard.generateKey(first))
                .isNotEqualTo(idempotentGuard.generateKey(secondDifferentContent));
        assertThat(template.send(first).isSuccess()).isTrue();
        assertThat(template.send(secondDifferentContent).isSuccess()).isTrue();
        assertThatThrownBy(() -> template.send(first))
                .isInstanceOf(SmsIdempotentException.class);
        assertThat(mockProvider.getSentCount()).isEqualTo(2);
    }

    private static final class ProbeHttpSmsProvider extends AbstractHttpSmsProvider {

        private ProbeHttpSmsProvider(int maxCacheSize) {
            super(RestClient.builder(), maxCacheSize);
        }

        String normalize(String endpoint, String fallback) {
            return normalizeEndpoint(endpoint, fallback);
        }

        RestClient getClient(Duration timeout) {
            return buildRestClient(timeout);
        }

        @Override
        public SmsSupplier getSupplier() {
            return SmsSupplier.MOCK;
        }

        @Override
        public SmsResult send(SmsRequest request, SmsChannel channel) {
            return SmsResult.mock(channel.getName(), "B", "R");
        }
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
