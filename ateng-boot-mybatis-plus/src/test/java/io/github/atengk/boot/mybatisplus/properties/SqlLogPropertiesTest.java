package io.github.atengk.boot.mybatisplus.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL 日志打印配置属性单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("SQL 日志打印配置属性单元测试")
class SqlLogPropertiesTest {

    @Test
    @DisplayName("默认值校验：生产防灾默认关闭 (enabled=false)，默认 INFO 级别与 4KB 截断保护")
    void shouldHaveSensibleDefaults() {
        SqlLogProperties properties = new SqlLogProperties();
        assertFalse(properties.isEnabled(), "SQL 日志打印默认必须保持关闭状态防灾");
        assertEquals("INFO", properties.getLevel(), "默认日志级别应为 INFO");
        assertTrue(properties.isFormatSql(), "默认应启用 SQL 格式化美化");
        assertTrue(properties.isMaskSensitive(), "默认应启用敏感字段脱敏掩码");
        assertTrue(properties.isShowCallLocation(), "默认应输出代码调用位置");
        assertEquals(4096, properties.getMaxSqlLength(), "默认最大 SQL 长度应为 4096 字符");
    }

    @Test
    @DisplayName("自定义 Setter/Getter 校验与根配置映射")
    void shouldSupportCustomProperties() {
        SqlLogProperties properties = new SqlLogProperties();
        properties.setEnabled(true);
        properties.setLevel("DEBUG");
        properties.setFormatSql(false);
        properties.setMaskSensitive(false);
        properties.setShowCallLocation(false);
        properties.setMaxSqlLength(2048);

        assertTrue(properties.isEnabled());
        assertEquals("DEBUG", properties.getLevel());
        assertFalse(properties.isFormatSql());
        assertFalse(properties.isMaskSensitive());
        assertFalse(properties.isShowCallLocation());
        assertEquals(2048, properties.getMaxSqlLength());

        MybatisPlusProperties mpProps = new MybatisPlusProperties();
        assertNotNull(mpProps.getSqlLog());
        assertFalse(mpProps.getSqlLog().isEnabled());

        mpProps.setSqlLog(properties);
        assertTrue(mpProps.getSqlLog().isEnabled());
        assertEquals("DEBUG", mpProps.getSqlLog().getLevel());
    }
}
