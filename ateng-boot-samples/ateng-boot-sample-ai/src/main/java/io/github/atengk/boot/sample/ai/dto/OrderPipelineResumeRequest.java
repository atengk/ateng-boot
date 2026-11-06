package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 订单履约流水线人工审核断点恢复请求 DTO
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderPipelineResumeRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工作流会话线程标识
     */
    @NotBlank(message = "会话标识 threadId 不能为空")
    private String threadId;

    /**
     * 审核决断动作：APPROVED (放行) / REJECTED (拒绝驳回)
     */
    @NotBlank(message = "审核动作 action 不能为空 (APPROVED / REJECTED)")
    private String action;

    /**
     * 审核操作员姓名或工号
     */
    private String operator;

    /**
     * 审核人批注或审批意见
     */
    private String comment;
}
