package io.github.atengk.boot.sample.realtime.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 全服/租户跨节点广播消息请求传输对象
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushBroadcastRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 下行事件名 (如 "system:announcement", "upgrade:notice")
     */
    @NotNull(message = "广播事件名不能为 null")
    @NotBlank(message = "广播事件名不能为空")
    private String event;

    /**
     * 广播正文内容
     */
    @NotNull(message = "广播正文内容不能为 null")
    @NotBlank(message = "广播正文内容不能为空")
    private String content;

    /**
     * 指定租户标识 (可选，非空时仅向该租户所属会话广播)
     */
    private Long tenantId;
}
