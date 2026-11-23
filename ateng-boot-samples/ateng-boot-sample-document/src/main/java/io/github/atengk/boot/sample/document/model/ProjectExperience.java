package io.github.atengk.boot.sample.document.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 历史负责与交付项目经验条目
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectExperience {

    /**
     * 项目名称
     */
    @NotBlank(message = "项目名称不能为空")
    private String projectName;

    /**
     * 承担角色职务
     */
    @NotBlank(message = "承担角色不能为空")
    private String role;

    /**
     * 周期历时
     */
    @NotBlank(message = "周期历时不能为空")
    private String duration;
}
