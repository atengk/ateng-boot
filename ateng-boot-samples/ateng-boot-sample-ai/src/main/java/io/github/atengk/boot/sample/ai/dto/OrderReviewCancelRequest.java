package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 订单风控审核流程主动撤销请求 DTO
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderReviewCancelRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工作流执行会话标识 (threadId)
     */
    @NotBlank(message = "会话标识 threadId 不能为空")
    private String threadId;

    /**
     * 主动撤销原因说明
     */
    @NotBlank(message = "撤销原因 reason 不能为空")
    private String reason;
}
