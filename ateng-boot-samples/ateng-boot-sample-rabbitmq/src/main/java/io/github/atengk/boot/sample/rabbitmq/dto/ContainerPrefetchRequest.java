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
 * 监听容器预取数量 (PrefetchCount) 动态调节请求 DTO
 *
 * @author Ateng
 * @since 2026-11-15
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerPrefetchRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标监听容器 ID 或队列名称
     */
    @NotBlank(message = "目标容器 ID 或队列名称不能为空")
    private String target;

    /**
     * 通道预取消息数量
     */
    @Positive(message = "通道预取数量必须大于 0")
    private int prefetchCount;
}
