package io.github.atengk.boot.mybatisplus.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生产加固型受管 JSON 字段类型处理器单元测试
 * 验证：
 * 1. LocalDateTime 与复杂 POJO 自动序列化/反序列化（JSR-310 默认支持）
 * 2. 集合与 Map 属性空值保底返回不可变空容器（防御 NPE）
 * 3. GenericJsonTypeHandler 泛型参数自适应推导与 TypeReference 精准反序列化
 * 4. JDBC 参数设置与结果集提取逻辑
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("AtengJacksonTypeHandler 核心加固与泛型空安全测试")
class AtengJacksonTypeHandlerTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DemoUserPojo {
        private String username;
        private LocalDateTime registerTime;
    }

    @Data
    public static class DemoContainer {
        private List<String> tags;
        private Set<String> roles;
        private Map<String, Object> extra;
        private DemoUserPojo user;
    }

    public static class TagListTypeHandler extends GenericJsonTypeHandler<List<String>> {
    }

    public static class UserPojoTypeHandler extends GenericJsonTypeHandler<DemoUserPojo> {
    }

    @Test
    @DisplayName("1. 验证 LocalDateTime 与复杂 POJO 序列化反序列化（JSR-310 默认兼容）")
    void shouldSerializeAndDeserializeLocalDateTimePojo() {
        AtengJacksonTypeHandler<DemoUserPojo> handler = new AtengJacksonTypeHandler<>(DemoUserPojo.class);

        LocalDateTime now = LocalDateTime.of(2026, 10, 27, 22, 30, 0);
        DemoUserPojo pojo = new DemoUserPojo("ateng", now);

        String json = handler.toJson(pojo);
        assertThat(json).contains("ateng").contains("2026-10-27");

        Object parsed = handler.parse(json);
        assertThat(parsed).isInstanceOf(DemoUserPojo.class);
        DemoUserPojo parsedPojo = (DemoUserPojo) parsed;
        assertThat(parsedPojo.getUsername()).isEqualTo("ateng");
        assertThat(parsedPojo.getRegisterTime()).isEqualTo(now);
    }

    @Test
    @DisplayName("2. 验证 List/Set/Map 属性遇到 NULL 或空白字符串时保底返回不可变空容器")
    void shouldReturnEmptyContainerOnNullOrBlank() throws NoSuchFieldException {
        Field tagsField = DemoContainer.class.getDeclaredField("tags");
        Field rolesField = DemoContainer.class.getDeclaredField("roles");
        Field extraField = DemoContainer.class.getDeclaredField("extra");
        Field userField = DemoContainer.class.getDeclaredField("user");

        AtengJacksonTypeHandler<?> listHandler = new AtengJacksonTypeHandler<>(List.class, tagsField);
        AtengJacksonTypeHandler<?> setHandler = new AtengJacksonTypeHandler<>(Set.class, rolesField);
        AtengJacksonTypeHandler<?> mapHandler = new AtengJacksonTypeHandler<>(Map.class, extraField);
        AtengJacksonTypeHandler<?> pojoHandler = new AtengJacksonTypeHandler<>(DemoUserPojo.class, userField);

        // 数据库为 null
        assertThat(listHandler.parse(null)).isEqualTo(Collections.emptyList());
        assertThat(setHandler.parse(null)).isEqualTo(Collections.emptySet());
        assertThat(mapHandler.parse(null)).isEqualTo(Collections.emptyMap());
        assertThat(pojoHandler.parse(null)).isNull();

        // 数据库为空白字符串
        assertThat(listHandler.parse("  ")).isEqualTo(Collections.emptyList());
        assertThat(setHandler.parse("")).isEqualTo(Collections.emptySet());
        assertThat(mapHandler.parse(" \t \n ")).isEqualTo(Collections.emptyMap());
        assertThat(pojoHandler.parse("")).isNull();
    }

    @Test
    @DisplayName("3. 验证 GenericJsonTypeHandler 泛型参数自适应向上溯源推导")
    void shouldResolveGenericTypeAutomaticallyViaSubclass() {
        TagListTypeHandler tagHandler = new TagListTypeHandler();

        assertThat(tagHandler.getFieldType().getTypeName()).contains("java.util.List<java.lang.String>");

        // 空值保底
        Object emptyResult = tagHandler.parse(null);
        assertThat(emptyResult).isEqualTo(Collections.emptyList());

        // 正常解析
        Object parsed = tagHandler.parse("[\"dev\", \"ops\", \"arch\"]");
        assertThat(parsed).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> list = (List<String>) parsed;
        assertThat(list).containsExactly("dev", "ops", "arch");
    }

    @Test
    @DisplayName("4. 验证 GenericJsonTypeHandler 基于 TypeReference 的深层复杂泛型反序列化")
    void shouldSupportTypeReferenceConstruction() {
        GenericJsonTypeHandler<Map<String, List<Integer>>> handler = new GenericJsonTypeHandler<>(
                new TypeReference<Map<String, List<Integer>>>() {}) {};

        String json = "{\"scores\": [100, 95, 98]}";
        Object parsed = handler.parse(json);
        assertThat(parsed).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, List<Integer>> map = (Map<String, List<Integer>>) parsed;
        assertThat(map.get("scores")).containsExactly(100, 95, 98);

        // 空值保底返回空 Map
        assertThat(handler.parse("")).isEqualTo(Collections.emptyMap());
    }

    @Test
    @DisplayName("5. 验证 JDBC ResultSet 与 PreparedStatement 接口读写交互")
    void shouldInteractWithJdbcStatementsCorrectly() throws SQLException {
        TagListTypeHandler handler = new TagListTypeHandler();

        // 1. setNonNullParameter
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        handler.setNonNullParameter(ps, 1, List.of("alpha", "beta"), JdbcType.VARCHAR);
        verify(ps).setString(1, "[\"alpha\",\"beta\"]");

        // 2. getNullableResult by column name (normal)
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(rs.getString("tags")).thenReturn("[\"alpha\",\"beta\"]");
        Object result = handler.getNullableResult(rs, "tags");
        assertThat(result).isEqualTo(List.of("alpha", "beta"));

        // 3. getNullableResult by column name (null/blank fallback)
        when(rs.getString("empty_tags")).thenReturn(null);
        Object emptyResult = handler.getNullableResult(rs, "empty_tags");
        assertThat(emptyResult).isEqualTo(Collections.emptyList());

        // 4. getNullableResult by column index
        when(rs.getString(2)).thenReturn("[\"gamma\"]");
        assertThat(handler.getNullableResult(rs, 2)).isEqualTo(List.of("gamma"));

        // 5. getNullableResult by CallableStatement
        CallableStatement cs = Mockito.mock(CallableStatement.class);
        when(cs.getString(3)).thenReturn(null);
        assertThat(handler.getNullableResult(cs, 3)).isEqualTo(Collections.emptyList());
    }
}
