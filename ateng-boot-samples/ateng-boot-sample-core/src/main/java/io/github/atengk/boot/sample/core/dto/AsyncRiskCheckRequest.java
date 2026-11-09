package io.github.atengk.boot.sample.core.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 异步风控并行短路预检请求对象
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsyncRiskCheckRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 各维度风控检测分值
     */
    @NotEmpty(message = "风控检测分值不能为空")
    private List<Integer> scores;

    /**
     * 安全分值阈值（低于该分值即阻断短路）
     */
    @NotNull(message = "安全阈值不能为空")
    private Integer safetyThreshold;
}
