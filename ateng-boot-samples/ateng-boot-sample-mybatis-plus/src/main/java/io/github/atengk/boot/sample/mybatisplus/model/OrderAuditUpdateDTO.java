package io.github.atengk.boot.sample.mybatisplus.model;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单变更审计演示更新入参传输对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderAuditUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单主键 ID
     */
    @NotNull(message = "订单 ID 不能为空")
    private Long orderId;

    /**
     * 新订单标题
     */
    private String newTitle;

    /**
     * 新订单金额
     */
    private BigDecimal newAmount;

    /**
     * 新订单状态
     */
    private String newStatus;
}
