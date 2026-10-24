package io.github.atengk.boot.pay.enums;

import lombok.Getter;

/**
 * 统一标准退款生命周期状态机枚举
 * <p>
 * 统一抹平微信支付（SUCCESS, PROCESSING, CLOSED, ABNORMAL）与
 * 支付宝（REFUND_SUCCESS 等）退款状态差异。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Getter
public enum PayRefundState {

    /**
     * 退款处理中
     */
    PROCESSING("PROCESSING", "退款处理中"),

    /**
     * 退款成功
     */
    SUCCESS("SUCCESS", "退款成功"),

    /**
     * 退款关闭
     */
    CLOSED("CLOSED", "退款关闭"),

    /**
     * 退款异常
     */
    ABNORMAL("ABNORMAL", "退款异常");

    private final String code;
    private final String description;

    PayRefundState(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
