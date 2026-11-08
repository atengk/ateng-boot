package io.github.atengk.boot.sample.redis.model.traffic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 征信核验结果视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreditCheckVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String idCard;
    private String customerName;
    private Integer creditScore;
    private Boolean fallback;
    private String message;
}
