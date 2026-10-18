package io.github.atengk.boot.mybatisplus.util;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.SortItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PageUtil 单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("MyBatis-Plus 分页模型与传输契约转换单测")
class PageUtilTest {

    @Test
    @DisplayName("toPage: 正常入参转换")
    void shouldConvertPageQueryToPageCorrectly() {
        PageQuery query = new PageQuery(2L, 20L);
        Page<Object> page = PageUtil.toPage(query);

        assertEquals(2L, page.getCurrent());
        assertEquals(20L, page.getSize());
    }

    @Test
    @DisplayName("toPage: null 入参防御降级为第 1 页每页 10 条")
    void shouldDefaultPageWhenQueryIsNull() {
        Page<Object> page = PageUtil.toPage(null);

        assertEquals(1L, page.getCurrent());
        assertEquals(10L, page.getSize());
    }

    @Test
    @DisplayName("toPage: 非正整数参数防御性修正为默认第 1 页每页 10 条")
    void shouldNormalizeInvalidPageParameters() {
        PageQuery invalidQuery = new PageQuery(-5L, 0L);
        Page<Object> page = PageUtil.toPage(invalidQuery);

        assertEquals(1L, page.getCurrent());
        assertEquals(10L, page.getSize());
    }

    @Test
    @DisplayName("toPage: 带 searchCount 参数转换")
    void shouldConvertPageQueryWithSearchCount() {
        PageQuery query = new PageQuery(3L, 15L);
        Page<Object> page = PageUtil.toPage(query, false);

        assertEquals(3L, page.getCurrent());
        assertEquals(15L, page.getSize());
        assertFalse(page.searchCount());

        Page<Object> nullQueryPage = PageUtil.toPage(null, true);
        assertEquals(1L, nullQueryPage.getCurrent());
        assertEquals(10L, nullQueryPage.getSize());
        assertTrue(nullQueryPage.searchCount());
    }

    @Test
    @DisplayName("toPageResult: 将 IPage 转换为标准 PageResult")
    void shouldConvertIPageToPageResult() {
        Page<String> mpPage = new Page<>(1L, 10L, 25L);
        mpPage.setRecords(Arrays.asList("A", "B", "C"));

        PageResult<String> result = PageUtil.toPageResult(mpPage);

        assertEquals(Arrays.asList("A", "B", "C"), result.getRecords());
        assertEquals(25L, result.getTotal());
        assertEquals(1L, result.getCurrent());
        assertEquals(10L, result.getSize());
        assertEquals(3L, result.getPages());
    }

    @Test
    @DisplayName("toPageResult: null 入参防御性返回 empty PageResult")
    void shouldReturnEmptyPageResultWhenPageIsNull() {
        PageResult<String> result = PageUtil.toPageResult((IPage<String>) null);

        assertNotNull(result);
        assertTrue(result.getRecords().isEmpty());
        assertEquals(0L, result.getTotal());
    }

    @Test
    @DisplayName("toPageResult 带映射转换：将 IPage 实体流式转换为 VO")
    void shouldConvertIPageAndMapToVO() {
        Page<Integer> mpPage = new Page<>(2L, 5L, 12L);
        mpPage.setRecords(Arrays.asList(10, 20));

        PageResult<String> result = PageUtil.toPageResult(mpPage, num -> "Code-" + num);

        assertEquals(Arrays.asList("Code-10", "Code-20"), result.getRecords());
        assertEquals(12L, result.getTotal());
        assertEquals(2L, result.getCurrent());
        assertEquals(5L, result.getSize());
        assertEquals(3L, result.getPages());
    }

    @Test
    @DisplayName("toPageResult 带映射转换：null 页面防御返回 empty，null 转换器防御报错")
    void shouldHandleDefensiveNullsWithMapper() {
        PageResult<String> empty = PageUtil.toPageResult(null, num -> "val");
        assertNotNull(empty);
        assertTrue(empty.getRecords().isEmpty());

        Page<String> mpPage = new Page<>(1, 10);
        assertThrows(NullPointerException.class, () -> PageUtil.toPageResult(mpPage, null));
    }

