package io.github.atengk.boot.sample.tcp.service;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sample.tcp.codec.Jt808CompositeFrameCodec;
import io.github.atengk.boot.sample.tcp.codec.Jt808RouteKeyExtractor;
import io.github.atengk.boot.sample.tcp.dto.Jt808FrameDecodeRequest;
import io.github.atengk.boot.sample.tcp.dto.Jt808FrameEncodeRequest;
import io.github.atengk.boot.sample.tcp.dto.TelemetryReportRequest;
import io.github.atengk.boot.sample.tcp.dto.VehicleControlRequest;
import io.github.atengk.boot.sample.tcp.dto.VehicleParamQueryRequest;
import io.github.atengk.boot.sample.tcp.model.LocationReportPacket;
import io.github.atengk.boot.sample.tcp.model.VehicleControlAck;
import io.github.atengk.boot.sample.tcp.model.VehicleParamQueryAck;
import io.github.atengk.boot.sample.tcp.model.VehicleStatusVO;
import io.github.atengk.boot.sample.tcp.simulator.FleetSimulationRunner;
import io.github.atengk.boot.sample.tcp.vo.Jt808FrameVO;
import io.github.atengk.boot.sample.tcp.vo.LocationReportVO;
import io.github.atengk.boot.sample.tcp.vo.VehicleControlAckVO;
import io.github.atengk.boot.sample.tcp.vo.VehicleParamQueryAckVO;
import io.github.atengk.boot.tcp.client.TcpClient;
import io.github.atengk.boot.tcp.client.TcpClientRegistry;
import io.github.atengk.boot.tcp.exception.TcpBackpressureException;
import io.github.atengk.boot.tcp.exception.TcpException;
import io.github.atengk.boot.tcp.exception.TcpTimeoutException;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.util.ChecksumUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 车联网协议编解码、双向 RPC 下行控制与仿真推流编排服务
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Service
@RequiredArgsConstructor
public class TcpProtocolConsoleService {

    private final VehicleTelemetryScenarioService scenarioService;
    private final Jt808RouteKeyExtractor jt808RouteKeyExtractor;
    private final ObjectProvider<TcpClientRegistry> tcpClientRegistryProvider;
    private final ObjectProvider<FleetSimulationRunner> fleetSimulationRunnerProvider;
    private final AtomicLong localFallbackSeq = new AtomicLong(5000L);

