package io.github.atengk.boot.admin.autoconfigure;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.filter.SaServletFilter;
import cn.dev33.satoken.stp.StpUtil;
import de.codecentric.boot.admin.server.config.AdminServerMarkerConfiguration;
import de.codecentric.boot.admin.server.config.AdminServerProperties;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import io.github.atengk.boot.admin.controller.AdminLoginController;
import io.github.atengk.boot.admin.notifier.AtengAdminNotificationNotifier;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.provider.AdminAuthProvider;
import io.github.atengk.boot.admin.provider.AtengActuatorHttpHeadersProvider;
import io.github.atengk.boot.admin.provider.DefaultPropertiesAdminAuthProvider;
import io.github.atengk.boot.admin.security.AdminSsrfGuardFilter;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.StringUtils;

import java.util.concurrent.ScheduledExecutorService;

/**
 * Spring Boot Admin Server 企业级自动化装配类
 *
 * @author Ateng
 * @since 2026-10-26
 */
@AutoConfiguration(afterName = "de.codecentric.boot.admin.server.config.AdminServerAutoConfiguration")
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
    @ConditionalOnMissingBean
    public AtengActuatorHttpHeadersProvider atengActuatorHttpHeadersProvider(AtengAdminServerProperties properties) {
        return new AtengActuatorHttpHeadersProvider(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @Order(Ordered.HIGHEST_PRECEDENCE + 50)
    public AdminSsrfGuardFilter adminSsrfGuardFilter(AtengAdminServerProperties properties) {
        return new AdminSsrfGuardFilter(properties);
    }

    @Bean(name = "sbaNotificationTaskScheduler", destroyMethod = "destroy")
    @ConditionalOnMissingBean(name = "sbaNotificationTaskScheduler")
    public ThreadPoolTaskScheduler sbaNotificationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("sba-notify-debounce-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(InstanceRepository.class)
    public AtengAdminNotificationNotifier atengAdminNotificationNotifier(
            InstanceRepository repository,
            ObjectProvider<NotificationTemplate> notificationTemplateProvider,
            AtengAdminServerProperties properties,
            @Qualifier("sbaNotificationTaskScheduler") ObjectProvider<ThreadPoolTaskScheduler> schedulerProvider,
            ObjectProvider<ScheduledExecutorService> scheduledExecutorProvider) {
        ThreadPoolTaskScheduler taskScheduler = schedulerProvider.getIfAvailable();
        ScheduledExecutorService executor = taskScheduler != null ? taskScheduler.getScheduledExecutor() : scheduledExecutorProvider.getIfAvailable();
        return new AtengAdminNotificationNotifier(repository, notificationTemplateProvider, properties, executor);
    }

    /**
     * 规范化 Admin Server 的 context-path
     * 去除前后空白与末尾多余斜杠，确保始终以单个 '/' 开头（为空时返回空字符串）
     *
     * @param contextPath 原始 contextPath 字符串
     * @return 规范化后的 contextPath
     */
    public static String normalizeContextPath(String contextPath) {
        if (!StringUtils.hasText(contextPath)) {
            return "";
        }
        String path = contextPath.strip();
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.isEmpty() && !path.startsWith("/")) {
            path = "/" + path;
        }
        return "/".equals(path) ? "" : path;
    }

    private static boolean isInstancesEndpoint(String path, String contextPath) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        String target = contextPath.isEmpty() ? "/instances" : (contextPath + "/instances");
        return target.equals(normalized);
    }

    private static boolean isInstanceDetailEndpoint(String path, String contextPath) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        String targetPrefix = contextPath.isEmpty() ? "/instances/" : (contextPath + "/instances/");
        return path.startsWith(targetPrefix);
    }

    @Bean
    @ConditionalOnClass(SaServletFilter.class)
    @ConditionalOnMissingBean(name = "adminSaServletFilter")
    public SaServletFilter adminSaServletFilter(ObjectProvider<AdminServerProperties> adminServerPropertiesProvider) {
        AdminServerProperties adminServerProperties = adminServerPropertiesProvider.getIfAvailable();
        final String contextPath = normalizeContextPath(adminServerProperties != null ? adminServerProperties.getContextPath() : null);
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
                        "/login",
                        "/logout",
                        contextPath + "/assets/**",
                        "/assets/**",
                        contextPath + "/actuator/health",
                        contextPath + "/actuator/info",
                        "/actuator/health",
                        "/actuator/info",
                        "/favicon.ico"
                )
                .setAuth(obj -> {
                    String reqPath = SaHolder.getRequest().getRequestPath();
                    String method = SaHolder.getRequest().getMethod();

                    // 1. 放行微服务客户端生命周期通信（注册 POST /instances 与下线注销 DELETE /instances/{id}）
                    // 实际安全校验由前沿 AdminSsrfGuardFilter（X-Admin-Client-Secret 与 SSRF 清洗）深度守护
                    boolean isInstanceLifecycle = ("POST".equalsIgnoreCase(method) && isInstancesEndpoint(reqPath, contextPath))
                            || ("DELETE".equalsIgnoreCase(method) && isInstanceDetailEndpoint(reqPath, contextPath));
                    if (isInstanceLifecycle) {
                        return;
                    }

                    // 2. 核心看板与敏感管理端点强制校验 Sa-Token 登录态
                    StpUtil.checkLogin();
                })
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