    @Test
    @DisplayName("toPage: 超大 pageSize 自动截断为 DEFAULT_MAX_LIMIT (500) 防御 OOM")
    void shouldTruncateExcessivePageSize() {
        PageQuery query = new PageQuery();
        query.setCurrent(1L);
        query.setSize(1000000L); // 恶意超大页容量

        Page<?> page = PageUtil.toPage(query);
        assertEquals(PageUtil.DEFAULT_MAX_LIMIT, page.getSize());

        // 自定义 maxLimit 测试
        Page<?> customPage = PageUtil.toPage(query, true, 200L);
        assertEquals(200L, customPage.getSize());
    }

    static class TestUserEntity extends io.github.atengk.boot.mybatisplus.entity.BaseIdEntity {
        @com.baomidou.mybatisplus.annotation.TableField("nick_name")
        private String nickName;

        @com.baomidou.mybatisplus.annotation.TableField("user_age")
        private Integer userAge;

        public String getNickName() {
            return nickName;
        }

        public void setNickName(String nickName) {
            this.nickName = nickName;
        }

        public Integer getUserAge() {
            return userAge;
        }

        public void setUserAge(Integer userAge) {
            this.userAge = userAge;
        }
    }

    interface TestUserMapper {
    }

    @Test
    @DisplayName("toPage(query): 未指定实体类型时按驼峰下划线规则转译 sorts")
    void shouldTranslateSortsWithoutEntityClass() {
        PageQuery query = new PageQuery(1L, 10L)
                .addAsc("createTime")
                .addDesc("userName");

        Page<Object> page = PageUtil.toPage(query);

        assertNotNull(page.orders());
        assertEquals(2, page.orders().size());

        assertEquals("create_time", page.orders().get(0).getColumn());
        assertTrue(page.orders().get(0).isAsc());

        assertEquals("user_name", page.orders().get(1).getColumn());
        assertFalse(page.orders().get(1).isAsc());
    }

    @Test
    @DisplayName("toPage(query, entityClass): 严格依托实体类元数据白名单安全转译，非法字段平滑过滤")
    void shouldTranslateSortsWithEntityClassWhitelist() {
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "resource");
        assistant.setCurrentNamespace(TestUserMapper.class.getName());
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, TestUserEntity.class);

        PageQuery query = new PageQuery(1L, 10L)
                .addAsc("id")           // 主键映射
                .addDesc("nickName")     // 实体字段驼峰映射
                .addAsc("userAge")      // 实体字段驼峰映射
                .addAsc("notExistField") // 不存在的非法/未授权字段 -> 必须被平滑忽略
                .addDesc("passwordHash"); // 敏感列未声明探测 -> 必须被平滑忽略

        Page<TestUserEntity> page = PageUtil.toPage(query, TestUserEntity.class);

        assertNotNull(page.orders());
        assertEquals(3, page.orders().size());

        assertEquals("id", page.orders().get(0).getColumn());
        assertTrue(page.orders().get(0).isAsc());

        assertEquals("nick_name", page.orders().get(1).getColumn());
        assertFalse(page.orders().get(1).isAsc());

        assertEquals("user_age", page.orders().get(2).getColumn());
        assertTrue(page.orders().get(2).isAsc());
    }

    @Test
    @DisplayName("toPage(query): 遇到 null 排序项或非法字段时平滑过滤防护")
    void shouldSilentlyIgnoreIllegalOrNullSorts() {
        PageQuery query = new PageQuery(1L, 10L);
        query.getSorts().add(null);

        // 利用反射绕过 SortItem 自身 setter 注入非法字符，测试 PageUtil 的多重防线
        SortItem dirtyItem = new SortItem();
        try {
            java.lang.reflect.Field propField = SortItem.class.getDeclaredField("property");
            propField.setAccessible(true);
            propField.set(dirtyItem, "id; DROP TABLE user;");
        } catch (Exception e) {
            fail("反射设置字段失败: " + e.getMessage());
        }
        query.getSorts().add(dirtyItem);
        query.addAsc("validColumn");

        Page<Object> page = PageUtil.toPage(query);

        assertNotNull(page.orders());
        assertEquals(1, page.orders().size());
        assertEquals("valid_column", page.orders().get(0).getColumn());
        assertTrue(page.orders().get(0).isAsc());
    }
}
