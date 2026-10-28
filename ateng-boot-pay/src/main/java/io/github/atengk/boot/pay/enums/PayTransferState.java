package io.github.atengk.boot.pay.enums;

import lombok.Getter;

/**
 * 统一标准资金转账生命周期状态机枚举
 * <p>
 * 抹平微信支付（PROCESSING, SUCCESS, FAIL, CLOSED）与
 * 支付宝（SUCCESS, FAIL, DEALING, REFUND, CLOSED）单笔转账状态机差异。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Getter
public enum PayTransferState {

    /**
     * 转账处理中（中间态，需要轮询或等待异步通知）
     */
    PROCESSING("PROCESSING", "转账处理中"),

    /**
     * 转账成功（终态）
     */
    SUCCESS("SUCCESS", "转账成功"),

    /**
     * 转账失败（终态）
     */
    FAIL("FAIL", "转账失败"),

    /**
     * 转账关闭（终态）
     */
    CLOSED("CLOSED", "转账关闭"),

    /**
     * 资金退回 / 银行退票（终态，转账到银行卡失败退回）
     */
    REFUND("REFUND", "转账退票");

    private final String code;
    private final String description;

    PayTransferState(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public boolean isProcessing() {
        return this == PROCESSING;
    }

    public boolean isFinal() {
        return this == SUCCESS || this == FAIL || this == CLOSED || this == REFUND;
    }
}
