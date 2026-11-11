package io.github.atengk.boot.sample.search.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 地理空间距离周边搜索请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductGeoSearchRequest {

    @NotNull(message = "中心点纬度不能为空")
    private Double lat;

    @NotNull(message = "中心点经度不能为空")
    private Double lon;

    @NotBlank(message = "距离半径范围不能为空（如 10km）")
    private String distance;
}
