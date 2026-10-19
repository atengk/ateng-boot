package io.github.atengk.boot.mybatisplus.dynamicquery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DynamicQueryParser 单元测试
 * 覆盖 URL Query 字符串解析、Map 参数解析、冒号语法、波浪线 between 切分、or_组号 分组及边界防卫
 *
 * @author Ateng
 * @since 2026-10-19
 */
@DisplayName("DynamicQueryParser URL Query 参数解析器单测")
class DynamicQueryParserTest {

    @Test
    @DisplayName("null 与空字符串防御：保底返回空请求")
    void shouldHandleEmptyQueries() {
        DynamicQueryRequest req1 = DynamicQueryParser.parse((String) null);
        assertNotNull(req1);
        assertEquals(1L, req1.getCurrent());
        assertEquals(10L, req1.getSize());
        assertTrue(req1.getConditions().isEmpty());

        DynamicQueryRequest req2 = DynamicQueryParser.parse("");
        assertTrue(req2.getConditions().isEmpty());

        DynamicQueryRequest req3 = DynamicQueryParser.parse("   ?   ");
        assertTrue(req3.getConditions().isEmpty());

        DynamicQueryRequest req4 = DynamicQueryParser.parse((Map<String, ?>) null);
        assertTrue(req4.getConditions().isEmpty());
    }

    @Test
    @DisplayName("分页与排序参数解析：current, size, sort=field:desc,id:asc")
    void shouldParsePaginationAndSortParameters() {
        String query = "current=2&size=50&sort=createTime:desc,id:asc";
        DynamicQueryRequest req = DynamicQueryParser.parse(query);

        assertEquals(2L, req.getCurrent());
        assertEquals(50L, req.getSize());
        assertEquals(2, req.getSorts().size());

        assertEquals("createTime", req.getSorts().get(0).getProperty());
        assertFalse(req.getSorts().get(0).isAsc());

        assertEquals("id", req.getSorts().get(1).getProperty());
        assertTrue(req.getSorts().get(1).isAsc());
    }

    @Test
    @DisplayName("分页候选名与非法非数字容错")
    void shouldTolerateInvalidPageParameters() {
        String query = "pageNum=abc&limit=-10&sort=name:invalidDir";
        DynamicQueryRequest req = DynamicQueryParser.parse(query);

        // 非法数字降级为默认值 1 和 10
        assertEquals(1L, req.getCurrent());
        assertEquals(10L, req.getSize());
        // 非 desc 默认视作 asc
        assertEquals(1, req.getSorts().size());
        assertEquals("name", req.getSorts().get(0).getProperty());
        assertTrue(req.getSorts().get(0).isAsc());
    }

    @Test
    @DisplayName("冒号操作符语法解析：field:operator=value")
    void shouldParseColonOperatorSyntax() {
        String query = "userName:like=admin&age:ge=18&score:lt=100.0&status:ne=0";
        DynamicQueryRequest req = DynamicQueryParser.parse(query);

        assertThat(req.getConditions()).hasSize(4);

        QueryCondition c1 = req.getConditions().get(0);
        assertEquals("userName", c1.getField());
        assertEquals(QueryOperator.LIKE, c1.getOperator());
        assertEquals("admin", c1.getValue());
        assertNull(c1.getOrGroup());

        QueryCondition c2 = req.getConditions().get(1);
        assertEquals("age", c2.getField());
        assertEquals(QueryOperator.GE, c2.getOperator());
        assertEquals("18", c2.getValue());

        QueryCondition c3 = req.getConditions().get(2);
        assertEquals("score", c3.getField());
        assertEquals(QueryOperator.LT, c3.getOperator());
        assertEquals("100.0", c3.getValue());

        QueryCondition c4 = req.getConditions().get(3);
        assertEquals("status", c4.getField());
        assertEquals(QueryOperator.NE, c4.getOperator());
        assertEquals("0", c4.getValue());
    }

    @Test
    @DisplayName("IN 与 NOT_IN 逗号分隔解析：status:in=1,2,3")
    void shouldParseInAndNotInConditions() {
        String query = "status:in=1,2,3&role:not_in=guest,visitor";
        DynamicQueryRequest req = DynamicQueryParser.parse(query);

        assertThat(req.getConditions()).hasSize(2);

        QueryCondition inCond = req.getConditions().get(0);
        assertEquals("status", inCond.getField());
        assertEquals(QueryOperator.IN, inCond.getOperator());
        assertThat(inCond.getValues()).containsExactly("1", "2", "3");

        QueryCondition notInCond = req.getConditions().get(1);
        assertEquals("role", notInCond.getField());
        assertEquals(QueryOperator.NOT_IN, notInCond.getOperator());
        assertThat(notInCond.getValues()).containsExactly("guest", "visitor");
    }

