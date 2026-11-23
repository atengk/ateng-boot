package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 智能体长效情景记忆对话响应 VO
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEpisodicChatVo implements Serializable {

    /**
     * 目标用户唯一标识
     */
    private String userId;

    /**
     * 智能体模型回复内容
     */
    private String reply;

    /**
     * 本次对话触发前召回的用户长效情景记忆事实列表
     */
    @Builder.Default
    private List<MemoryFactVo> recalledFacts = Collections.emptyList();
}
