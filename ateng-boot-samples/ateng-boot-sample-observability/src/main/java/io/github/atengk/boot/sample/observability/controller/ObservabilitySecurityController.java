package io.github.atengk.boot.sample.observability.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.observability.listener.ObservabilityAlertEventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 端点安全加固与运维审计领域控制器
 * <p>
 * 提供白名单放行体验、加固访问拦截模拟以及最近捕获的运维领域事件回溯查询接口
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@RestController
@RequestMapping("/sample/observability/security")
public class ObservabilitySecurityController {

    private final ObservabilityAlertEventListener alertEventListener;

    public ObservabilitySecurityController(ObservabilityAlertEventListener alertEventListener) {
        this.alertEventListener = alertEventListener;
    }

    /**
     * 开放白名单巡检端点（不受 Actuator 加固拦截影响）
     */
    @GetMapping("/public-ping")
    public Result<String> publicPing() {
        return Result.success("可观测性服务正常在线，白名单业务端点畅通");
    }

    /**
     * 加固拦截指引提示
     */
    @GetMapping("/simulate-lockdown")
    public Result<String> simulateLockdown() {
        return Result.success("高危端点（/actuator/env, /actuator/beans）已加固：外网访问需携带 X-Actuator-Token 或 Basic Auth 凭据，CIDR 白名单内网免密放行");
    }

    /**
     * 查询系统最近捕获的运维领域事件列表（线程池拒绝、探针降级、未授权访问阻断）
     */
    @GetMapping("/recent-events")
    public Result<List<Map<String, Object>>> getRecentEvents() {
        return Result.success(alertEventListener.getRecentEvents());
    }
}
