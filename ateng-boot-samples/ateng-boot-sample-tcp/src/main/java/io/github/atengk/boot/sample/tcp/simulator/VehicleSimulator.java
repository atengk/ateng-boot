package io.github.atengk.boot.sample.tcp.simulator;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sample.tcp.model.LocationReportPacket;
import io.github.atengk.boot.sample.tcp.model.VehicleParamQuery;
import io.github.atengk.boot.sample.tcp.model.VehicleParamQueryAck;
import io.github.atengk.boot.tcp.client.TcpClient;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.properties.TcpClientProperties;
import io.github.atengk.boot.tcp.route.TcpDispatcher;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 车载长连接终端仿真模拟器
 * <p>
 * 模拟车联网实际车机终端硬件，支持登录注册、定位报文定时上报、全双工 RPC 参数查询自动应答与广播预警监听。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class VehicleSimulator {

    private static final Logger log = LoggerFactory.getLogger(VehicleSimulator.class);

    private final String deviceId;
    private final String host;
    private final int port;
    private final List<String> receivedAlerts = new CopyOnWriteArrayList<>();
    private final AtomicBoolean autoReplyParamQuery = new AtomicBoolean(true);

    private TcpClient tcpClient;

    public VehicleSimulator(String deviceId, String host, int port) {
        this.deviceId = deviceId;
        this.host = host;
        this.port = port;
    }

    /**
     * 启动仿真终端连接服务端
     */
    public synchronized void start() {
        TcpClientProperties properties = new TcpClientProperties();
        properties.setHost(host);
        properties.setPort(port);
        properties.getReconnect().setEnabled(false);
        properties.getFraming().setMode(io.github.atengk.boot.tcp.enums.FramingMode.DELIMITER);
        properties.getFraming().setDelimiter("\n");

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
        log.info("车机终端模拟器已启动: deviceId={}, target={}:{}", deviceId, host, port);
    }

    private void handleInboundObject(Channel channel, Object msg) {
        TcpPacket packet;
        if (msg instanceof TcpPacket p) {
            packet = p;
        } else if (msg instanceof io.github.atengk.boot.tcp.model.TcpPayload payload) {
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
        java.util.Map<String, Object> payload = java.util.Map.of(
                "command", "LOGIN",
                "deviceId", deviceId
        );
        sendPacket(TcpPacket.ofJson("LOGIN", payload));
        log.info("车机终端模拟器已发送登录认证: deviceId={}", deviceId);
    }

    /**
     * 上报定位遥测数据包
     *
     * @param seq       流水号
     * @param longitude 经度
     * @param latitude  纬度
     * @param speed     车速
     */
    public void reportLocation(long seq, double longitude, double latitude, double speed) {
        LocationReportPacket report = new LocationReportPacket(
                deviceId,
                seq,
                longitude,
                latitude,
                speed,
                System.currentTimeMillis()
        );
        report.setCommand("LOCATION_REPORT");
        sendPacket(TcpPacket.ofJson("LOCATION_REPORT", report));
        log.info("车机终端模拟器已上报定位: deviceId={}, seq={}, speed={}", deviceId, seq, speed);
    }

    private void handleInboundPacket(Channel channel, TcpPacket packet) {
        String command = packet.getCommand();
        String jsonText = packet.getPayload() != null ? packet.getPayload().asString() : null;
        if (jsonText != null && jsonText.trim().startsWith("{")) {
            try {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> map = JsonUtil.parseObject(jsonText, java.util.Map.class);
                if (map != null) {
                    if (command == null && map.get("command") != null) {
                        command = map.get("command").toString();
                    }
                    if (packet.getRequestId() == null && map.get("requestId") != null) {
                        packet.setRequestId(map.get("requestId").toString());
                    }
                }
            } catch (Exception ignored) {
            }
        }
        log.info("车机终端模拟器收到服务端下行报文: command={}, requestId={}", command, packet.getRequestId());

        if ("PARAM_QUERY".equalsIgnoreCase(command) && autoReplyParamQuery.get()) {
            // 解析服务端下发的参数查询请求
            VehicleParamQuery query = packet.getPayload() != null
                    ? JsonUtil.parseObject(packet.getPayload().asString(), VehicleParamQuery.class)
                    : null;
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

            // 回复应答，将 requestId 原样回写
            TcpPacket ackPacket = TcpPacket.of("PARAM_QUERY_ACK", reqId, io.github.atengk.boot.tcp.model.TcpPayload.fromJson(ack));
            channel.writeAndFlush(ackPacket);
            log.info("车机终端模拟器已自动应答 RPC 参数查询: deviceId={}, reqId={}", deviceId, reqId);
        } else if ("ALERT_BROADCAST".equalsIgnoreCase(command)) {
            String alert = packet.getPayload() != null ? packet.getPayload().asString() : "";
            if (alert != null && alert.trim().startsWith("{")) {
                try {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> map = JsonUtil.parseObject(alert, java.util.Map.class);
                    if (map != null && map.get("message") != null) {
                        alert = map.get("message").toString();
                    }
                } catch (Exception ignored) {
                }
            }
            receivedAlerts.add(alert);
            log.info("车机终端模拟器收到全网广播预警: deviceId={}, alert={}", deviceId, alert);
        }
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

    public synchronized void stop() {
        if (tcpClient != null) {
            tcpClient.stop();
            log.info("车机终端模拟器已停止: deviceId={}", deviceId);
        }
    }
}
