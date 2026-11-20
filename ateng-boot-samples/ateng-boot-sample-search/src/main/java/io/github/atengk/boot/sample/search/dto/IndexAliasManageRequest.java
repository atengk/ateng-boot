package io.github.atengk.boot.sample.search.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 索引别名精细管理请求 DTO (addAlias / removeAlias)
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexAliasManageRequest {

    /**
     * 物理索引名
     */
    @NotBlank(message = "索引名称不能为空")
    private String indexName;

    /**
     * 别名名称
     */
    @NotBlank(message = "别名名称不能为空")
    private String aliasName;

    /**
     * 是否标记为写入主索引 (is_write_index)
     */
    private boolean writeIndex;
}
