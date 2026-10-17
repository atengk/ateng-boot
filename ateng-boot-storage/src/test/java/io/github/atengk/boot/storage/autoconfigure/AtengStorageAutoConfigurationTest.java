package io.github.atengk.boot.storage.autoconfigure;

import io.github.atengk.boot.storage.controller.LocalStorageDownloadController;
import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.properties.StorageProperties;
import io.github.atengk.boot.storage.template.StorageOperations;
import io.github.atengk.boot.storage.template.StorageTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengStorageAutoConfiguration 自动装配测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("AtengStorageAutoConfiguration 自动装配测试")
class AtengStorageAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengStorageAutoConfiguration.class));

    @Test
    @DisplayName("默认非 Web 配置下自动装配 StorageDriver 与 StorageTemplate Bean")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(StorageDriver.class);
            assertThat(context).hasSingleBean(StorageOperations.class);
            assertThat(context).hasSingleBean(StorageTemplate.class);
            assertThat(context).hasSingleBean(StorageProperties.class);
            assertThat(context).doesNotHaveBean(LocalStorageDownloadController.class);
        });
    }

    @Test
    @DisplayName("Servlet Web 环境下自动装配 LocalStorageDownloadController 端点")
    void testWebAutoConfiguration() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengStorageAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(StorageDriver.class);
                    assertThat(context).hasSingleBean(StorageTemplate.class);
                    assertThat(context).hasSingleBean(LocalStorageDownloadController.class);
                });
    }

    @Test
    @DisplayName("配置 ateng.boot.storage.enabled=false 时禁用自动装配")
    void testDisabledAutoConfiguration() {
        contextRunner
                .withPropertyValues("ateng.boot.storage.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(StorageDriver.class);
                    assertThat(context).doesNotHaveBean(StorageOperations.class);
                    assertThat(context).doesNotHaveBean(StorageTemplate.class);
                    assertThat(context).doesNotHaveBean(LocalStorageDownloadController.class);
                });
    }

    @Test
    @DisplayName("用户自定义 StorageDriver 时不会被自动装配覆盖")
    void testCustomDriverTakesPrecedence(@TempDir Path tempDir) {
        contextRunner
                .withUserConfiguration(CustomDriverConfig.class)
                .withPropertyValues("test.custom.dir=" + tempDir.toString())
                .run(context -> {
                    assertThat(context).hasSingleBean(StorageDriver.class);
                    StorageDriver driver = context.getBean(StorageDriver.class);
                    assertThat(driver).isInstanceOf(LocalDriver.class);
                });
    }

    @Configuration
    static class CustomDriverConfig {
        @Bean
        public StorageDriver customStorageDriver() {
            return new LocalDriver(System.getProperty("java.io.tmpdir"));
        }
    }
}
