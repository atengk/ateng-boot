package io.github.atengk.boot.sample.kafka.dto;

import io.github.atengk.boot.core.validation.ValidList;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 批量订单聚合投递请求 DTO
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchOrderProduceRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 批量订单请求列表
     */
    @Valid
    @NotNull(message = "批量订单列表不能为空")
    private ValidList<OrderProduceRequest> orders;
}
