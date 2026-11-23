package io.github.atengk.boot.sample.mqtt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 批量连续毒丸投递请求 DTO（用于验证 MqttDeadLetterStore 多条有序留存而非单槽覆盖）
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiPoisonRequest implements Serializable {

    /**
     * 待连续投递的毒丸载荷集合（列表非空且元素均不可为空白）
     */
    @NotEmpty(message = "毒丸载荷列表 payloads 不能为空")
    private List<@NotBlank(message = "单条毒丸载荷不能为空") String> payloads;
}
