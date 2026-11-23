package io.github.atengk.boot.sample.tcp.codec;

import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.route.JsonRouteKeyExtractor;
import io.github.atengk.boot.tcp.route.TcpRouteKeyExtractor;
import io.github.atengk.boot.tcp.util.ChecksumUtil;
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
            // 国标二进制复合帧：0x7E 开头，先提取帧内头部数据执行反转义，防御 0x7D 转义字符造成的偏移
            int probeLen = Math.min(bytes.length - 1, 8);
            byte[] headerProbe = new byte[probeLen];
            System.arraycopy(bytes, 1, headerProbe, 0, probeLen);
            byte[] unescaped = ChecksumUtil.unescapeJt808(headerProbe);
            if (unescaped.length >= 2) {
                int msgId = ((unescaped[0] & 0xFF) << 8) | (unescaped[1] & 0xFF);
                if (msgId == (Jt808CompositeFrameCodec.MSG_ID_LOCATION & 0xFFFF)) {
                    return "JT808_LOCATION";
                }
                return String.format("JT808_0x%04X", msgId);
            }
        }
        // 兜底委托给标准 JSON 路由命令字提取器
        return jsonExtractor.extract(packet);
    }
}
