package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 订单风控审核流程恢复/审批请求 DTO
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderReviewResumeRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工作流执行会话标识 (threadId)
     */
    @NotBlank(message = "会话标识 threadId 不能为空")
    private String threadId;

    /**
     * 审批决断动作：PASSED (通过) / REJECTED (拒绝) / REQUEST_DOCS (要求补件)
     */
    @NotBlank(message = "审批动作 action 不能为空 (PASSED / REJECTED / REQUEST_DOCS)")
    private String action;

    /**
     * 补充资质材料说明 (action=REQUEST_DOCS 或后续补齐材料时使用)
     */
    private String additionalDocs;

    /**
     * 审核员审批意见
     */
    private String comment;
}
