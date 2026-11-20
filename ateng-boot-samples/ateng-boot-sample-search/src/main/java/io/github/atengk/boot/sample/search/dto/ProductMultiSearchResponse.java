package io.github.atengk.boot.sample.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 商品批量异构检索 (_msearch) 响应 VO
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductMultiSearchResponse {

    /**
     * 第一路关键词检索命中商品列表
     */
    private List<ProductResponse> items;

    /**
     * 第二路分类汇总匹配总文档数
     */
    private long statTotal;
}
