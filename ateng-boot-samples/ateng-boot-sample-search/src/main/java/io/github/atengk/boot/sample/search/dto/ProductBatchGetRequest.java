package io.github.atengk.boot.sample.search.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 商品批量拉取请求 DTO (MGet)
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductBatchGetRequest {

    @NotEmpty(message = "商品ID集合不能为空")
    private List<String> ids;

    private String brand;
}
