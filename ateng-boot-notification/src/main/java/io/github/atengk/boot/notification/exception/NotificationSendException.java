package io.github.atengk.boot.notification.exception;

import io.github.atengk.boot.core.code.IResultCode;
import lombok.Getter;

/**
 * 协同通知发信失败异常
 * 结构化记录发信渠道、目标平台、底层返回报文及原始因果链
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
public class NotificationSendException extends NotificationException {

    private static final long serialVersionUID = 1L;

    /**
     * 发送渠道标识
     */
    private final String channelName;

    /**
     * 目标协同平台类型
     */
    private final String platform;

    /**
     * 平台原始响应报文
     */
    private final String rawResponse;

    public NotificationSendException(IResultCode resultCode, String msg) {
        super(resultCode, msg);
        this.channelName = null;
        this.platform = null;
        this.rawResponse = null;
    }

    public NotificationSendException(IResultCode resultCode, String msg, Throwable cause) {
        super(resultCode, msg, cause);
        this.channelName = null;
        this.platform = null;
        this.rawResponse = null;
    }

    public NotificationSendException(String channelName, String platform, String rawResponse, IResultCode resultCode, String msg) {
        super(resultCode, msg);
        this.channelName = channelName;
        this.platform = platform;
        this.rawResponse = rawResponse;
    }

    public NotificationSendException(String channelName, String platform, String rawResponse, IResultCode resultCode, String msg, Throwable cause) {
        super(resultCode, msg, cause);
        this.channelName = channelName;
        this.platform = platform;
        this.rawResponse = rawResponse;
    }
}
