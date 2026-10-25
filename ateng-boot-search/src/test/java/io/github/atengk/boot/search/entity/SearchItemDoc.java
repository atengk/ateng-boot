package io.github.atengk.boot.search.entity;

import io.github.atengk.boot.search.annotation.HighlightField;
import io.github.atengk.boot.search.annotation.SearchId;
import io.github.atengk.boot.search.annotation.SearchIndex;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 带有高亮富文本回填字段的商品搜索测试文档
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SearchIndex("item_idx")
public class SearchItemDoc {

    @SearchId
    private String id;

    private String title;

    private String category;

    private BigDecimal price;

    private String description;

    private String location;

    private String tenantId;

    /**
     * 显式指定回填 title 字段的高亮富文本
     */
    @HighlightField(target = "title")
    private String highlightedTitle;

    /**
     * 未显式指定 target 时默认回填字段自身名称 (description)
     */
    @HighlightField
    private String highlightedDescription;
}
