package io.github.atengk.boot.sample.statemachine.vo;

import io.github.atengk.boot.sample.statemachine.constant.OrderEvent;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单状态跃迁结果展示对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderTransitionVO {

    /**
     * 订单主键 ID
     */
    private Long orderId;

    /**
     * 流转前源状态
     */
    private OrderState fromState;

    /**
     * 流转后目标状态
     */
    private OrderState toState;

    /**
     * 触发事件
     */
    private OrderEvent event;

    /**
     * 跃迁是否被状态机接受并成功执行
     */
    private boolean accepted;

    /**
     * 是否触发了幂等放行逻辑
     */
    private boolean idempotent;

    /**
     * 执行说明或原因
     */
    private String message;

    /**
     * 从底层 TransitionResult 转译为统一展示对象
     *
     * @param orderId 订单 ID
     * @param result  状态机跃迁结果模型
     * @return 跃迁展示视图对象
     */
    public static OrderTransitionVO fromResult(Long orderId, TransitionResult<OrderState> result) {
        if (result == null) {
            return null;
        }
        String msg = result.isAccepted() ? "状态机跃迁成功"
                : (result.getFailureReason() != null ? result.getFailureReason()
                : (result.getCause() != null ? result.getCause().getMessage() : "流转未放行"));

        return OrderTransitionVO.builder()
                .orderId(orderId)
                .fromState(result.getSourceState())
                .toState(result.getTargetState())
                .event((OrderEvent) result.getEvent())
                .accepted(result.isAccepted())
                .idempotent(result.isIdempotent())
                .message(msg)
                .build();
    }
}
