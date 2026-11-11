package io.github.atengk.boot.sample.tcp.simulator;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sample.tcp.codec.Jt808CompositeFrameCodec;
import io.github.atengk.boot.sample.tcp.model.LocationReportPacket;
import io.github.atengk.boot.sample.tcp.model.VehicleControlAck;
import io.github.atengk.boot.sample.tcp.model.VehicleParamQuery;
import io.github.atengk.boot.sample.tcp.model.VehicleParamQueryAck;
import io.github.atengk.boot.tcp.client.TcpClient;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.properties.TcpClientProperties;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 车载长连接终端仿真模拟器
 * <p>
 * 模拟车联网实际车机终端硬件，支持登录注册、JSON与JT/T 808国标定位报文上报、心跳保活、
 * 全双工远程控制与 RPC 参数查询自动应答、车队分组组播预警与全网广播监听、以及断线自愈重连。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class VehicleSimulator {

    private static final Logger log = LoggerFactory.getLogger(VehicleSimulator.class);
    private static final Set<String> CONTROL_COMMANDS = Set.of("LOCK", "UNLOCK", "SPEED_LIMIT", "REBOOT");

    private final String deviceId;
    private final String host;
    private final int port;
    private final boolean enableReconnect;
    private final List<String> receivedAlerts = new CopyOnWriteArrayList<>();
    private final List<String> receivedFleetAlerts = new CopyOnWriteArrayList<>();
    private final List<String> executedCommands = new CopyOnWriteArrayList<>();
    private final List<String> receivedAcks = new CopyOnWriteArrayList<>();
    private final AtomicBoolean autoReplyParamQuery = new AtomicBoolean(true);

    private TcpClient tcpClient;

    public VehicleSimulator(String deviceId, String host, int port) {
        this(deviceId, host, port, false);
    }

    public VehicleSimulator(String deviceId, String host, int port, boolean enableReconnect) {
        this.deviceId = deviceId;
        this.host = host;
        this.port = port;
        this.enableReconnect = enableReconnect;
    }

    /**
     * 启动仿真终端连接服务端
     */
    public synchronized void start() {
        TcpClientProperties properties = new TcpClientProperties();
        properties.setHost(host);
        properties.setPort(port);
        properties.getReconnect().setEnabled(enableReconnect);
        if (enableReconnect) {
            properties.getReconnect().setInitialInterval(Duration.ofMillis(200));
            properties.getReconnect().setMaxInterval(Duration.ofSeconds(1));
            properties.getReconnect().setMaxAttempts(5);
        }
        properties.getFraming().setMode(io.github.atengk.boot.tcp.enums.FramingMode.LENGTH_FIELD);

        this.tcpClient = new TcpClient(
                "sim-" + deviceId,
                properties,
                Collections.emptyList(),
                List.of((io.github.atengk.boot.tcp.spi.TcpChannelCustomizer) channel -> {
                    // 挂载终端业务消息消费与 RPC 自动应答处理器
                    channel.pipeline().addLast("simulatorHandler", new SimpleChannelInboundHandler<Object>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                            handleInboundObject(ctx.channel(), msg);
                        }
                    });
                }),
                null,
                null,
                null
        );

        tcpClient.start();
        log.info("车机终端模拟器已启动: deviceId={}, target={}:{}, reconnect={}", deviceId, host, port, enableReconnect);
    }

    private void handleInboundObject(Channel channel, Object msg) {
        TcpPacket packet;
        if (msg instanceof TcpPacket p) {
            packet = p;
        } else if (msg instanceof TcpPayload payload) {
            packet = TcpPacket.of(payload);
        } else if (msg instanceof byte[] bytes) {
            packet = TcpPacket.ofBytes(bytes);
        } else if (msg instanceof String str) {
            packet = TcpPacket.ofText(str);
        } else {
            return;
        }
        handleInboundPacket(channel, packet);
    }

    /**
     * 发送登录包认证当前设备身份
     */
    public void login() {
        Map<String, Object> payload = Map.of(
                "command", "LOGIN",
                "deviceId", deviceId
        );
        sendPacket(TcpPacket.ofJson("LOGIN", payload));
        log.info("车机终端模拟器已发送登录认证: deviceId={}", deviceId);
    }

    /**
     * 发送心跳保活包
     */
    public void sendHeartbeat() {
        Map<String, Object> payload = Map.of(
                "command", "HEARTBEAT",
                "deviceId", deviceId,
                "timestamp", System.currentTimeMillis()
        );
        sendPacket(TcpPacket.ofJson("HEARTBEAT", payload));
        log.info("车机终端模拟器已发送心跳保活: deviceId={}", deviceId);
    }

    /**
     * 上报定位遥测数据包 (JSON 格式)
     *
     * @param report 定位上报实体
     */
    public void reportLocation(LocationReportPacket report) {
        if (report == null) {
            return;
        }
        report.setCommand("LOCATION_REPORT");
        report.setDeviceId(deviceId);
        String reqId = "REQ-LOC-" + report.getSeq();
        TcpPacket packet = TcpPacket.builder()
                .command("LOCATION_REPORT")
                .requestId(reqId)
                .deviceId(deviceId)
                .payload(TcpPayload.fromJson(report))
                .build();
        sendPacket(packet);
        log.info("车机终端模拟器已上报定位 (JSON): deviceId={}, seq={}, speed={}, engine={}",
                deviceId, report.getSeq(), report.getSpeed(), report.getEngineStatus());
    }

    /**
     * 上报定位遥测快捷方法 (JSON 格式)
     *
     * @param seq          流水号
     * @param longitude    经度
     * @param latitude     纬度
     * @param speed        车速
     * @param engineStatus 发动机状态
     */
    public void reportLocation(long seq, double longitude, double latitude, double speed, String engineStatus) {
        LocationReportPacket report = new LocationReportPacket(
                deviceId,
                seq,
                longitude,
                latitude,
                speed,
                engineStatus,
                System.currentTimeMillis()
        );
        reportLocation(report);
    }

    public void reportLocation(long seq, double longitude, double latitude, double speed) {
        reportLocation(seq, longitude, latitude, speed, "ON");
    }

    /**
     * 上报交通部 JT/T 808 国标风格复合定位报文（通过结构化实体包）
     *
     * @param packet 定位数据包实体
     */
    public void reportJt808Location(LocationReportPacket packet) {
        if (packet == null) {
            return;
        }
        byte[] frame = Jt808CompositeFrameCodec.encodeLocationReport(packet);
        TcpPacket tcpPacket = TcpPacket.builder()
                .command("JT808_LOCATION")
                .deviceId(packet.getDeviceId() != null ? packet.getDeviceId() : this.deviceId)
                .payload(TcpPayload.of(frame))
                .build();
        sendPacket(tcpPacket);
        log.info("车机终端模拟器已上报 JT/T 808 国标实体定位: deviceId={}, seq={}, speed={}, frameBytes={}",
                packet.getDeviceId(), packet.getSeq(), packet.getSpeed(), frame.length);
    }

    /**
     * 上报交通部 JT/T 808 国标风格复合定位报文（含 0x7E 分隔符、0x7D 转义还原与 BCC 校验码）
     *
     * @param seq          流水号
     * @param longitude    经度
     * @param latitude     纬度
     * @param speed        车速
     * @param engineStatus 发动机状态
     */
    public void reportJt808Location(long seq, double longitude, double latitude, double speed, String engineStatus) {
        byte[] frame = Jt808CompositeFrameCodec.encodeLocationReport(deviceId, seq, longitude, latitude, speed, engineStatus);
        TcpPacket packet = TcpPacket.builder()
                .command("JT808_LOCATION")
                .deviceId(deviceId)
                .payload(TcpPayload.of(frame))
                .build();
        sendPacket(packet);
        log.info("车机终端模拟器已上报 JT/T 808 国标定位: deviceId={}, seq={}, speed={}, frameBytes={}",
                deviceId, seq, speed, frame.length);
    }

    public void reportJt808Location(long seq, double longitude, double latitude, double speed) {
        reportJt808Location(seq, longitude, latitude, speed, "ON");
    }

    /**
     * 模拟上报 BCC 校验码被篡改损坏的 JT/T 808 报文（用于负向防卫验证）
     *
     * @param seq 流水号
     */
    public void reportCorruptedJt808Location(long seq) {
        byte[] frame = Jt808CompositeFrameCodec.encodeLocationReport(deviceId, seq, 116.4, 39.9, 60.0, "ON");
        frame[frame.length - 2] = (byte) (frame[frame.length - 2] ^ 0xFF);
        TcpPacket packet = TcpPacket.builder()
                .command("JT808_LOCATION")
                .deviceId(deviceId)
                .payload(TcpPayload.of(frame))
                .build();
        sendPacket(packet);
        log.info("车机终端模拟器已发送 BCC 损坏的 JT/T 808 报文: deviceId={}, seq={}", deviceId, seq);
    }

    private void handleInboundPacket(Channel channel, TcpPacket packet) {
        String command = packet.getCommand();
        String jsonText = packet.getPayload() != null ? packet.getPayload().asString() : null;
        if (jsonText != null && jsonText.trim().startsWith("{")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = JsonUtil.parseObject(jsonText, Map.class);
                if (map != null) {
                    if (map.get("command") != null) {
                        command = map.get("command").toString();
                    }
                    if (packet.getRequestId() == null && map.get("requestId") != null) {
                        packet.setRequestId(map.get("requestId").toString());
                    }
                }
            } catch (Exception ex) {
                log.warn("车机终端模拟器解析报文元数据异常: {}", ex.getMessage());
            }
        }
        log.info("车机终端模拟器收到服务端下行报文: command={}, requestId={}", command, packet.getRequestId());

        if (command != null && CONTROL_COMMANDS.contains(command.toUpperCase())) {
            // 1. 响应下行控制指令 (LOCK / UNLOCK / SPEED_LIMIT / REBOOT)
            String reqId = packet.getRequestId();
            VehicleControlAck ack = new VehicleControlAck(
                    reqId,
                    deviceId,
                    command,
                    "SUCCESS",
                    "车机终端已成功执行指令: " + command
            );
            executedCommands.add(command);
            TcpPacket ackPacket = TcpPacket.of(command + "_ACK", reqId, TcpPayload.fromJson(ack));
            channel.writeAndFlush(ackPacket);
            log.info("车机终端模拟器已应答控制指令: deviceId={}, command={}, reqId={}", deviceId, command, reqId);
        } else if ("PARAM_QUERY".equalsIgnoreCase(command) && autoReplyParamQuery.get()) {
            // 2. 响应全双工参数查询请求
            VehicleParamQuery query = null;
            if (packet.getPayload() != null) {
                try {
                    query = JsonUtil.parseObject(packet.getPayload().asString(), VehicleParamQuery.class);
                } catch (Exception ex) {
                    log.warn("解析参数查询报文异常: {}", ex.getMessage());
                }
            }
            String reqId = packet.getRequestId() != null
                    ? packet.getRequestId()
                    : (query != null ? query.getRequestId() : null);

            VehicleParamQueryAck ack = new VehicleParamQueryAck(
                    reqId,
                    deviceId,
                    "v2.1.0-release",
                    88,
                    "NORMAL"
            );

            TcpPacket ackPacket = TcpPacket.of("PARAM_QUERY_ACK", reqId, TcpPayload.fromJson(ack));
            channel.writeAndFlush(ackPacket);
            log.info("车机终端模拟器已自动应答 RPC 参数查询: deviceId={}, reqId={}", deviceId, reqId);
        } else if ("FLEET_ALERT".equalsIgnoreCase(command)) {
            // 3. 接收车队分组集群组播预警
            String alert = extractMessage(packet.getPayload());
            receivedFleetAlerts.add(alert);
            receivedAlerts.add(alert);
            log.info("车机终端模拟器收到车队分组集群预警: deviceId={}, alert={}", deviceId, alert);
        } else if ("ALERT_BROADCAST".equalsIgnoreCase(command)) {
            // 4. 接收全网预警广播
            String alert = extractMessage(packet.getPayload());
            receivedAlerts.add(alert);
            log.info("车机终端模拟器收到全网广播预警: deviceId={}, alert={}", deviceId, alert);
        } else if ("LOCATION_ACK".equalsIgnoreCase(command) || "JT808_LOCATION_ACK".equalsIgnoreCase(command)) {
            // 5. 接收定位上报的服务器自动 ACK 回执
            receivedAcks.add(command);
            log.info("车机终端模拟器收到定位 ACK 回执: deviceId={}, cmd={}", deviceId, command);
        } else if ("HEARTBEAT_ACK".equalsIgnoreCase(command)) {
            // 6. 接收心跳 ACK 回执
            receivedAcks.add("HEARTBEAT_ACK");
            log.info("车机终端模拟器收到心跳 ACK 回执: deviceId={}", deviceId);
        }
    }

    private String extractMessage(TcpPayload payload) {
        if (payload == null) {
            return "";
        }
        String text = payload.asString();
        if (text != null && text.trim().startsWith("{")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = JsonUtil.parseObject(text, Map.class);
                if (map != null && map.get("message") != null) {
                    return map.get("message").toString();
                }
            } catch (Exception ex) {
                log.warn("解析预警报文载荷异常: {}", ex.getMessage());
            }
        }
        return text != null ? text : "";
    }

    private void sendPacket(TcpPacket packet) {
        if (tcpClient != null && tcpClient.isConnected()) {
            tcpClient.getChannel().writeAndFlush(packet);
        } else {
            throw new IllegalStateException("模拟车机终端尚未建立连接: deviceId=" + deviceId);
        }
    }

    public boolean isConnected() {
        return tcpClient != null && tcpClient.isConnected();
    }

    public List<String> getReceivedAlerts() {
        return Collections.unmodifiableList(receivedAlerts);
    }

    public List<String> getReceivedFleetAlerts() {
        return Collections.unmodifiableList(receivedFleetAlerts);
    }

    public List<String> getExecutedCommands() {
        return Collections.unmodifiableList(executedCommands);
    }

    public List<String> getReceivedAcks() {
        return Collections.unmodifiableList(receivedAcks);
    }

    public TcpClient getTcpClient() {
        return tcpClient;
    }

    public synchronized void stop() {
        if (tcpClient != null) {
            tcpClient.stop();
            log.info("车机终端模拟器已停止: deviceId={}", deviceId);
        }
    }
}
