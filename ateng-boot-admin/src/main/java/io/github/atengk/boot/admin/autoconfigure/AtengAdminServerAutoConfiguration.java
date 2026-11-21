package io.github.atengk.boot.admin.autoconfigure;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.filter.SaServletFilter;
import cn.dev33.satoken.stp.StpUtil;
import de.codecentric.boot.admin.server.config.AdminServerMarkerConfiguration;
import de.codecentric.boot.admin.server.config.AdminServerProperties;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.services.InstanceRegistry;
import io.github.atengk.boot.admin.admission.AdminInstanceAdmissionPolicy;
import io.github.atengk.boot.admin.admission.DefaultAdminInstanceAdmissionPolicy;
import io.github.atengk.boot.admin.cluster.AdminClusterCoordinator;
import io.github.atengk.boot.admin.cluster.InMemoryAdminClusterCoordinator;
import io.github.atengk.boot.admin.cluster.RedisAdminClusterCoordinator;
import io.github.atengk.boot.admin.controller.AdminLoginController;
import io.github.atengk.boot.admin.eventstore.AtengInMemoryEventStore;
import io.github.atengk.boot.admin.notifier.AtengAdminNotificationNotifier;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.provider.AdminAuthProvider;
import io.github.atengk.boot.admin.provider.AtengActuatorHttpHeadersProvider;
import io.github.atengk.boot.admin.provider.DefaultPropertiesAdminAuthProvider;
import io.github.atengk.boot.admin.scheduler.ZombieInstanceCleanupScheduler;
import io.github.atengk.boot.admin.security.AdminPathMatcher;
import io.github.atengk.boot.admin.security.AdminSsrfGuardFilter;
import io.github.atengk.boot.admin.security.AdminWriteGuardFilter;
import io.github.atengk.boot.admin.silence.AdminSilenceManager;
import io.github.atengk.boot.admin.silence.DefaultAdminSilenceManager;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Spring Boot Admin Server 企业级自动化装配类
 *
 * @author Ateng
 * @since 2026-10-26
 */
