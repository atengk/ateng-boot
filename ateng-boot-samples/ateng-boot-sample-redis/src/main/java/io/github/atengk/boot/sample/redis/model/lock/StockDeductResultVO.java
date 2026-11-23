package io.github.atengk.boot.sample.redis.model.lock;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀库存扣减结果视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockDeductResultVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long productId;
    private Integer remainingStock;
    private Boolean success;
    private String message;
}
