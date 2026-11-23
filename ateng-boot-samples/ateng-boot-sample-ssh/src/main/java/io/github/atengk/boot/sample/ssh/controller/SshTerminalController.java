package io.github.atengk.boot.sample.ssh.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ssh.vo.SshTerminalInfoVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WebSSH 终端服务诊断与规格控制器
 * <p>
 * 提供 WebSSH 默认终端类型、默认行列尺寸、双向 WebSocket 端点说明等诊断信息。
 * 真实双向交互通过原生 WebSocket 端点（/ws/ssh）直接传输 ANSI 字符与字节帧。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/ssh/terminal")
public class SshTerminalController {

    /**
     * 查询 WebSSH 终端配置规格与运行状态
     *
     * @return 终端规格 VO
     */
    @GetMapping("/info")
    public Result<SshTerminalInfoVO> getTerminalInfo() {
        log.info("SshTerminalController: 收到查询 WebSSH 终端诊断指标请求");

        SshTerminalInfoVO vo = SshTerminalInfoVO.builder()
                .defaultTerm("xterm-256color")
                .defaultCols(80)
                .defaultRows(24)
                .websocketEndpoint("/ws/ssh")
                .activeSessions(0)
                .status("UP")
                .build();

        return Result.success(vo);
    }
}
