package io.github.atengk.boot.pay.autoconfigure;

import io.github.atengk.boot.pay.core.WechatPayTemplate;
import io.github.atengk.boot.pay.core.profitsharing.WechatProfitSharingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信分账专有业务服务自动装配与物理总开关隔离测试
 *
 * @author Ateng
 * @since 2026-10-24
 */
class WechatProfitSharingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengPayAutoConfiguration.class))
            .withUserConfiguration(MockWechatTemplateConfiguration.class);

    @Test
    @DisplayName("默认情况下 (ateng.boot.pay.profit-sharing.enabled 未配或为 false) 不注册 WechatProfitSharingService")
    void testProfitSharingServiceNotLoadedByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(WechatProfitSharingService.class);
        });

        contextRunner.withPropertyValues("ateng.boot.pay.profit-sharing.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(WechatProfitSharingService.class);
                });
    }

    @Test
    @DisplayName("显式配置 ateng.boot.pay.profit-sharing.enabled=true 时成功注册 WechatProfitSharingService")
    void testProfitSharingServiceLoadedWhenEnabled() {
        contextRunner.withPropertyValues("ateng.boot.pay.profit-sharing.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(WechatProfitSharingService.class);
                    WechatProfitSharingService service = context.getBean(WechatProfitSharingService.class);
                    assertThat(service).isNotNull();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class MockWechatTemplateConfiguration {
        @Bean
        public WechatPayTemplate wechatPayTemplate() {
            return Mockito.mock(WechatPayTemplate.class);
        }
    }
}