    @Test
    @DisplayName("BETWEEN 波浪线 ~ 分隔语法（防御时间串含空格与冒号）：createTime:between=start~end")
    void shouldParseBetweenWithTilde() {
        String query = "createTime:between=2026-01-01 00:00:00~2026-12-31 23:59:59&age:between=20,30";
        DynamicQueryRequest req = DynamicQueryParser.parse(query);

        assertThat(req.getConditions()).hasSize(2);

        QueryCondition timeCond = req.getConditions().get(0);
        assertEquals("createTime", timeCond.getField());
        assertEquals(QueryOperator.BETWEEN, timeCond.getOperator());
        assertThat(timeCond.getValues()).containsExactly("2026-01-01 00:00:00", "2026-12-31 23:59:59");

        QueryCondition ageCond = req.getConditions().get(1);
        assertEquals("age", ageCond.getField());
        assertEquals(QueryOperator.BETWEEN, ageCond.getOperator());
        assertThat(ageCond.getValues()).containsExactly("20", "30");
    }

    @Test
    @DisplayName("IS_NULL 与 IS_NOT_NULL 无值操作符解析")
    void shouldParseNullOperators() {
        String query = "email:isNull&phone:is_not_null=true";
        DynamicQueryRequest req = DynamicQueryParser.parse(query);

        assertThat(req.getConditions()).hasSize(2);
        assertEquals(QueryOperator.IS_NULL, req.getConditions().get(0).getOperator());
        assertEquals("email", req.getConditions().get(0).getField());

        assertEquals(QueryOperator.IS_NOT_NULL, req.getConditions().get(1).getOperator());
        assertEquals("phone", req.getConditions().get(1).getField());
    }

    @Test
    @DisplayName("or_组号: 作用域前缀分组解析：or_1:name:like=a&or_1:email:like=a")
    void shouldParseOrGroupPrefixes() {
        String query = "status=1&or_1:username:like=admin&or_1:email:like=admin&or_2:code=A&or_2:tag=VIP";
        DynamicQueryRequest req = DynamicQueryParser.parse(query);

        assertThat(req.getConditions()).hasSize(5);

        // 顶层 AND 条件
        QueryCondition c0 = req.getConditions().get(0);
        assertEquals("status", c0.getField());
        assertNull(c0.getOrGroup());

        // or_1 组
        QueryCondition c1 = req.getConditions().get(1);
        assertEquals("username", c1.getField());
        assertEquals("1", c1.getOrGroup());

        QueryCondition c2 = req.getConditions().get(2);
        assertEquals("email", c2.getField());
        assertEquals("1", c2.getOrGroup());

        // or_2 组
        QueryCondition c3 = req.getConditions().get(3);
        assertEquals("code", c3.getField());
        assertEquals("2", c3.getOrGroup());

        QueryCondition c4 = req.getConditions().get(4);
        assertEquals("tag", c4.getField());
        assertEquals("2", c4.getOrGroup());
    }

    @Test
    @DisplayName("URL 编码解码与特殊字符容错")
    void shouldDecodeUrlQueryString() {
        String query = "name%3Alike=%E5%BC%A0%E4%B8%89&age%3Agt=18";
        DynamicQueryRequest req = DynamicQueryParser.parse(query);

        assertThat(req.getConditions()).hasSize(2);
        assertEquals("name", req.getConditions().get(0).getField());
        assertEquals(QueryOperator.LIKE, req.getConditions().get(0).getOperator());
        assertEquals("张三", req.getConditions().get(0).getValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "name:unknownOp=123",       // 未知操作符
            ":=abc",                     // 无字段
            "id;drop:eq=1",              // 非法字段注入字符
            "createTime:between=onlyOne",// between 缺少截止边界
            "name:like=",                // 缺失参数值
            "&&&&&",                     // 空分隔符
            "=emptyKey"                  // 空 key
    })
    @DisplayName("边界防卫：对格式非法、未知操作符或缺失值优雅跳过，杜绝次生异常")
    void shouldDefensivelyIgnoreIllegalExpressions(String dirtyExpression) {
        assertDoesNotThrow(() -> {
            DynamicQueryRequest req = DynamicQueryParser.parse(dirtyExpression);
            assertNotNull(req);
        });
    }

    @Test
    @DisplayName("Map 参数解析：适配 Servlet Map<String, String[]> 与普通 Map<String, Object>")
    void shouldParseFromParameterMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("current", new String[]{"3"});
        map.put("size", 25);
        map.put("sort", new String[]{"age:asc", "createTime:desc"});
        map.put("username:like", new String[]{"bob"});
        map.put("status:in", List.of("1", "2"));
        map.put("or_groupA:remark:like", "vip");

        DynamicQueryRequest req = DynamicQueryParser.parse(map);

        assertEquals(3L, req.getCurrent());
        assertEquals(25L, req.getSize());
        assertEquals(2, req.getSorts().size());

        assertThat(req.getConditions()).hasSize(3);
        assertEquals("username", req.getConditions().get(0).getField());
        assertEquals("status", req.getConditions().get(1).getField());
        assertThat(req.getConditions().get(1).getValues()).containsExactly("1", "2");

        assertEquals("remark", req.getConditions().get(2).getField());
        assertEquals("groupA", req.getConditions().get(2).getOrGroup());
    }
}
