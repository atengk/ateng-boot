package io.github.atengk.boot.excel;

import io.github.atengk.boot.excel.autoconfigure.AtengExcelAutoConfiguration;
import io.github.atengk.boot.excel.properties.ExcelProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动装配测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
class AtengExcelAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengExcelAutoConfiguration.class));

    @Test
    @DisplayName("默认情况下自动装配生效并注入配置属性")
    void testAutoConfigurationEnabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengExcelAutoConfiguration.class);
            assertThat(context).hasSingleBean(ExcelProperties.class);
            ExcelProperties properties = context.getBean(ExcelProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getDefaultBatchSize()).isEqualTo(1000);
        });
    }

    @Test
    @DisplayName("当 ateng.boot.excel.enabled=false 时装配不生效")
    void testAutoConfigurationDisabled() {
        contextRunner.withPropertyValues("ateng.boot.excel.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengExcelAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(ExcelProperties.class);
                });
    }
}
