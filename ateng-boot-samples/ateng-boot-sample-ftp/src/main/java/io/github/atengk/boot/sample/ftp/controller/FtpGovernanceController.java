package io.github.atengk.boot.sample.ftp.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.ftp.model.FtpPoolStats;
import io.github.atengk.boot.ftp.registry.FtpClientRegistry;
import io.github.atengk.boot.ftp.template.FtpTemplate;
import io.github.atengk.boot.sample.ftp.dto.DynamicRoutingRequest;
import io.github.atengk.boot.sample.ftp.dto.RegisterNodeRequest;
import io.github.atengk.boot.sample.ftp.dto.UnregisterNodeRequest;
import io.github.atengk.boot.sample.ftp.service.ConnectivityProbeScenarioService;
import io.github.atengk.boot.sample.ftp.service.DynamicRoutingScenarioService;
import io.github.atengk.boot.sample.ftp.vo.FtpPoolStatsVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

/**
 * 远程 FTP 节点治理与健康运维控制器
 * <p>
 * 提供零副作用物理连通性探针（NOOP）、断言检查、FtpContextHolder 线程栈级动态切源读写、
 * 运行期节点动态热注册/注销与连接池指标端点。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@RestController
@RequestMapping("/sample/ftp/governance")
@RequiredArgsConstructor
@Validated
@Slf4j
public class FtpGovernanceController {

    private final ConnectivityProbeScenarioService connectivityProbeService;
    private final DynamicRoutingScenarioService dynamicRoutingService;
    private final FtpTemplate ftpTemplate;

    /**
     * 零物理副作用连通性健康探测 (RFC 959 NOOP 心跳)
     *
     * @param serverName 目标服务节点标识（可选，留空探测默认主节点）
     * @return 连通可用性
     */
    @GetMapping("/probe-health")
    public Result<Boolean> probeHealth(@RequestParam(value = "serverName", required = false) String serverName) {
        boolean healthy = StringUtils.hasText(serverName)
                ? connectivityProbeService.checkServerHealth(serverName)
                : connectivityProbeService.checkDefaultServerHealth();
        return Result.success(healthy);
    }

    /**
     * 强校验节点连通性（失败抛出语义化因果异常）
     *
     * @param serverName 目标服务节点标识（可选）
     * @return 空操作成功响应
     */
    @PostMapping("/assert-healthy")
    public Result<Void> assertHealthy(@RequestParam(value = "serverName", required = false) String serverName) {
        if (StringUtils.hasText(serverName)) {
            connectivityProbeService.assertServerHealthy(serverName);
        } else {
            ftpTemplate.testConnectionOrThrow();
        }
        return Result.success();
    }

    /**
     * 动态切源写入内容至指定 FTP 节点 (FtpContextHolder 栈式安全隔离)
     *
     * @param request 动态切源写入请求
     * @return 写入结果
     */
    @PostMapping("/dynamic-routing-write")
    public Result<Boolean> dynamicRoutingWrite(@Valid @RequestBody DynamicRoutingRequest request) {
        boolean success = dynamicRoutingService.executeOnServer(request.getServerName(), () ->
                ftpTemplate.writeString(request.getRemotePath(), request.getContent()));
        return Result.success(success);
    }

    /**
     * 动态切源从指定 FTP 节点读取内容 (FtpContextHolder 栈式安全隔离)
     *
     * @param serverName 目标服务节点标识
     * @param remotePath 远程文件路径
     * @return 文本内容
     */
    @GetMapping("/dynamic-routing-read")
    public Result<String> dynamicRoutingRead(
            @RequestParam("serverName") @NotBlank(message = "服务节点标识不能为空") String serverName,
            @RequestParam("remotePath") @NotBlank(message = "远程文件路径不能为空") String remotePath) {
        String content = dynamicRoutingService.executeOnServer(serverName, () ->
                ftpTemplate.readString(remotePath));
        return Result.success(content);
    }

    /**
     * 运行期动态注册或热替换具名 FTP 服务节点
     *
     * @param request 节点注册请求
     * @return 注册后节点的连接池运行时指标
     */
    @PostMapping("/register-node")
    public Result<FtpPoolStatsVO> registerNode(@Valid @RequestBody RegisterNodeRequest request) {
        return Result.success(toStatsVO(dynamicRoutingService.registerNode(request)));
    }

    /**
     * 运行期安全注销具名 FTP 服务节点并释放连接池（严禁注销 default 主节点）
     *
     * @param request 节点注销请求
     * @return 是否存在并成功注销
     */
    @PostMapping("/unregister-node")
    public Result<Boolean> unregisterNode(@Valid @RequestBody UnregisterNodeRequest request) {
        return Result.success(dynamicRoutingService.unregisterNode(request.getServerName()));
    }

    /**
     * 查看 Commons Pool2 受管连接池实时运行指标（直接调用原生 FtpPoolStats 门面）
     *
     * @param serverName 目标服务节点标识（可选）
     * @return 连接池指标
     */
    @GetMapping("/pool-stats")
    public Result<FtpPoolStatsVO> getPoolStats(@RequestParam(value = "serverName", required = false) String serverName) {
        String targetServer = StringUtils.hasText(serverName) ? serverName : FtpClientRegistry.DEFAULT_SERVER;
        return Result.success(toStatsVO(dynamicRoutingService.getPoolStats(targetServer)));
    }

    private FtpPoolStatsVO toStatsVO(FtpPoolStats stats) {
        return FtpPoolStatsVO.builder()
                .serverName(stats.getServerName())
                .activeConnections(stats.getActiveConnections())
                .idleConnections(stats.getIdleConnections())
                .waitingBorrowers(stats.getWaitingBorrowers())
                .maxConnections(stats.getMaxTotal())
                .maxIdle(stats.getMaxIdle())
                .minIdle(stats.getMinIdle())
                .build();
    }
}
