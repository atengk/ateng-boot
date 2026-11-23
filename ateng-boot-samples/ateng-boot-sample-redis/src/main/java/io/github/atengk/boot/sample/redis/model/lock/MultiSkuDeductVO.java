package io.github.atengk.boot.sample.redis.model.lock;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 组合套餐多 SKU 联锁扣减结果视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiSkuDeductVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String comboId;
    private List<Long> productIds;
    private Boolean success;
    private String message;
}
