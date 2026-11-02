package io.github.atengk.boot.sample.tcp.service;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.sample.tcp.model.LocationReportPacket;
import io.github.atengk.boot.sample.tcp.model.VehicleControlAck;
import io.github.atengk.boot.sample.tcp.model.VehicleParamQuery;
import io.github.atengk.boot.sample.tcp.model.VehicleParamQueryAck;
import io.github.atengk.boot.sample.tcp.model.VehicleStatusVO;
import io.github.atengk.boot.tcp.event.TcpChannelWritabilityChangedEvent;
import io.github.atengk.boot.tcp.event.TcpDeviceBoundEvent;
import io.github.atengk.boot.tcp.event.TcpIpBlockedEvent;
import io.github.atengk.boot.tcp.event.TcpSessionClosedEvent;
import io.github.atengk.boot.tcp.event.TcpSessionConnectedEvent;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.github.atengk.boot.tcp.template.TcpServerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 车联网车载长连接全业务场景协同与管控服务
 * <p>
 * 纯净自洽的业务场景服务组件，遵循工业级代码成熟度规范，实现车机身份接入、高频遥测摄取、
 * 全双工 RPC 远程控制与参数查询、车队集群分组组播、出站背压流控感知以及全生命周期事件闭环。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Service
public class VehicleTelemetryScenarioService {

    private static final Logger log = LoggerFactory.getLogger(VehicleTelemetryScenarioService.class);
    private static final int MAX_EVENT_LOG_SIZE = 300;

    private final TcpServerTemplate tcpServerTemplate;
    private final TcpSessionManager sessionManager;

    private final ConcurrentMap<String, VehicleState> vehicleStore = new ConcurrentHashMap<>();
    private final List<String> eventLogs = new CopyOnWriteArrayList<>();

    public VehicleTelemetryScenarioService(TcpServerTemplate tcpServerTemplate,
                                          TcpSessionManager sessionManager) {
        this.tcpServerTemplate = tcpServerTemplate;
        this.sessionManager = sessionManager;
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
        VehicleState state = vehicleStore.computeIfAbsent(deviceId, VehicleState::new);
        state.setOnline(true);
        state.setLastLongitude(report.getLongitude());
        state.setLastLatitude(report.getLatitude());
        state.setLastSpeed(report.getSpeed());
        if (report.getEngineStatus() != null) {
            state.setEngineStatus(report.getEngineStatus());
        }
        state.setUpdateTime(report.getTimestamp() != null ? report.getTimestamp() : System.currentTimeMillis());

        log.info("车机在线定位已更新: deviceId={}, speed={}km/h, engine={}, coord=({},{})",
                deviceId, report.getSpeed(), state.getEngineStatus(), report.getLongitude(), report.getLatitude());
    }

    /**
     * 获取指定车机的在线状态与最新定位（返回纯净防御性拷贝 VO）
     *
     * @param deviceId 设备编号
     * @return 车辆状态 VO，若无则返回 null
     */
    public VehicleStatusVO getVehicleStatus(String deviceId) {
        if (deviceId == null) {
            return null;
        }
        VehicleState state = vehicleStore.get(deviceId);
        return state != null ? state.toVO() : null;
    }

    /**
     * 向指定车载终端下发全双工远程控制指令（如 LOCK / UNLOCK / SPEED_LIMIT / REBOOT）
     *
     * @param deviceId 目标设备编号
     * @param command  控制指令码
     * @param params   指令扩展参数
     * @return 终端执行回执
     */
    public VehicleControlAck sendControlCommand(String deviceId, String command, Map<String, Object> params) {
        String requestId = IdUtil.fastSimpleUUID();
        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("command", command);
        payloadMap.put("deviceId", deviceId);
        payloadMap.put("requestId", requestId);
        if (params != null && !params.isEmpty()) {
            payloadMap.putAll(params);
        }

        TcpPacket packet = TcpPacket.builder()
                .command(command)
                .requestId(requestId)
                .deviceId(deviceId)
                .payload(TcpPayload.fromJson(payloadMap))
                .build();

        log.info("正在向车机下发远程控制指令: deviceId={}, command={}, requestId={}", deviceId, command, requestId);
        return tcpServerTemplate.requestToDevice(deviceId, packet, Duration.ofSeconds(3), VehicleControlAck.class);
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
                .deviceId(deviceId)
                .payload(TcpPayload.fromJson(query))
                .build();

        log.info("正在向车机下发全双工 RPC 参数查询: deviceId={}, requestId={}, type={}", deviceId, requestId, paramType);
        return tcpServerTemplate.requestToDevice(deviceId, packet, Duration.ofSeconds(3), VehicleParamQueryAck.class);
    }

