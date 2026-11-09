package io.github.atengk.boot.sample.realtime.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户单播消息请求传输对象
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushUserMessageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标用户唯一标识
     */
    @NotNull(message = "目标用户标识不能为 null")
    @NotBlank(message = "目标用户标识不能为空")
    private String userId;

    /**
     * 下行事件名 (如 "notice:order", "chat:message")
     */
    @NotNull(message = "下行事件名不能为 null")
    @NotBlank(message = "下行事件名不能为空")
    private String event;

    /**
     * 消息正文内容
     */
    @NotNull(message = "消息正文内容不能为 null")
    @NotBlank(message = "消息正文内容不能为空")
    private String content;

    /**
     * 指定设备类型 (可选，如 "WEB", "APP", "DESKTOP")
     */
    private String deviceType;

    /**
     * 指定客户端端点会话标识 (可选，针对特定 Tab 专线投递)
     */
    private String clientId;
}
