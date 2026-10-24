package io.github.atengk.boot.pay.config.properties;

import io.github.atengk.boot.pay.autoconfigure.AtengPayAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付配置属性绑定与默认值单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class PayPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengPayAutoConfiguration.class));

    @Test
    @DisplayName("默认配置属性装配与生产合理默认值测试")
    void testDefaultProperties() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PayProperties.class);
            PayProperties properties = context.getBean(PayProperties.class);

            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getCallback().getPathPrefix()).isEqualTo("/ateng-pay/callback");
            assertThat(properties.getCallback().getIdempotentTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.getBarcode().getMaxWaitTime()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getBarcode().getPollInterval()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.getBarcode().isAutoCancelOnTimeout()).isTrue();
            assertThat(properties.getTransfer().isEnabled()).isFalse();
            assertThat(properties.getProfitSharing().isEnabled()).isFalse();

            assertThat(properties.getWechat().isEnabled()).isTrue();
            assertThat(properties.getWechat().getDefaultMerchant()).isEqualTo("default");
            assertThat(properties.getWechat().isServiceProviderMode()).isFalse();

            assertThat(properties.getAlipay().isEnabled()).isTrue();
            assertThat(properties.getAlipay().getDefaultApp()).isEqualTo("default");
            assertThat(properties.getAlipay().isServiceProviderMode()).isFalse();
        });
    }

    @Test
    @DisplayName("自定义多商户与自定义超时时间绑定测试")
    void testCustomPropertiesBinding() {
        runner.withPropertyValues(
                "ateng.boot.pay.enabled=true",
                "ateng.boot.pay.callback.path-prefix=/custom/pay/callback",
                "ateng.boot.pay.callback.idempotent-timeout=30s",
                "ateng.boot.pay.barcode.max-wait-time=45s",
                "ateng.boot.pay.barcode.poll-interval=5s",
                "ateng.boot.pay.barcode.auto-cancel-on-timeout=false",
                "ateng.boot.pay.transfer.enabled=true",
                "ateng.boot.pay.profit-sharing.enabled=true",
                "ateng.boot.pay.wechat.default-merchant=store1",
                "ateng.boot.pay.wechat.merchants.store1.app-id=wx1234567890",
                "ateng.boot.pay.wechat.merchants.store1.mch-id=1900000109",
                "ateng.boot.pay.wechat.merchants.store1.api-v3-key=test_api_v3_key_32_bytes_long!!",
                "ateng.boot.pay.wechat.merchants.store1.sub-mch-id=sub999",
                "ateng.boot.pay.alipay.default-app=app1",
                "ateng.boot.pay.alipay.apps.app1.app-id=2021000000000000",
                "ateng.boot.pay.alipay.apps.app1.app-auth-token=token_abc_123"
        ).run(context -> {
            assertThat(context).hasSingleBean(PayProperties.class);
            PayProperties properties = context.getBean(PayProperties.class);

            assertThat(properties.getCallback().getPathPrefix()).isEqualTo("/custom/pay/callback");
            assertThat(properties.getCallback().getIdempotentTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getBarcode().getMaxWaitTime()).isEqualTo(Duration.ofSeconds(45));
            assertThat(properties.getBarcode().getPollInterval()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.getBarcode().isAutoCancelOnTimeout()).isFalse();
            assertThat(properties.getTransfer().isEnabled()).isTrue();
            assertThat(properties.getProfitSharing().isEnabled()).isTrue();

            WechatMerchantProperties store1 = properties.getWechat().getMerchants().get("store1");
            assertThat(store1).isNotNull();
            assertThat(store1.getAppId()).isEqualTo("wx1234567890");
            assertThat(store1.getMchId()).isEqualTo("1900000109");
            assertThat(store1.getApiV3Key()).isEqualTo("test_api_v3_key_32_bytes_long!!");
            assertThat(store1.getSubMchId()).isEqualTo("sub999");

            AlipayAppProperties app1 = properties.getAlipay().getApps().get("app1");
            assertThat(app1).isNotNull();
            assertThat(app1.getAppId()).isEqualTo("2021000000000000");
            assertThat(app1.getAppAuthToken()).isEqualTo("token_abc_123");
        });
    }

    @Test
    @DisplayName("全局开关关闭时跳过自动装配测试")
    void testDisabledAutoConfiguration() {
        runner.withPropertyValues("ateng.boot.pay.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PayProperties.class));
    }
}