    /**
     * 将指定车机当前活跃会话加入车队分组（Fleet Group）
     *
     * @param deviceId 车机硬件标识
     * @param group    车队分组标识（如 FLEET-ALPHA）
     * @return 是否成功加入
     */
    public boolean assignFleetGroup(String deviceId, String group) {
        if (deviceId == null || group == null) {
            return false;
        }
        TcpSession session = sessionManager.getSessionByDeviceId(deviceId);
        if (session != null) {
            sessionManager.addToGroup(group, session.getSessionId());
            log.info("车机已加入车队分组: deviceId={}, sessionId={}, group={}", deviceId, session.getSessionId(), group);
            return true;
        }
        log.warn("车机当前不在线，无法加入分组: deviceId={}, group={}", deviceId, group);
        return false;
    }

    /**
     * 向指定车队分组进行集群多节点跨机组播（Cluster Fleet Group Multicast）
     *
     * @param group        目标车队分组标识
     * @param alertMessage 调度预警消息
     */
    public void sendFleetGroupAlert(String group, String alertMessage) {
        Map<String, Object> payload = Map.of(
                "command", "FLEET_ALERT",
                "group", group,
                "message", alertMessage
        );
        TcpPacket packet = TcpPacket.ofJson("FLEET_ALERT", payload);
        tcpServerTemplate.sendGroupToCluster(group, packet);
        recordEventLog("GROUP_ALERT_DISPATCHED: group=" + group + ", msg=" + alertMessage);
        log.info("车队分组预警已跨节点集群组播: group={}, msg={}", group, alertMessage);
    }

    /**
     * 向全网在线车机广播限速预警通知
     *
     * @param alertMessage 预警信息
     */
    public void broadcastSpeedLimitAlert(String alertMessage) {
        Map<String, Object> alertMap = Map.of(
                "command", "ALERT_BROADCAST",
                "message", alertMessage
        );
        TcpPacket packet = TcpPacket.ofJson("ALERT_BROADCAST", alertMap);
        tcpServerTemplate.broadcast(packet);
        recordEventLog("BROADCAST_ALERT_DISPATCHED: msg=" + alertMessage);
        log.info("全网广播预警已下发: msg={}", alertMessage);
    }

    /**
     * 探测指定车机长连接出站通道背压可写状态
     *
     * @param deviceId 设备编号
     * @return 是否处于健康可写状态（若离线返回 false）
     */
    public boolean isOutboundWritable(String deviceId) {
        TcpSession session = sessionManager.getSessionByDeviceId(deviceId);
        return session != null && session.isWritable();
    }

    /**
     * 获取指定车机长连接出站待发送缓冲字节数
     *
     * @param deviceId 设备编号
     * @return 待发送字节数（若离线返回 -1）
     */
    public long getPendingWriteBytes(String deviceId) {
        TcpSession session = sessionManager.getSessionByDeviceId(deviceId);
        return session != null ? session.getPendingWriteBytes() : -1L;
    }

    /**
     * 获取当前记录的领域事件日志
     *
     * @return 不可变事件日志列表
     */
    public List<String> getEventLogs() {
        return Collections.unmodifiableList(eventLogs);
    }

    private void recordEventLog(String logEntry) {
        eventLogs.add(logEntry);
        while (eventLogs.size() > MAX_EVENT_LOG_SIZE) {
            eventLogs.remove(0);
        }
    }

