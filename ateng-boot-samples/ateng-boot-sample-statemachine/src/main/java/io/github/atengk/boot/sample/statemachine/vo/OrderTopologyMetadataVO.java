package io.github.atengk.boot.sample.statemachine.vo;

import io.github.atengk.boot.sample.statemachine.constant.OrderEvent;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.Set;

/**
 * 状态机拓扑元数据只读内省视图对象
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderTopologyMetadataVO {

    /**
     * 状态机唯一标识
     */
    private String machineId;

    /**
     * 已声明的全部状态全集
     */
    private Set<OrderState> allStates;

    /**
     * 已声明的全部事件全集
     */
    private Set<OrderEvent> allEvents;

    /**
     * 各源状态下合法事件的可达目标状态集合映射
     */
    private Map<OrderState, Map<OrderEvent, Set<OrderState>>> reachableTransitions;
}
