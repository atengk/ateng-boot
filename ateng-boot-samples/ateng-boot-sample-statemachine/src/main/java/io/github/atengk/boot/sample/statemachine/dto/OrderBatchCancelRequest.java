package io.github.atengk.boot.sample.statemachine.dto;

import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量取消超时订单请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderBatchCancelRequest {

    /**
     * 目标订单 ID 列表（若为空则自动扫描超时订单）
     */
    private List<Long> orderIds;

    /**
     * 取消原因
     */
    private String cancelReason;

    /**
     * 切片批次大小（默认 50）
     */
    @Positive(message = "切片批次大小必须为正数")
    @Builder.Default
    private Integer batchSize = 50;
}
