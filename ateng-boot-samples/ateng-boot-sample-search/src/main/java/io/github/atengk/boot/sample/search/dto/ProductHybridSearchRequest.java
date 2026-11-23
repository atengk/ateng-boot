package io.github.atengk.boot.sample.search.dto;

import io.github.atengk.boot.search.enums.SearchHybridMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 商品混合检索请求 DTO (BM25 + kNN)
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductHybridSearchRequest {

    /**
     * 全文关键词
     */
    @NotBlank(message = "搜索关键字不能为空")
    private String keyword;

    /**
     * 查询特征向量
     */
    @NotEmpty(message = "查询向量不能为空")
    private List<Float> vector;

    /**
     * 融合返回条数
     */
    @Min(value = 1, message = "返回条数 k 必须大于等于 1")
    private Integer k;

    /**
     * 融合模式（默认 CLIENT_RRF）
     */
    private SearchHybridMode mode;
}
