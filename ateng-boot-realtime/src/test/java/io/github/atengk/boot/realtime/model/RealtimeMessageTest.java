package io.github.atengk.boot.realtime.model;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实时消息数据契约单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
class RealtimeMessageTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContextHolder.clear();
    }

    @Test
    void testMessageCreationWithAutoContext() {
        MDC.put("traceId", "trace-test-123");
        TenantContextHolder.setTenantId("10086");

        RealtimeMessage<Map<String, Object>> message = RealtimeMessage.of("order:created", Map.of("orderId", "OD1001", "amount", 99.5));

        assertThat(message.getId()).isNotNull().isNotBlank();
        assertThat(message.getEvent()).isEqualTo("order:created");
        assertThat(message.getData()).containsEntry("orderId", "OD1001");
        assertThat(message.getData()).containsEntry("amount", 99.5);
        assertThat(message.getTimestamp()).isPositive();
        assertThat(message.getTraceId()).isEqualTo("trace-test-123");
        assertThat(message.getTenantId()).isEqualTo("10086");
    }

    @Test
    void testMessageCreationWithoutContextFallback() {
        RealtimeMessage<String> message = RealtimeMessage.of("notice", "hello world");

        assertThat(message.getId()).isNotNull().isNotBlank();
        assertThat(message.getEvent()).isEqualTo("notice");
        assertThat(message.getData()).isEqualTo("hello world");
        assertThat(message.getTraceId()).isNotNull().isNotBlank();
        assertThat(message.getTenantId()).isNull();
    }

    @Test
    void testPingPongFactories() {
        RealtimeMessage<String> ping = RealtimeMessage.ping();
        assertThat(ping.getEvent()).isEqualTo(RealtimeMessage.EVENT_PING);
        assertThat(ping.getData()).isEqualTo("ping");

        RealtimeMessage<String> pong = RealtimeMessage.pong();
        assertThat(pong.getEvent()).isEqualTo(RealtimeMessage.EVENT_PONG);
        assertThat(pong.getData()).isEqualTo("pong");
    }

    @Test
    void testJsonSerializationNoClassPollution() {
        RealtimeMessage<String> message = RealtimeMessage.of("msg-001", "chat:text", "Welcome to ateng-boot!");
        String json = JsonUtil.toJsonString(message);

        assertThat(json).isNotNull();
        assertThat(json).contains("\"event\":\"chat:text\"");
        assertThat(json).contains("\"data\":\"Welcome to ateng-boot!\"");
        assertThat(json).doesNotContain("@class");

        RealtimeMessage<?> parsed = JsonUtil.parseObject(json, RealtimeMessage.class);
        assertThat(parsed).isNotNull();
        assertThat(parsed.getId()).isEqualTo("msg-001");
        assertThat(parsed.getEvent()).isEqualTo("chat:text");
        assertThat(parsed.getData()).isEqualTo("Welcome to ateng-boot!");
    }
}
