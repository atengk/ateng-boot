package io.github.atengk.boot.sample.redis.model.pubsub;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 系统通配事件广播发布请求参数
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemEventBroadcastRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 事件类型 (如 config_refresh, user_logout 等)
     */
    @NotBlank(message = "事件类型不能为空")
    private String eventType;

    /**
     * 事件携带数据报文
     */
    @NotBlank(message = "事件数据不能为空")
    private String eventData;
}
