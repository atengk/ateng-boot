package io.github.atengk.boot.sample.search.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 组件模板 (Component Template) 创建/更新请求 DTO
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComponentTemplateRequest {

    /**
     * 组件模板名称
     */
    @NotBlank(message = "组件模板名称不能为空")
    private String templateName;

    /**
     * 组件模板 JSON 内容
     */
    @NotBlank(message = "组件模板 JSON 不能为空")
    private String templateJson;
}
