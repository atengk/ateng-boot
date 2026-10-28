package io.github.atengk.boot.sample.document.model;

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
    private String projectName;

    /**
     * 承担角色职务
     */
    private String role;

    /**
     * 周期历时
     */
    private String duration;
}
