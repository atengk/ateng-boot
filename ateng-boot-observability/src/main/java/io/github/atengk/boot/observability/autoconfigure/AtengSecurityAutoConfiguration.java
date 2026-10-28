package io.github.atengk.boot.observability.autoconfigure;

import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import io.github.atengk.boot.observability.security.ActuatorEndpointSecurityFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Actuator 端点生产安全守卫自动装配配置
 * <p>
 * 仅在 Servlet Web 环境下自动生效，装配 {@link ActuatorEndpointSecurityFilter} 拦截高危敏感端点并实施鉴权防护。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(OncePerRequestFilter.class)
@ConditionalOnProperty(prefix = "ateng.boot.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class AtengSecurityAutoConfiguration {

    /**
     * 注册 Actuator 端点高优先级安全守卫过滤器
     */
    @Bean
    @ConditionalOnMissingBean(ActuatorEndpointSecurityFilter.class)
    @ConditionalOnProperty(prefix = "ateng.boot.observability.security", name = "lockdown-enabled", havingValue = "true", matchIfMissing = true)
    public ActuatorEndpointSecurityFilter actuatorEndpointSecurityFilter(
            ObservabilityProperties properties,
            Environment environment) {
        String basePath = environment.getProperty("management.endpoints.web.base-path", ActuatorEndpointSecurityFilter.DEFAULT_BASE_PATH);
        return new ActuatorEndpointSecurityFilter(properties, basePath);
    }
}
