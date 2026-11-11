package io.github.atengk.boot.sample.statemachine.vo;

import io.github.atengk.boot.sample.statemachine.constant.OrderEvent;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 订单控制台动态操作权限展示对象（前端灰化按钮支撑）
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderButtonActionVO {

    /**
     * 订单主键 ID
     */
    private Long orderId;

    /**
     * 当前订单状态
     */
    private OrderState status;

    /**
     * 可用事件列表
     */
    private List<OrderEvent> availableEvents;

    /**
     * 前端具体按钮名称与是否启用权限映射（如 btnPay: true, btnShip: false）
     */
    private Map<String, Boolean> buttonPermissions;
}
