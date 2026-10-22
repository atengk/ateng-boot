package io.github.atengk.boot.openapi.autoconfigure;

import io.github.atengk.boot.openapi.guard.OpenApiGuardFilter;
import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * OpenAPI 3.0 / Swagger 接口文档自动化装配配置
 *
 * @author Ateng
 * @since 2026-10-22
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(OpenAPI.class)
@EnableConfigurationProperties(OpenApiProperties.class)
public class OpenApiAutoConfiguration {

    /**
     * 注册基础 OpenAPI 元数据契约 Bean
     *
     * @param properties 配置属性
     * @return 预配置的 OpenAPI 对象
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.openapi", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OpenAPI customOpenAPI(OpenApiProperties properties) {
        OpenAPI openAPI = new OpenAPI();

        // 1. 构建 Info 元数据
        Info info = new Info()
                .title(properties.getTitle())
                .description(properties.getDescription())
                .version(properties.getVersion());

        if (StringUtils.hasText(properties.getTermsOfService())) {
            info.termsOfService(properties.getTermsOfService());
        }

        // 联系人
        OpenApiProperties.Contact contactProp = properties.getContact();
        if (contactProp != null && (StringUtils.hasText(contactProp.getName())
                || StringUtils.hasText(contactProp.getUrl())
                || StringUtils.hasText(contactProp.getEmail()))) {
            Contact contact = new Contact()
                    .name(contactProp.getName())
                    .url(contactProp.getUrl())
                    .email(contactProp.getEmail());
            info.contact(contact);
        }

        // 许可证
        OpenApiProperties.License licenseProp = properties.getLicense();
        if (licenseProp != null && StringUtils.hasText(licenseProp.getName())) {
            License license = new License()
                    .name(licenseProp.getName())
                    .url(licenseProp.getUrl());
            info.license(license);
        }

        openAPI.info(info);

        // 2. 构建多环境 Servers 列表
        List<OpenApiProperties.Server> servers = properties.getServers();
        if (servers != null && !servers.isEmpty()) {
            for (OpenApiProperties.Server s : servers) {
                if (StringUtils.hasText(s.getUrl())) {
                    openAPI.addServersItem(new Server().url(s.getUrl()).description(s.getDescription()));
                }
            }
        } else {
            openAPI.addServersItem(new Server().url("/").description("Default Gateway / Server"));
        }

        return openAPI;
    }

    /**
     * 注册极简 /docs 与 /doc 友好路由重定向
     *
     * @param properties 配置属性
     * @return WebMvcConfigurer 视图重定向配置
     */
    @Bean
    @ConditionalOnMissingBean(name = "openApiWebMvcConfigurer")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${ateng.boot.openapi.enabled:true} and ${ateng.boot.openapi.redirect-docs:true}")
    public WebMvcConfigurer openApiWebMvcConfigurer(OpenApiProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addViewControllers(ViewControllerRegistry registry) {
                if (properties.isRedirectDocs()) {
                    registry.addRedirectViewController("/docs", "/swagger-ui/index.html");
                    registry.addRedirectViewController("/doc", "/swagger-ui/index.html");
                }
            }
        };
    }

    /**
     * 注册高优先级 OpenAPI 生产安全守卫过滤器
     * <p>
     * 无论是否启用 OpenAPI 均全局生效：
     * - {@code enabled=false} 时执行物理切断拦截，响应 HTTP 404；
     * - {@code enabled=true} 且开启守卫时执行 Basic / Sa-Token 访问认证。
     * </p>
     *
     * @param properties 配置属性
     * @return 过滤器注册 Bean
     */
    @Bean
    @ConditionalOnMissingBean(name = "openApiGuardFilterRegistration")
    public FilterRegistrationBean<OpenApiGuardFilter> openApiGuardFilterRegistration(OpenApiProperties properties) {
        OpenApiGuardFilter filter = new OpenApiGuardFilter(properties);
        FilterRegistrationBean<OpenApiGuardFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("openApiGuardFilter");
        registration.setOrder(OpenApiGuardFilter.ORDER);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
