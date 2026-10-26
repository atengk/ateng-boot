package io.github.atengk.boot.tcp.cluster;

import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Base64;

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
     * 载荷类型：TEXT | BYTES | JSON
     */
    private String payloadType;

    /**
     * 文本数据（当 payloadType 为 TEXT 或 JSON 时使用）
     */
    private String textContent;

    /**
     * Base64 编码的二进制数据（当 payloadType 为 BYTES 时使用）
     */
    private String binaryBase64;

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
            if (packet.getPayload() != null) {
                message.setPayloadType("BYTES");
                message.setBinaryBase64(Base64.getEncoder().encodeToString(packet.getPayload().getBytes()));
            } else {
                message.setPayloadType("TEXT");
                message.setTextContent("");
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
        if ("BYTES".equalsIgnoreCase(payloadType) && binaryBase64 != null) {
            return Base64.getDecoder().decode(binaryBase64);
        }
        if (textContent != null) {
            return textContent;
        }
        return "";
    }
}
