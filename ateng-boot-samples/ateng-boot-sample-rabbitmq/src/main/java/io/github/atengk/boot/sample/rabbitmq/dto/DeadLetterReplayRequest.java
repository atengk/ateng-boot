package io.github.atengk.boot.sample.rabbitmq.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 死信重放请求 DTO
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeadLetterReplayRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 死信队列名称
     */
    @NotBlank(message = "死信队列名称不能为空")
    private String dlq;

    /**
     * 目标业务交换机
     */
    @NotBlank(message = "目标交换机不能为空")
    private String targetExchange;

    /**
     * 目标业务路由键
     */
    @NotBlank(message = "目标路由键不能为空")
    private String targetRoutingKey;

    /**
     * 单批次最大重放数量
     */
    @Positive(message = "重放上限必须大于0")
    private int limit;
}
