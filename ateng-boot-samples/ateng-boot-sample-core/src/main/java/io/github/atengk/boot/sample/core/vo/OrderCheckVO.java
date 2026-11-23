package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单结算校验结果视图模型
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCheckVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单编号
     */
    private String orderNo;

    /**
     * 校验判定状态
     */
    private String checkStatus;

    /**
     * 校验金额
     */
    private BigDecimal amount;

    /**
     * 轻量校验堆栈深度（无 cause 默认清空堆栈，零抓栈开销）
     */
    private Integer lightweightStackLength;

    /**
     * 带 Cause 异常堆栈深度（系统转译自动保留完整因果链）
     */
    private Integer causalStackLength;

    /**
     * 业务状态码归属领域号段名称
     */
    private String resultCodeSegment;
}
