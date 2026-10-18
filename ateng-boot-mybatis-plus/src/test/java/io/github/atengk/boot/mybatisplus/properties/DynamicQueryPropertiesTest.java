package io.github.atengk.boot.mybatisplus.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态查询配置属性单元测试
 *
 * @author Ateng
 * @since 2026-10-19
 */
@DisplayName("动态查询配置属性单元测试")
class DynamicQueryPropertiesTest {

    @Test
    @DisplayName("默认值校验：默认开启动态查询引擎 (enabled=true)")
    void shouldHaveSensibleDefaults() {
        DynamicQueryProperties properties = new DynamicQueryProperties();
        assertTrue(properties.isEnabled(), "动态查询转译引擎默认应保持开启状态");
    }

    @Test
    @DisplayName("自定义 Setter/Getter 校验")
    void shouldSupportCustomProperties() {
        DynamicQueryProperties properties = new DynamicQueryProperties();
        properties.setEnabled(false);
        assertFalse(properties.isEnabled());

        MybatisPlusProperties mpProps = new MybatisPlusProperties();
        assertNotNull(mpProps.getDynamicQuery());
        assertTrue(mpProps.getDynamicQuery().isEnabled());

        mpProps.setDynamicQuery(properties);
        assertFalse(mpProps.getDynamicQuery().isEnabled());
    }
}
