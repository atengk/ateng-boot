package io.github.atengk.boot.sample.ssh.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ssh.dto.SshBatchInspectRequest;
import io.github.atengk.boot.sample.ssh.dto.SshContextSwitchRequest;
import io.github.atengk.boot.sample.ssh.dto.SshDynamicHostRequest;
import io.github.atengk.boot.sample.ssh.service.SshScenarioService;
import io.github.atengk.boot.sample.ssh.vo.SshBatchExecResultVO;
import io.github.atengk.boot.sample.ssh.vo.SshExecResultVO;
import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import io.github.atengk.boot.ssh.model.SshBatchExecResult;
import io.github.atengk.boot.ssh.model.SshBatchOptions;
import io.github.atengk.boot.ssh.model.SshExecResult;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * SSH 集群多主机巡检与动态切源治理控制器
 * <p>
 * 提供集群高级受控批量巡检（并发受控与失败快速熔断）、CMDB 纯内存资产动态直连、
 * 以及基于 SshContextHolder 栈式线程级上下文动态切源等核心接口。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/ssh/cluster")
@RequiredArgsConstructor
public class SshClusterController {

    private final SshScenarioService sshScenarioService;

    /**
     * 集群多命名节点高级批量巡检（支持并发控制与失败熔断回执）
     *
     * @param request 批量巡检请求载荷
     * @return 聚合回执报告
     */
    @PostMapping("/batch-inspect")
    public Result<SshBatchExecResultVO> batchInspect(@Valid @RequestBody SshBatchInspectRequest request) {
        log.info("SshClusterController: 收到多节点批量巡检请求 [servers={}, concurrency={}, failFast={}]",
                request.getServerNames(), request.getConcurrency(), request.getFailFast());

        var optionsBuilder = SshBatchOptions.builder()
                .concurrency(request.getConcurrency())
                .failFast(Boolean.TRUE.equals(request.getFailFast()));

        if (request.getTimeoutSeconds() != null && request.getTimeoutSeconds() > 0) {
            optionsBuilder.timeout(Duration.ofSeconds(request.getTimeoutSeconds()));
        }

        SshBatchExecResult batchResult = sshScenarioService.runBatchInspectionNamed(
                request.getServerNames(), request.getCommand(), optionsBuilder.build());

        return Result.success(SshBatchExecResultVO.from(batchResult));
    }

    /**
     * CMDB 纯内存资产动态直连命令执行（无需在 YAML 中声明）
     *
     * @param request 动态主机请求载荷
     * @return 执行结果
     */
    @PostMapping("/dynamic-connect")
    public Result<SshExecResultVO> dynamicConnect(@Valid @RequestBody SshDynamicHostRequest request) {
        log.info("SshClusterController: 收到 CMDB 动态资产直连请求 [{}:{}@{}]",
                request.getUsername(), request.getPort(), request.getHost());

        var serverBuilder = SshServerInfo.builder()
                .host(request.getHost())
                .port(request.getPort())
                .username(request.getUsername())
                .hostKeyPolicy(HostKeyPolicy.LENIENT);

        if (StringUtils.hasText(request.getPassword())) {
            serverBuilder.password(request.getPassword());
        }
        if (StringUtils.hasText(request.getPrivateKey())) {
            serverBuilder.privateKey(request.getPrivateKey());
        }

        SshExecResult result = sshScenarioService.exec(serverBuilder.build(), request.getCommand());
        return Result.success(SshExecResultVO.from(result));
    }

    /**
     * 基于 SshContextHolder 栈式线程级上下文动态切源执行
     *
     * @param request 动态切源请求载荷
     * @return 执行输出文本
     */
    @PostMapping("/context-switch")
    public Result<String> contextSwitch(@Valid @RequestBody SshContextSwitchRequest request) {
        log.info("SshClusterController: 收到线程上下文动态切源请求 [serverName={}, command={}]",
                request.getServerName(), request.getCommand());

        String output = sshScenarioService.executeWithContextSwitch(request.getServerName(), request.getCommand());
        return Result.success(output);
    }
}
