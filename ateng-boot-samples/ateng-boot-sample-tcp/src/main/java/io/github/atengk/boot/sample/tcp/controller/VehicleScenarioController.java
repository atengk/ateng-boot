package io.github.atengk.boot.sample.tcp.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.tcp.model.VehicleParamQueryAck;
import io.github.atengk.boot.sample.tcp.model.VehicleStatusVO;
import io.github.atengk.boot.sample.tcp.service.VehicleControlService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 车载长连接业务场景体验与管控 HTTP 控制器
 * <p>
 * 遵循 ADR-0002 显式 Result 强类型契约规范，提供车机实时状态查询、全双工 RPC 下发与全网预警广播体验接口。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
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
    public Result<VehicleStatusVO> getStatus(@RequestParam("deviceId") String deviceId) {
        VehicleStatusVO status = vehicleControlService.getVehicleStatus(deviceId);
        return Result.success(status);
    }

    /**
     * 向车机下发全双工 RPC 参数查询指令并同步等待应答
     *
     * @param deviceId  设备编号
     * @param paramType 查询类型（默认 SYSTEM）
     * @return 终端应答回执
     */
    @PostMapping("/query-params")
    public Result<VehicleParamQueryAck> queryParams(@RequestParam("deviceId") String deviceId,
                                                    @RequestParam(value = "paramType", defaultValue = "SYSTEM") String paramType) {
        VehicleParamQueryAck ack = vehicleControlService.queryDeviceParams(deviceId, paramType);
        return Result.success(ack);
    }

    /**
     * 向全网在线长连接车机广播预警消息
     *
     * @param message 预警内容
     * @return 统一成功结果
     */
    @PostMapping("/broadcast-alert")
    public Result<String> broadcastAlert(@RequestParam("message") String message) {
        vehicleControlService.broadcastSpeedLimitAlert(message);
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
