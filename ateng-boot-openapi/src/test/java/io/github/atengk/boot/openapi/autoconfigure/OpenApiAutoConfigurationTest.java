package io.github.atengk.boot.openapi.autoconfigure;

import io.github.atengk.boot.openapi.converter.OpenApiModelConverter;
import io.github.atengk.boot.openapi.customizer.GlobalHeaderCustomizer;
import io.github.atengk.boot.openapi.customizer.ResultCodeDictionaryCustomizer;
import io.github.atengk.boot.openapi.customizer.SaTokenSecurityCustomizer;
import io.github.atengk.boot.openapi.customizer.StandardApiResponseCustomizer;
import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAPI 自动装配脚手架与缺省行为测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("OpenAPI 自动装配与配置测试")
class OpenApiAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenApiAutoConfiguration.class));

    @Test
    @DisplayName("测试默认配置下 OpenAPI Bean 与重定向配置正常注入")
    void testDefaultConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(OpenAPI.class);
            assertThat(context).hasBean("openApiWebMvcConfigurer");
            assertThat(context).hasSingleBean(OpenApiProperties.class);
            assertThat(context).hasSingleBean(GlobalHeaderCustomizer.class);
            assertThat(context).hasSingleBean(SaTokenSecurityCustomizer.class);
            assertThat(context).hasSingleBean(ResultCodeDictionaryCustomizer.class);
            assertThat(context).hasSingleBean(StandardApiResponseCustomizer.class);
            assertThat(context).hasSingleBean(OpenApiModelConverter.class);

            OpenAPI openAPI = context.getBean(OpenAPI.class);
            Info info = openAPI.getInfo();
            assertThat(info).isNotNull();
            assertThat(info.getTitle()).isEqualTo("ateng-boot API Documentation");
            assertThat(info.getVersion()).isEqualTo("1.0.0");
            assertThat(openAPI.getServers()).hasSize(1);
            assertThat(openAPI.getServers().get(0).getUrl()).isEqualTo("/");
        });
    }

    @Test
    @DisplayName("测试当 enabled=false 时完全不装配 OpenAPI Bean")
    void testDisabledConfiguration() {
        contextRunner
                .withPropertyValues("ateng.boot.openapi.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(OpenAPI.class);
                    assertThat(context).doesNotHaveBean("openApiWebMvcConfigurer");
                    assertThat(context).doesNotHaveBean(GlobalHeaderCustomizer.class);
                    assertThat(context).doesNotHaveBean(SaTokenSecurityCustomizer.class);
                    assertThat(context).doesNotHaveBean(ResultCodeDictionaryCustomizer.class);
                    assertThat(context).doesNotHaveBean(StandardApiResponseCustomizer.class);
                    assertThat(context).doesNotHaveBean(OpenApiModelConverter.class);
                });
    }

    @Test
    @DisplayName("测试自定义元数据与 Servers 列表绑定")
    void testCustomProperties() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.openapi.title=Custom Microservice API",
                        "ateng.boot.openapi.version=3.0.0",
                        "ateng.boot.openapi.description=Custom Description",
                        "ateng.boot.openapi.contact.name=Ateng",
                        "ateng.boot.openapi.contact.email=admin@example.com",
                        "ateng.boot.openapi.license.name=Apache 2.0",
                        "ateng.boot.openapi.license.url=https://www.apache.org/licenses/LICENSE-2.0",
                        "ateng.boot.openapi.servers[0].url=https://api.mygateway.com",
                        "ateng.boot.openapi.servers[0].description=Gateway Entry"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(OpenAPI.class);
                    OpenAPI openAPI = context.getBean(OpenAPI.class);
                    Info info = openAPI.getInfo();
                    assertThat(info.getTitle()).isEqualTo("Custom Microservice API");
                    assertThat(info.getVersion()).isEqualTo("3.0.0");
                    assertThat(info.getDescription()).isEqualTo("Custom Description");
                    assertThat(info.getContact()).isNotNull();
                    assertThat(info.getContact().getName()).isEqualTo("Ateng");
                    assertThat(info.getContact().getEmail()).isEqualTo("admin@example.com");
                    assertThat(info.getLicense()).isNotNull();
                    assertThat(info.getLicense().getName()).isEqualTo("Apache 2.0");

                    assertThat(openAPI.getServers()).hasSize(1);
                    assertThat(openAPI.getServers().get(0).getUrl()).isEqualTo("https://api.mygateway.com");
                    assertThat(openAPI.getServers().get(0).getDescription()).isEqualTo("Gateway Entry");
                });
    }

    @Test
    @DisplayName("测试当 redirect-docs=false 时不注册重定向配置")
    void testRedirectDocsDisabled() {
        contextRunner
                .withPropertyValues("ateng.boot.openapi.redirect-docs=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(OpenAPI.class);
                    assertThat(context).doesNotHaveBean("openApiWebMvcConfigurer");
                });
    }

    @Test
    @DisplayName("测试用户显式声明 OpenAPI Bean 时支持平滑覆盖 (@ConditionalOnMissingBean)")
    void testCustomOpenApiBeanOverride() {
        contextRunner
                .withUserConfiguration(CustomOpenApiConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(OpenAPI.class);
                    OpenAPI openAPI = context.getBean(OpenAPI.class);
                    assertThat(openAPI.getInfo().getTitle()).isEqualTo("User Overridden API");
                });
    }

    @Test
    @DisplayName("测试用户自定义 GlobalHeaderCustomizer 支持平滑覆盖 (@ConditionalOnMissingBean)")
    void testCustomHeaderCustomizerOverride() {
        contextRunner
                .withUserConfiguration(CustomHeaderCustomizerConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(GlobalHeaderCustomizer.class);
                    GlobalHeaderCustomizer customizer = context.getBean(GlobalHeaderCustomizer.class);
                    assertThat(customizer).isInstanceOf(SubHeaderCustomizer.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomHeaderCustomizerConfig {
        @Bean
        public GlobalHeaderCustomizer globalHeaderCustomizer(OpenApiProperties properties) {
            return new SubHeaderCustomizer(properties);
        }
    }

    static class SubHeaderCustomizer extends GlobalHeaderCustomizer {
        public SubHeaderCustomizer(OpenApiProperties properties) {
            super(properties);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomOpenApiConfig {
        @Bean
        public OpenAPI customOpenAPI() {
            return new OpenAPI().info(new Info().title("User Overridden API"));
        }
    }
}
