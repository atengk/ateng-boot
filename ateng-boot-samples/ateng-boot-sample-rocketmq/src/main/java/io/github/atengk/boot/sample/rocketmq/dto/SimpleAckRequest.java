package io.github.atengk.boot.sample.rocketmq.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * SimpleConsumer 显式 ACK 确认请求入参
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimpleAckRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待确认并出队的目标消息 ID
     */
    @NotBlank(message = "消息 ID 不能为空")
    private String messageId;

    /**
     * 消费者组（可选，若为空则从句柄缓存推断）
     */
    private String group;
}
