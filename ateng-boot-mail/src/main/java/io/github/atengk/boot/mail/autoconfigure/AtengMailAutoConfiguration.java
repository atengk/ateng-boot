package io.github.atengk.boot.mail.autoconfigure;

import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.properties.MailAccountProperties;
import io.github.atengk.boot.mail.properties.MailProperties;
import io.github.atengk.boot.mail.registry.MailAccountRegistry;
import io.github.atengk.boot.mail.resolver.MailAccountResolver;
import io.github.atengk.boot.mail.resolver.TenantMailAccountResolver;
import io.github.atengk.boot.mail.template.MailTemplate;
import io.github.atengk.boot.mail.template.renderer.FreemarkerMailTemplateRenderer;
import io.github.atengk.boot.mail.template.renderer.MailTemplateRenderer;
import io.github.atengk.boot.mail.template.renderer.SimpleMailTemplateRenderer;
import io.github.atengk.boot.mail.template.renderer.ThymeleafMailTemplateRenderer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.util.StringUtils;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

/**
 * 邮件通信基础设施自动装配入口
 *
 * @author Ateng
 * @since 2026-10-22
 */
@AutoConfiguration
@ConditionalOnClass(JavaMailSender.class)
@ConditionalOnProperty(prefix = "ateng.boot.mail", name = "enabled", havingValue = "true", matchIfMissing = true)
@Conditional(MailConfiguredCondition.class)
@EnableConfigurationProperties(MailProperties.class)
public class AtengMailAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MailAccountRegistry mailAccountRegistry(MailProperties properties, Environment env) {
        MailAccountRegistry registry = new MailAccountRegistry(properties.getDefaultAccount(), properties.getMaxDynamicAccounts());
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
            props.setProtocol(properties.getProtocol());
            props.setSsl(properties.isSsl());
            props.setStarttls(properties.isStarttls());
            props.setDefaultFrom(properties.getDefaultFrom());
            props.setDefaultFromName(properties.getDefaultFromName());
            props.setProperties(properties.getProperties());
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
    public MailTemplateRenderer simpleMailTemplateRenderer() {
        return new SimpleMailTemplateRenderer();
    }

    @Bean
    @ConditionalOnMissingBean(MailAccountResolver.class)
    public MailAccountResolver mailAccountResolver() {
        return new TenantMailAccountResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public MailTemplate mailTemplate(MailAccountRegistry registry, MailProperties properties,
                                     MailTemplateRenderer templateRenderer, MailAccountResolver accountResolver) {
        return new MailTemplate(registry, properties, templateRenderer, accountResolver);
    }

    private MailAccount createMailAccount(String name, MailAccountProperties accountProps, MailProperties globalProps) {
        MailAccount account = MailAccount.builder()
                .name(name)
                .host(accountProps.getHost())
                .port(accountProps.getPort() != null ? accountProps.getPort() : 465)
                .username(accountProps.getUsername())
                .password(accountProps.getPassword())
                .protocol(accountProps.getProtocol() != null ? accountProps.getProtocol() : "smtp")
                .ssl(accountProps.isSsl())
                .starttls(accountProps.isStarttls())
                .defaultFrom(accountProps.getDefaultFrom())
                .defaultFromName(accountProps.getDefaultFromName())
                .properties(accountProps.getProperties())
                .build();

        JavaMailSenderImpl sender = MailAccountRegistry.createSender(account, globalProps);
        account.setSender(sender);
        return account;
    }
}
