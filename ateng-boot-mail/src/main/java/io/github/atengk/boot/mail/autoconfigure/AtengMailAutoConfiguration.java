package io.github.atengk.boot.mail.autoconfigure;

import io.github.atengk.boot.core.autoconfigure.AsyncExecutorFactory;
import io.github.atengk.boot.core.autoconfigure.AtengAsyncAutoConfiguration;
import io.github.atengk.boot.mail.guard.InMemoryMailDailyQuotaBreaker;
import io.github.atengk.boot.mail.guard.InMemoryMailIdempotentGuard;
import io.github.atengk.boot.mail.guard.InMemoryMailRateLimiter;
import io.github.atengk.boot.mail.guard.MailDailyQuotaBreaker;
import io.github.atengk.boot.mail.guard.MailIdempotentGuard;
import io.github.atengk.boot.mail.guard.MailRateLimiter;
import io.github.atengk.boot.mail.guard.RedisMailDailyQuotaBreaker;
import io.github.atengk.boot.mail.guard.RedisMailIdempotentGuard;
import io.github.atengk.boot.mail.guard.RedisMailRateLimiter;
import io.github.atengk.boot.mail.handler.MailSendFailureHandler;
import io.github.atengk.boot.mail.interceptor.MailSandboxInterceptor;
import io.github.atengk.boot.mail.interceptor.MailSendInterceptor;
import io.github.atengk.boot.mail.listener.MailAccountEvictListener;
import io.github.atengk.boot.mail.model.AuthMode;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.properties.MailAccountProperties;
import io.github.atengk.boot.mail.properties.MailProperties;
import io.github.atengk.boot.mail.provider.MailAccessTokenProvider;
import io.github.atengk.boot.mail.provider.MailAccountProvider;
import io.github.atengk.boot.mail.registry.MailAccountRegistry;
import io.github.atengk.boot.mail.resolver.MailAccountResolver;
import io.github.atengk.boot.mail.resolver.TenantMailAccountResolver;
import io.github.atengk.boot.mail.template.MailTemplate;
import io.github.atengk.boot.mail.template.renderer.FreemarkerMailTemplateRenderer;
import io.github.atengk.boot.mail.template.renderer.MailTemplateRenderer;
import io.github.atengk.boot.mail.template.renderer.SimpleMailTemplateRenderer;
import io.github.atengk.boot.mail.template.renderer.ThymeleafMailTemplateRenderer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.util.StringUtils;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * 邮件通信基础设施自动装配入口
 *
 * @author Ateng
 * @since 2026-10-22
 */
