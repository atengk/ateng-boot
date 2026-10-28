package io.github.atengk.boot.mail.autoconfigure;

import io.github.atengk.boot.mail.properties.MailAccountProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 邮件配置探测条件
 * 探测是否存在有效的发信配置，若未配置任何 SMTP 信息则平滑跳过并记录告警日志
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class MailConfiguredCondition extends SpringBootCondition {

    private static final Logger log = LoggerFactory.getLogger(MailConfiguredCondition.class);

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();

        // 1. 探测顶级单账号 host
        String host = env.getProperty("ateng.boot.mail.host");
        if (StringUtils.hasText(host)) {
            return ConditionOutcome.match("Found ateng.boot.mail.host");
        }

        // 2. 探测多账号配置 accounts.*
        Binder binder = Binder.get(env);
        BindResult<Map<String, MailAccountProperties>> accountsResult =
                binder.bind("ateng.boot.mail.accounts", Bindable.mapOf(String.class, MailAccountProperties.class));
        if (accountsResult.isBound() && !accountsResult.get().isEmpty()) {
            return ConditionOutcome.match("Found ateng.boot.mail.accounts");
        }

        // 3. 探测 Spring 官方原生配置 spring.mail.host
        String springMailHost = env.getProperty("spring.mail.host");
        if (StringUtils.hasText(springMailHost)) {
            return ConditionOutcome.match("Found spring.mail.host fallback");
        }

        log.warn("No mail account configuration detected, ateng-boot-mail auto-configuration skipped.");
        return ConditionOutcome.noMatch("No mail account configured in ateng.boot.mail or spring.mail");
    }
}
