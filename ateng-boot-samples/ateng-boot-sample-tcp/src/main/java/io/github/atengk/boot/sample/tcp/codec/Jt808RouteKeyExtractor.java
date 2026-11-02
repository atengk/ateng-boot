package io.github.atengk.boot.sample.tcp.codec;

import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.route.JsonRouteKeyExtractor;
import io.github.atengk.boot.tcp.route.TcpRouteKeyExtractor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 交通部 JT/T 808 国标风格二进制复合报文与双轨 JSON 路由命令字提取器
 * <p>
 * 遵循框架可插拔路由 SPI 契约，优先探测报文首字节是否为国标 {@code 0x7E} 标识符，
 * 若匹配则根据消息 ID 提取国标业务命令字（如 {@code "JT808_LOCATION"}）；
 * 若非国标报文，则兜底委托给 {@link JsonRouteKeyExtractor} 完成标准 JSON 业务命令字解析。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class Jt808RouteKeyExtractor implements TcpRouteKeyExtractor {

    private final JsonRouteKeyExtractor jsonExtractor = new JsonRouteKeyExtractor();

    @Override
    public String extract(TcpPacket packet) {
        if (packet == null || packet.getPayload() == null) {
            return null;
        }
        byte[] bytes = packet.getPayload().asBytes();
        if (bytes != null && bytes.length > 3 && bytes[0] == Jt808CompositeFrameCodec.DELIMITER) {
            // 国标二进制复合帧：0x7E 开头，第 1、2 字节为消息 ID（大端序）
            int msgId = ((bytes[1] & 0xFF) << 8) | (bytes[2] & 0xFF);
            if (msgId == (Jt808CompositeFrameCodec.MSG_ID_LOCATION & 0xFFFF)) {
                return "JT808_LOCATION";
            }
            return String.format("JT808_0x%04X", msgId);
        }
        // 兜底委托给标准 JSON 路由命令字提取器
        return jsonExtractor.extract(packet);
    }
}
