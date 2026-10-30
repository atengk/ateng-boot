package io.github.atengk.boot.observability.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Actuator 端点未授权访问拦截领域事件
 * <p>
 * 当客户端尝试访问受加固保护的高危敏感端点（如 {@code /actuator/env}、{@code /actuator/beans}），
 * 且未命中 IP 白名单且未提供有效授权凭据时由 {@code ActuatorEndpointSecurityFilter} 发布。
 * 下游运维告警组件（如飞书、钉钉、企微告警推送）可监听此事件实施毫秒级安全审计与入侵防御联动。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Getter
public class ActuatorAccessDeniedEvent extends ApplicationEvent {

    /**
     * 被拦截请求的 URI（例如 /actuator/env）
     */
    private final String uri;

    /**
     * 发起请求的客户端真实 IP 地址
     */
    private final String clientIp;

    /**
     * 访问拒绝的具体原因说明
     */
    private final String reason;

    public ActuatorAccessDeniedEvent(Object source, String uri, String clientIp, String reason) {
        super(source);
        this.uri = uri;
        this.clientIp = clientIp;
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "ActuatorAccessDeniedEvent{" +
                "uri='" + uri + '\'' +
                ", clientIp='" + clientIp + '\'' +
                ", reason='" + reason + '\'' +
                ", timestamp=" + getTimestamp() +
                '}';
    }
}