    // ==================== 领域与网络治理事件感知 ====================

    @EventListener
    public void onSessionConnected(TcpSessionConnectedEvent event) {
        String logEntry = String.format("CONNECTED: sessionId=%s, remote=%s",
                event.getSession().getSessionId(), event.getSession().getRemoteAddress());
        recordEventLog(logEntry);
        log.info("监听到 TCP 会话建立事件: {}", logEntry);
    }

    @EventListener
    public void onDeviceBound(TcpDeviceBoundEvent event) {
        String logEntry = String.format("BOUND: deviceId=%s, sessionId=%s",
                event.getDeviceId(), event.getSession().getSessionId());
        recordEventLog(logEntry);
        VehicleState state = vehicleStore.computeIfAbsent(event.getDeviceId(), VehicleState::new);
        state.setOnline(true);
        state.setUpdateTime(System.currentTimeMillis());
        log.info("监听到车机绑定事件: {}", logEntry);
    }

    @EventListener
    public void onSessionClosed(TcpSessionClosedEvent event) {
        String deviceId = event.getSession().getDeviceId();
        String logEntry = String.format("CLOSED: sessionId=%s, deviceId=%s, reason=%s",
                event.getSession().getSessionId(), deviceId, event.getReason());
        recordEventLog(logEntry);
        if (deviceId != null) {
            VehicleState state = vehicleStore.get(deviceId);
            if (state != null) {
                state.setOnline(false);
                state.setUpdateTime(System.currentTimeMillis());
            }
        }
        log.info("监听到 TCP 会话关闭事件: {}", logEntry);
    }

    @EventListener
    public void onWritabilityChanged(TcpChannelWritabilityChangedEvent event) {
        long pending = event.getSession() != null ? event.getSession().getPendingWriteBytes() : -1L;
        String logEntry = String.format("WRITABILITY_CHANGED: sessionId=%s, writable=%s, pending=%d",
                event.getSession() != null ? event.getSession().getSessionId() : "unknown",
                event.isWritable(),
                pending);
        recordEventLog(logEntry);
        log.info("监听到 TCP 通道可写状态变化事件: {}", logEntry);
    }

    @EventListener
    public void onIpBlocked(TcpIpBlockedEvent event) {
        String logEntry = String.format("IP_BLOCKED: remote=%s, rule=%s",
                event.getRemoteAddress(), event.getRuleDetail());
        recordEventLog(logEntry);
        log.warn("监听到 TCP 网络防火墙拦截事件: {}", logEntry);
    }

    /**
     * 内部线程安全领域状态模型
     */
    private static class VehicleState {
        private final String deviceId;
        private volatile boolean online;
        private volatile Double lastLongitude;
        private volatile Double lastLatitude;
        private volatile Double lastSpeed;
        private volatile String engineStatus = "OFF";
        private volatile Long updateTime;

        VehicleState(String deviceId) {
            this.deviceId = deviceId;
            this.online = true;
            this.updateTime = System.currentTimeMillis();
        }

        public void setOnline(boolean online) {
            this.online = online;
        }

        public void setLastLongitude(Double lastLongitude) {
            this.lastLongitude = lastLongitude;
        }

        public void setLastLatitude(Double lastLatitude) {
            this.lastLatitude = lastLatitude;
        }

        public void setLastSpeed(Double lastSpeed) {
            this.lastSpeed = lastSpeed;
        }

        public String getEngineStatus() {
            return engineStatus;
        }

        public void setEngineStatus(String engineStatus) {
            this.engineStatus = engineStatus;
        }

        public void setUpdateTime(Long updateTime) {
            this.updateTime = updateTime;
        }

        public VehicleStatusVO toVO() {
            return new VehicleStatusVO(
                    deviceId,
                    online,
                    lastLongitude,
                    lastLatitude,
                    lastSpeed,
                    engineStatus,
                    updateTime
            );
        }
    }
}
