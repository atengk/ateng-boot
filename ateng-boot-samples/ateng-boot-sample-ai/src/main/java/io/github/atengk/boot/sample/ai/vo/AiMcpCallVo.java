package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 远程 MCP 工具执行响应视图对象
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiMcpCallVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标 MCP 服务端名称
     */
    private String serverName;

    /**
     * 执行的工具名称
     */
    private String toolName;

    /**
     * 远程工具执行返回结果正文
     */
    private String result;

    /**
     * 工具执行耗时 (毫秒)
     */
    private Long durationMs;
}
