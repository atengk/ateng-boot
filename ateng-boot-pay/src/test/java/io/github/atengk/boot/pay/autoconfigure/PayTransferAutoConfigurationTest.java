package io.github.atengk.boot.pay.autoconfigure;

import io.github.atengk.boot.pay.core.PayTemplate;
import io.github.atengk.boot.pay.core.transfer.PayTransferService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单笔资金转账专有业务服务自动装配与物理总开关隔离测试
 *
 * @author Ateng
 * @since 2026-10-24
 */
class PayTransferAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengPayAutoConfiguration.class))
            .withUserConfiguration(MockPayTemplateConfiguration.class);

    @Test
    @DisplayName("默认情况下 (ateng.boot.pay.transfer.enabled 未配或为 false) 不注册 PayTransferService")
    void testTransferServiceNotLoadedByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(PayTransferService.class);
        });

        contextRunner.withPropertyValues("ateng.boot.pay.transfer.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PayTransferService.class);
                });
    }

    @Test
    @DisplayName("显式配置 ateng.boot.pay.transfer.enabled=true 时成功注册 PayTransferService")
    void testTransferServiceLoadedWhenEnabled() {
        contextRunner.withPropertyValues("ateng.boot.pay.transfer.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(PayTransferService.class);
                    PayTransferService service = context.getBean(PayTransferService.class);
                    assertThat(service).isNotNull();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class MockPayTemplateConfiguration {
        @Bean
        public PayTemplate payTemplate() {
            return Mockito.mock(PayTemplate.class);
        }
    }
}
