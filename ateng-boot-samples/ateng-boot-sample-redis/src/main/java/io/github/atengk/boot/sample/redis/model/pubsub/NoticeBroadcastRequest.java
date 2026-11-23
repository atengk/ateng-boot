package io.github.atengk.boot.sample.redis.model.pubsub;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 全站公告发布请求参数
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoticeBroadcastRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 公告通知 ID
     */
    @NotBlank(message = "公告 ID 不能为空")
    private String noticeId;

    /**
     * 公告标题
     */
    @NotBlank(message = "公告标题不能为空")
    private String title;

    /**
     * 公告正文
     */
    @NotBlank(message = "公告正文不能为空")
    private String content;

    /**
     * 紧急级别 (INFO, WARN, CRITICAL)
     */
    private String level;
}
