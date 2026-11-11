package io.github.atengk.boot.sample.job.snail.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 跨商户账单结算 MapReduce 切片触发请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillSettlementRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待结算账单范围 (格式如 "1-1000")
     */
    @NotBlank(message = "账单结算范围不能为空")
    private String dataRange;

    /**
     * 结算结算周期标识 (如 "2026-11")
     */
    private String billingCycle;
}
