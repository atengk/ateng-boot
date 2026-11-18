package io.github.atengk.boot.pay.autoconfigure;

import io.github.atengk.boot.pay.core.AlipayTemplate;
import io.github.atengk.boot.pay.core.profitsharing.AlipayProfitSharingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付宝交易分账专有业务服务自动装配与物理总开关隔离测试
 *
 * @author Ateng
 * @since 2026-11-18
 */
class AlipayProfitSharingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengPayAutoConfiguration.class))
            .withUserConfiguration(MockAlipayTemplateConfiguration.class);

    @Test
    @DisplayName("默认情况下 (ateng.boot.pay.profit-sharing.enabled 未配或为 false) 不注册 AlipayProfitSharingService")
    void testAlipayProfitSharingServiceNotLoadedByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(AlipayProfitSharingService.class);
        });

        contextRunner.withPropertyValues("ateng.boot.pay.profit-sharing.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AlipayProfitSharingService.class);
                });
    }

    @Test
    @DisplayName("显式配置 ateng.boot.pay.profit-sharing.enabled=true 时成功注册 AlipayProfitSharingService")
    void testAlipayProfitSharingServiceLoadedWhenEnabled() {
        contextRunner.withPropertyValues("ateng.boot.pay.profit-sharing.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(AlipayProfitSharingService.class);
                    AlipayProfitSharingService service = context.getBean(AlipayProfitSharingService.class);
                    assertThat(service).isNotNull();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class MockAlipayTemplateConfiguration {
        @Bean
        public AlipayTemplate alipayTemplate() {
            return Mockito.mock(AlipayTemplate.class);
        }
    }
}
