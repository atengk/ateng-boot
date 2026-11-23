package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 财务合规审计请求传输对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancialAuditRequest {

    /**
     * 待审核订单号
     */
    @NotBlank(message = "审核订单号不能为空")
    private String orderId;

    /**
     * 审核备忘与补充说明
     */
    private String remarks;
}
