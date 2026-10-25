package io.github.atengk.boot.sample.statemachine.constant;

/**
 * 电商订单生命周期状态枚举（涵盖 8 大标准生命周期阶段）
 *
 * @author Ateng
 * @since 2026-10-25
 */
public enum OrderState {

    /**
     * 待支付
     */
    WAIT_PAY("待支付"),

    /**
     * 已支付（等待风控或出库准备）
     */
    PAID("已支付"),

    /**
     * 待发货（风控审核通过，等待仓库打包发货）
     */
    WAIT_DELIVER("待发货"),

    /**
     * 配送中（已发货，物流在途中）
     */
    DELIVERING("配送中"),

    /**
     * 已送达（买家已签收/妥投）
     */
    DELIVERED("已送达"),

    /**
     * 已完成（交易闭环，过售后维权期或买家主动确认完成）
     */
    FINISHED("已完成"),

    /**
     * 退款中（风控拦截或买家发起逆向售后退款）
     */
    REFUNDING("退款中"),

    /**
     * 已关闭（取消订单或退款完成终态）
     */
    CLOSED("已关闭");

    private final String description;

    OrderState(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
