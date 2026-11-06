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
        assertTrue(properties.isConsoleEnabled(), "默认应在控制台输出诊断看板");
        assertEquals("INFO", properties.getLevel(), "默认日志级别应为 INFO");
        assertTrue(properties.isFormatSql(), "默认应启用 SQL 格式化美化");
        assertTrue(properties.isMaskSensitive(), "默认应启用敏感字段脱敏掩码");
        assertTrue(properties.isShowCallLocation(), "默认应输出代码调用位置");
        assertEquals(4096, properties.getMaxSqlLength(), "默认最大 SQL 长度应为 4096 字符");
        assertNotNull(properties.getExcludeStatements(), "默认排除语句清单非空");
        assertTrue(properties.getExcludeStatements().isEmpty(), "默认排除语句清单为空");

        assertNotNull(properties.getStorage(), "默认存储配置非空");
        assertFalse(properties.getStorage().isEnabled(), "默认 SPI 存储应关闭");
        assertFalse(properties.getStorage().isIncludeSelect(), "默认只读 SELECT 不纳入审计存储");
        assertTrue(properties.getStorage().isAsync(), "默认应开启受管异步派发");
        assertEquals(8192, properties.getStorage().getMaxSqlLength(), "存储默认最大 SQL 长度应为 8192 字符");
    }

    @Test
    @DisplayName("自定义 Setter/Getter 校验与根配置映射")
    void shouldSupportCustomProperties() {
        SqlLogProperties properties = new SqlLogProperties();
        properties.setEnabled(true);
        properties.setConsoleEnabled(false);
        properties.setLevel("DEBUG");
        properties.setFormatSql(false);
        properties.setMaskSensitive(false);
        properties.setShowCallLocation(false);
        properties.setMaxSqlLength(2048);
        properties.setExcludeStatements(java.util.List.of("*.selectHeartbeat"));

        SqlLogProperties.StorageProperties storage = new SqlLogProperties.StorageProperties();
        storage.setEnabled(true);
        storage.setIncludeSelect(true);
        storage.setAsync(false);
        storage.setMaxSqlLength(16384);
        properties.setStorage(storage);

        assertTrue(properties.isEnabled());
        assertFalse(properties.isConsoleEnabled());
        assertEquals("DEBUG", properties.getLevel());
        assertFalse(properties.isFormatSql());
        assertFalse(properties.isMaskSensitive());
        assertFalse(properties.isShowCallLocation());
        assertEquals(2048, properties.getMaxSqlLength());
        assertEquals(1, properties.getExcludeStatements().size());
        assertEquals("*.selectHeartbeat", properties.getExcludeStatements().get(0));

        assertTrue(properties.getStorage().isEnabled());
        assertTrue(properties.getStorage().isIncludeSelect());
        assertFalse(properties.getStorage().isAsync());
        assertEquals(16384, properties.getStorage().getMaxSqlLength());

        MybatisPlusProperties mpProps = new MybatisPlusProperties();
        assertNotNull(mpProps.getSqlLog());
        assertFalse(mpProps.getSqlLog().isEnabled());

        mpProps.setSqlLog(properties);
        assertTrue(mpProps.getSqlLog().isEnabled());
        assertEquals("DEBUG", mpProps.getSqlLog().getLevel());
    }
}
