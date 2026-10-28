package io.github.atengk.boot.tcp.route;

import io.github.atengk.boot.tcp.model.TcpPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由命令字提取器单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("TCP 路由命令字提取器单元测试")
class TcpRouteKeyExtractorTest {

    @Test
    @DisplayName("测试 JsonRouteKeyExtractor 默认提取策略")
    void testJsonExtractorDefault() {
        JsonRouteKeyExtractor extractor = new JsonRouteKeyExtractor();

        // 1. command 字段
        TcpPacket p1 = TcpPacket.ofJson("{\"command\":\"ORDER_SUBMIT\",\"id\":123}");
        assertThat(extractor.extract(p1)).isEqualTo("ORDER_SUBMIT");

        // 2. action 字段
        TcpPacket p2 = TcpPacket.ofJson("{\"action\":\"DEVICE_LOGIN\",\"sn\":\"SN-001\"}");
        assertThat(extractor.extract(p2)).isEqualTo("DEVICE_LOGIN");

        // 3. cmd 字段
        TcpPacket p3 = TcpPacket.ofJson("{\"cmd\":\"QUERY_INFO\"}");
        assertThat(extractor.extract(p3)).isEqualTo("QUERY_INFO");

        // 4. 报文本身已有 command 时直接返回
        TcpPacket p4 = TcpPacket.ofJson("CUSTOM_CMD", "{\"cmd\":\"IGNORED\"}");
        assertThat(extractor.extract(p4)).isEqualTo("CUSTOM_CMD");

        // 5. 非 JSON 字符串返回 null
        TcpPacket p5 = TcpPacket.ofText("PLAIN TEXT DATA");
        assertThat(extractor.extract(p5)).isNull();
    }

    @Test
    @DisplayName("测试 JsonRouteKeyExtractor 自定义属性名")
    void testJsonExtractorCustomProperty() {
        JsonRouteKeyExtractor extractor = new JsonRouteKeyExtractor(List.of("event", "serviceCode"));

        TcpPacket packet = TcpPacket.ofJson("{\"event\":\"USER_REGISTERED\",\"code\":100}");
        assertThat(extractor.extract(packet)).isEqualTo("USER_REGISTERED");
    }

    @Test
    @DisplayName("测试 BinaryOffsetRouteKeyExtractor 十六进制模式提取")
    void testBinaryExtractorHex() {
        // 模拟报文：帧头 2字节 0xAA 0x55，功能码 2字节 0x01 0x02，数据 0x99
        byte[] data = new byte[]{(byte) 0xAA, 0x55, 0x01, 0x02, (byte) 0x99};
        TcpPacket packet = TcpPacket.ofBytes(data);

        // 偏移量 2，长度 2，提取 HEX
        BinaryOffsetRouteKeyExtractor hexExtractor = new BinaryOffsetRouteKeyExtractor(2, 2, BinaryOffsetRouteKeyExtractor.Format.HEX);
        assertThat(hexExtractor.extract(packet)).isEqualTo("0102");

        // 提取带 0x 前缀
        BinaryOffsetRouteKeyExtractor prefixExtractor = new BinaryOffsetRouteKeyExtractor(2, 2, BinaryOffsetRouteKeyExtractor.Format.HEX_PREFIX);
        assertThat(prefixExtractor.extract(packet)).isEqualTo("0x0102");
    }

    @Test
    @DisplayName("测试 BinaryOffsetRouteKeyExtractor 整数与 ASCII 模式提取")
    void testBinaryExtractorIntegerAndAscii() {
        // 报文：前 4 字节为 ASCII 命令 "PING"，后接数据
        byte[] asciiData = "PING123456".getBytes(StandardCharsets.US_ASCII);
        TcpPacket p1 = TcpPacket.ofBytes(asciiData);

        BinaryOffsetRouteKeyExtractor asciiExtractor = new BinaryOffsetRouteKeyExtractor(0, 4, BinaryOffsetRouteKeyExtractor.Format.ASCII);
        assertThat(asciiExtractor.extract(p1)).isEqualTo("PING");

        // 整数格式：偏移量 1，长度 2，0x00 0x2A -> 42
        byte[] intData = new byte[]{0x00, 0x00, 0x2A, 0x00};
        TcpPacket p2 = TcpPacket.ofBytes(intData);
        BinaryOffsetRouteKeyExtractor intExtractor = new BinaryOffsetRouteKeyExtractor(1, 2, BinaryOffsetRouteKeyExtractor.Format.INTEGER);
        assertThat(intExtractor.extract(p2)).isEqualTo("42");
    }

    @Test
    @DisplayName("测试 BinaryOffsetRouteKeyExtractor 越界防卫")
    void testBinaryExtractorOutOfBounds() {
        byte[] shortData = new byte[]{0x01};
        TcpPacket packet = TcpPacket.ofBytes(shortData);

        BinaryOffsetRouteKeyExtractor extractor = new BinaryOffsetRouteKeyExtractor(1, 2);
        assertThat(extractor.extract(packet)).isNull();
    }

    @Test
    @DisplayName("测试 CompositeRouteKeyExtractor 责任链组合回退")
    void testCompositeExtractor() {
        CompositeRouteKeyExtractor composite = new CompositeRouteKeyExtractor();
        composite.addExtractor(new JsonRouteKeyExtractor("action"));
        composite.addExtractor(new BinaryOffsetRouteKeyExtractor(0, 4, BinaryOffsetRouteKeyExtractor.Format.ASCII));

        // 1. JSON 报文
        TcpPacket p1 = TcpPacket.ofJson("{\"action\":\"JSON_ACTION\"}");
        assertThat(composite.extract(p1)).isEqualTo("JSON_ACTION");

        // 2. 二进制 ASCII 报文
        TcpPacket p2 = TcpPacket.ofBytes("ECHO_DATA".getBytes(StandardCharsets.US_ASCII));
        assertThat(composite.extract(p2)).isEqualTo("ECHO");
    }
}
