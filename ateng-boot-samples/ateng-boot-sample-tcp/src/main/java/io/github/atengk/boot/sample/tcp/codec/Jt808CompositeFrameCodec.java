package io.github.atengk.boot.sample.tcp.codec;

import io.github.atengk.boot.sample.tcp.model.LocationReportPacket;
import io.github.atengk.boot.tcp.util.ChecksumUtil;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 交通部 JT/T 808 国标风格车载终端复合报文编解码器
 * <p>
 * 支持首尾标识符（{@code 0x7E}）、字节转义与还原（{@code 0x7D 0x02 <-> 0x7E}、{@code 0x7D 0x01 <-> 0x7D}）
 * 以及基于 BCC 异或（XOR）的全真校验和校验。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
public final class Jt808CompositeFrameCodec {

    public static final byte DELIMITER = 0x7E;
    public static final short MSG_ID_LOCATION = 0x0200;
    public static final int DEVICE_ID_LENGTH = 16;

    private Jt808CompositeFrameCodec() {
    }

    /**
     * 将车机定位实体数据编码为符合 JT/T 808 规范的复合二进制帧
     *
     * @param packet 定位数据包实体
     * @return 完整的复合报文字节数组
     */
    public static byte[] encodeLocationReport(LocationReportPacket packet) {
        if (packet == null) {
            throw new IllegalArgumentException("LocationReportPacket 实体不能为空");
        }
        return encodeLocationReport(
                packet.getDeviceId(),
                packet.getSeq() != null ? packet.getSeq() : 0L,
                packet.getLongitude() != null ? packet.getLongitude() : 0.0,
                packet.getLatitude() != null ? packet.getLatitude() : 0.0,
                packet.getSpeed() != null ? packet.getSpeed() : 0.0,
                packet.getEngineStatus()
        );
    }

    /**
     * 将车机定位数据编码为符合 JT/T 808 规范的复合二进制帧（含 0x7E 标识符、转义与 BCC 校验码）
     *
     * @param deviceId     车机硬件编号
     * @param seq          消息流水号
     * @param longitude    经度
     * @param latitude     纬度
     * @param speed        车速（km/h）
     * @param engineStatus 发动机状态（ON / OFF）
     * @return 完整的复合报文字节数组
     */
    public static byte[] encodeLocationReport(String deviceId, long seq, double longitude, double latitude, double speed, String engineStatus) {
        // 1. 组装消息体
        byte[] bodyBytes = buildLocationBody(longitude, latitude, speed, engineStatus);

        // 2. 组装消息头与消息体（未转义）
        byte[] headerBytes = buildHeader(MSG_ID_LOCATION, deviceId, seq, bodyBytes.length);
        byte[] unescaped = new byte[headerBytes.length + bodyBytes.length + 1];
        System.arraycopy(headerBytes, 0, unescaped, 0, headerBytes.length);
        System.arraycopy(bodyBytes, 0, unescaped, headerBytes.length, bodyBytes.length);

        // 3. 计算 BCC 异或校验码并置于最后一位
        byte bcc = ChecksumUtil.calculateXor(unescaped, 0, unescaped.length - 1);
        unescaped[unescaped.length - 1] = bcc;

        // 4. 对整个数据块（消息头 + 消息体 + 校验码）执行 0x7D 转义
        byte[] escaped = ChecksumUtil.escapeJt808(unescaped);

        // 5. 首尾包裹 0x7E 标识符
        byte[] frame = new byte[escaped.length + 2];
        frame[0] = DELIMITER;
        System.arraycopy(escaped, 0, frame, 1, escaped.length);
        frame[frame.length - 1] = DELIMITER;

        return frame;
    }

