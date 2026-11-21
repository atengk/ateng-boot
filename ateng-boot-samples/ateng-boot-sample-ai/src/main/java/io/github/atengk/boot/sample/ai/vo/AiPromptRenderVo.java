package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 动态提示词模板渲染响应 VO
 *
 * @author Ateng
 * @since 2026-11-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiPromptRenderVo {

    private String code;
    private String version;
    private String label;
    private boolean strict;
    private String renderedContent;
}
