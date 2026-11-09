package io.github.atengk.boot.sample.realtime.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 分布式群组/房间组播消息请求传输对象
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushGroupMessageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 群组/房间唯一标识 (如 "room-meeting-1001", "chat-group-888")
     */
    @NotBlank(message = "群组标识不能为空")
    private String groupId;

    /**
     * 下行事件名 (如 "group:notice", "meeting:mute")
     */
    @NotBlank(message = "下行事件名不能为空")
    private String event;

    /**
     * 消息正文内容
     */
    @NotBlank(message = "消息正文内容不能为空")
    private String content;
}
