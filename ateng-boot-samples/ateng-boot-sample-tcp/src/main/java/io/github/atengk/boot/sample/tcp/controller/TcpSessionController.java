package io.github.atengk.boot.sample.tcp.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.tcp.dto.SessionBindRequest;
import io.github.atengk.boot.sample.tcp.dto.SessionGroupRequest;
import io.github.atengk.boot.sample.tcp.dto.SessionKickRequest;
import io.github.atengk.boot.sample.tcp.service.TcpEndpointConsoleService;
import io.github.atengk.boot.sample.tcp.vo.TcpSessionDetailVO;
import io.github.atengk.boot.sample.tcp.vo.TcpSessionInfoVO;
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
 * TCP 长连接会话与车队分组治理控制器
 * 严格遵循 DTO/VO 传输契约隔离要求，提供会话巡检、手工设备绑定、分组加减与强踢下线能力
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/tcp/session")
@RequiredArgsConstructor
public class TcpSessionController {

    private final TcpEndpointConsoleService endpointConsoleService;

    /**
     * 查询所有活跃 TCP 长连接会话（支持按业务分组过滤）
     *
     * @param group 可选分组名称（如 FLEET-ALPHA）
     * @return 只读会话视图列表
     */
    @GetMapping("/list")
    public Result<List<TcpSessionInfoVO>> listSessions(@RequestParam(value = "group", required = false) String group) {
        return Result.success(endpointConsoleService.listSessions(group));
    }

    /**
     * 聚合查询指定设备的活跃会话详情、最新车载定位遥测及 Actuator 巡检快照
     *
     * @param deviceId 设备唯一编号
     * @return 聚合详情视图
     */
    @GetMapping("/detail")
    public Result<TcpSessionDetailVO> getSessionDetail(
            @NotBlank(message = "设备编号 deviceId 不能为空")
            @RequestParam("deviceId") String deviceId) {
        return Result.success(endpointConsoleService.getSessionDetail(deviceId));
    }

    /**
     * 为指定活跃会话手工绑定或重新映射业务设备号
     *
     * @param request 绑定请求参数
     * @return 更新后的只读会话视图
     */
    @PostMapping("/bind")
    public Result<TcpSessionInfoVO> bindDevice(@Valid @RequestBody SessionBindRequest request) {
        log.info("[HTTP-SESSION] 会话绑定设备: sessionId={}, deviceId={}", request.getSessionId(), request.getDeviceId());
        return Result.success(endpointConsoleService.bindDevice(request));
    }

    /**
     * 动态调整在线设备所属的车队或业务分组（JOIN 加入 / LEAVE 退出）
     *
     * @param request 分组变更请求
     * @return 操作是否成功
     */
    @PostMapping("/group")
    public Result<Boolean> manageGroup(@Valid @RequestBody SessionGroupRequest request) {
        log.info("[HTTP-SESSION] 调整设备分组: deviceId={}, group={}, action={}",
                request.getDeviceId(), request.getGroup(), request.getAction());
        return Result.success(endpointConsoleService.manageGroup(request));
    }

    /**
     * 强制踢下线指定设备或会话连接（触发 CloseReason.KICK_OUT 优雅关闭并清理路由绑定）
     *
     * @param request 强踢请求
     * @return 操作结果
     */
    @PostMapping("/kick")
    public Result<Boolean> kickSession(@Valid @RequestBody SessionKickRequest request) {
        log.info("[HTTP-SESSION] 强制断连强踢: deviceId={}, sessionId={}", request.getDeviceId(), request.getSessionId());
        return Result.success(endpointConsoleService.kickSession(request));
    }
}
