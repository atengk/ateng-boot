package io.github.atengk.boot.sample.rocketmq.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 延时消息精准撤回请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderRecallRequest {

    /**
     * 目标主题（默认为 sample_delay_topic）
     */
    private String topic;

    /**
     * 精准撤回句柄（从发信响应中获取）
     */
    @NotBlank(message = "撤回句柄不能为空")
    private String recallHandle;
}
