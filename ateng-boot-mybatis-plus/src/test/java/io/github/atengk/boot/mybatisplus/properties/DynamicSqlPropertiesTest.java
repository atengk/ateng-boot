package io.github.atengk.boot.mybatisplus.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 外部动态 SQL 配置属性单元测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("外部动态 SQL 配置属性单元测试")
class DynamicSqlPropertiesTest {

    @Test
    @DisplayName("验证默认配置属性值契约")
    void testDefaultValues() {
        DynamicSqlProperties properties = new DynamicSqlProperties();
        assertFalse(properties.isEnabled(), "默认状态下动态 SQL 引擎应当保持关闭 (enabled=false)");
        assertTrue(properties.isAutoScriptWrap(), "默认状态下应当开启 <script> 自动容错包裹 (autoScriptWrap=true)");
        assertNotNull(properties.getLocations(), "locations 列表不应为 null");
        assertEquals(1, properties.getLocations().size(), "默认应包含 1 个扫描路径");
        assertEquals("classpath*:dynamic-sql/**/*.xml", properties.getLocations().get(0));
    }

    @Test
    @DisplayName("验证配置属性自定义 Setter 与 Getter")
    void testCustomProperties() {
        DynamicSqlProperties properties = new DynamicSqlProperties();
        properties.setEnabled(true);
        properties.setAutoScriptWrap(false);
        properties.setLocations(List.of("classpath*:custom-sql/*.xml", "file:/etc/sql/*.xml"));

        assertTrue(properties.isEnabled());
        assertFalse(properties.isAutoScriptWrap());
        assertEquals(2, properties.getLocations().size());
        assertEquals("file:/etc/sql/*.xml", properties.getLocations().get(1));
    }
}
