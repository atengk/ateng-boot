package io.github.atengk.boot.core.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨中间件标准消息头常量单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("MessageHeaderConstants 单元测试")
class MessageHeaderConstantsTest {

    @Test
    @DisplayName("验证常量定义与命名规范")
    void testConstants() {
        assertEquals("X-Trace-Id", MessageHeaderConstants.TRACE_ID);
        assertEquals("X-Tenant-Id", MessageHeaderConstants.TENANT_ID);
        assertEquals("X-User-Id", MessageHeaderConstants.USER_ID);
        assertEquals("X-Ignore-Tenant", MessageHeaderConstants.IGNORE_TENANT);
        assertEquals("X-Send-Timestamp", MessageHeaderConstants.SEND_TIMESTAMP);
        assertEquals("X-Biz-Key", MessageHeaderConstants.BIZ_KEY);
    }

    @Test
    @DisplayName("私有构造函数反射防卫覆盖")
    void testPrivateConstructor() throws Exception {
        Constructor<MessageHeaderConstants> constructor = MessageHeaderConstants.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        assertNotNull(constructor.newInstance());
    }
}
