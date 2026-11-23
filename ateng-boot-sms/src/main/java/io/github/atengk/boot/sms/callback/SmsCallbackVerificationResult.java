package io.github.atengk.boot.sms.callback;

import lombok.Getter;

/**
 * 入站 Webhook 回调安全校验结果值对象
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Getter
public final class SmsCallbackVerificationResult {

    private static final SmsCallbackVerificationResult OK = new SmsCallbackVerificationResult(true, 200, "OK");

    private final boolean allowed;
    private final int httpStatus;
    private final String reason;

    private SmsCallbackVerificationResult(boolean allowed, int httpStatus, String reason) {
        this.allowed = allowed;
        this.httpStatus = httpStatus;
        this.reason = reason;
    }

    /**
     * 构建校验放行结果
     *
     * @return 放行结果
     */
    public static SmsCallbackVerificationResult ok() {
        return OK;
    }

    /**
     * 构建未授权拦截结果（HTTP 401）
     *
     * @param reason 拒绝原因
     * @return 401 拒绝结果
     */
    public static SmsCallbackVerificationResult unauthorized(String reason) {
        return new SmsCallbackVerificationResult(false, 401, reason != null ? reason : "Unauthorized");
    }

    /**
     * 构建禁止访问拦截结果（HTTP 403）
     *
     * @param reason 拒绝原因
     * @return 403 拒绝结果
     */
    public static SmsCallbackVerificationResult forbidden(String reason) {
        return new SmsCallbackVerificationResult(false, 403, reason != null ? reason : "Forbidden");
    }
}
