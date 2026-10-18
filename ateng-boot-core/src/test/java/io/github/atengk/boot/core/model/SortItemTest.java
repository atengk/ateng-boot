package io.github.atengk.boot.core.model;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.ValidatorUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SortItem 单元测试
 * 覆盖强类型排序模型、空安全、大小写容错、Direction 解析以及 SQL 注入对抗性防护
 *
 * @author Ateng
 * @since 2026-10-19
 */
@DisplayName("SortItem 排序项模型单元测试")
class SortItemTest {

    @Test
    @DisplayName("默认构造方法：属性为 null，默认方向为 ASC")
    void shouldCreateDefaultSortItem() {
        SortItem sortItem = new SortItem();
        assertNull(sortItem.getProperty());
        assertEquals(SortItem.Direction.ASC, sortItem.getDirection());
        assertTrue(sortItem.isAsc());
    }

    @Test
    @DisplayName("构造方法与静态工厂方法：asc, desc, of")
    void shouldConstructSortItemsViaFactoryMethods() {
        SortItem ascItem = SortItem.asc("createTime");
        assertEquals("createTime", ascItem.getProperty());
        assertEquals(SortItem.Direction.ASC, ascItem.getDirection());
        assertTrue(ascItem.isAsc());
        assertTrue(ascItem.getDirection().isAscending());
        assertFalse(ascItem.getDirection().isDescending());

        SortItem descItem = SortItem.desc("id");
        assertEquals("id", descItem.getProperty());
        assertEquals(SortItem.Direction.DESC, descItem.getDirection());
        assertFalse(descItem.isAsc());
        assertFalse(descItem.getDirection().isAscending());
        assertTrue(descItem.getDirection().isDescending());

        SortItem boolItem = SortItem.of("name", false);
        assertEquals("name", boolItem.getProperty());
        assertEquals(SortItem.Direction.DESC, boolItem.getDirection());

        SortItem directItem = SortItem.of("code", SortItem.Direction.ASC);
        assertEquals("code", directItem.getProperty());
        assertEquals(SortItem.Direction.ASC, directItem.getDirection());
    }

    @Test
    @DisplayName("空安全与前后空格修剪：trim 正常属性")
    void shouldTrimPropertyNames() {
        SortItem sortItem = SortItem.asc("  updated_time  ");
        assertEquals("updated_time", sortItem.getProperty());
    }

    @Test
    @DisplayName("Direction.fromString: 大小写容错与非法值拦截")
    void shouldParseDirectionFromString() {
        assertEquals(SortItem.Direction.ASC, SortItem.Direction.fromString("asc"));
        assertEquals(SortItem.Direction.ASC, SortItem.Direction.fromString("ASC"));
        assertEquals(SortItem.Direction.ASC, SortItem.Direction.fromString(" Asc "));
        assertEquals(SortItem.Direction.DESC, SortItem.Direction.fromString("desc"));
        assertEquals(SortItem.Direction.DESC, SortItem.Direction.fromString("DESC"));
        assertEquals(SortItem.Direction.DESC, SortItem.Direction.fromString(" Desc "));

        // null 或空字符串保底降级为 ASC
        assertEquals(SortItem.Direction.ASC, SortItem.Direction.fromString(null));
        assertEquals(SortItem.Direction.ASC, SortItem.Direction.fromString(""));
        assertEquals(SortItem.Direction.ASC, SortItem.Direction.fromString("   "));

        // 非法值抛出异常
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SortItem.Direction.fromString("unknown"));
        assertTrue(ex.getMessage().contains("未知的排序方向"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "userName", "user_name", "createTime", "status1", "a_b_c_123"})
    @DisplayName("合法属性名称校验通过")
    void shouldAcceptValidProperties(String property) {
        assertTrue(SortItem.isValidProperty(property));
        SortItem sortItem = SortItem.asc(property);
        assertEquals(property, sortItem.getProperty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "user name",             // 包含空格
            "id; DROP TABLE user;",  // 包含分号与 SQL 注入命令
            "name' OR '1'='1",       // 包含单引号注入
            "createTime--",          // 包含注释符
            "user.name",             // 跨表点号注入探测
            "func()",                // 函数括号探测
            "id/*comment*/",         // 多行注释注入
            "name\\x00"              // 非法特殊字符
    })
    @DisplayName("对抗性用例：非法字符与 SQL 注入探测必须抛出 IllegalArgumentException")
    void shouldRejectIllegalPropertyNames(String illegalProperty) {
        assertFalse(SortItem.isValidProperty(illegalProperty));

        assertThrows(IllegalArgumentException.class, () -> SortItem.asc(illegalProperty));
        assertThrows(IllegalArgumentException.class, () -> SortItem.desc(illegalProperty));
        assertThrows(IllegalArgumentException.class, () -> SortItem.of(illegalProperty, true));

        SortItem sortItem = new SortItem();
        assertThrows(IllegalArgumentException.class, () -> sortItem.setProperty(illegalProperty));
    }

    @Test
    @DisplayName("静态工厂 null 入参防御：抛出 NullPointerException")
    void shouldThrowNpeWhenPropertyIsNullInFactory() {
        assertThrows(NullPointerException.class, () -> SortItem.asc(null));
        assertThrows(NullPointerException.class, () -> SortItem.desc(null));
        assertThrows(NullPointerException.class, () -> SortItem.of(null, true));
    }

    @Test
    @DisplayName("JSR-303 格式防卫：空属性名称拦截")
    void shouldValidateViaJsr303() {
        // 合法对象校验通过
        ValidatorUtil.validate(new SortItem("validProp", SortItem.Direction.ASC));

        // 空属性名称校验拦截
        BusinessException ex = assertThrows(BusinessException.class,
                () -> ValidatorUtil.validate(new SortItem()));
        assertTrue(ex.getMessage().contains("排序属性名称不能为空"));
    }

    @Test
    @DisplayName("equals 与 hashCode 一致性契约")
    void shouldVerifyEqualsAndHashCode() {
        SortItem item1 = SortItem.asc("age");
        SortItem item2 = SortItem.asc("age");
        SortItem item3 = SortItem.desc("age");

        assertEquals(item1, item2);
        assertEquals(item1.hashCode(), item2.hashCode());
        assertNotEquals(item1, item3);
        assertNotNull(item1.toString());
    }
}
