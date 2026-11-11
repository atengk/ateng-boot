package io.github.atengk.boot.sample.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 字段折叠去重检索请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductCollapseRequest {

    private String brand;

    @Builder.Default
    private String collapseField = "brand.keyword";
}
