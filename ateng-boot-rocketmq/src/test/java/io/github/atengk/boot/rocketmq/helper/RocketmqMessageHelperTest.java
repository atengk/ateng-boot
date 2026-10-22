package io.github.atengk.boot.rocketmq.helper;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RocketmqMessageHelper 单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("RocketmqMessageHelper 单元测试")
class RocketmqMessageHelperTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("toPayloadBytes 序列化测试：null、byte[]、String、POJO")
    void testToPayloadBytes() {
        assertThat(RocketmqMessageHelper.toPayloadBytes(null)).isEmpty();

        byte[] raw = new byte[]{1, 2, 3};
        assertThat(RocketmqMessageHelper.toPayloadBytes(raw)).isSameAs(raw);

        String text = "hello-rocketmq";
        assertThat(RocketmqMessageHelper.toPayloadBytes(text)).isEqualTo(text.getBytes(StandardCharsets.UTF_8));

        TestOrder order = new TestOrder("ORD-1001", "PAID");
        byte[] jsonBytes = RocketmqMessageHelper.toPayloadBytes(order);
        assertThat(new String(jsonBytes, StandardCharsets.UTF_8)).contains("ORD-1001").contains("PAID");
    }

    @Test
    @DisplayName("resolveBizKey 测试：显式传入、反射提取、MDC 兜底与 UUID 最终兜底")
    void testResolveBizKey() {
        // 1. 显式传入优先
        assertThat(RocketmqMessageHelper.resolveBizKey("EXPLICIT-KEY", new TestOrder("ORD-1", "INIT")))
                .isEqualTo("EXPLICIT-KEY");

        // 2. 反射从载荷提取
        assertThat(RocketmqMessageHelper.resolveBizKey(null, new TestOrder("ORD-2001", "INIT")))
                .isEqualTo("ORD-2001");
        assertThat(RocketmqMessageHelper.resolveBizKey("", new TestCodeObj("CODE-888")))
                .isEqualTo("CODE-888");

        // 3. MDC traceId 兜底
        MDC.put(MessageHeaderConstants.TRACE_ID, "trace-xyz-123");
        assertThat(RocketmqMessageHelper.resolveBizKey(null, "plain-string"))
                .isEqualTo("trace-xyz-123");

        // 4. UUID 最终保底
        MDC.clear();
        String uuidKey = RocketmqMessageHelper.resolveBizKey(null, "plain-string");
        assertThat(uuidKey).isNotNull().hasSize(32);
    }

    @Test
    @DisplayName("GZIP 压缩与解压测试")
    void testCompressAndDecompressGzip() throws IOException {
        assertThat(RocketmqMessageHelper.compressGzip(null)).isNull();
        assertThat(RocketmqMessageHelper.decompressGzip(null)).isNull();
        assertThat(RocketmqMessageHelper.compressGzip(new byte[0])).isEmpty();
        assertThat(RocketmqMessageHelper.decompressGzip(new byte[0])).isEmpty();

        String rawText = "RocketMQ 5.x gRPC production hardening transparent compression test data! ".repeat(20);
        byte[] original = rawText.getBytes(StandardCharsets.UTF_8);

        byte[] compressed = RocketmqMessageHelper.compressGzip(original);
        assertThat(compressed.length).isLessThan(original.length);

        byte[] decompressed = RocketmqMessageHelper.decompressGzip(compressed);
        assertThat(new String(decompressed, StandardCharsets.UTF_8)).isEqualTo(rawText);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class TestOrder {
        private String orderNo;
        private String status;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class TestCodeObj {
        private String code;
    }
}
