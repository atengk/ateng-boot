package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * RAG 多轮问答查询改写响应视图对象
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagTransformVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 原始问句
     */
    private String originalQuery;

    /**
     * 改写后语义独立、消解代词的完整检索 Query
     */
    private String transformedQuery;

    /**
     * 参与改写的历史消息条数
     */
    private Integer historyCount;

    /**
     * 是否实际触发并完成了改写
     */
    private Boolean hasRewritten;

    /**
     * 说明消息
     */
    private String message;
}
