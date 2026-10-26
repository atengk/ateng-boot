package io.github.atengk.boot.sample.tcp.listener;

import io.github.atengk.boot.sample.tcp.model.LocationReportPacket;
import io.github.atengk.boot.sample.tcp.service.VehicleControlService;
import io.github.atengk.boot.tcp.annotation.TcpDeviceId;
import io.github.atengk.boot.tcp.annotation.TcpHeader;
import io.github.atengk.boot.tcp.annotation.TcpListener;
import io.github.atengk.boot.tcp.annotation.TcpMapping;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 车载长连接遥测数据与控制指令监听器
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Component
@TcpListener
public class VehicleTelemetryListener {

    private static final Logger log = LoggerFactory.getLogger(VehicleTelemetryListener.class);

    private final VehicleControlService vehicleControlService;
    private final TcpSessionManager sessionManager;

    public VehicleTelemetryListener(VehicleControlService vehicleControlService,
                                  TcpSessionManager sessionManager) {
        this.vehicleControlService = vehicleControlService;
        this.sessionManager = sessionManager;
    }

    /**
     * 终端上线注册认证，将长连接会话与车机硬件标识绑定
     *
     * @param session  当前 TCP 会话
     * @param deviceId 设备唯一硬件编号（通过报文载荷或属性自动提取）
     * @param body     报文体 Map 映射
     */
    @TcpMapping(command = "LOGIN")
    public void onLogin(TcpSession session, @TcpDeviceId String deviceId, java.util.Map<String, Object> body) {
        String effectiveDeviceId = deviceId;
        if (effectiveDeviceId == null && body != null && body.containsKey("deviceId")) {
            effectiveDeviceId = String.valueOf(body.get("deviceId"));
        }
        if (effectiveDeviceId != null && session != null) {
            sessionManager.bind(session.getSessionId(), effectiveDeviceId.trim());
            log.info("车机登录认证成功，完成设备标识绑定: sessionId={}, deviceId={}",
                    session.getSessionId(), effectiveDeviceId);
        }
    }

    /**
     * 接收终端上报的高频定位遥测数据包
     *
     * @param session  当前 TCP 会话
     * @param deviceId 通过 @TcpDeviceId 自动提取当前长连接已绑定的硬件标识
     * @param report   定位数据实体
     */
    @TcpMapping(command = "LOCATION_REPORT")
    public void onLocationReport(TcpSession session, @TcpDeviceId String deviceId, LocationReportPacket report) {
        String targetDeviceId = deviceId != null ? deviceId : (report != null ? report.getDeviceId() : null);
        if (targetDeviceId != null && session != null && session.getDeviceId() == null) {
            sessionManager.bind(session.getSessionId(), targetDeviceId);
        }
        log.info("接收到车机定位上报: deviceId={}, seq={}, speed={}km/h",
                targetDeviceId, report != null ? report.getSeq() : null, report != null ? report.getSpeed() : null);
        vehicleControlService.updateLocation(targetDeviceId, report);
    }
}
