package io.github.atengk.boot.sample.tcp.service;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.sample.tcp.model.LocationReportPacket;
import io.github.atengk.boot.sample.tcp.model.VehicleParamQuery;
import io.github.atengk.boot.sample.tcp.model.VehicleParamQueryAck;
import io.github.atengk.boot.sample.tcp.model.VehicleStatusVO;
import io.github.atengk.boot.tcp.event.TcpDeviceBoundEvent;
import io.github.atengk.boot.tcp.event.TcpSessionClosedEvent;
import io.github.atengk.boot.tcp.event.TcpSessionConnectedEvent;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.template.TcpServerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 车载长连接业务协同与管控服务
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Service
public class VehicleControlService {

    private static final Logger log = LoggerFactory.getLogger(VehicleControlService.class);

    private final TcpServerTemplate tcpServerTemplate;

    private final ConcurrentMap<String, VehicleStatusVO> vehicleStore = new ConcurrentHashMap<>();
    private final List<String> eventLogs = new CopyOnWriteArrayList<>();

    public VehicleControlService(TcpServerTemplate tcpServerTemplate) {
        this.tcpServerTemplate = tcpServerTemplate;
    }

    /**
     * 更新车机在线遥测与定位状态
     *
     * @param deviceId 设备编号
     * @param report   定位上报数据包
     */
    public void updateLocation(String deviceId, LocationReportPacket report) {
        if (deviceId == null || report == null) {
            return;
        }
        VehicleStatusVO status = vehicleStore.computeIfAbsent(deviceId, id -> new VehicleStatusVO(id, true, null, null, null, null));
        status.setOnline(true);
        status.setLastLongitude(report.getLongitude());
        status.setLastLatitude(report.getLatitude());
        status.setLastSpeed(report.getSpeed());
        status.setUpdateTime(report.getTimestamp() != null ? report.getTimestamp() : System.currentTimeMillis());
        log.info("车机在线定位已更新: deviceId={}, speed={}km/h, coord=({},{})",
                deviceId, report.getSpeed(), report.getLongitude(), report.getLatitude());
    }

    /**
     * 获取指定车机的在线状态与最新定位
     *
     * @param deviceId 设备编号
     * @return 车辆状态 VO，若无则返回 null
     */
    public VehicleStatusVO getVehicleStatus(String deviceId) {
        return vehicleStore.get(deviceId);
    }

    /**
     * 下发全双工 RPC 参数查询指令并同步等待车载终端回执
     *
     * @param deviceId  目标设备编号
     * @param paramType 查询参数类型
     * @return 终端应答对象
     */
    public VehicleParamQueryAck queryDeviceParams(String deviceId, String paramType) {
        String requestId = IdUtil.fastSimpleUUID();
        VehicleParamQuery query = new VehicleParamQuery(requestId, deviceId, paramType);

        TcpPacket packet = TcpPacket.builder()
                .command("PARAM_QUERY")
                .requestId(requestId)
                .payload(io.github.atengk.boot.tcp.model.TcpPayload.fromJson(query))
                .build();

        log.info("正在向车机下发全双工 RPC 参数查询: deviceId={}, requestId={}, type={}", deviceId, requestId, paramType);
        TcpPacket responsePacket = tcpServerTemplate.requestToDevice(deviceId, packet, Duration.ofSeconds(3));
        if (responsePacket != null && responsePacket.getPayload() != null) {
            return io.github.atengk.boot.core.util.JsonUtil.parseObject(responsePacket.getPayload().asString(), VehicleParamQueryAck.class);
        }
        return null;
    }

    /**
     * 向全网在线车机广播限速预警通知
     *
     * @param alertMessage 预警信息
     */
    public void broadcastSpeedLimitAlert(String alertMessage) {
        java.util.Map<String, Object> alertMap = java.util.Map.of(
                "command", "ALERT_BROADCAST",
                "message", alertMessage
        );
        TcpPacket packet = TcpPacket.ofJson("ALERT_BROADCAST", alertMap);
        tcpServerTemplate.broadcast(packet);
        log.info("全网广播预警已下发: msg={}", alertMessage);
    }

    /**
     * 获取当前记录的领域事件日志
     *
     * @return 不可变事件日志列表
     */
    public List<String> getEventLogs() {
        return Collections.unmodifiableList(eventLogs);
    }

    // ==================== 领域事件感知 ====================

    @EventListener
    public void onSessionConnected(TcpSessionConnectedEvent event) {
        String logEntry = String.format("CONNECTED: sessionId=%s, remote=%s",
                event.getSession().getSessionId(), event.getSession().getRemoteAddress());
        eventLogs.add(logEntry);
        log.info("监听到 TCP 会话建立事件: {}", logEntry);
    }

    @EventListener
    public void onDeviceBound(TcpDeviceBoundEvent event) {
        String logEntry = String.format("BOUND: deviceId=%s, sessionId=%s",
                event.getDeviceId(), event.getSession().getSessionId());
        eventLogs.add(logEntry);
        vehicleStore.computeIfAbsent(event.getDeviceId(), id -> new VehicleStatusVO(id, true, null, null, null, System.currentTimeMillis()))
                .setOnline(true);
        log.info("监听到车机绑定事件: {}", logEntry);
    }

    @EventListener
    public void onSessionClosed(TcpSessionClosedEvent event) {
        String deviceId = event.getSession().getDeviceId();
        String logEntry = String.format("CLOSED: sessionId=%s, deviceId=%s, reason=%s",
                event.getSession().getSessionId(), deviceId, event.getReason());
        eventLogs.add(logEntry);
        if (deviceId != null) {
            VehicleStatusVO vo = vehicleStore.get(deviceId);
            if (vo != null) {
                vo.setOnline(false);
            }
        }
        log.info("监听到 TCP 会话关闭事件: {}", logEntry);
    }
}
