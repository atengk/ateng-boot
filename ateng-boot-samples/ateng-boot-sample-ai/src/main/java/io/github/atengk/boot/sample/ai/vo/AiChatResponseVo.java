package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 对话响应视图对象 VO
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatResponseVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 大模型响应回答文本
     */
    private String reply;

    /**
     * 实际调用的模型名称或标识
     */
    private String model;
}
