package io.github.atengk.boot.mail.autoconfigure;

import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.properties.MailAccountProperties;
import io.github.atengk.boot.mail.properties.MailProperties;
import io.github.atengk.boot.mail.registry.MailAccountRegistry;
import io.github.atengk.boot.mail.template.MailTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.util.StringUtils;

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
        MailAccountRegistry registry = new MailAccountRegistry(properties.getDefaultAccount());
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

    @Bean
    @ConditionalOnMissingBean
    public MailTemplate mailTemplate(MailAccountRegistry registry, MailProperties properties) {
        return new MailTemplate(registry, properties);
    }

    private MailAccount createMailAccount(String name, MailAccountProperties accountProps, MailProperties globalProps) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(accountProps.getHost());
        sender.setPort(accountProps.getPort() != null ? accountProps.getPort() : 465);
        sender.setUsername(accountProps.getUsername());
        sender.setPassword(accountProps.getPassword());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", accountProps.getProtocol() != null ? accountProps.getProtocol() : "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", String.valueOf(globalProps.getConnectionTimeout().toMillis()));
        props.put("mail.smtp.timeout", String.valueOf(globalProps.getReadTimeout().toMillis()));
        props.put("mail.smtp.writetimeout", String.valueOf(globalProps.getWriteTimeout().toMillis()));

        if (accountProps.isSsl()) {
            props.put("mail.smtp.ssl.enable", "true");
        }
        if (accountProps.isStarttls()) {
            props.put("mail.smtp.starttls.enable", "true");
        }
        if (accountProps.getProperties() != null) {
            props.putAll(accountProps.getProperties());
        }

        return MailAccount.builder()
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
                .sender(sender)
                .build();
    }
}
