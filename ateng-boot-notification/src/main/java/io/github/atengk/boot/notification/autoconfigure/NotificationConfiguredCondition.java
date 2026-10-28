package io.github.atengk.boot.notification.autoconfigure;

import io.github.atengk.boot.notification.properties.NotificationChannelProperties;
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

import java.util.Map;

/**
 * 协同通知基础装配探测条件
 * 探测是否存在有效的渠道配置或允许动态提供者接管
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class NotificationConfiguredCondition extends SpringBootCondition {

    private static final Logger log = LoggerFactory.getLogger(NotificationConfiguredCondition.class);

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();

        Boolean enabled = env.getProperty("ateng.boot.notification.enabled", Boolean.class, Boolean.TRUE);
        if (Boolean.FALSE.equals(enabled)) {
            return ConditionOutcome.noMatch("ateng.boot.notification.enabled is false");
        }

        Binder binder = Binder.get(env);
        BindResult<Map<String, NotificationChannelProperties>> channelsResult =
                binder.bind("ateng.boot.notification.channels", Bindable.mapOf(String.class, NotificationChannelProperties.class));

        if (channelsResult.isBound() && !channelsResult.get().isEmpty()) {
            return ConditionOutcome.match("Found static notification channels in ateng.boot.notification.channels");
        }

        log.debug("No static channels found under ateng.boot.notification.channels, enabling dynamic channel provider support");
        return ConditionOutcome.match("Notification is enabled (channels may be loaded dynamically via NotificationChannelProvider)");
    }
}
