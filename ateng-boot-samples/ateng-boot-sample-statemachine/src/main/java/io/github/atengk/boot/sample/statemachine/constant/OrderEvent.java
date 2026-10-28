package io.github.atengk.boot.sample.statemachine.constant;

/**
 * 电商订单生命周期流转事件枚举
 *
 * @author Ateng
 * @since 2026-10-25
 */
public enum OrderEvent {

    /**
     * 买家付款
     */
    PAY("买家付款"),

    /**
     * 风控自动评估（触发 Choice 动态条件路由）
     */
    RISK_AUDIT("风控自动评估"),

    /**
     * 商家发货出库
     */
    SHIP("商家发货出库"),

    /**
     * 追加物流追踪节点（内部自跃迁，状态保持不变）
     */
    ADD_TRACKING("追加物流追踪节点"),

    /**
     * 买家签收/妥投
     */
    CONFIRM_DELIVERY("买家签收妥投"),

    /**
     * 交易完成评价/归档终态
     */
    FINISH("交易完成归档"),

    /**
     * 取消订单（多源汇聚跃迁至 CLOSED）
     */
    CANCEL("取消订单"),

    /**
     * 发起退款申请
     */
    APPLY_REFUND("发起退款申请");

    private final String description;

    OrderEvent(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
