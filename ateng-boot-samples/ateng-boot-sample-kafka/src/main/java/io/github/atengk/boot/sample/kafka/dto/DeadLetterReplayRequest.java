package io.github.atengk.boot.sample.kafka.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 死信主题受控重放请求 DTO
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
     * 源死信主题名称 (如 order.create.topic.DLT)
     */
    @NotBlank(message = "死信主题不能为空")
    private String dlqTopic;

    /**
     * 目标业务重放主题名称 (如 order.create.topic)
     */
    @NotBlank(message = "目标业务主题不能为空")
    private String targetTopic;

    /**
     * 单批最大重放数量限制 (默认 100)
     */
    @Min(value = 1, message = "重放数量必须大于0")
    @Builder.Default
    private int limit = 100;
}
