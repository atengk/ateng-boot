package io.github.atengk.boot.sample.ssh.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ssh.dto.SshProxyJumpRequest;
import io.github.atengk.boot.sample.ssh.dto.SshRemoteTcpEchoRequest;
import io.github.atengk.boot.sample.ssh.dto.SshRemoteTunnelCloseRequest;
import io.github.atengk.boot.sample.ssh.dto.SshRemoteTunnelOpenRequest;
import io.github.atengk.boot.sample.ssh.dto.SshTcpEchoRequest;
import io.github.atengk.boot.sample.ssh.dto.SshTunnelCloseRequest;
import io.github.atengk.boot.sample.ssh.dto.SshTunnelOpenRequest;
import io.github.atengk.boot.sample.ssh.service.SshScenarioService;
import io.github.atengk.boot.sample.ssh.vo.SshExecResultVO;
import io.github.atengk.boot.sample.ssh.vo.SshTunnelInfoVO;
import io.github.atengk.boot.ssh.enums.TunnelMode;
import io.github.atengk.boot.ssh.model.SshExecResult;
import io.github.atengk.boot.ssh.tunnel.SshTunnelHandle;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SSH 受管网络穿透与跳板机级联控制器
 * <p>
 * 提供本地端口转发受管句柄建立与对称释放、ProxyJump 级联跳板机穿透、
 * 以及隧道端到端真实 TCP 流量交互回显等核心端点。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/ssh/tunnel")
@RequiredArgsConstructor
public class SshTunnelController {

    private final SshScenarioService sshScenarioService;

    /**
     * 建立受管的本地端口转发隧道
     *
     * @param request 隧道开启请求载荷
     * @return 实际绑定的隧道状态信息
     */
    @PostMapping("/open-tunnel")
    public Result<SshTunnelInfoVO> openTunnel(@Valid @RequestBody SshTunnelOpenRequest request) {
        log.info("SshTunnelController: 收到建立本地端口转发隧道请求 [serverName={}, localPort={}, remote={}:{}]",
                request.getServerName(), request.getLocalPort(), request.getRemoteHost(), request.getRemotePort());

        int boundPort = sshScenarioService.openManagedTunnel(
                request.getServerName(),
                request.getLocalPort() != null ? request.getLocalPort() : 0,
                request.getRemoteHost(),
                request.getRemotePort());

        SshTunnelInfoVO vo = SshTunnelInfoVO.builder()
                .tunnelMode(TunnelMode.LOCAL)
                .localPort(boundPort)
                .localHost("127.0.0.1")
                .remoteHost(request.getRemoteHost())
                .remotePort(request.getRemotePort())
                .alive(true)
                .build();

        return Result.success(vo);
    }

    /**
     * 对称安全释放受管的本地端口转发隧道
     *
     * @param request 释放请求载荷
     * @return true 若成功释放
     */
    @PostMapping("/close-tunnel")
    public Result<Boolean> closeTunnel(@Valid @RequestBody SshTunnelCloseRequest request) {
        log.info("SshTunnelController: 收到释放本地端口转发隧道请求 [localPort={}]", request.getLocalPort());
        boolean closed = sshScenarioService.closeManagedTunnel(request.getLocalPort());
        return Result.success(closed);
    }

    /**
     * 建立受管的远程端口转发隧道 (ssh -R)
     *
     * @param request 远程隧道开启请求载荷
     * @return 实际绑定的远端监听与本地目标状态信息
     */
    @PostMapping("/open-remote-tunnel")
    public Result<SshTunnelInfoVO> openRemoteTunnel(@Valid @RequestBody SshRemoteTunnelOpenRequest request) {
        log.info("SshTunnelController: 收到建立远程端口转发隧道请求 [serverName={}, remotePort={}, local={}:{}]",
                request.getServerName(), request.getRemotePort(), request.getLocalHost(), request.getLocalPort());

        SshTunnelHandle handle = sshScenarioService.openManagedRemoteTunnel(
                request.getServerName(),
                request.getRemotePort() != null ? request.getRemotePort() : 0,
                request.getLocalHost(),
                request.getLocalPort());

        return Result.success(SshTunnelInfoVO.from(handle));
    }

    /**
     * 对称安全释放受管的远程端口转发隧道
     *
     * @param request 远程隧道释放请求载荷
     * @return true 若成功释放
     */
    @PostMapping("/close-remote-tunnel")
    public Result<Boolean> closeRemoteTunnel(@Valid @RequestBody SshRemoteTunnelCloseRequest request) {
        log.info("SshTunnelController: 收到释放远程端口转发隧道请求 [remotePort={}]", request.getRemotePort());
        boolean closed = sshScenarioService.closeManagedRemoteTunnel(request.getRemotePort());
        return Result.success(closed);
    }

    /**
     * 两级跳板机级联穿透执行运维命令 (Client -> Jump1 -> Jump2 -> Target)
     *
     * @param request 级联穿透请求载荷
     * @return 执行结果
     */
    @PostMapping("/proxy-jump")
    public Result<SshExecResultVO> proxyJump(@Valid @RequestBody SshProxyJumpRequest request) {
        log.info("SshTunnelController: 收到 ProxyJump 两级跳板机级联穿透执行请求 [target={}:{}, command={}]",
                request.getTargetHost(), request.getTargetPort(), request.getCommand());

        SshExecResult result = sshScenarioService.runCascadingProxyJump(
                request.getTargetHost(), request.getTargetPort(),
                request.getJump1Host(), request.getJump1Port(),
                request.getJump2Host(), request.getJump2Port(),
                request.getCommand());

        return Result.success(SshExecResultVO.from(result));
    }

    /**
     * 演示建立端口转发隧道并完成端到端 TCP 通信回显
     *
     * @param request TCP 交互请求载荷
     * @return 远端服务回显内容
     * @throws Exception 通信异常
     */
    @PostMapping("/tcp-echo")
    public Result<String> tcpEcho(@Valid @RequestBody SshTcpEchoRequest request) throws Exception {
        log.info("SshTunnelController: 收到本地端口转发真实 TCP 流量透传请求 [{}:{}]",
                request.getRemoteHost(), request.getRemotePort());

        String echoReply = sshScenarioService.forwardAndQueryTcp(
                request.getRemoteHost(), request.getRemotePort(), request.getMessage());

        return Result.success(echoReply);
    }

    /**
     * 演示建立远程端口转发隧道并完成反向穿透 TCP 通信回显 (ssh -R)
     *
     * @param request 远程隧道 TCP 交互请求载荷
     * @return 本地服务经反向隧道回传的响应内容
     * @throws Exception 通信异常
     */
    @PostMapping("/remote-tcp-echo")
    public Result<String> remoteTcpEcho(@Valid @RequestBody SshRemoteTcpEchoRequest request) throws Exception {
        log.info("SshTunnelController: 收到远程端口转发反向 TCP 流量穿透请求 [serverName={}, remotePort={}, local={}:{}]",
                request.getServerName(), request.getRemotePort(), request.getLocalHost(), request.getLocalPort());

        String echoReply = sshScenarioService.reverseForwardAndQueryTcp(
                request.getServerName(),
                request.getRemotePort() != null ? request.getRemotePort() : 0,
                request.getLocalHost(),
                request.getLocalPort(),
                request.getMessage());

        return Result.success(echoReply);
    }
}
