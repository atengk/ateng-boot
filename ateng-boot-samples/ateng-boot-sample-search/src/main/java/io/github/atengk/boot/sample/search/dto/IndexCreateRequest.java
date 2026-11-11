package io.github.atengk.boot.sample.search.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 索引创建请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexCreateRequest {

    @NotBlank(message = "索引名称不能为空")
    private String indexName;

    /**
     * 可选 Mapping JSON 定义
     */
    private String mappingJson;
}
