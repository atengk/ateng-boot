package io.github.atengk.boot.sample.observability.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 物流履约调度推单请求传输对象
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsDispatchRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 物流运单号
     */
    @NotBlank(message = "物流运单号不能为空")
    private String trackId;
}
