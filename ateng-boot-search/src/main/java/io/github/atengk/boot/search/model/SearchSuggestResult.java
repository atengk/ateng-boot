package io.github.atengk.boot.search.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 强类型检索建议 / 自动补全结果模型 (Completion / Term / Phrase Suggester)
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchSuggestResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 推荐词或纠错后的文本内容
     */
    private String text;

    /**
     * 建议候选得分
     */
    private Double score;

    /**
     * 词项词频（仅 Term Suggester 返回，其他类型为 null）
     */
    private Long freq;

    /**
     * 高亮建议文本（仅支持高亮的 Term/Phrase Suggester 返回）
     */
    private String highlighted;

    /**
     * 关联文档主键（仅 Completion Suggester 返回，其他类型为 null）
     */
    private String id;
}
