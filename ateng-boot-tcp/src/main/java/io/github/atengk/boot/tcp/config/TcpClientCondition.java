package io.github.atengk.boot.tcp.config;

import io.github.atengk.boot.tcp.properties.TcpClientProperties;
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
 * TCP 客户端自动装配条件探测器
 * <p>
 * 当主配置 {@code ateng.boot.tcp.client.enabled=true} 或多客户端映射 {@code ateng.boot.tcp.clients.<name>.enabled=true} 时激活。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
class TcpClientCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        Boolean clientEnabled = env.getProperty("ateng.boot.tcp.client.enabled", Boolean.class, false);
        if (Boolean.TRUE.equals(clientEnabled)) {
            return ConditionOutcome.match("ateng.boot.tcp.client.enabled=true");
        }

        BindResult<Map<String, TcpClientProperties>> result = Binder.get(env)
                .bind("ateng.boot.tcp.clients", Bindable.mapOf(String.class, TcpClientProperties.class));
        if (result.isBound() && result.get().values().stream().anyMatch(TcpClientProperties::isEnabled)) {
            return ConditionOutcome.match("ateng.boot.tcp.clients 包含已启用的客户端配置");
        }

        return ConditionOutcome.noMatch("未检测到启用的 TCP 客户端配置 (ateng.boot.tcp.client 或 ateng.boot.tcp.clients)");
    }
}
