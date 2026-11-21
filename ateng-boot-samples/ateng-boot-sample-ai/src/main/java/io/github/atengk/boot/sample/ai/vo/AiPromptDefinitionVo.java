package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 动态提示词定义传输视图 VO
 *
 * @author Ateng
 * @since 2026-11-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiPromptDefinitionVo {

    private String code;
    private String version;
    private String label;
    private String content;
    private Set<String> requiredVariables;
    private String description;
}
