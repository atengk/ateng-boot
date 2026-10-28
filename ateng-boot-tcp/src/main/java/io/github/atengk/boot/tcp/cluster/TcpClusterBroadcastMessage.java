package io.github.atengk.boot.tcp.cluster;

import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TCP 集群跨节点广播消息模型
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Getter
@Setter
@NoArgsConstructor
public class TcpClusterBroadcastMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标设备唯一标识（为 null 表示集群全量广播）
     */
    private String deviceId;

    /**
     * 发起广播的源节点唯一标识（防回环过滤）
     */
    private String sourceNodeId;

    /**
     * 载荷类型：TEXT | BYTES | JSON | PACKET
     */
    private String payloadType;

    /**
     * 文本数据（当 payloadType 为 TEXT 或 JSON 时使用）
     */
    private String textContent;

    /**
     * Base64 编码的二进制数据（当 payloadType 为 BYTES 或 PACKET 时使用）
     */
    private String binaryBase64;

    /**
     * 当 payloadType 为 PACKET 时承载的业务命令字
     */
    private String packetCommand;

    /**
     * 当 payloadType 为 PACKET 时承载的消息关联流水号
     */
    private String packetRequestId;

    /**
     * 当 payloadType 为 PACKET 时承载的设备标识
     */
    private String packetDeviceId;

    /**
     * 当 payloadType 为 PACKET 时承载的扩展头字典
     */
    private Map<String, Object> packetHeaders;

    /**
     * 消息产生的时间戳
     */
    private long timestamp = System.currentTimeMillis();

    public static TcpClusterBroadcastMessage of(String deviceId, String sourceNodeId, Object payload) {
        TcpClusterBroadcastMessage message = new TcpClusterBroadcastMessage();
        message.setDeviceId(deviceId);
        message.setSourceNodeId(sourceNodeId);

        if (payload instanceof byte[] bytes) {
            message.setPayloadType("BYTES");
            message.setBinaryBase64(Base64.getEncoder().encodeToString(bytes));
        } else if (payload instanceof TcpPayload tcpPayload) {
            message.setPayloadType("BYTES");
            message.setBinaryBase64(Base64.getEncoder().encodeToString(tcpPayload.getBytes()));
        } else if (payload instanceof TcpPacket packet) {
            message.setPayloadType("PACKET");
            message.setPacketCommand(packet.getCommand());
            message.setPacketRequestId(packet.getRequestId());
            message.setPacketDeviceId(packet.getDeviceId());
            if (packet.getHeaders() != null && !packet.getHeaders().isEmpty()) {
                message.setPacketHeaders(new LinkedHashMap<>(packet.getHeaders()));
            }
            if (packet.getPayload() != null && packet.getPayload().getBytes() != null) {
                message.setBinaryBase64(Base64.getEncoder().encodeToString(packet.getPayload().getBytes()));
            }
        } else if (payload instanceof String str) {
            message.setPayloadType("TEXT");
            message.setTextContent(str);
        } else {
            // POJO 序列化
            message.setPayloadType("JSON");
            message.setTextContent(io.github.atengk.boot.core.util.JsonUtil.toJsonString(payload));
        }

        return message;
    }

    /**
     * 还原为可直接发送给设备的报文载荷对象
     *
     * @return 还原后的载荷
     */
    public Object resolvePayload() {
        if ("PACKET".equalsIgnoreCase(payloadType)) {
            byte[] bytes = binaryBase64 != null ? Base64.getDecoder().decode(binaryBase64) : new byte[0];
            TcpPayload tcpPayload = TcpPayload.of(bytes);
            return new TcpPacket(packetCommand, packetDeviceId, packetRequestId, tcpPayload, packetHeaders);
        }
        if ("BYTES".equalsIgnoreCase(payloadType) && binaryBase64 != null) {
            return Base64.getDecoder().decode(binaryBase64);
        }
        if (textContent != null) {
            return textContent;
        }
        return "";
    }
}
