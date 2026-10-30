package io.github.atengk.boot.openapi.autoconfigure;

import io.github.atengk.boot.openapi.converter.OpenApiModelConverter;
import io.github.atengk.boot.openapi.customizer.GlobalHeaderCustomizer;
import io.github.atengk.boot.openapi.customizer.ResultCodeDictionaryCustomizer;
import io.github.atengk.boot.openapi.customizer.SaTokenSecurityCustomizer;
import io.github.atengk.boot.openapi.customizer.SecuritySchemesCustomizer;
import io.github.atengk.boot.openapi.customizer.StandardApiResponseCustomizer;
import io.github.atengk.boot.openapi.export.OpenApiExportController;
import io.github.atengk.boot.openapi.export.OpenApiExportHelper;
import io.github.atengk.boot.openapi.group.DynamicGroupedOpenApiRegistrar;
import io.github.atengk.boot.openapi.guard.OpenApiGuardFilter;
import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import io.github.atengk.boot.openapi.spi.ResultCodeProvider;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.webmvc.api.MultipleOpenApiWebMvcResource;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Collections;
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
@Import(DynamicGroupedOpenApiRegistrar.class)
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
     * - {@code enabled=true} 且开启守卫时执行 Basic / Sa-Token / Token / IP 白名单访问认证。
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

    /**
     * 注册全局请求头自动注入定制器 (OperationCustomizer)
     *
     * @param properties 配置属性
     * @return 全局请求头定制器 Bean
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.openapi", name = "enabled", havingValue = "true", matchIfMissing = true)
    public GlobalHeaderCustomizer globalHeaderCustomizer(OpenApiProperties properties) {
        return new GlobalHeaderCustomizer(properties);
    }

    /**
     * 注册 Sa-Token 锁头鉴权定制器 (OpenApiCustomizer)
     * <p>仅当引入 Sa-Token 依赖且 OpenAPI 启用时激活</p>
     *
     * @return Sa-Token 锁头鉴权定制器 Bean
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "cn.dev33.satoken.SaManager")
    @ConditionalOnProperty(prefix = "ateng.boot.openapi", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SaTokenSecurityCustomizer saTokenSecurityCustomizer() {
        return new SaTokenSecurityCustomizer();
    }

    /**
     * 注册系统通用与业务扩展状态码全景字典注入定制器 (OpenApiCustomizer)
     *
     * @param properties        配置属性
     * @param providersProvider 业务状态码扩展 Provider 提供者
     * @return 状态码字典定制器 Bean
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.openapi", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ResultCodeDictionaryCustomizer resultCodeDictionaryCustomizer(
            OpenApiProperties properties,
            ObjectProvider<ResultCodeProvider> providersProvider) {
        List<ResultCodeProvider> providers = providersProvider.orderedStream().toList();
        return new ResultCodeDictionaryCustomizer(properties, providers);
    }

    /**
     * 注册通用标准 HTTP 响应说明定制器 (OperationCustomizer)
     *
     * @param properties 配置属性
     * @return 标准响应说明定制器 Bean
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.openapi", name = "enabled", havingValue = "true", matchIfMissing = true)
    public StandardApiResponseCustomizer standardApiResponseCustomizer(OpenApiProperties properties) {
        return new StandardApiResponseCustomizer(properties);
    }

    /**
     * 注册通用 SecuritySchemes 配置化定制器 (OpenApiCustomizer)
     * <p>根据配置文件声明的 security-schemes 列表动态注册安全方案并激活锁头</p>
     *
     * @param properties 配置属性
     * @return 通用鉴权定制器 Bean
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.openapi", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SecuritySchemesCustomizer securitySchemesCustomizer(OpenApiProperties properties) {
        return new SecuritySchemesCustomizer(properties);
    }

    /**
     * 注册契约模型转换器 (ModelConverter)
     * <p>自动对齐 IBaseEnum 强类型 Schema/字典、ValidList 数组契约与 @FieldSensitive 掩码警示</p>
     *
     * @return 契约模型转换器 Bean
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.openapi", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OpenApiModelConverter openApiModelConverter() {
        return new OpenApiModelConverter();
    }

    /**
     * 注册离线契约导出门面 (OpenApiExportHelper)
     *
     * @param multipleOpenApiResourceProvider 多分组 WebMvc 资源提供者
     * @param openApiResourceProvider         默认单分组 WebMvc 资源提供者
     * @param openApiProvider                 OpenAPI 基础对象提供者
     * @param groupedOpenApisProvider         分组列表提供者
     * @return 离线契约导出门面 Bean
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.openapi", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OpenApiExportHelper openApiExportHelper(
            ObjectProvider<MultipleOpenApiWebMvcResource> multipleOpenApiResourceProvider,
            ObjectProvider<OpenApiWebMvcResource> openApiResourceProvider,
            ObjectProvider<OpenAPI> openApiProvider,
            ObjectProvider<List<GroupedOpenApi>> groupedOpenApisProvider) {
        List<GroupedOpenApi> groupedOpenApis = groupedOpenApisProvider.getIfAvailable(Collections::emptyList);
        return new OpenApiExportHelper(multipleOpenApiResourceProvider, openApiResourceProvider, openApiProvider, groupedOpenApis);
    }

    /**
     * 注册受管离线导出 REST 控制器 (OpenApiExportController)
     *
     * @param exportHelper 离线契约导出门面
     * @return 导出控制器 Bean
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(OpenApiExportHelper.class)
    @ConditionalOnProperty(prefix = "ateng.boot.openapi.export", name = "enabled", havingValue = "true")
    public OpenApiExportController openApiExportController(OpenApiExportHelper exportHelper) {
        return new OpenApiExportController(exportHelper);
    }
}
