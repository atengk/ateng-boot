package io.github.atengk.boot.sample.rocketmq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * SimpleConsumer 故障隔离与幂等容错微批消费结果报告 VO
 *
 * @author Ateng
 * @since 2026-11-15
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimpleConsumeReportVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 已确认出队条目总数（含成功消费与幂等静默 ACK 条目）
     */
    private int settledCount;

    /**
     * 成功执行业务逻辑的订单数量
     */
    private int consumedCount;

    /**
     * 命中 IdempotentException 并静默 ACK 的条目数量
     */
    private int idempotentCount;

    /**
     * 触发单条异常回调并保留重投资格的条目数量
     */
    private int failedCount;

    /**
     * 成功消费的订单编号列表
     */
    @Builder.Default
    private List<String> consumedOrderIds = Collections.emptyList();

    /**
     * 消费失败的订单编号或消息标识列表
     */
    @Builder.Default
    private List<String> failedOrderIds = Collections.emptyList();
}
