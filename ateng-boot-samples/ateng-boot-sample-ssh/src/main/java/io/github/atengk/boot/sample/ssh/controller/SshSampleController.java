package io.github.atengk.boot.sample.ssh.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ssh.dto.*;
import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import io.github.atengk.boot.ssh.model.SshExecOptions;
import io.github.atengk.boot.ssh.model.SshExecResult;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.template.SshTemplate;
import io.github.atengk.boot.ssh.tunnel.SshTunnelHandle;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SSH 场景样例服务 Web 控制器
 * <p>
 * 遵循显式强类型 {@link Result} 契约，演示远程命令执行、Sudo 提权安全管道、集群批量并发巡检、
 * 实时按行流式消费、CMDB 纯内存动态直连与受管本地端口转发全场景功能。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/ssh")
@RequiredArgsConstructor
public class SshSampleController {

    private final SshTemplate sshTemplate;

    /**
     * 同步短命令执行（支持指定节点与富选项）
     *
     * @param request 命令执行入参
     * @return 强类型命令执行结果
     */
    @PostMapping("/exec")
    public Result<SshExecResult> execCommand(@Valid @RequestBody SshExecCommandRequest request) {
        SshExecOptions.SshExecOptionsBuilder optionsBuilder = SshExecOptions.builder();
        if (request.getWorkingDir() != null && !request.getWorkingDir().isBlank()) {
            optionsBuilder.workingDir(request.getWorkingDir());
        }
        if (request.getTimeoutSeconds() != null && request.getTimeoutSeconds() > 0) {
            optionsBuilder.timeout(Duration.ofSeconds(request.getTimeoutSeconds()));
        }

        SshExecResult result;
        if (request.getServer() != null && !request.getServer().isBlank()) {
            result = sshTemplate.exec(request.getServer(), request.getCommand(), optionsBuilder.build());
        } else {
            result = sshTemplate.exec(request.getCommand(), optionsBuilder.build());
        }
        return Result.success(result);
    }

    /**
     * 原生 Sudo 提权安全管道执行（密码即时内存擦除抹零）
     *
     * @param request Sudo 请求入参
     * @return 强类型执行结果
     */
    @PostMapping("/exec-sudo")
    public Result<SshExecResult> execSudoCommand(@Valid @RequestBody SshSudoCommandRequest request) {
        SshExecResult result;
        if (request.getServer() != null && !request.getServer().isBlank()) {
            result = sshTemplate.execSudo(request.getServer(), request.getCommand(), request.getSudoPassword());
        } else {
            result = sshTemplate.execSudo(request.getCommand(), request.getSudoPassword());
        }
        return Result.success(result);
    }

    /**
     * 集群多主机并发巡检执行
     *
     * @param request 批量执行请求入参
     * @return 节点名称至执行结果的映射字典
     */
    @PostMapping("/exec-batch")
    public Result<Map<String, SshExecResult>> execBatch(@Valid @RequestBody SshBatchCommandRequest request) {
        Map<String, SshExecResult> resultMap = sshTemplate.execBatchNamed(request.getServers(), request.getCommand());
        return Result.success(resultMap);
    }

    /**
     * 长任务流式按行日志捕获执行
     *
     * @param request 命令执行请求
     * @return 捕获的逐行输出列表
     */
    @PostMapping("/stream-log")
    public Result<List<String>> streamLog(@Valid @RequestBody SshExecCommandRequest request) {
        List<String> capturedLines = new ArrayList<>();
        int exitCode = sshTemplate.execStream(
                request.getCommand(),
                line -> {
                    synchronized (capturedLines) {
                        capturedLines.add(line);
                    }
                },
                null
        );
        log.info("ateng-boot-ssh-sample: 流式命令执行完成，exitCode={}, 捕获行数={}",
                exitCode, capturedLines.size());
        return Result.success(capturedLines);
    }

    /**
     * CMDB / 数据库动态读取资产连接执行（纯内存 PEM 私钥与宽容安全指纹）
     *
     * @param request 动态连接入参
     * @return 强类型执行结果
     */
    @PostMapping("/dynamic-connect")
    public Result<SshExecResult> dynamicConnect(@Valid @RequestBody SshDynamicConnectRequest request) {
        SshServerInfo serverInfo = SshServerInfo.builder()
                .host(request.getHost())
                .port(request.getPort())
                .username(request.getUsername())
                .password(request.getPassword())
                .privateKey(request.getPrivateKey())
                .hostKeyPolicy(HostKeyPolicy.LENIENT)
                .build();

        SshExecResult result = sshTemplate.exec(serverInfo, request.getCommand());
        return Result.success(result);
    }

    /**
     * 开启本地端口转发受管隧道并返回实际绑定的本地端口
     *
     * @param request 隧道请求入参
     * @return 真实绑定的本地端口
     */
    @PostMapping("/tunnel/open")
    public Result<Integer> openTunnel(@Valid @RequestBody SshTunnelRequest request) {
        SshTunnelHandle handle;
        if (request.getServer() != null && !request.getServer().isBlank()) {
            handle = sshTemplate.openLocalTunnel(request.getServer(), request.getLocalPort(), request.getRemoteHost(), request.getRemotePort());
        } else {
            handle = sshTemplate.openLocalTunnel(request.getLocalPort(), request.getRemoteHost(), request.getRemotePort());
        }
        return Result.success(handle.getLocalPort());
    }
}
