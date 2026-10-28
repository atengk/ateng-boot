package io.github.atengk.boot.notification.exception;

import io.github.atengk.boot.core.code.IResultCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 协同通知服务业务状态码枚举 (5701~5710)
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@AllArgsConstructor
public enum NotificationResultCode implements IResultCode {

    /**
     * 通知推送失败
     */
    NOTIFICATION_SEND_ERROR(5701, "通知推送失败"),

    /**
     * 通知服务网络连接超时
     */
    NOTIFICATION_CONNECT_TIMEOUT(5702, "通知服务网络连接超时"),

    /**
     * 通知服务签名或密钥鉴权失败
     */
    NOTIFICATION_AUTHENTICATION_ERROR(5703, "通知服务签名或密钥鉴权失败"),

    /**
     * 通知发送频控超限已触发防刷拦截
     */
    NOTIFICATION_RATE_LIMIT_EXCEEDED(5704, "通知发送频控超限已触发防刷拦截"),

    /**
     * 未找到指定的通知渠道配置
     */
    NOTIFICATION_CHANNEL_NOT_FOUND(5705, "未找到指定的通知渠道配置"),

    /**
     * 通知消息正文不能为空
     */
    NOTIFICATION_MESSAGE_EMPTY(5706, "通知消息正文不能为空"),

    /**
     * 通知模板动态渲染失败
     */
    NOTIFICATION_TEMPLATE_RENDER_ERROR(5707, "通知模板动态渲染失败"),

    /**
     * 不支持的通知协同平台类型
     */
    NOTIFICATION_UNSUPPORTED_PLATFORM(5708, "不支持的通知协同平台类型"),

    /**
     * 通知配置参数不合法
     */
    NOTIFICATION_PARAMETER_INVALID(5709, "通知配置参数不合法"),

    /**
     * 通知告警风暴抑制熔断中
     */
    NOTIFICATION_CIRCUIT_BROKEN(5710, "通知告警风暴抑制熔断中");

    private final Integer code;
    private final String msg;
}
