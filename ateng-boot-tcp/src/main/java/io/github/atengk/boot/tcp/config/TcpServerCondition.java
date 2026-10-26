package io.github.atengk.boot.tcp.config;

import io.github.atengk.boot.tcp.properties.TcpServerProperties;
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
 * TCP 服务端自动装配条件探测器
 * <p>
 * 当主配置 {@code ateng.boot.tcp.server.enabled=true} 或多端口映射 {@code ateng.boot.tcp.servers.<name>.enabled=true} 时激活。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
class TcpServerCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        Boolean serverEnabled = env.getProperty("ateng.boot.tcp.server.enabled", Boolean.class, false);
        if (Boolean.TRUE.equals(serverEnabled)) {
            return ConditionOutcome.match("ateng.boot.tcp.server.enabled=true");
        }

        BindResult<Map<String, TcpServerProperties>> result = Binder.get(env)
                .bind("ateng.boot.tcp.servers", Bindable.mapOf(String.class, TcpServerProperties.class));
        if (result.isBound() && result.get().values().stream().anyMatch(TcpServerProperties::isEnabled)) {
            return ConditionOutcome.match("ateng.boot.tcp.servers 包含已启用的服务端配置");
        }

        return ConditionOutcome.noMatch("未检测到启用的 TCP 服务端配置 (ateng.boot.tcp.server 或 ateng.boot.tcp.servers)");
    }
}
