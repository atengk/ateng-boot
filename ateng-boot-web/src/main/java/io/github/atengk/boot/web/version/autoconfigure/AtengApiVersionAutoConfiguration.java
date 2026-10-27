package io.github.atengk.boot.web.version.autoconfigure;

import io.github.atengk.boot.web.version.mapping.ApiVersionRequestMappingHandlerMapping;
import io.github.atengk.boot.web.version.properties.ApiVersionProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * API 多版本控制自动装配
 * 装配感知 {@link io.github.atengk.boot.web.version.annotation.ApiVersion} 的 {@link WebMvcRegistrations} 扩展点与 {@link ApiVersionRequestMappingHandlerMapping}
 *
 * @author Ateng
 * @since 2026-10-27
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(WebMvcRegistrations.class)
@EnableConfigurationProperties(ApiVersionProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.web.api-version", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengApiVersionAutoConfiguration {

    /**
     * 注册 API 版本感知型 WebMvcRegistrations 扩展点
     *
     * @param properties API 版本配置属性
     * @return WebMvcRegistrations 实例
     */
    @Bean
    @ConditionalOnMissingBean(name = "apiVersionWebMvcRegistrations")
    public WebMvcRegistrations apiVersionWebMvcRegistrations(ApiVersionProperties properties) {
        return new WebMvcRegistrations() {
            @Override
            public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
                return new ApiVersionRequestMappingHandlerMapping(properties);
            }
        };
    }
}
