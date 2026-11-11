package io.github.atengk.boot.sample.search.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * AI KNN 稠密向量近邻检索请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductKnnSearchRequest {

    @NotEmpty(message = "特征向量不能为空")
    private List<Float> vector;

    @Min(value = 1, message = "召回数 k 至少为 1")
    @Builder.Default
    private Integer k = 5;
}
