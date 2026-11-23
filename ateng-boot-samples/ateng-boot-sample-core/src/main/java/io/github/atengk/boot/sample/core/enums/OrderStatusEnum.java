package io.github.atengk.boot.sample.core.enums;

import io.github.atengk.boot.core.annotation.EnumAlias;
import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Map;

/**
 * 订单生命周期业务状态枚举
 * 演示 IBaseEnum 强类型契约、@EnumAlias 字典别名重写、禁用态与扩展属性
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Getter
@AllArgsConstructor
@EnumAlias("orderStatus")
public enum OrderStatusEnum implements IBaseEnum<Integer> {

    /**
     * 待支付状态
     */
    PENDING_PAYMENT(1, "待支付"),

    /**
     * 已支付状态
     */
    PAID(2, "已支付"),

    /**
     * 已发货状态
     */
    SHIPPED(3, "已发货"),

    /**
     * 已完成状态
     */
    COMPLETED(4, "已完成"),

    /**
     * 已取消状态（禁用态与扩展徽标）
     */
    CANCELLED(5, "已取消") {
        @Override
        public Boolean getDisabled() {
            return true;
        }

        @Override
        public Map<String, Object> getExtra() {
            return Map.of("badge", "danger");
        }
    };

    private final Integer code;
    private final String label;
}
