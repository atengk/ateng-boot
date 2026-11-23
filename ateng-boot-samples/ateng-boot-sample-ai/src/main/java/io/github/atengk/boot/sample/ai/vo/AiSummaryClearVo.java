package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * AI 长会话摘要记忆清空响应视图对象
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSummaryClearVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话标识 ID
     */
    private String sessionId;

    /**
     * 清空操作执行状态
     */
    private Boolean cleared;

    /**
     * 操作说明消息
     */
    private String message;
}
