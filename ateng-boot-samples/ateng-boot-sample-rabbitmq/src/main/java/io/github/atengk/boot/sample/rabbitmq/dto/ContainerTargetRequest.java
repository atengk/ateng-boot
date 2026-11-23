package io.github.atengk.boot.sample.rabbitmq.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 监听容器启停控制请求 DTO
 *
 * @author Ateng
 * @since 2026-11-15
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerTargetRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标监听容器 ID 或队列名称
     */
    @NotBlank(message = "目标容器 ID 或队列名称不能为空")
    private String target;
}
