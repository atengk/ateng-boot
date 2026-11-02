package io.github.atengk.boot.sample.tcp.listener;

import io.github.atengk.boot.sample.tcp.codec.Jt808CompositeFrameCodec;
import io.github.atengk.boot.sample.tcp.model.LocationReportPacket;
import io.github.atengk.boot.sample.tcp.service.VehicleTelemetryScenarioService;
import io.github.atengk.boot.tcp.annotation.TcpDeviceId;
import io.github.atengk.boot.tcp.annotation.TcpListener;
import io.github.atengk.boot.tcp.annotation.TcpMapping;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 车载长连接遥测数据与控制指令监听器
 * <p>
 * 双轨支持标准 JSON 契约报文与交通部 JT/T 808 国标风格复合报文（0x7E 边界分帧、0x7D 反转义与 BCC XOR 校验和验证）。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Component
@TcpListener
public class VehicleTelemetryListener {

    private static final Logger log = LoggerFactory.getLogger(VehicleTelemetryListener.class);

    private final VehicleTelemetryScenarioService scenarioService;
    private final TcpSessionManager sessionManager;

    public VehicleTelemetryListener(VehicleTelemetryScenarioService scenarioService,
                                  TcpSessionManager sessionManager) {
        this.scenarioService = scenarioService;
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
    public void onLogin(TcpSession session, @TcpDeviceId String deviceId, Map<String, Object> body) {
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
     * 接收终端上报的高频定位遥测数据包（JSON契约）并自动返回 ACK 回执
     *
     * @param session  当前 TCP 会话
     * @param deviceId 通过 @TcpDeviceId 自动提取当前长连接已绑定的硬件标识
     * @param report   定位数据实体
     * @return 自动向车机回写的定位回执 ACK 载荷
     */
    @TcpMapping(command = "LOCATION_REPORT")
    public Map<String, Object> onLocationReport(TcpSession session, @TcpDeviceId String deviceId, LocationReportPacket report) {
        String targetDeviceId = deviceId != null ? deviceId : (report != null ? report.getDeviceId() : null);
        if (targetDeviceId != null && session != null && session.getDeviceId() == null) {
            sessionManager.bind(session.getSessionId(), targetDeviceId);
        }
        log.info("接收到车机定位上报 (JSON): deviceId={}, seq={}, speed={}km/h, engine={}",
                targetDeviceId,
                report != null ? report.getSeq() : null,
                report != null ? report.getSpeed() : null,
                report != null ? report.getEngineStatus() : null);
        scenarioService.updateLocation(targetDeviceId, report);

        return Map.of(
                "command", "LOCATION_ACK",
                "seq", report != null && report.getSeq() != null ? report.getSeq() : 0L,
                "status", "SUCCESS"
        );
    }

    /**
     * 接收交通部 JT/T 808 国标风格复合报文（含 0x7E 分隔符、0x7D 转义还原与 BCC 异或校验和）
     *
     * @param session 当前 TCP 会话
     * @param packet  包含原始二进制报文的 TCP 数据包
     * @return 自动向车机回写的 JT/T 808 通用应答 ACK
     */
    @TcpMapping(command = "JT808_LOCATION")
    public Map<String, Object> onJt808LocationReport(TcpSession session, TcpPacket packet) {
        byte[] rawBytes = packet.getPayload() != null ? packet.getPayload().asBytes() : null;
        if (rawBytes == null || rawBytes.length == 0) {
            throw new IllegalArgumentException("JT/T 808 报文载荷为空");
        }

        // 1. 调用解码器执行 0x7E 首尾校验、0x7D 反转义与 BCC 异或校验和验证
        LocationReportPacket report = Jt808CompositeFrameCodec.decodeLocationReport(rawBytes);
        String deviceId = report.getDeviceId();

        // 2. 自动绑定会话设备标识（若尚未绑定）
        if (deviceId != null && session != null && session.getDeviceId() == null) {
            sessionManager.bind(session.getSessionId(), deviceId);
        }

        // 3. 更新车机领域状态
        scenarioService.updateLocation(deviceId, report);
        log.info("成功处理 JT/T 808 国标复合定位报文: deviceId={}, seq={}, speed={}km/h, engine={}",
                deviceId, report.getSeq(), report.getSpeed(), report.getEngineStatus());

        return Map.of(
                "command", "JT808_LOCATION_ACK",
                "deviceId", deviceId != null ? deviceId : "",
                "seq", report.getSeq(),
                "status", "SUCCESS"
        );
    }

    /**
     * 终端长连接心跳保活
     *
     * @param session 当前 TCP 会话
     * @return 心跳响应
     */
    @TcpMapping(command = "HEARTBEAT")
    public Map<String, Object> onHeartbeat(TcpSession session) {
        log.debug("接收到车机心跳保活: sessionId={}, deviceId={}",
                session != null ? session.getSessionId() : null,
                session != null ? session.getDeviceId() : null);
        return Map.of(
                "command", "HEARTBEAT_ACK",
                "timestamp", System.currentTimeMillis()
        );
    }
}
