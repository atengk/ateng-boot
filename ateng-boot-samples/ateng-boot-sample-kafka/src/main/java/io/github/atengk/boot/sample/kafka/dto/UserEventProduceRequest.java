package io.github.atengk.boot.sample.kafka.dto;

import io.github.atengk.boot.sample.kafka.model.UserEventMessage;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户事件异步投递请求 DTO
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserEventProduceRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户ID
     */
    @NotBlank(message = "用户ID不能为空")
    private String userId;

    /**
     * 行为事件类型 (如 LOGIN, LOGOUT, VIEW_ITEM)
     */
    @NotBlank(message = "事件类型不能为空")
    private String eventType;

    /**
     * 转换为用户行为事件领域模型
     *
     * @return 用户事件消息
     */
    public UserEventMessage toUserEventMessage() {
        return new UserEventMessage(userId, eventType, System.currentTimeMillis());
    }
}
