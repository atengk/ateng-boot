package io.github.atengk.boot.sample.search.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 索引运行期 Settings 动态更新请求 DTO
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexSettingsUpdateRequest {

    /**
     * 目标索引名
     */
    @NotBlank(message = "索引名称不能为空")
    private String indexName;

    /**
     * Settings 配置 JSON 内容
     */
    @NotBlank(message = "Settings JSON 不能为空")
    private String settingsJson;
}
