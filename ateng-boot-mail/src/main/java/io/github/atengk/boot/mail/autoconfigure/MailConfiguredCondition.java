package io.github.atengk.boot.mail.autoconfigure;

import io.github.atengk.boot.mail.properties.MailAccountProperties;
import io.github.atengk.boot.mail.provider.MailAccountProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 邮件配置探测条件
 * 探测是否存在静态 SMTP 配置、允许空账号/纯日志沙箱开关或 {@link MailAccountProvider} Bean，
 * 若上述均未就绪则平滑跳过自动装配并记录告警日志。
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class MailConfiguredCondition extends SpringBootCondition implements ConfigurationCondition {

    private static final Logger log = LoggerFactory.getLogger(MailConfiguredCondition.class);

    @Override
    public ConfigurationPhase getConfigurationPhase() {
        return ConfigurationPhase.REGISTER_BEAN;
    }

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        Binder binder = Binder.get(env);

        // 1. 探测顶级单账号 host（支持 Relaxed Binding）
        String host = binder.bind("ateng.boot.mail.host", String.class).orElse(null);
        if (StringUtils.hasText(host)) {
            return ConditionOutcome.match("Found ateng.boot.mail.host");
        }

        // 2. 探测多账号配置 accounts.*
        BindResult<Map<String, MailAccountProperties>> accountsResult =
                binder.bind("ateng.boot.mail.accounts", Bindable.mapOf(String.class, MailAccountProperties.class));
        if (accountsResult.isBound() && !accountsResult.get().isEmpty()) {
            return ConditionOutcome.match("Found ateng.boot.mail.accounts");
        }

        // 3. 探测 Spring 官方原生配置 spring.mail.host（支持 Relaxed Binding）
        String springMailHost = binder.bind("spring.mail.host", String.class).orElse(null);
        if (StringUtils.hasText(springMailHost)) {
            return ConditionOutcome.match("Found spring.mail.host fallback");
        }

        // 4. 探测显式允许空静态账号模式（兼容驼峰与环境变量风格宽松绑定）
        boolean allowEmptyAccounts = Boolean.TRUE.equals(
                binder.bind("ateng.boot.mail.allow-empty-accounts", Boolean.class).orElse(Boolean.FALSE));
        if (allowEmptyAccounts) {
            return ConditionOutcome.match("Found ateng.boot.mail.allow-empty-accounts=true");
        }

        // 5. 探测沙箱纯日志模拟发信模式（兼容驼峰与环境变量风格宽松绑定）
        boolean sandboxEnabled = Boolean.TRUE.equals(
                binder.bind("ateng.boot.mail.sandbox.enabled", Boolean.class).orElse(Boolean.FALSE));
        boolean sandboxLogOnly = Boolean.TRUE.equals(
                binder.bind("ateng.boot.mail.sandbox.log-only", Boolean.class).orElse(Boolean.FALSE));
        if (sandboxEnabled && sandboxLogOnly) {
            return ConditionOutcome.match("Found ateng.boot.mail.sandbox log-only mode");
        }

        // 6. 探测动态账号供给器 MailAccountProvider Bean（兼容纯 DB 托管账号无静态配置启动）
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        if (beanFactory != null) {
            String[] providerNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
                    beanFactory, MailAccountProvider.class, true, false);
            if (providerNames.length > 0) {
                return ConditionOutcome.match("Found MailAccountProvider bean");
            }
        }

        log.warn("No mail account configuration detected, ateng-boot-mail auto-configuration skipped.");
        return ConditionOutcome.noMatch("No mail account, provider, or log-only sandbox configured");
    }
}