@AutoConfiguration(afterName = {
        "de.codecentric.boot.admin.server.config.AdminServerAutoConfiguration",
        "io.github.atengk.boot.admin.autoconfigure.AtengAdminServerDefenseAutoConfiguration"
})
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
    public AdminInstanceAdmissionPolicy adminInstanceAdmissionPolicy(AtengAdminServerProperties properties) {
        return new DefaultAdminInstanceAdmissionPolicy(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @Order(AdminSsrfGuardFilter.DEFAULT_ORDER)
    public AdminSsrfGuardFilter adminSsrfGuardFilter(AdminInstanceAdmissionPolicy admissionPolicy,
                                                     AtengAdminServerProperties properties,
                                                     ObjectProvider<AdminServerProperties> adminServerPropertiesProvider) {
        return new AdminSsrfGuardFilter(
                admissionPolicy,
                properties,
                adminServerPropertiesProvider.getIfAvailable()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @Order(Ordered.HIGHEST_PRECEDENCE + 60)
    public AdminWriteGuardFilter adminWriteGuardFilter(AtengAdminServerProperties properties,
                                                       ObjectProvider<AdminServerProperties> adminServerPropertiesProvider) {
        AdminServerProperties adminServerProperties = adminServerPropertiesProvider.getIfAvailable();
        return new AdminWriteGuardFilter(properties, adminServerProperties);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnProperty(prefix = "ateng.boot.admin.server.cluster", name = "enabled", havingValue = "true")
    static class RedisClusterCoordinatorConfiguration {

        @Bean(name = "adminClusterCoordinator")
        @ConditionalOnMissingBean(AdminClusterCoordinator.class)
        public AdminClusterCoordinator redisAdminClusterCoordinator(StringRedisTemplate stringRedisTemplate,
                                                                   AtengAdminServerProperties properties,
                                                                   ObjectProvider<Clock> clockProvider) {
            String keyPrefix = properties != null && properties.getCluster() != null
                    ? properties.getCluster().getKeyPrefix()
                    : "ateng:admin:";
            Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
            return new RedisAdminClusterCoordinator(stringRedisTemplate, keyPrefix, clock);
        }
    }

    @Bean
    @ConditionalOnMissingBean(AdminClusterCoordinator.class)
    public AdminClusterCoordinator inMemoryAdminClusterCoordinator(ObjectProvider<Clock> clockProvider) {
        return new InMemoryAdminClusterCoordinator(clockProvider.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean(AdminSilenceManager.class)
    public AdminSilenceManager adminSilenceManager(AdminClusterCoordinator clusterCoordinator,
                                                   AtengAdminServerProperties properties,
                                                   ObjectProvider<Clock> clockProvider) {
        Duration defaultTtl = properties != null && properties.getCluster() != null
                ? properties.getCluster().getSilenceRuleDefaultTtl()
                : Duration.ofHours(2);
        return new DefaultAdminSilenceManager(clusterCoordinator, defaultTtl, clockProvider.getIfAvailable(Clock::systemUTC));
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

    @Bean(name = "sbaCleanupTaskScheduler", destroyMethod = "destroy")
    @ConditionalOnMissingBean(name = "sbaCleanupTaskScheduler")
    public ThreadPoolTaskScheduler sbaCleanupTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("sba-zombie-cleanup-");
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
            ObjectProvider<ScheduledExecutorService> scheduledExecutorProvider,
            ObjectProvider<AdminSilenceManager> silenceManagerProvider,
            ObjectProvider<AdminClusterCoordinator> clusterCoordinatorProvider,
            ObjectProvider<Clock> clockProvider) {
        ThreadPoolTaskScheduler taskScheduler = schedulerProvider.getIfAvailable();
        ScheduledExecutorService executor = taskScheduler != null ? taskScheduler.getScheduledExecutor() : scheduledExecutorProvider.getIfAvailable();
        return new AtengAdminNotificationNotifier(
                repository,
                notificationTemplateProvider,
                properties,
                executor,
                silenceManagerProvider.getIfAvailable(),
                clusterCoordinatorProvider.getIfAvailable(),
                clockProvider.getIfAvailable()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(InstanceRepository.class)
    @ConditionalOnProperty(prefix = "ateng.boot.admin.server.cleanup", name = "enabled", havingValue = "true")
    public ZombieInstanceCleanupScheduler zombieInstanceCleanupScheduler(
            InstanceRepository repository,
            ObjectProvider<InstanceRegistry> registryProvider,
            @Qualifier("sbaCleanupTaskScheduler") ObjectProvider<ThreadPoolTaskScheduler> cleanupSchedulerProvider,
            AtengAdminServerProperties properties,
            ObjectProvider<AdminClusterCoordinator> clusterCoordinatorProvider,
            ObjectProvider<AtengInMemoryEventStore> eventStoreProvider,
            ObjectProvider<AtengAdminNotificationNotifier> notifierProvider) {
        AtengAdminNotificationNotifier notifier = notifierProvider.getIfAvailable();
        return new ZombieInstanceCleanupScheduler(
                repository,
                registryProvider.getIfAvailable(),
                properties,
                cleanupSchedulerProvider.getIfAvailable(),
                clusterCoordinatorProvider.getIfAvailable(),
                eventStoreProvider.getIfAvailable(),
                notifier != null ? notifier::purgeInstanceState : null
        );
    }

    /**
     * 规范化 Admin Server 的 context-path
     * 去除前后空白与末尾多余斜杠，确保始终以单个 '/' 开头（为空时返回空字符串）
     *
     * @param contextPath 原始 contextPath 字符串
     * @return 规范化后的 contextPath
     */
    public static String normalizeContextPath(String contextPath) {
        return AdminPathMatcher.normalizeContextPath(contextPath);
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
                    String servletContextPath = "";
                    Object source = SaHolder.getRequest().getSource();
                    if (source instanceof HttpServletRequest servletRequest) {
                        reqPath = servletRequest.getRequestURI();
                        servletContextPath = servletRequest.getContextPath();
                    }
                    String method = SaHolder.getRequest().getMethod();

                    // 1. 放行微服务客户端生命周期通信（严格锚定 contextPath：注册 POST /instances 与单段注销 DELETE /instances/{id}）
                    // 实际安全校验由前沿 AdminSsrfGuardFilter（X-Admin-Client-Secret / 会话态与 SSRF 清洗）深度守护
                    boolean isInstanceLifecycle = ("POST".equalsIgnoreCase(method)
                            && AdminPathMatcher.isInstancesCollectionEndpoint(reqPath, servletContextPath, contextPath))
                            || ("DELETE".equalsIgnoreCase(method)
                            && AdminPathMatcher.isSingleInstanceEndpoint(reqPath, servletContextPath, contextPath));
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
