package io.github.atengk.boot.sample.rabbitmq.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 广播消息发布请求 DTO
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BroadcastPublishRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 广播内容
     */
    @NotBlank(message = "广播内容不能为空")
    private String message;
}
