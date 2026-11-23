package io.github.atengk.boot.sample.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 商品批量异构检索 (_msearch) 请求 DTO
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductMultiSearchRequest {

    /**
     * 全文关键词（第一路检索）
     */
    private String keyword;

    /**
     * 指定商品分类（第二路统计检索）
     */
    private String category;
}
