package io.github.atengk.boot.sample.notification.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 动态切源发信请求传输对象
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
public class ChannelSwitchRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标切源渠道名称
     */
    @NotBlank(message = "目标切源渠道不能为空")
    private String targetChannel;

    /**
     * 通知正文内容
     */
    @NotBlank(message = "通知内容不能为空")
    private String content;

    /**
     * 是否 @所有人
     */
    private Boolean atAll;

    /**
     * 指定 @提醒的手机号列表
     */
    private List<String> atMobiles;

    /**
     * 指定 @提醒的用户工号或 OpenID 列表
     */
    private List<String> atUserIds;
}
