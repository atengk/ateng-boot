package io.github.atengk.boot.pay.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一标准化支付交易生命周期状态枚举
 * <p>
 * 抹平微信支付与支付宝异构的交易状态代码，提供统一强类型枚举判定。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@AllArgsConstructor
public enum PayTradeState {

    /**
     * 支付成功（微信 SUCCESS，支付宝 TRADE_SUCCESS / TRADE_FINISHED）
     */
    SUCCESS("SUCCESS", "支付成功"),

    /**
     * 未支付 / 等待买家付款（微信 NOTPAY，支付宝 WAIT_BUYER_PAY）
     */
    NOTPAY("NOTPAY", "未支付"),

    /**
     * 用户支付中 / 刷卡被扫输入密码中（微信 USERPAYING）
     */
    USERPAYING("USERPAYING", "用户支付中"),

    /**
     * 交易已关闭（未付款超时关闭或全额退款后关闭）
     */
    CLOSED("CLOSED", "交易已关闭"),

    /**
     * 转入退款（全额或部分退款进行中）
     */
    REFUND("REFUND", "转入退款"),

    /**
     * 交易已撤销（当面付付款码超时或刷卡失败主动撤销冲正）
     */
    REVOKED("REVOKED", "已撤销"),

    /**
     * 支付失败（银行拒绝、余额不足等明确失败原因）
     */
    PAYERROR("PAYERROR", "支付失败");

    /**
     * 状态代码
     */
    private final String code;

    /**
     * 状态描述
     */
    private final String description;

    /**
     * 判断当前状态是否属于支付成功终态
     *
     * @return true 若为 SUCCESS
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /**
     * 判断当前状态是否属于交易终态（不可再继续支付）
     *
     * @return true 若为 SUCCESS、CLOSED 或 REVOKED
     */
    public boolean isFinalState() {
        return this == SUCCESS || this == CLOSED || this == REVOKED;
    }
}
