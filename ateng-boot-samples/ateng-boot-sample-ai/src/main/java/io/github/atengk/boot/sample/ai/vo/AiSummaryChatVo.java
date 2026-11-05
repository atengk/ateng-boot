package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 长会话异步摘要对话响应视图对象
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSummaryChatVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话标识
     */
    private String sessionId;

    /**
     * 大模型本轮回复
     */
    private String reply;

    /**
     * 当前提炼的历史全局语义摘要 (若尚未触发摘要则为空或初始状态)
     */
    private String summary;

    /**
     * 当前保持在滑动窗口内的最新明细消息条数
     */
    private int recentMessagesCount;
}
