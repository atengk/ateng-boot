package io.github.atengk.boot.sample.mqtt.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 批量下发单条记录请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchPublishItemRequest implements Serializable {

    @NotBlank(message = "下发主题 topic 不能为空")
    private String topic;

    @NotNull(message = "下发载荷 payload 不能为空")
    private Object payload;

    @Min(value = 0, message = "QoS 最小为 0")
    @Max(value = 2, message = "QoS 最大为 2")
    @Builder.Default
    private Integer qos = 1;

    @Builder.Default
    private boolean retained = false;
}
