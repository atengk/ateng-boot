package io.github.atengk.boot.sample.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 多渠道并发广播通知请求传输对象
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
public class BroadcastRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 广播目标渠道标识列表（自动去重并按入参顺序保序返回）
     */
    @NotEmpty(message = "广播目标渠道列表不能为空")
    private List<@NotBlank(message = "广播目标渠道名称不能为空白") String> channels;

    /**
     * 广播通知正文内容
     */
    @NotBlank(message = "广播通知正文不能为空")
    private String content;

    /**
     * 是否使用异步门面 sendBroadcastAsync 派发（默认 false 使用同步并发门面）
     */
    private boolean async;

    /**
     * 是否 @所有人
     */
    private Boolean atAll;

    /**
     * 需要 @ 的成员手机号列表
     */
    private List<String> atMobiles;

    /**
     * 需要 @ 的成员用户 ID / 工号列表
     */
    private List<String> atUserIds;
}
