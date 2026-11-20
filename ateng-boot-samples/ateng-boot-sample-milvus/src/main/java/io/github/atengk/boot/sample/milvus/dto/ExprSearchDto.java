package io.github.atengk.boot.sample.milvus.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 基于 MilvusExprHelper 复合表达式检索请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExprSearchDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标集合名称 (默认 sample_document)
     */
    @Builder.Default
    private String collectionName = "sample_document";

    /**
     * 查询特征向量
     */
    @NotEmpty(message = "查询特征向量不能为空")
    private List<Float> vector;

    /**
     * 分类过滤条件 (可选，生成 eq 表达式)
     */
    private String category;

    /**
     * 标题前缀模糊条件 (可选，生成 like 表达式)
     */
    private String titlePrefix;

    /**
     * 最小文档 ID 阈值 (可选，生成 gte 表达式)
     */
    private Long minId;

    /**
     * BM25 全文分词检索词 (可选，生成 TEXT_MATCH 表达式)
     */
    private String textMatchQuery;

    /**
     * 召回上限 TopK
     */
    @Min(value = 1, message = "topK 最小为 1")
    @Max(value = 100, message = "topK 最大为 100")
    @Builder.Default
    private Integer topK = 5;
}
