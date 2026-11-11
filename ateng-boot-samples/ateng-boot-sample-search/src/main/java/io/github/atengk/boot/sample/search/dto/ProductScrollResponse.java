package io.github.atengk.boot.sample.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Search-After 游标分页响应 VO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductScrollResponse {

    /**
     * 本页命中文档列表
     */
    private List<ProductResponse> items;

    /**
     * 下一页游标凭据值（若为空或无下一页则为 null）
     */
    private List<Object> nextSortValues;

    /**
     * 是否存在更多记录
     */
    private boolean hasMore;
}
