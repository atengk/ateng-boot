package io.github.atengk.boot.sample.ssh.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * WebSSH 终端规格与服务诊断指标 VO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshTerminalInfoVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 默认支持的终端类型（如 xterm-256color）
     */
    private String defaultTerm;

    /**
     * 默认终端视口宽度（列数 cols）
     */
    private Integer defaultCols;

    /**
     * 默认终端视口高度（行数 rows）
     */
    private Integer defaultRows;

    /**
     * 实时双向 WebSocket 端点相对路径
     */
    private String websocketEndpoint;

    /**
     * 当前运行环境状态描述
     */
    private String status;
}
