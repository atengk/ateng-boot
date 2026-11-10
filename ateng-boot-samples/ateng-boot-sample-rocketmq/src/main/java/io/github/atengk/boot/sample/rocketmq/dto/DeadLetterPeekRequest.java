package io.github.atengk.boot.sample.rocketmq.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 死信队列只读探查请求入参
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeadLetterPeekRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标消费组名称
     */
    @NotBlank(message = "消费组不能为空")
    private String consumerGroup;

    /**
     * 最大探查消息条数（1-32，默认 10）
     */
    @Min(value = 1, message = "探查条数最小为 1")
    @Max(value = 32, message = "探查条数最大为 32")
    private Integer limit;

    public int resolveLimit() {
        return limit != null && limit > 0 ? limit : 10;
    }
}
