package io.github.atengk.boot.sms.exception;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import lombok.Getter;

/**
 * 短信出站黑名单拦截异常
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
public class SmsBlacklistException extends SmsException {

    private static final long serialVersionUID = 1L;

    /**
     * 拦截手机号
     */
    private final String mobile;

    /**
     * 拦截原因
     */
    private final String reason;

    public SmsBlacklistException(String mobile, String reason) {
        super(SmsResultCode.SMS_BLACKLISTED, String.format("目标手机号 [%s] 处于黑名单中，已被拦截: %s", DesensitizeUtil.mobilePhone(mobile), reason));
        this.mobile = mobile;
        this.reason = reason;
    }
}
