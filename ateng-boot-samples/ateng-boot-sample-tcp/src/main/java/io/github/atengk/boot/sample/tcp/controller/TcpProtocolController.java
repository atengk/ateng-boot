package io.github.atengk.boot.sample.tcp.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.tcp.dto.Jt808FrameDecodeRequest;
import io.github.atengk.boot.sample.tcp.dto.Jt808FrameEncodeRequest;
import io.github.atengk.boot.sample.tcp.dto.TelemetryReportRequest;
import io.github.atengk.boot.sample.tcp.dto.VehicleControlRequest;
import io.github.atengk.boot.sample.tcp.dto.VehicleParamQueryRequest;
import io.github.atengk.boot.sample.tcp.model.LocationReportPacket;
import io.github.atengk.boot.sample.tcp.model.VehicleControlAck;
import io.github.atengk.boot.sample.tcp.model.VehicleParamQueryAck;
import io.github.atengk.boot.sample.tcp.model.VehicleStatusVO;
import io.github.atengk.boot.sample.tcp.service.TcpProtocolConsoleService;
import io.github.atengk.boot.sample.tcp.vo.Jt808FrameVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 车联网协议编解码与双向控制通信体验控制器
 * 演示同步车控 RPC、参数查询 RPC、JT/T 808 复合二进制帧编解码校验与双协议定位遥测上行演练
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/tcp/protocol")
@RequiredArgsConstructor
public class TcpProtocolController {

    private final TcpProtocolConsoleService protocolConsoleService;

    /**
     * 下发同步远程车辆控制指令并等待车机返回回执（基于 TcpServerTemplate.requestToDevice）
     *
     * @param request 车控指令请求
     * @return 车机控制回执
     */
    @PostMapping("/control")
    public Result<VehicleControlAck> sendControl(@Valid @RequestBody VehicleControlRequest request) {
        log.info("[HTTP-PROTOCOL] 下发同步车控指令: deviceId={}, command={}", request.getDeviceId(), request.getCommand());
        return Result.success(protocolConsoleService.sendControlCommand(request));
    }

    /**
     * 下发同步车载终端参数查询指令并获取固件、电量与运行状态应答
     *
     * @param request 参数查询请求
     * @return 参数应答模型
     */
    @PostMapping("/param-query")
    public Result<VehicleParamQueryAck> queryParams(@Valid @RequestBody VehicleParamQueryRequest request) {
        log.info("[HTTP-PROTOCOL] 下发参数查询: deviceId={}, paramType={}", request.getDeviceId(), request.getParamType());
        return Result.success(protocolConsoleService.queryDeviceParams(request));
    }

    /**
     * 将业务定位实体编码为带 0x7E 起止符与转义校验的 JT/T 808 国标复合二进制帧
     *
     * @param request 编码参数
     * @return 二进制十六进制报文及多种工业校验码视图
     */
    @PostMapping("/jt808/encode")
    public Result<Jt808FrameVO> encodeJt808(@Valid @RequestBody Jt808FrameEncodeRequest request) {
        return Result.success(protocolConsoleService.encodeJt808Frame(request));
    }

    /**
     * 解析并校验十六进制 JT/T 808 复合二进制帧（非法长度或校验和篡改将拦截报错）
     *
     * @param request 解码参数
     * @return 解码得到的定位实体
     */
    @PostMapping("/jt808/decode")
    public Result<LocationReportPacket> decodeJt808(@Valid @RequestBody Jt808FrameDecodeRequest request) {
        return Result.success(protocolConsoleService.decodeJt808Frame(request));
    }

    /**
     * 触发一次真实的终端上行定位遥测数据包发送（支持 JSON 与 JT808 二进制帧双协议演示）
     *
     * @param request 遥测上报请求
     * @return 刷新后的车辆实时状态视图
     */
    @PostMapping("/report")
    public Result<VehicleStatusVO> triggerTelemetryReport(@Valid @RequestBody TelemetryReportRequest request) {
        log.info("[HTTP-PROTOCOL] 触发遥测上行推流: deviceId={}, protocol={}, speed={}",
                request.getDeviceId(), request.getProtocol(), request.getSpeed());
        return Result.success(protocolConsoleService.triggerTelemetryReport(request));
    }
}
