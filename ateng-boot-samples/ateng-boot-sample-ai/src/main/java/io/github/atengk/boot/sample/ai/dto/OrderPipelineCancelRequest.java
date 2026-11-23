package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 订单履约流水线主动撤销请求 DTO
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderPipelineCancelRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工作流会话线程标识
     */
    @NotBlank(message = "会话标识 threadId 不能为空")
    private String threadId;

    /**
     * 撤销原因说明
     */
    @NotBlank(message = "撤销原因 reason 不能为空")
    private String reason;
}
