package io.github.atengk.boot.sample.ssh.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ssh.dto.SshCommandRequest;
import io.github.atengk.boot.sample.ssh.dto.SshScriptRequest;
import io.github.atengk.boot.sample.ssh.dto.SshStreamLogRequest;
import io.github.atengk.boot.sample.ssh.dto.SshSudoRequest;
import io.github.atengk.boot.sample.ssh.service.SshScenarioService;
import io.github.atengk.boot.sample.ssh.vo.SshExecResultVO;
import io.github.atengk.boot.ssh.model.SshExecResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * SSH 远程命令执行与安全提权业务控制器
 * <p>
 * 提供同步短命令、非阻塞异步命令、Sudo 提权安全管道（密码内存抹零）、
 * 多行脚本安全执行、长命令流式日志实时按行消费与无害连通性探针等核心接口。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/ssh/command")
@RequiredArgsConstructor
public class SshCommandController {

    private final SshScenarioService sshScenarioService;

    /**
     * 同步执行远程短命令
     *
     * @param request 命令请求载荷
     * @return 强类型执行结果
     */
    @PostMapping("/exec")
    public Result<SshExecResultVO> exec(@Valid @RequestBody SshCommandRequest request) {
        log.info("SshCommandController: 收到同步命令执行请求 [serverName={}, command={}]",
                request.getServerName(), request.getCommand());
        SshExecResult result;
        if (StringUtils.hasText(request.getServerName())) {
            result = sshScenarioService.exec(request.getServerName(), request.getCommand());
        } else {
            result = sshScenarioService.exec(request.getCommand());
        }
        return Result.success(SshExecResultVO.from(result));
    }

    /**
     * 非阻塞异步执行远程命令（返回 CompletableFuture，并在受管线程池中调度）
     *
     * @param request 命令请求载荷
     * @return 异步执行结果 Future
     */
    @PostMapping("/exec-async")
    public CompletableFuture<Result<SshExecResultVO>> execAsync(@Valid @RequestBody SshCommandRequest request) {
        log.info("SshCommandController: 收到异步命令下发请求 [serverName={}, command={}]",
                request.getServerName(), request.getCommand());
        CompletableFuture<SshExecResult> future;
        if (StringUtils.hasText(request.getServerName())) {
            future = sshScenarioService.execAsync(request.getServerName(), request.getCommand());
        } else {
            future = sshScenarioService.execAsync(request.getCommand());
        }
        return future.thenApply(res -> Result.success(SshExecResultVO.from(res)));
    }

    /**
     * 原生 Sudo 提权安全管道执行（密码即时内存擦除抹零防泄露）
     *
     * @param request Sudo 提权请求载荷
     * @return 执行结果
     */
    @PostMapping("/exec-sudo")
    public Result<SshExecResultVO> execSudo(@Valid @RequestBody SshSudoRequest request) {
        log.info("SshCommandController: 收到 Sudo 安全提权执行请求 [serverName={}, command={}]",
                request.getServerName(), request.getCommand());
        SshExecResult result;
        if (StringUtils.hasText(request.getServerName())) {
            result = sshScenarioService.execSudo(request.getServerName(), request.getCommand(), request.getSudoPassword());
        } else {
            result = sshScenarioService.execSudo(request.getCommand(), request.getSudoPassword());
        }
        return Result.success(SshExecResultVO.from(result));
    }

    /**
     * 多行复杂脚本管道规范化执行
     *
     * @param request 脚本请求载荷
     * @return 执行结果
     */
    @PostMapping("/exec-script")
    public Result<SshExecResultVO> execScript(@Valid @RequestBody SshScriptRequest request) {
        log.info("SshCommandController: 收到多行运维脚本管道执行请求 [serverName={}]", request.getServerName());
        SshExecResult result = sshScenarioService.runMaintenanceScript(request.getServerName(), request.getScriptBody());
        return Result.success(SshExecResultVO.from(result));
    }

    /**
     * 长任务命令日志按行实时流式消费（零内存缓冲防 OOM）
     *
     * @param request 流式日志消费请求载荷
     * @return 捕获到的所有日志行列表
     */
    @PostMapping("/stream-log")
    public Result<List<String>> streamLog(@Valid @RequestBody SshStreamLogRequest request) {
        log.info("SshCommandController: 收到流式按行捕获日志请求 [serverName={}, command={}]",
                request.getServerName(), request.getCommand());
        List<String> capturedLines = Collections.synchronizedList(new ArrayList<>());
        int exitCode = sshScenarioService.streamLogs(request.getServerName(), request.getCommand(), capturedLines::add);
        log.info("SshCommandController: 流式捕获完成，捕获行数: {}, exitCode: {}", capturedLines.size(), exitCode);
        return Result.success(capturedLines);
    }

    /**
     * 节点无害轻量连通性探针（仅握手鉴权即断，零进程开销）
     *
     * @param serverName 目标节点名称（缺省探测主节点）
     * @return 连通鉴权是否成功
     */
    @GetMapping("/probe-health")
    public Result<Boolean> probeHealth(@RequestParam(value = "serverName", required = false) String serverName) {
        log.info("SshCommandController: 正在探测节点连通性 [serverName={}]", serverName);
        boolean connected = sshScenarioService.testConnection(serverName);
        return Result.success(connected);
    }
}