@AutoConfiguration(
        after = AtengAsyncAutoConfiguration.class,
        afterName = {
                "org.springframework.boot.autoconfigure.thymeleaf.ThymeleafAutoConfiguration",
                "org.springframework.boot.autoconfigure.freemarker.FreeMarkerAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
        }
)
@ConditionalOnClass(JavaMailSender.class)
@ConditionalOnProperty(prefix = "ateng.boot.mail", name = "enabled", havingValue = "true", matchIfMissing = true)
@Conditional(MailConfiguredCondition.class)
@EnableConfigurationProperties(MailProperties.class)
public class AtengMailAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MailAccountRegistry mailAccountRegistry(MailProperties properties, Environment env,
                                                   ObjectProvider<MailAccessTokenProvider> accessTokenProviderProvider) {
        MailAccountRegistry registry = new MailAccountRegistry(
                properties.getDefaultAccount(),
                properties.getMaxDynamicAccounts(),
                properties,
                accessTokenProviderProvider.getIfAvailable()
        );
        Map<String, MailAccountProperties> accountMap = properties.getAccounts();

        if (accountMap != null && !accountMap.isEmpty()) {
            for (Map.Entry<String, MailAccountProperties> entry : accountMap.entrySet()) {
                String name = entry.getKey();
                MailAccountProperties props = entry.getValue();
                registry.register(createMailAccount(name, props, properties));
            }
        } else if (StringUtils.hasText(properties.getHost())) {
            // 顶级单账号快捷配置
            MailAccountProperties props = new MailAccountProperties();
            props.setHost(properties.getHost());
            props.setPort(properties.getPort());
            props.setUsername(properties.getUsername());
            props.setPassword(properties.getPassword());
            props.setAuthMode(properties.getAuthMode());
            props.setAccessToken(properties.getAccessToken());
            props.setSslTrust(properties.getSslTrust());
            props.setProtocol(properties.getProtocol());
            props.setSsl(properties.isSsl());
            props.setStarttls(properties.isStarttls());
            props.setDefaultFrom(properties.getDefaultFrom());
            props.setDefaultFromName(properties.getDefaultFromName());
            props.setArchiveBcc(properties.getArchiveBcc());
            props.setProperties(properties.getProperties());
            props.setBackupAccounts(properties.getBackupAccounts());
            registry.register(createMailAccount(properties.getDefaultAccount(), props, properties));
        } else if (StringUtils.hasText(env.getProperty("spring.mail.host"))) {
            // Spring 官方原生配置平滑保底
            MailAccountProperties props = new MailAccountProperties();
            props.setHost(env.getProperty("spring.mail.host"));
            String portStr = env.getProperty("spring.mail.port");
            if (StringUtils.hasText(portStr)) {
                try {
                    props.setPort(Integer.parseInt(portStr));
                } catch (NumberFormatException ignored) {
                }
            }
            props.setUsername(env.getProperty("spring.mail.username"));
            props.setPassword(env.getProperty("spring.mail.password"));
            props.setProtocol(env.getProperty("spring.mail.protocol", "smtp"));
            String sslEnable = env.getProperty("spring.mail.properties.mail.smtp.ssl.enable");
            if (sslEnable != null) {
                props.setSsl(Boolean.parseBoolean(sslEnable));
            }
            String starttlsEnable = env.getProperty("spring.mail.properties.mail.smtp.starttls.enable");
            if (starttlsEnable != null) {
                props.setStarttls(Boolean.parseBoolean(starttlsEnable));
            }
            // 完整透传 spring.mail.properties.* 原生扩展配置（遵循环境配置源优先级，高优先级源不被低优先级源覆盖）
            if (env instanceof ConfigurableEnvironment configurableEnv) {
                Map<String, String> extraProps = new LinkedHashMap<>();
                for (PropertySource<?> ps : configurableEnv.getPropertySources()) {
                    if (ps instanceof EnumerablePropertySource<?> eps) {
                        for (String key : eps.getPropertyNames()) {
                            if (key.startsWith("spring.mail.properties.")) {
                                String propKey = key.substring("spring.mail.properties.".length());
                                if (!extraProps.containsKey(propKey)) {
                                    String val = env.getProperty(key);
                                    if (val != null) {
                                        extraProps.put(propKey, val);
                                    }
                                }
                            }
                        }
                    }
                }
                if (!extraProps.isEmpty()) {
                    props.setProperties(extraProps);
                }
            }
            registry.register(createMailAccount(properties.getDefaultAccount(), props, properties));
        }

        return registry;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SpringTemplateEngine.class)
    public static class ThymeleafTemplateConfiguration {

        @Bean
        @ConditionalOnMissingBean(MailTemplateRenderer.class)
        @ConditionalOnBean(SpringTemplateEngine.class)
        public MailTemplateRenderer thymeleafMailTemplateRenderer(SpringTemplateEngine templateEngine) {
            return new ThymeleafMailTemplateRenderer(templateEngine);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(freemarker.template.Configuration.class)
    public static class FreemarkerTemplateConfiguration {

        @Bean
        @ConditionalOnMissingBean(MailTemplateRenderer.class)
        @ConditionalOnBean(freemarker.template.Configuration.class)
        public MailTemplateRenderer freemarkerMailTemplateRenderer(freemarker.template.Configuration configuration) {
            return new FreemarkerMailTemplateRenderer(configuration);
        }
    }

    @Bean
    @ConditionalOnMissingBean(MailTemplateRenderer.class)
    public MailTemplateRenderer simpleMailTemplateRenderer(MailProperties properties) {
        boolean htmlEscape = properties.getTemplate() != null && properties.getTemplate().isHtmlEscape();
        return new SimpleMailTemplateRenderer(htmlEscape);
    }

    @Bean
    @ConditionalOnMissingBean(MailAccountResolver.class)
    public MailAccountResolver mailAccountResolver() {
        return new TenantMailAccountResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public MailAccountEvictListener mailAccountEvictListener(MailAccountRegistry registry) {
        return new MailAccountEvictListener(registry);
    }

    /**
     * 注册邮件安全沙箱前置拦截器 Bean
     * 设定为框架最高优先级 (Ordered.HIGHEST_PRECEDENCE)，支持白名单放行、重定向清场与纯日志模拟发信
     *
     * @param properties 邮件全局配置属性
     * @return 邮件沙箱拦截器实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.mail.sandbox", name = "enabled", havingValue = "true")
    public MailSandboxInterceptor mailSandboxInterceptor(MailProperties properties) {
        return new MailSandboxInterceptor(properties.getSandbox());
    }

    /**
     * 注册专职邮件受管异步执行器 Bean
     * 依据 ateng.boot.mail.async 配置参数构建，双模自适应平台线程池与虚拟线程，自动挂载四维上下文透传装饰器
     *
     * @param properties  邮件模块全局配置
     * @param environment Spring 运行时环境
     * @return 受管异步执行器实例
     */
    @Bean(name = "mailTaskExecutor")
    @ConditionalOnMissingBean(name = "mailTaskExecutor")
    @ConditionalOnProperty(prefix = "ateng.boot.mail.async", name = "enabled", havingValue = "true", matchIfMissing = true)
    public Executor mailTaskExecutor(MailProperties properties, Environment environment) {
        return AsyncExecutorFactory.createExecutor("mail", properties.getAsync(), environment);
    }

    /**
     * 分布式邮件频控配置（检测到容器存在 StringRedisTemplate Bean 且开启 rate-limit 时激活）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ateng.boot.mail.rate-limit", name = "enabled", havingValue = "true")
    @ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
    @ConditionalOnBean(type = "org.springframework.data.redis.core.StringRedisTemplate")
    public static class RedisRateLimitConfiguration {

        @Bean
        @ConditionalOnMissingBean(MailRateLimiter.class)
        public MailRateLimiter redisMailRateLimiter(org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
                                                    MailProperties properties) {
            return new RedisMailRateLimiter(redisTemplate, properties.getRateLimit());
        }
    }

    /**
     * 单机内存邮件频控默认配置（未注册 StringRedisTemplate Bean 且开启 rate-limit 时保底激活）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ateng.boot.mail.rate-limit", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(value = MailRateLimiter.class, type = "org.springframework.data.redis.core.StringRedisTemplate")
    public static class InMemoryRateLimitConfiguration {

        @Bean
        public MailRateLimiter inMemoryMailRateLimiter(MailProperties properties) {
            return new InMemoryMailRateLimiter(properties.getRateLimit());
        }
    }

    /**
     * 分布式邮件日配额熔断配置（检测到容器存在 StringRedisTemplate Bean 且开启 quota 时激活）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ateng.boot.mail.quota", name = "enabled", havingValue = "true")
    @ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
    @ConditionalOnBean(type = "org.springframework.data.redis.core.StringRedisTemplate")
    public static class RedisQuotaConfiguration {

        @Bean
        @ConditionalOnMissingBean(MailDailyQuotaBreaker.class)
        public MailDailyQuotaBreaker redisMailDailyQuotaBreaker(org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
                                                               MailProperties properties) {
            return new RedisMailDailyQuotaBreaker(redisTemplate, properties.getQuota());
        }
    }

    /**
     * 单机内存邮件日配额熔断默认配置（未注册 StringRedisTemplate Bean 且开启 quota 时保底激活）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ateng.boot.mail.quota", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(value = MailDailyQuotaBreaker.class, type = "org.springframework.data.redis.core.StringRedisTemplate")
    public static class InMemoryQuotaConfiguration {

        @Bean
        public MailDailyQuotaBreaker inMemoryMailDailyQuotaBreaker(MailProperties properties) {
            return new InMemoryMailDailyQuotaBreaker(properties.getQuota());
        }
    }

    /**
     * 分布式邮件出站幂等防重配置（检测到容器存在 StringRedisTemplate Bean 且开启 idempotent 时激活）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ateng.boot.mail.idempotent", name = "enabled", havingValue = "true")
    @ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
    @ConditionalOnBean(type = "org.springframework.data.redis.core.StringRedisTemplate")
    public static class RedisIdempotentConfiguration {

        @Bean
        @ConditionalOnMissingBean(MailIdempotentGuard.class)
        public MailIdempotentGuard redisMailIdempotentGuard(org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
                                                            MailProperties properties) {
            return new RedisMailIdempotentGuard(redisTemplate, properties.getIdempotent());
        }
    }

    /**
     * 单机内存邮件出站幂等防重默认配置（未注册 StringRedisTemplate Bean 且开启 idempotent 时保底激活）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ateng.boot.mail.idempotent", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(value = MailIdempotentGuard.class, type = "org.springframework.data.redis.core.StringRedisTemplate")
    public static class InMemoryIdempotentConfiguration {

        @Bean
        public MailIdempotentGuard inMemoryMailIdempotentGuard(MailProperties properties) {
            return new InMemoryMailIdempotentGuard(properties.getIdempotent());
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public MailTemplate mailTemplate(MailAccountRegistry registry, MailProperties properties,
                                     MailTemplateRenderer templateRenderer, MailAccountResolver accountResolver,
                                     ObjectProvider<MailAccountProvider> mailAccountProviderProvider,
                                     ObjectProvider<MailAccessTokenProvider> accessTokenProviderProvider,
                                     @Qualifier("mailTaskExecutor") ObjectProvider<Executor> mailTaskExecutorProvider,
                                     @Qualifier("taskExecutor") ObjectProvider<Executor> genericTaskExecutorProvider,
                                     ObjectProvider<ApplicationEventPublisher> eventPublisherProvider,
                                     ObjectProvider<List<MailSendFailureHandler>> failureHandlersProvider,
                                     ObjectProvider<List<MailSendInterceptor>> interceptorsProvider,
                                     ObjectProvider<MailRateLimiter> rateLimiterProvider,
                                     ObjectProvider<MailDailyQuotaBreaker> dailyQuotaBreakerProvider,
                                     ObjectProvider<MailIdempotentGuard> idempotentGuardProvider) {
        Executor executor = mailTaskExecutorProvider.getIfAvailable();
        if (executor == null) {
            executor = genericTaskExecutorProvider.getIfAvailable();
        }
        MailAccountProvider accountProvider = mailAccountProviderProvider.getIfAvailable();
        MailAccessTokenProvider accessTokenProvider = accessTokenProviderProvider.getIfAvailable();
        ApplicationEventPublisher eventPublisher = eventPublisherProvider.getIfAvailable();
        List<MailSendFailureHandler> failureHandlers = failureHandlersProvider.getIfAvailable();
        List<MailSendInterceptor> interceptors = interceptorsProvider.getIfAvailable();
        MailRateLimiter rateLimiter = rateLimiterProvider.getIfAvailable();
        MailDailyQuotaBreaker dailyQuotaBreaker = dailyQuotaBreakerProvider.getIfAvailable();
        MailIdempotentGuard idempotentGuard = idempotentGuardProvider.getIfAvailable();
        return new MailTemplate(registry, properties, templateRenderer, accountResolver, accountProvider, accessTokenProvider,
                executor, eventPublisher, failureHandlers, interceptors, rateLimiter, dailyQuotaBreaker, idempotentGuard);
    }

    private MailAccount createMailAccount(String name, MailAccountProperties accountProps, MailProperties globalProps) {
        String archiveBcc = (accountProps != null && StringUtils.hasText(accountProps.getArchiveBcc()))
                ? accountProps.getArchiveBcc()
                : (globalProps != null ? globalProps.getArchiveBcc() : null);

        List<String> sanitizedBackups = new ArrayList<>();
        if (accountProps.getBackupAccounts() != null) {
            for (String backup : accountProps.getBackupAccounts()) {
                if (StringUtils.hasText(backup)) {
                    sanitizedBackups.add(backup.trim());
                }
            }
        }

        MailAccount account = MailAccount.builder()
                .name(name)
                .host(accountProps.getHost())
                .port(accountProps.getPort() != null ? accountProps.getPort() : 465)
                .username(accountProps.getUsername())
                .password(accountProps.getPassword())
                .authMode(accountProps.getAuthMode() != null ? accountProps.getAuthMode() : AuthMode.AUTO)
                .accessToken(accountProps.getAccessToken())
                .sslTrust(accountProps.getSslTrust())
                .protocol(accountProps.getProtocol() != null ? accountProps.getProtocol() : "smtp")
                .ssl(accountProps.isSsl())
                .starttls(accountProps.isStarttls())
                .defaultFrom(accountProps.getDefaultFrom())
                .defaultFromName(accountProps.getDefaultFromName())
                .archiveBcc(archiveBcc)
                .properties(accountProps.getProperties())
                .backupAccounts(sanitizedBackups)
                .build();

        JavaMailSenderImpl sender = MailAccountRegistry.createSender(account, globalProps);
        account.setSender(sender);
        return account;
    }
}
