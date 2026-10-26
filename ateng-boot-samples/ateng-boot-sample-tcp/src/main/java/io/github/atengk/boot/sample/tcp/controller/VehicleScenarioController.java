package io.github.atengk.boot.sample.tcp.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.tcp.model.*;
import io.github.atengk.boot.sample.tcp.service.VehicleControlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 车载长连接业务场景体验与管控 HTTP 控制器
 * <p>
 * 遵循 ADR-0002 显式 Result 强类型契约规范，配合 JSR-303 注解实现传输边界 Fail-Fast 防卫。
 * 提供车机实时状态查询、远程控制指令下发、全双工 RPC 参数查询与全网预警广播体验接口。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Validated
@RestController
@RequestMapping("/api/tcp/vehicle")
public class VehicleScenarioController {

    private final VehicleControlService vehicleControlService;

    public VehicleScenarioController(VehicleControlService vehicleControlService) {
        this.vehicleControlService = vehicleControlService;
    }

    /**
     * 查询指定车机硬件标识的最新在线状态与定位
     *
     * @param deviceId 设备编号
     * @return 车辆状态 VO
     */
    @GetMapping("/status")
    public Result<VehicleStatusVO> getStatus(@RequestParam("deviceId") @NotBlank(message = "设备标识 deviceId 不能为空") String deviceId) {
        VehicleStatusVO status = vehicleControlService.getVehicleStatus(deviceId);
        return Result.success(status);
    }

    /**
     * 向指定车载终端下发远程控制指令（如 LOCK / UNLOCK / SPEED_LIMIT / REBOOT）
     *
     * @param request 控制请求 DTO
     * @return 终端执行回执
     */
    @PostMapping("/control")
    public Result<VehicleControlAck> control(@RequestBody @Valid VehicleCommandRequest request) {
        VehicleControlAck ack = vehicleControlService.sendControlCommand(
                request.getDeviceId(),
                request.getCommand(),
                request.getParams()
        );
        return Result.success(ack);
    }

    /**
     * 向车机下发全双工 RPC 参数查询指令并同步等待应答
     *
     * @param request 查询请求 DTO
     * @return 终端应答回执
     */
    @PostMapping("/query-params")
    public Result<VehicleParamQueryAck> queryParams(@RequestBody @Valid VehicleParamQueryRequest request) {
        VehicleParamQueryAck ack = vehicleControlService.queryDeviceParams(request.getDeviceId(), request.getParamType());
        return Result.success(ack);
    }

    /**
     * 向全网在线长连接车机广播预警消息
     *
     * @param request 广播请求 DTO
     * @return 统一成功结果
     */
    @PostMapping("/broadcast-alert")
    public Result<String> broadcastAlert(@RequestBody @Valid BroadcastAlertRequest request) {
        vehicleControlService.broadcastSpeedLimitAlert(request.getMessage());
        return Result.success("广播预警已下发");
    }

    /**
     * 获取上下线与连接绑定的领域事件审计日志
     *
     * @return 事件日志列表
     */
    @GetMapping("/events")
    public Result<List<String>> getEvents() {
        return Result.success(vehicleControlService.getEventLogs());
    }
}
