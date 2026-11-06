package io.github.atengk.boot.sample.liteflow.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 结算流水明细项目
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettleItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 明细唯一项目标识
     */
    @NotBlank(message = "明细标识 (itemId) 不能为空")
    private String itemId;

    /**
     * 结算金额（元）
     */
    @NotNull(message = "结算金额 (amount) 不能为空")
    @DecimalMin(value = "0.01", message = "结算金额必须大于 0")
    private BigDecimal amount;

    /**
     * 商品品类或结算类目
     */
    private String category;

    /**
     * 是否模拟外部税务计算服务超时异常（用于验证 BaseIfComponent onError 优雅降级）
     */
    private boolean simulateTaxError;

    /**
     * 是否为可疑/高风险项目（用于验证 BaseNodeComponent endFlow 流程提前正常终止）
     */
    private boolean suspicious;
}