    /**
     * 下发同步远程车辆控制指令并等待车机回执
     *
     * @param request 控制请求
     * @return 车辆执行回执视图
     */
    public VehicleControlAckVO sendControlCommand(VehicleControlRequest request) {
        try {
            VehicleControlAck ack = scenarioService.sendControlCommand(request.getDeviceId(), request.getCommand(), request.getParams());
            return VehicleControlAckVO.from(ack);
        } catch (TcpTimeoutException ex) {
            throw new BusinessException(CommonResultCode.SERVICE_UNAVAILABLE,
                    "等待车机控制回执超时: deviceId=" + request.getDeviceId(), ex);
        } catch (TcpBackpressureException ex) {
            throw new BusinessException(CommonResultCode.TOO_MANY_REQUESTS,
                    "出站通道触发背压流控拦截: deviceId=" + request.getDeviceId(), ex);
        } catch (TcpException | IllegalStateException ex) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST,
                    "远程指令下发失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 下发同步设备运行参数查询并等待响应
     *
     * @param request 参数查询请求
     * @return 终端参数应答视图
     */
    public VehicleParamQueryAckVO queryDeviceParams(VehicleParamQueryRequest request) {
        try {
            VehicleParamQueryAck ack = scenarioService.queryDeviceParams(request.getDeviceId(), request.getParamType());
            return VehicleParamQueryAckVO.from(ack);
        } catch (TcpTimeoutException ex) {
            throw new BusinessException(CommonResultCode.SERVICE_UNAVAILABLE,
                    "等待终端参数查询回执超时: deviceId=" + request.getDeviceId(), ex);
        } catch (TcpException | IllegalStateException ex) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST,
                    "设备参数查询失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 按 JT/T 808 复合二进制规约编码定位报文，并计算多种工业校验和及提取路由键
     *
     * @param request 编码入参
     * @return 编码结果视图（含十六进制报文、BCC/CRC16 校验值与提取路由）
     */
    public Jt808FrameVO encodeJt808Frame(Jt808FrameEncodeRequest request) {
        LocationReportPacket packetModel = new LocationReportPacket(
                request.getDeviceId(),
                request.getSeq(),
                request.getLongitude(),
                request.getLatitude(),
                request.getSpeed(),
                request.getEngineStatus(),
                System.currentTimeMillis()
        );
        byte[] frame = Jt808CompositeFrameCodec.encodeLocationReport(packetModel);
        String routeKey = jt808RouteKeyExtractor.extract(TcpPacket.ofBytes(frame));
        byte bccByte = frame.length >= 2 ? frame[frame.length - 2] : 0;
        int modbusCrc = ChecksumUtil.calculateCrc16Modbus(frame, 1, Math.max(0, frame.length - 3));
        int ccittCrc = ChecksumUtil.calculateCrc16Ccitt(frame, 1, Math.max(0, frame.length - 3));
        return new Jt808FrameVO(
                request.getDeviceId(),
                request.getSeq(),
                routeKey,
                ChecksumUtil.toHexString(frame),
                frame.length,
                String.format("%02X", bccByte & 0xFF),
                formatUint16Hex(modbusCrc),
                formatUint16Hex(ccittCrc)
        );
    }

    /**
     * 解码并验证 JT/T 808 十六进制帧（不合法报文或校验和被篡改将快速失败转译为 BAD_REQUEST）
     *
     * @param request 解码请求
     * @return 解析后的定位视图对象
     */
    public LocationReportVO decodeJt808Frame(Jt808FrameDecodeRequest request) {
        try {
            byte[] raw = ChecksumUtil.fromHexString(request.getHexFrame());
            LocationReportPacket packet = Jt808CompositeFrameCodec.decodeLocationReport(raw);
            return LocationReportVO.from(packet);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST,
                    "JT/T 808 报文解码或校验失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 触发一帧实时定位遥测上行链路演练（非 local 优先经由对应仿真终端发包，否则经 default 回环客户端发包）
     *
     * @param request 上报请求
     * @return 更新后的车辆最新状态视图
     */
    public VehicleStatusVO triggerTelemetryReport(TelemetryReportRequest request) {
        FleetSimulationRunner runner = fleetSimulationRunnerProvider.getIfAvailable();
        if (runner != null && runner.isManagedOnline(request.getDeviceId())) {
            runner.emitSimulatedTelemetry(
                    request.getDeviceId(),
                    request.getProtocol(),
                    request.getLongitude(),
                    request.getLatitude(),
                    request.getSpeed(),
                    request.getEngineStatus()
            );
            return scenarioService.getVehicleStatus(request.getDeviceId());
        }

        TcpClientRegistry clientRegistry = tcpClientRegistryProvider.getIfAvailable();
        TcpClient defaultClient = clientRegistry != null ? clientRegistry.getDefaultClient() : null;
        if (defaultClient == null || !defaultClient.isConnected()) {
            throw new BusinessException(CommonResultCode.SERVICE_UNAVAILABLE,
                    "目标设备 [" + request.getDeviceId() + "] 非托管仿真在线设备，且默认回环客户端未连接");
        }
        LocationReportPacket report = new LocationReportPacket(
                request.getDeviceId(),
                localFallbackSeq.incrementAndGet(),
                request.getLongitude(),
                request.getLatitude(),
                request.getSpeed(),
                request.getEngineStatus(),
                System.currentTimeMillis()
        );
        try {
            if ("JT808".equalsIgnoreCase(request.getProtocol())) {
                byte[] frame = Jt808CompositeFrameCodec.encodeLocationReport(report);
                clientRegistry.getRequiredTemplate("default").send(TcpPacket.ofBytes(frame));
            } else {
                clientRegistry.getRequiredTemplate("default")
                        .send(TcpPacket.of("LOCATION_REPORT", TcpPayload.of(JsonUtil.toJsonString(report))));
            }
            return waitForStatusRefresh(request.getDeviceId(), request.getSpeed());
        } catch (TcpException ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                    "通过回环客户端触发定位上行失败: " + ex.getMessage(), ex);
        }
    }

    private VehicleStatusVO waitForStatusRefresh(String deviceId, double expectedSpeed) {
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline) {
            VehicleStatusVO vo = scenarioService.getVehicleStatus(deviceId);
            if (vo != null && vo.getLastSpeed() != null && Math.abs(vo.getLastSpeed() - expectedSpeed) < 0.01) {
                return vo;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        VehicleStatusVO latest = scenarioService.getVehicleStatus(deviceId);
        return latest != null ? latest : new VehicleStatusVO(deviceId, false, null, null, null, null, System.currentTimeMillis());
    }

    private String formatUint16Hex(int value) {
        byte[] bytes = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort((short) value).array();
        return ChecksumUtil.toHexString(bytes);
    }
}
