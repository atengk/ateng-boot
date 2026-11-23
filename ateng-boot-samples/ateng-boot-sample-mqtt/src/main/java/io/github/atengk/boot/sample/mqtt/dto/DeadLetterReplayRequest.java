package io.github.atengk.boot.sample.mqtt.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 死信重放请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeadLetterReplayRequest implements Serializable {

    @NotBlank(message = "死信源主题 sourceTopic 不能为空")
    private String sourceTopic;

    @NotBlank(message = "目标恢复主题 targetTopic 不能为空")
    private String targetTopic;

    @Min(value = 1, message = "重放条数至少为 1")
    @Max(value = 1000, message = "单次重放条数不能超过 1000")
    @Builder.Default
    private Integer count = 10;

    @Min(value = 1, message = "超时时间至少为 1 秒")
    @Max(value = 60, message = "超时时间不能超过 60 秒")
    @Builder.Default
    private Integer timeoutSeconds = 5;
}
