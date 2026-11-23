package io.github.atengk.boot.sample.ai.vo;

import io.github.atengk.boot.ai.annotation.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 订单退款高危操作触发响应视图对象
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderRefundTriggerVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 生成的审批工单唯一标识（若被拦截挂起）
     */
    private String ticketId;

    /**
     * 沙箱拦截原始反馈消息
     */
    private String interceptedMessage;

    /**
     * 是否需要人机协同审批挂起
     */
    private Boolean approvalRequired;

    /**
     * 风险等级
     */
    private RiskLevel riskLevel;

    /**
     * 审批业务类型
     */
    private String approvalType;

    /**
     * 关联业务订单号
     */
    private String orderId;

    /**
     * 退款金额（元）
     */
    private Double amount;
}
