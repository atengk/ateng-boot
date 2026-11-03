package io.github.atengk.boot.sample.liteflow.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 订单履约执行结果
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderFulfillmentResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务订单标识
     */
    private String orderId;

    /**
     * 整体履约流程是否成功
     */
    private boolean success;

    /**
     * 最终履约状态 (FULFILLED: 履约成功, COMPENSATED: 故障已触发逆序补偿, FAILED: 履约失败)
     */
    private String fulfillmentStatus;

    /**
     * 逆序回滚补偿动作审计日志
     */
    private List<String> compensationLogs;

    /**
     * 异常或阻断信息摘要
     */
    private String errorSummary;

    /**
     * 规则执行轨迹摘要
     */
    private String traceSummary;
}
