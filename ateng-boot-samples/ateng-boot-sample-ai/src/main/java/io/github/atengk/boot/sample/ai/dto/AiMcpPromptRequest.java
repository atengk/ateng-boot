package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

/**
 * 远程 MCP 提示词模板渲染请求传输对象
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiMcpPromptRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标 MCP 服务端名称
     */
    @NotBlank(message = "目标 MCP 服务名称 serverName 不能为空")
    private String serverName;

    /**
     * 提示词模板名称
     */
    @NotBlank(message = "提示词模板名称 promptName 不能为空")
    private String promptName;

    /**
     * 模板入参字典
     */
    @Builder.Default
    private Map<String, Object> arguments = Collections.emptyMap();
}
