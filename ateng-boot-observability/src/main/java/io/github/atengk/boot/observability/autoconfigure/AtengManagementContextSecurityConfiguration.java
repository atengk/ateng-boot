package io.github.atengk.boot.observability.autoconfigure;

import io.github.atengk.boot.observability.security.ActuatorEndpointSecurityFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.SearchStrategy;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;

import java.util.EnumSet;

/**
 * 独立管理端口子上下文 Actuator 安全守卫桥接自动配置
 * <p>
 * 当配置 {@code management.server.port != server.port} 时，Spring Boot 会为管理端口创建独立子
 * Servlet 上下文（{@link ManagementContextType#CHILD}），主上下文注册的 Servlet Filter 不会自动挂载到子容器。
 * 本配置仅在独立管理端口子上下文激活，通过轻量委托 {@link FilterRegistrationBean} 复用主上下文的
 * {@link ActuatorEndpointSecurityFilter} 实例，避免子容器重复初始化或停机时提前销毁父 Filter，从而确保双端口隔离模式下
 * 凭据鉴权、CIDR 白名单、404 隐身与 {@code ActuatorAccessDeniedEvent} 审计能力与单端口模式保持一致。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-28
 */
@ManagementContextConfiguration(value = ManagementContextType.CHILD, proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({OncePerRequestFilter.class, FilterRegistrationBean.class})
@ConditionalOnProperty(prefix = "ateng.boot.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengManagementContextSecurityConfiguration {

    /**
     * 独立管理端口安全过滤器注册 Bean 名称
     */
    public static final String MANAGEMENT_SECURITY_FILTER_REGISTRATION_BEAN_NAME =
            "atengManagementActuatorEndpointSecurityFilterRegistration";

    /**
     * 独立管理端口安全过滤器注册名称
     */
    public static final String MANAGEMENT_SECURITY_FILTER_NAME =
            "atengManagementActuatorEndpointSecurityFilter";

    /**
     * 向独立管理端口子 Servlet 容器桥接挂载主上下文中的 {@link ActuatorEndpointSecurityFilter}。
     *
     * @param parentSecurityFilter 主上下文装配的安全守卫过滤器实例（支持用户自定义覆盖 Bean）
     * @return 轻量委托的 {@link FilterRegistrationBean}
     */
    @Bean(name = MANAGEMENT_SECURITY_FILTER_REGISTRATION_BEAN_NAME)
    @ConditionalOnBean(ActuatorEndpointSecurityFilter.class)
    @ConditionalOnMissingBean(
            value = ActuatorEndpointSecurityFilter.class,
            name = MANAGEMENT_SECURITY_FILTER_REGISTRATION_BEAN_NAME,
            search = SearchStrategy.CURRENT
    )
    @ConditionalOnProperty(
            prefix = "ateng.boot.observability.security",
            name = {"lockdown-enabled", "management-context-enabled"},
            havingValue = "true",
            matchIfMissing = true
    )
    public FilterRegistrationBean<Filter> managementActuatorEndpointSecurityFilterRegistration(
            ActuatorEndpointSecurityFilter parentSecurityFilter) {
        // 使用无状态委托 Filter 避免子容器生命周期回调干扰主上下文 OncePerRequestFilter 的 filterConfig 状态
        Filter delegate = (request, response, chain) ->
                parentSecurityFilter.doFilter(request, response, chain);
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(delegate);
        registration.setName(MANAGEMENT_SECURITY_FILTER_NAME);
        registration.setOrder(parentSecurityFilter.getOrder());
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR));
        return registration;
    }
}
