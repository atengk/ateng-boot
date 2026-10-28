package io.github.atengk.boot.pay.autoconfigure;

import io.github.atengk.boot.pay.core.bill.PayBillParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 财务对账单解析引擎自动装配测试
 *
 * @author Ateng
 * @since 2026-10-24
 */
class PayBillAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengPayAutoConfiguration.class));

    @Test
    @DisplayName("默认自动装配: 自动注册 PayBillParser 单例")
    void testPayBillParserRegisteredByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PayBillParser.class);
            PayBillParser parser = context.getBean(PayBillParser.class);
            assertThat(parser).isNotNull();
        });
    }

    @Test
    @DisplayName("开发者自定义接管: 用户提供自定义 Bean 时 ConditionalOnMissingBean 平滑生效")
    void testPayBillParserCustomOverride() {
        contextRunner.withUserConfiguration(CustomPayBillParserConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(PayBillParser.class);
                    PayBillParser parser = context.getBean(PayBillParser.class);
                    assertThat(parser).isInstanceOf(CustomPayBillParser.class);
                });
    }

    static class CustomPayBillParser extends PayBillParser {
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomPayBillParserConfiguration {
        @Bean
        public PayBillParser payBillParser() {
            return new CustomPayBillParser();
        }
    }
}
