package io.github.atengk.boot.sms.exception;

import io.github.atengk.boot.core.code.IResultCode;
import lombok.Getter;

/**
 * 短信发信失败异常
 * 结构化记录发信渠道、云厂商原始响应码与因果链
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
public class SmsSendException extends SmsException {

    private static final long serialVersionUID = 1L;

    /**
     * 发送渠道标识
     */
    private final String channel;

    /**
     * 云厂商原始错误码
     */
    private final String rawCode;

    /**
     * 云厂商原始错误描述
     */
    private final String rawMessage;

    public SmsSendException(IResultCode resultCode, String msg) {
        super(resultCode, msg);
        this.channel = null;
        this.rawCode = null;
        this.rawMessage = null;
    }

    public SmsSendException(IResultCode resultCode, String msg, Throwable cause) {
        super(resultCode, msg, cause);
        this.channel = null;
        this.rawCode = null;
        this.rawMessage = null;
    }

    public SmsSendException(String channel, String rawCode, String rawMessage, IResultCode resultCode, String msg) {
        super(resultCode, msg);
        this.channel = channel;
        this.rawCode = rawCode;
        this.rawMessage = rawMessage;
    }

    public SmsSendException(String channel, String rawCode, String rawMessage, IResultCode resultCode, String msg, Throwable cause) {
        super(resultCode, msg, cause);
        this.channel = channel;
        this.rawCode = rawCode;
        this.rawMessage = rawMessage;
    }
}
