package io.github.atengk.boot.sample.rocketmq.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;

/**
 * SimpleConsumer 不可见租约续期请求入参
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimpleRenewRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待续期的目标消息 ID
     */
    @NotBlank(message = "消息 ID 不能为空")
    private String messageId;

    /**
     * 消费者组（可选，若为空则从句柄缓存推断）
     */
    private String group;

    /**
     * 新的不可见租约时长（秒）
     */
    @NotNull(message = "续约时长不能为空")
    @Min(value = 1, message = "续约时长最小为 1 秒")
    private Long invisibleSeconds;

    public Duration resolveDuration() {
        return Duration.ofSeconds(invisibleSeconds);
    }
}
