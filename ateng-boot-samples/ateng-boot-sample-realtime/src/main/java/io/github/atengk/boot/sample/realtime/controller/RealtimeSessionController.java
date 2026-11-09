package io.github.atengk.boot.sample.realtime.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.realtime.enums.ChannelType;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 实时通信会话治理与在线度量统计控制器
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@RestController
@RequestMapping("/sample/realtime/session")
@RequiredArgsConstructor
public class RealtimeSessionController {

    private final RealtimeTemplate realtimeTemplate;

    /**
     * 获取当前服务节点在线连接指标（总连接数、独立用户数、通道分布）
     */
    @GetMapping("/metrics")
    public Result<Map<String, Object>> getLocalMetrics() {
        int totalOnline = realtimeTemplate.getOnlineCount();
        int userCount = realtimeTemplate.getOnlineUserCount();
        int wsCount = realtimeTemplate.getOnlineCountByChannel(ChannelType.WEBSOCKET);
        int sseCount = realtimeTemplate.getOnlineCountByChannel(ChannelType.SSE);

        Map<String, Object> metrics = Map.of(
                "onlineCount", totalOnline,
                "onlineUserCount", userCount,
                "websocketCount", wsCount,
                "sseCount", sseCount
        );
        return Result.success(metrics);
    }

    /**
     * 获取多节点分布式集群全域在线连接汇总总数
     */
    @GetMapping("/cluster-metrics")
    public Result<Integer> getClusterOnlineCount() {
        int clusterTotal = realtimeTemplate.getClusterOnlineCount();
        return Result.success(clusterTotal);
    }

    /**
     * 判断指定用户是否在线
     */
    @GetMapping("/user-online")
    public Result<Boolean> isUserOnline(@RequestParam String userId) {
        boolean online = realtimeTemplate.isUserOnline(userId);
        return Result.success(online);
    }

    /**
     * 主动驱逐指定用户下线（全集群广播切断长连接，返回 4002 USER_KICKED_OUT 状态码）
     */
    @PostMapping("/kickout-user")
    public Result<Integer> kickoutUser(@RequestParam String userId) {
        log.info("[Sample] 主动踢出用户下线: userId={}", userId);
        int count = realtimeTemplate.kickoutUser(userId);
        return Result.success(count);
    }

    /**
     * 按租户批量强制切断全域长连接
     */
    @PostMapping("/kickout-tenant")
    public Result<Integer> kickoutTenant(@RequestParam Long tenantId) {
        log.info("[Sample] 主动踢出租户下线: tenantId={}", tenantId);
        int count = realtimeTemplate.kickoutTenant(tenantId);
        return Result.success(count);
    }
}
