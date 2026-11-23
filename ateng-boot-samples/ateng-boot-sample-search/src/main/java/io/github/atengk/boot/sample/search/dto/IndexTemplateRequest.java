package io.github.atengk.boot.sample.search.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 索引模板创建/应用请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexTemplateRequest {

    @NotBlank(message = "模板名称不能为空")
    private String templateName;

    @NotBlank(message = "模板定义 JSON 不能为空")
    private String templateJson;
}
