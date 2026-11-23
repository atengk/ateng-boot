package io.github.atengk.boot.sample.datasource.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 事务守卫与多源协调事务演练请求传输对象
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardTransactionRequest {

    /**
     * 主流程/外层事务订单号
     */
    @NotBlank(message = "主订单号不能为空")
    private String primaryOrderNo;

    /**
     * 嵌套/目标数据源事务订单号
     */
    @NotBlank(message = "目标订单号不能为空")
    private String targetOrderNo;
}