    /**
     * 解析接收到的 JT/T 808 复合报文，完成首尾 0x7E 校验、反转义与 BCC 校验和验证
     *
     * @param rawFrame 包含首尾 0x7E 标识符的原始报文字节数组
     * @return 解析后的定位数据包实体
     * @throws IllegalArgumentException 当帧格式非法、缺少 0x7E 或 BCC 校验失败时抛出
     */
    public static LocationReportPacket decodeLocationReport(byte[] rawFrame) {
        if (rawFrame == null || rawFrame.length < 5) {
            throw new IllegalArgumentException("JT/T 808 报文长度不足，无法解析: length=" + (rawFrame != null ? rawFrame.length : 0));
        }

        // 1. 验证并剥离首尾 0x7E 标识符
        int start = 0;
        int end = rawFrame.length;
        if (rawFrame[0] == DELIMITER) {
            start = 1;
        } else {
            throw new IllegalArgumentException("JT/T 808 报文头部缺少 0x7E 标识符");
        }
        if (rawFrame[end - 1] == DELIMITER) {
            end = end - 1;
        } else {
            throw new IllegalArgumentException("JT/T 808 报文尾部缺少 0x7E 标识符");
        }

        byte[] payloadEscaped = new byte[end - start];
        System.arraycopy(rawFrame, start, payloadEscaped, 0, payloadEscaped.length);

        // 2. 执行 0x7D 反转义还原真实字节
        byte[] unescaped = ChecksumUtil.unescapeJt808(payloadEscaped);
        int minHeaderLen = 4 + DEVICE_ID_LENGTH + 2; // 22 字节
        int minRequiredLen = minHeaderLen + 11 + 1;  // 报文头(22B) + 位置上报体(11B) + 校验位(1B) = 34 字节
        if (unescaped.length < minRequiredLen) {
            throw new IllegalArgumentException(String.format("JT/T 808 位置上报报文有效长度不足: 期望至少 %d 字节, 实际 %d 字节",
                    minRequiredLen, unescaped.length));
        }

        // 3. 校验 BCC 异或校验和
        byte expectedBcc = unescaped[unescaped.length - 1];
        boolean bccValid = ChecksumUtil.verifyXor(unescaped, 0, unescaped.length - 1, expectedBcc);
        if (!bccValid) {
            byte actualBcc = ChecksumUtil.calculateXor(unescaped, 0, unescaped.length - 1);
            throw new IllegalArgumentException(String.format("JT/T 808 BCC 校验和不匹配: 期望=0x%02X, 实际=0x%02X", expectedBcc, actualBcc));
        }

        // 4. 解析消息头 (MsgId:2B, Attr:2B, DeviceId:DEVICE_ID_LENGTH, Seq:2B)
        ByteBuffer buf = ByteBuffer.wrap(unescaped);
        short msgId = buf.getShort();
        short bodyAttr = buf.getShort();
        int bodyLength = bodyAttr & 0x03FF;

        byte[] deviceIdBytes = new byte[DEVICE_ID_LENGTH];
        buf.get(deviceIdBytes);
        String deviceId = new String(deviceIdBytes, StandardCharsets.US_ASCII).trim();

        int seq = buf.getShort() & 0xFFFF;

        // 5. 解析消息体 (经度:4B, 纬度:4B, 速度:2B, 状态:1B)
        double longitude = buf.getInt() / 1_000_000.0;
        double latitude = buf.getInt() / 1_000_000.0;
        double speed = (buf.getShort() & 0xFFFF) / 10.0;
        byte engineFlag = buf.get();
        String engineStatus = (engineFlag == 1) ? "ON" : "OFF";

        return new LocationReportPacket(
                deviceId,
                (long) seq,
                longitude,
                latitude,
                speed,
                engineStatus,
                System.currentTimeMillis()
        );
    }

    private static byte[] buildHeader(short msgId, String deviceId, long seq, int bodyLength) {
        ByteBuffer buf = ByteBuffer.allocate(6 + DEVICE_ID_LENGTH);
        buf.putShort(msgId);
        short attr = (short) (bodyLength & 0x03FF);
        buf.putShort(attr);

        // 规范化固定长度设备标识（左对齐，右侧补 0）
        byte[] devBytes = (deviceId != null ? deviceId.trim() : "").getBytes(StandardCharsets.US_ASCII);
        byte[] fixedDev = new byte[DEVICE_ID_LENGTH];
        int copyLen = Math.min(devBytes.length, DEVICE_ID_LENGTH);
        System.arraycopy(devBytes, 0, fixedDev, 0, copyLen);
        buf.put(fixedDev);

        buf.putShort((short) (seq & 0xFFFF));
        return buf.array();
    }

    private static byte[] buildLocationBody(double longitude, double latitude, double speed, String engineStatus) {
        ByteBuffer buf = ByteBuffer.allocate(11);
        buf.putInt((int) Math.round(longitude * 1_000_000));
        buf.putInt((int) Math.round(latitude * 1_000_000));
        buf.putShort((short) Math.round(speed * 10));
        byte engineFlag = "ON".equalsIgnoreCase(engineStatus) ? (byte) 1 : (byte) 0;
        buf.put(engineFlag);
        return buf.array();
    }
}
