package io.github.atengk.boot.core.context;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨中间件消息四维上下文传播器单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("MessageContextPropagator 单元测试")
class MessageContextPropagatorTest {

    @BeforeEach
    @AfterEach
    void cleanup() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("inject 正常注入完整四维上下文且全量归一化为 UTF-8 String")
    void testInjectFullContext() {
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "trace-12345");
        UserContextHolder.set("user-999");
        TenantContextHolder.setTenantId("tenant-888");
        TenantContextHolder.setIgnoreTenant(true);

        Map<String, Object> headers = new HashMap<>();
        MessageContextPropagator.inject(headers, "biz-order-001");

        assertEquals("trace-12345", headers.get(MessageHeaderConstants.TRACE_ID));
        assertEquals("user-999", headers.get(MessageHeaderConstants.USER_ID));
        assertEquals("tenant-888", headers.get(MessageHeaderConstants.TENANT_ID));
        assertEquals("true", headers.get(MessageHeaderConstants.IGNORE_TENANT));
        assertEquals("biz-order-001", headers.get(MessageHeaderConstants.BIZ_KEY));
        assertNotNull(headers.get(MessageHeaderConstants.SEND_TIMESTAMP));

        // 验证全量归一化为 String 类型
        headers.forEach((k, v) -> assertInstanceOf(String.class, v, "键 " + k + " 的值必须为 String"));
    }

    @Test
    @DisplayName("inject 空上下文与缺省 bizKey 三级自适应保底兜底")
    void testInjectEmptyContextFallback() {
        Map<String, Object> headers = new HashMap<>();
        MessageContextPropagator.inject(headers);

        String traceId = (String) headers.get(MessageHeaderConstants.TRACE_ID);
        assertNotNull(traceId, "无 traceId 时必须自适应生成");
        assertFalse(traceId.isBlank());

        // 无 bizKey 时必须自动回退至 traceId，保障 100% 非空
        assertEquals(traceId, headers.get(MessageHeaderConstants.BIZ_KEY));
        assertNull(headers.get(MessageHeaderConstants.USER_ID));
        assertNull(headers.get(MessageHeaderConstants.TENANT_ID));
        assertNull(headers.get(MessageHeaderConstants.IGNORE_TENANT));
        assertNotNull(headers.get(MessageHeaderConstants.SEND_TIMESTAMP));
    }

    @Test
    @DisplayName("activate 提取上下文并在 close 时绝对恢复调用前现场")
    void testActivateAndCloseRestoration() {
        // 设置调用前的初始现场
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "original-trace");
        UserContextHolder.set("original-user");
        TenantContextHolder.setTenantId("original-tenant");

        Map<String, Object> inboundHeaders = new HashMap<>();
        inboundHeaders.put(MessageHeaderConstants.TRACE_ID, "new-trace-999");
        inboundHeaders.put(MessageHeaderConstants.USER_ID, "new-user-888");
        inboundHeaders.put(MessageHeaderConstants.TENANT_ID, "new-tenant-777");
        inboundHeaders.put(MessageHeaderConstants.IGNORE_TENANT, "true");

        try (MessageContextPropagator.ContextScope scope = MessageContextPropagator.activate(inboundHeaders)) {
            // 验证激活态现场
            assertEquals("new-trace-999", MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY));
            assertEquals("new-user-888", UserContextHolder.get());
            assertEquals("new-tenant-777", TenantContextHolder.getTenantId());
            assertTrue(TenantContextHolder.isIgnoreTenant());
        }

        // 验证 close 后现场绝对恢复
        assertEquals("original-trace", MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY));
        assertEquals("original-user", UserContextHolder.get());
        assertEquals("original-tenant", TenantContextHolder.getTenantId());
        assertFalse(TenantContextHolder.isIgnoreTenant());
    }

    @Test
    @DisplayName("runInContext 闭包安全执行与现场清理")
    void testRunInContext() {
        Map<String, String> headers = Map.of(
                MessageHeaderConstants.TRACE_ID, "closure-trace",
                MessageHeaderConstants.USER_ID, "closure-user"
        );

        AtomicBoolean executed = new AtomicBoolean(false);
        MessageContextPropagator.runInContext(headers, () -> {
            assertEquals("closure-trace", MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY));
            assertEquals("closure-user", UserContextHolder.get());
            executed.set(true);
        });

        assertTrue(executed.get());
        assertNull(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY));
        assertNull(UserContextHolder.get());
    }

    @Test
    @DisplayName("callInContext 闭包安全执行返回值与现场清理")
    void testCallInContext() throws Exception {
        Map<String, String> headers = Map.of(
                MessageHeaderConstants.TENANT_ID, "tenant-closure-123"
        );

        String result = MessageContextPropagator.callInContext(headers, () -> {
            assertEquals("tenant-closure-123", TenantContextHolder.getTenantId());
            return "SUCCESS";
        });

        assertEquals("SUCCESS", result);
        assertNull(TenantContextHolder.getTenantId());
    }

    @Test
    @DisplayName("extractString 兼容 String, byte[], Object 等异构数据源")
    void testExtractString() {
        Map<String, Object> map = new HashMap<>();
        map.put("strKey", "hello");
        map.put("bytesKey", "world".getBytes(StandardCharsets.UTF_8));
        map.put("numKey", 12345);
        map.put("nullKey", null);

        assertEquals("hello", MessageContextPropagator.extractString(map, "strKey"));
        assertEquals("world", MessageContextPropagator.extractString(map, "bytesKey"));
        assertEquals("12345", MessageContextPropagator.extractString(map, "numKey"));
        assertNull(MessageContextPropagator.extractString(map, "nullKey"));
        assertNull(MessageContextPropagator.extractString(map, "nonExistentKey"));
        assertNull(MessageContextPropagator.extractString(null, "strKey"));
    }
}
