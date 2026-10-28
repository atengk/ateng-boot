package io.github.atengk.boot.sample.redis.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 系统公告/通知广播消息载荷
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NoticeBroadcastPayload implements Serializable {

    /**
     * 公告通知 ID
     */
    private String noticeId;

    /**
     * 公告标题
     */
    private String title;

    /**
     * 公告详细正文
     */
    private String content;

    /**
     * 发布时间戳
     */
    private Long timestamp;
}
