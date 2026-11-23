package io.github.atengk.boot.sample.search.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Search-After 深度游标分页请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductScrollQueryRequest {

    /**
     * 上一页末尾排序凭据值（第一页传空或 null）
     */
    private List<Object> sortValues;

    @Min(value = 1, message = "分页大小至少为 1")
    @Max(value = 100, message = "单页查询不能超过 100 条")
    @Builder.Default
    private Integer size = 10;
}
