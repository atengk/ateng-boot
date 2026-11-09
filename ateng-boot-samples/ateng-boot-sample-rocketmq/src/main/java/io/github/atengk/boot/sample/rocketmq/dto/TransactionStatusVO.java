package io.github.atengk.boot.sample.rocketmq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

/**
 * 本地事务回查器与模拟数据库状态 VO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionStatusVO {

    /**
     * Broker 回查触发次数
     */
    private int checkCount;

    /**
     * 决策提交成功的订单集合
     */
    private Set<String> committedOrders;

    /**
     * 决策回滚的订单集合
     */
    private Set<String> rolledBackOrders;

    /**
     * 本地模拟数据库已写入的订单列表
     */
    private List<String> localDbOrders;
}
