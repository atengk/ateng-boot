package io.github.atengk.boot.notification.properties;

import lombok.Data;

import java.time.Duration;

/**
 * 协同通知异常告警与防风暴抑制配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class NotificationAlertProperties {

    /**
     * 是否开启声明式 @NotifyAlert 异常告警切面拦截，默认开启
     */
    private boolean enabled = true;

    /**
     * 告警推送的缺省渠道标识（未指定时回退至全局 default-channel）
     */
    private String defaultChannel;

    /**
     * 防告警风暴抑制聚合时间窗口，默认 60 秒
     */
    private Duration suppressWindow = Duration.ofSeconds(60);

    /**
     * 是否在告警卡片中包含经过脱敏的方法调用入参，默认开启
     */
    private boolean includeArgs = true;

    /**
     * 入参脱敏的敏感字段关键字列表，默认包含 password, secret, token, credential, idCard, mobile 等
     */
    private java.util.List<String> desensitizeKeys = new java.util.ArrayList<>(java.util.List.of(
            "password", "secret", "token", "credential", "pwd", "passwd", "idcard", "mobile", "auth"
    ));
}
