package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * RAG 知识库问答增强结果 VO
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagAskVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户提问
     */
    private String question;

    /**
     * 大模型融合检索上下文生成的最终回答
     */
    private String answer;

    /**
     * 检索召回的参考知识上下文列表
     */
    @Builder.Default
    private List<String> referenceContexts = Collections.emptyList();
}
