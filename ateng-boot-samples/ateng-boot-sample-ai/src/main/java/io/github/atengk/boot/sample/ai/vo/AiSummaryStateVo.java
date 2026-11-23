package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 长会话双层上下文记忆状态视图对象
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSummaryStateVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话标识
     */
    private String sessionId;

    /**
     * 历史全局摘要内容
     */
    private String summary;

    /**
     * 最新明细消息列表 (包含角色与内容预览)
     */
    @Builder.Default
    private List<String> recentMessages = Collections.emptyList();
}
