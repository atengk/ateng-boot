package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 电商提单责任链编排响应视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderPipelineVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单编号
     */
    private String orderNo;

    /**
     * 流水线是否全阶段执行成功
     */
    private boolean passed;

    /**
     * 是否在某个阶段被提前短路中断
     */
    private boolean terminated;

    /**
     * 被中断的阶段名称
     */
    private String terminatedStageName;

    /**
     * 中断原因
     */
    private String interruptReason;

    /**
     * 最终优惠抵扣金额
     */
    private BigDecimal discountAmount;

    /**
     * 实际走过的阶段审计足迹
     */
    private List<String> auditTrail;
}
