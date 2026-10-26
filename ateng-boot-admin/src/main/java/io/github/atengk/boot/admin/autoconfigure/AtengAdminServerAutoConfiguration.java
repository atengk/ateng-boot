package io.github.atengk.boot.admin.autoconfigure;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.filter.SaServletFilter;
import cn.dev33.satoken.stp.StpUtil;
import de.codecentric.boot.admin.server.config.AdminServerMarkerConfiguration;
import de.codecentric.boot.admin.server.config.AdminServerProperties;
import io.github.atengk.boot.admin.controller.AdminLoginController;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.provider.AdminAuthProvider;
import io.github.atengk.boot.admin.provider.DefaultPropertiesAdminAuthProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * Spring Boot Admin Server 企业级自动化装配类
 *
 * @author Ateng
 * @since 2026-10-26
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(AdminServerMarkerConfiguration.Marker.class)
@ConditionalOnBean(AdminServerMarkerConfiguration.Marker.class)
@ConditionalOnProperty(prefix = "ateng.boot.admin.server", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AtengAdminServerProperties.class)
public class AtengAdminServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AdminAuthProvider adminAuthProvider(AtengAdminServerProperties properties) {
        return new DefaultPropertiesAdminAuthProvider(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminLoginController adminLoginController(AdminAuthProvider authProvider,
                                                     ObjectProvider<AdminServerProperties> adminServerPropertiesProvider) {
        AdminServerProperties adminServerProperties = adminServerPropertiesProvider.getIfAvailable();
        return new AdminLoginController(authProvider, adminServerProperties);
    }

    @Bean
    @ConditionalOnClass(SaServletFilter.class)
    @ConditionalOnMissingBean(name = "adminSaServletFilter")
    public SaServletFilter adminSaServletFilter(ObjectProvider<AdminServerProperties> adminServerPropertiesProvider) {
        AdminServerProperties adminServerProperties = adminServerPropertiesProvider.getIfAvailable();
        String rawPath = (adminServerProperties != null && StringUtils.hasText(adminServerProperties.getContextPath()))
                ? adminServerProperties.getContextPath().strip()
                : "";
        if (rawPath.endsWith("/") && rawPath.length() > 1) {
            rawPath = rawPath.substring(0, rawPath.length() - 1);
        }
        final String contextPath = rawPath;
        final String loginPath = contextPath.isEmpty() ? "/login" : (contextPath + "/login");
        final String logoutPath = contextPath.isEmpty() ? "/logout" : (contextPath + "/logout");

        SaServletFilter filter = new SaServletFilter();
        if (contextPath.isEmpty()) {
            filter.addInclude("/**");
        } else {
            filter.addInclude(contextPath, contextPath + "/**");
        }

        return filter.addExclude(
                        loginPath,
                        logoutPath,
                        contextPath + "/assets/**",
                        contextPath + "/instances",
                        contextPath + "/instances/**",
                        contextPath + "/actuator/**",
                        "/login",
                        "/logout",
                        "/assets/**",
                        "/instances",
                        "/instances/**",
                        "/actuator/**",
                        "/favicon.ico"
                )
                .setAuth(obj -> StpUtil.checkLogin())
                .setError(e -> {
                    String accept = SaHolder.getRequest().getHeader("Accept");
                    boolean isHtmlRequest = accept != null && accept.contains("text/html");
                    if (isHtmlRequest && !SaHolder.getRequest().isAjax()) {
                        SaHolder.getResponse().redirect(loginPath);
                    } else {
                        SaHolder.getResponse().setStatus(401);
                        SaHolder.getResponse().setHeader("Content-Type", "application/json;charset=UTF-8");
                        return "{\"code\":401,\"message\":\"Unauthorized\"}";
                    }
                    return "";
                });
    }
}
