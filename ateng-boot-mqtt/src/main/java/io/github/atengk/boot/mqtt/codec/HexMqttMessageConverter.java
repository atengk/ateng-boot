package io.github.atengk.boot.mqtt.codec;

import org.eclipse.paho.mqttv5.common.MqttMessage;

import java.util.HexFormat;

/**
 * 工业十六进制 HEX MQTT 消息转换器
 * 支持十六进制载荷与字节数组的高性能原生转译
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class HexMqttMessageConverter implements MqttMessageConverter {

    private static final HexFormat HEX_FORMAT = HexFormat.of();

    @Override
    public boolean supports(Class<?> targetType) {
        return HexPayload.class.isAssignableFrom(targetType);
    }

    @Override
    public Object fromMessage(MqttMessage message, Class<?> targetType) {
        byte[] payload = message != null && message.getPayload() != null ? message.getPayload() : new byte[0];
        return new HexPayload(payload);
    }

    @Override
    public byte[] toPayload(Object payload) {
        if (payload == null) {
            return new byte[0];
        }
        if (payload instanceof HexPayload hexPayload) {
            return hexPayload.getBytes();
        }
        if (payload instanceof String str) {
            return hexToBytes(str);
        }
        return new byte[0];
    }

    /**
     * 将字节数组转为标准小写无前缀十六进制字符串
     *
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        return HEX_FORMAT.formatHex(bytes);
    }

    /**
     * 将十六进制字符串转为字节数组
     *
     * @param hexStr 十六进制字符串（支持含空格或无空格）
     * @return 字节数组
     */
    public static byte[] hexToBytes(String hexStr) {
        if (hexStr == null || hexStr.isBlank()) {
            return new byte[0];
        }
        String cleanHex = hexStr.replaceAll("\\s+", "");
        if (cleanHex.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string length must be even: " + hexStr);
        }
        return HEX_FORMAT.parseHex(cleanHex);
    }
}
