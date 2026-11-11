package io.github.atengk.boot.sample.tcp.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.tcp.dto.GroupSendRequest;
import io.github.atengk.boot.sample.tcp.dto.ServerBroadcastRequest;
import io.github.atengk.boot.sample.tcp.service.TcpEndpointConsoleService;
import io.github.atengk.boot.sample.tcp.vo.TcpDispatchResultVO;
import io.github.atengk.boot.sample.tcp.vo.TcpServerInfoVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * TCP 多端口服务端与下行广播体验控制器
 * 提供服务端实例列表（含主端口、二进制与 TLS/mTLS 网关）、明细巡检及全网广播/车队组播能力
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/tcp/server")
@RequiredArgsConstructor
public class TcpServerController {

    private final TcpEndpointConsoleService endpointConsoleService;

    /**
     * 查询当前所有已装配 TCP 服务端实例拓扑与运行状态
     *
     * @return 服务端只读视图列表
     */
    @GetMapping("/list")
    public Result<List<TcpServerInfoVO>> listServers() {
        return Result.success(endpointConsoleService.listServers());
    }

    /**
     * 查询指定命名服务端实例详情（含实际绑定端口、SSL/mTLS 状态、IP 防火墙规则及活跃连接数）
     *
     * @param serverName 服务端实例名称（默认 {@code default}）
     * @return 服务端只读详情视图
     */
    @GetMapping("/detail")
    public Result<TcpServerInfoVO> getServerDetail(
            @NotBlank(message = "服务端名称 serverName 不能为空")
            @RequestParam(value = "serverName", defaultValue = "default") String serverName) {
        return Result.success(endpointConsoleService.getServerInfo(serverName));
    }

    /**
     * 向在线终端下发全网广播消息（可选经过集群桥接器跨节点投递）
     *
     * @param request 广播请求参数
     * @return 分发回执视图
     */
    @PostMapping("/broadcast")
    public Result<TcpDispatchResultVO> broadcast(@Valid @RequestBody ServerBroadcastRequest request) {
        log.info("[HTTP-SERVER] 触发全网广播: cluster={}, message={}", request.getCluster(), request.getMessage());
        return Result.success(endpointConsoleService.broadcast(request));
    }

    /**
     * 向指定车队/业务分组下发组播消息（可选经过集群桥接器跨节点分组投递）
     *
     * @param request 组播请求参数
     * @return 分发回执视图
     */
    @PostMapping("/group-send")
    public Result<TcpDispatchResultVO> sendToGroup(@Valid @RequestBody GroupSendRequest request) {
        log.info("[HTTP-SERVER] 触发车队组播: group={}, cluster={}", request.getGroup(), request.getCluster());
        return Result.success(endpointConsoleService.sendToGroup(request));
    }
}
