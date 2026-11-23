package io.github.atengk.boot.sample.kafka.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * Kafka 原生分布式事务双写请求 DTO
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NativeTxProduceRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待创建的订单信息
     */
    @Valid
    @NotNull(message = "订单信息不能为空")
    private OrderProduceRequest order;

    /**
     * 同事务原子多写的审计日志内容
     */
    @NotBlank(message = "审计日志内容不能为空")
    private String auditContent;
}
