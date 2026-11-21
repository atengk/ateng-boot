package io.github.atengk.boot.sms;

import io.github.atengk.boot.sms.config.AtengSmsAutoConfiguration;
import io.github.atengk.boot.sms.model.BatchSmsRequest;
import io.github.atengk.boot.sms.model.BatchSmsResult;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsParam;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsChannelProperties;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.provider.*;
import io.github.atengk.boot.sms.template.SmsTemplate;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单 #1146 自动装配 15 驱动矩阵、切片批限与跨厂商容灾端到端验证测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("工单 #1146 自动装配 15 驱动与端到端路由容灾测试")
class SmsTicket1146AutoConfigAndRoutingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengSmsAutoConfiguration.class));

    private MockWebServer mockWebServer;

    @AfterEach
    void tearDown() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @Test
    @DisplayName("自动装配验证：容器内全量注册 14 家内置真实厂商 + MOCK 共 15 个 SmsProvider Bean 且支持 @ConditionalOnMissingBean 覆盖")
    void shouldAutoConfigureAllFifteenBuiltinSmsProvidersAndAllowCustomOverride() {
        contextRunner.run(context -> {
            Map<String, SmsProvider> beans = context.getBeansOfType(SmsProvider.class);
            assertThat(beans).hasSize(15);

            List<SmsSupplier> registeredSuppliers = beans.values().stream()
                    .map(SmsProvider::getSupplier)
                    .toList();
            assertThat(registeredSuppliers).containsExactlyInAnyOrder(
                    SmsSupplier.MOCK,
                    SmsSupplier.ALIYUN,
                    SmsSupplier.TENCENT,
                    SmsSupplier.HUAWEI,
                    SmsSupplier.YUNPIAN,
                    SmsSupplier.CTYUN,
                    SmsSupplier.BAIDU,
                    SmsSupplier.JDCLOUD,
                    SmsSupplier.QINIU,
                    SmsSupplier.NETEASE,
                    SmsSupplier.CLOOPEN,
                    SmsSupplier.CHUANGLAN,
                    SmsSupplier.MAS,
                    SmsSupplier.UNISMS,
                    SmsSupplier.ZHUTONG
            );

            SmsTemplate template = context.getBean(SmsTemplate.class);
            assertThat(template.getProvider(SmsSupplier.BAIDU)).isInstanceOf(BaiduSmsProvider.class);
            assertThat(template.getProvider("zhutong")).isInstanceOf(ZhutongSmsProvider.class);
        });

        // 验证自定义 Bean 可平滑覆盖默认自动装配驱动
        SmsProvider customBaidu = new BaiduSmsProvider(RestClient.builder());
        contextRunner.withBean("baiduSmsProvider", BaiduSmsProvider.class, () -> (BaiduSmsProvider) customBaidu)
                .run(context -> {
                    assertThat(context.getBeansOfType(BaiduSmsProvider.class)).hasSize(1);
                    assertThat(context.getBean(BaiduSmsProvider.class)).isSameAs(customBaidu);
                });
    }

    @Test
    @DisplayName("批处理切片上限验证：京东 100 / 网易 100 / 容联 200 通过 SmsTemplate 自动分批下发")
    void shouldSliceBatchRequestsAccordingToVendorLimitsViaSmsTemplate() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        String endpoint = mockWebServer.url("/").toString();

        // 150 个号码经 JDCLOUD (limit=100) 应切为 2 批
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"requestId\":\"r1\",\"result\":{\"status\":true,\"data\":{\"sequenceNumber\":\"s1\"}}}"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"requestId\":\"r2\",\"result\":{\"status\":true,\"data\":{\"sequenceNumber\":\"s2\"}}}"));

        // 250 个号码经 CLOOPEN (limit=200) 应切为 2 批
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"statusCode\":\"000000\",\"templateSMS\":{\"smsMessageSid\":\"c1\"}}"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"statusCode\":\"000000\",\"templateSMS\":{\"smsMessageSid\":\"c2\"}}"));

        // 150 个号码经 NETEASE (limit=100) 应切为 2 批
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"code\":200,\"msg\":\"sendid\",\"obj\":1001}"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"code\":200,\"msg\":\"sendid\",\"obj\":1002}"));

        SmsProperties props = new SmsProperties();
        SmsChannelProperties jdCh = new SmsChannelProperties();
        jdCh.setSupplier(SmsSupplier.JDCLOUD);
        jdCh.setAccessKeyId("JD_AK");
        jdCh.setAccessKeySecret("JD_SK");
        jdCh.setSignId("jd_sign");
        jdCh.setEndpoint(endpoint);
        props.getChannels().put("jd-batch", jdCh);

        SmsChannelProperties clCh = new SmsChannelProperties();
        clCh.setSupplier(SmsSupplier.CLOOPEN);
        clCh.setAccessKeyId("CL_SID");
        clCh.setAccessKeySecret("CL_TOKEN");
        clCh.setAppId("CL_APP");
        clCh.setEndpoint(endpoint);
        props.getChannels().put("cloopen-batch", clCh);

        SmsChannelProperties neCh = new SmsChannelProperties();
        neCh.setSupplier(SmsSupplier.NETEASE);
        neCh.setAccessKeyId("NE_AK");
        neCh.setAccessKeySecret("NE_SK");
        neCh.setEndpoint(endpoint);
        props.getChannels().put("netease-batch", neCh);

        SmsTemplate template = new SmsTemplate(props, List.of(
                new JdCloudSmsProvider(RestClient.builder()),
                new CloopenSmsProvider(RestClient.builder()),
                new NeteaseSmsProvider(RestClient.builder())
        ));

        BatchSmsResult jdRes = template.sendBatch(BatchSmsRequest.builder()
                .phones(generatePhones(150))
                .templateId("jd_tpl")
                .params(SmsParam.ofArray("8888"))
                .channel("jd-batch")
                .build());
        assertThat(jdRes.isSuccess()).isTrue();
        assertThat(jdRes.getSuccessCount()).isEqualTo(150);
        assertThat(jdRes.getBatchResults()).hasSize(2);

        BatchSmsResult clRes = template.sendBatch(BatchSmsRequest.builder()
                .phones(generatePhones(250))
                .templateId("cl_tpl")
                .params(SmsParam.ofArray("9999"))
                .channel("cloopen-batch")
                .build());
        assertThat(clRes.isSuccess()).isTrue();
        assertThat(clRes.getSuccessCount()).isEqualTo(250);
        assertThat(clRes.getBatchResults()).hasSize(2);

        BatchSmsResult neRes = template.sendBatch(BatchSmsRequest.builder()
                .phones(generatePhones(150))
                .templateId("ne_tpl")
                .params(SmsParam.ofArray("7777"))
                .channel("netease-batch")
                .build());
        assertThat(neRes.isSuccess()).isTrue();
        assertThat(neRes.getSuccessCount()).isEqualTo(150);
        assertThat(neRes.getBatchResults()).hasSize(2);
    }

    @Test
    @DisplayName("端到端路由与容灾验证：主渠道百度网关故障时无缝顺延切换至创蓝 253 备用渠道")
    void shouldSwitchChannelsAndFailoverBetweenNewAndExistingProviders() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        String endpoint = mockWebServer.url("/").toString();

        // 主渠道百度 500 故障 -> 备用创蓝 200 成功
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("internal error"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"0\",\"errorMsg\":\"\",\"msgId\":\"cl-failover-ok\"}"));

        SmsProperties props = new SmsProperties();
        props.setDefaultChannel("baidu-primary");

        SmsChannelProperties baiduCh = new SmsChannelProperties();
        baiduCh.setSupplier(SmsSupplier.BAIDU);
        baiduCh.setAccessKeyId("BD_AK");
        baiduCh.setAccessKeySecret("BD_SK");
        baiduCh.setSignId("bd_sign");
        baiduCh.setEndpoint(endpoint);
        baiduCh.setFailoverChannel("chuanglan-backup");
        props.getChannels().put("baidu-primary", baiduCh);

        SmsChannelProperties clCh = new SmsChannelProperties();
        clCh.setSupplier(SmsSupplier.CHUANGLAN);
        clCh.setAccessKeyId("CL_USER");
        clCh.setAccessKeySecret("CL_PWD");
        clCh.setSignName("备用创蓝");
        clCh.setEndpoint(endpoint);
        props.getChannels().put("chuanglan-backup", clCh);

        SmsTemplate template = new SmsTemplate(props, List.of(
                new BaiduSmsProvider(RestClient.builder()),
                new ChuanglanSmsProvider(RestClient.builder())
        ));

        SmsResult result = template.send(SmsRequest.of("13800138000", "TPL_COMMON", SmsParam.of("code", "123456")));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getChannel()).isEqualTo("chuanglan-backup");
        assertThat(result.getSupplier()).isEqualTo(SmsSupplier.CHUANGLAN);
        assertThat(result.getBizId()).isEqualTo("cl-failover-ok");
    }

    @Test
    @DisplayName("依赖纯粹性校验：类路径严禁引入第三方云厂商 SDK 或 Hutool 重型依赖")
    void shouldNotHaveThirdPartyVendorSdksOrHutoolOnClasspath() {
        List<String> forbiddenClasses = List.of(
                "cn.hutool.core.util.StrUtil",
                "com.jdcloud.sdk.service.sms.client.SmsClient",
                "com.aliyun.dysmsapi20170525.Client",
                "com.tencentcloudapi.sms.v20210111.SmsClient"
        );
        for (String className : forbiddenClasses) {
            boolean present;
            try {
                Class.forName(className, false, getClass().getClassLoader());
                present = true;
            } catch (ClassNotFoundException ex) {
                present = false;
            }
            assertThat(present).as("不应存在第三方重型/云厂商 SDK 类: " + className).isFalse();
        }
    }

    private static List<String> generatePhones(int count) {
        List<String> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(String.format("139%08d", i));
        }
        return list;
    }
}
