package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 多模型故障自动转移容灾对话响应视图对象
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiFailoverChatVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 原始提问
     */
    private String prompt;

    /**
     * 最终回复
     */
    private String response;

    /**
     * 最终成功响应的模型名称
     */
    private String resolvedModel;

    /**
     * 是否发生了故障转移
     */
    private boolean failoverOccurred;

    /**
     * 说明消息
     */
    private String message;
}
