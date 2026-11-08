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
            assertThat(properties.getAsyncExport().getDownloadUrlPrefix()).isEqualTo("/api/excel/async-download?taskId=");
        });
    }

    @Test
    @DisplayName("测试自定义 downloadUrlPrefix 与 tempDir 配置生效")
    void testCustomPropertiesBinding() {
        contextRunner.withPropertyValues(
                "ateng.boot.excel.temp-dir=/custom/excel/tmp",
                "ateng.boot.excel.async-export.download-url-prefix=/custom/download?id="
        ).run(context -> {
            ExcelProperties properties = context.getBean(ExcelProperties.class);
            assertThat(properties.getTempDir()).isEqualTo("/custom/excel/tmp");
            assertThat(properties.getAsyncExport().getDownloadUrlPrefix()).isEqualTo("/custom/download?id=");
        });
    }

    @Test
    @DisplayName("测试 excelStaticFacadeInitializer 可被自定义 Bean 覆盖")
    void testExcelStaticFacadeInitializerOverride() {
        org.springframework.beans.factory.SmartInitializingSingleton customBean = () -> {};
        contextRunner.withBean("excelStaticFacadeInitializer", org.springframework.beans.factory.SmartInitializingSingleton.class, () -> customBean)
                .run(context -> {
                    assertThat(context).hasBean("excelStaticFacadeInitializer");
                    assertThat(context.getBean("excelStaticFacadeInitializer")).isSameAs(customBean);
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
