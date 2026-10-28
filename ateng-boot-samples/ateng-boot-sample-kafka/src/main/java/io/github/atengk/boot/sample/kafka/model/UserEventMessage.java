package io.github.atengk.boot.sample.kafka.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户行为事件实体
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserEventMessage implements Serializable {

    @NotBlank(message = "用户ID不能为空")
    private String userId;

    @NotBlank(message = "事件类型不能为空")
    private String eventType;

    private Long timestamp;
}
