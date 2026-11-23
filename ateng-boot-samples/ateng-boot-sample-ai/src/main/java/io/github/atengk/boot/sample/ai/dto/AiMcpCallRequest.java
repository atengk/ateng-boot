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
 * 远程 MCP 工具执行请求传输对象
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiMcpCallRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标 MCP 服务端名称 (对应注册中心中的服务标识)
     */
    @NotBlank(message = "目标 MCP 服务名称 serverName 不能为空")
    private String serverName;

    /**
     * 待调用的工具名称 (如 get_weather, calculate)
     */
    @NotBlank(message = "待调用的工具名称 toolName 不能为空")
    private String toolName;

    /**
     * 工具入参参数字典
     */
    @Builder.Default
    private Map<String, Object> arguments = Collections.emptyMap();
}
