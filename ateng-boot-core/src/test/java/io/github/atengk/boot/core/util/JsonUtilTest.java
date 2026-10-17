package io.github.atengk.boot.core.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonUtil 序列化与反序列化全场景单元测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("JsonUtil 序列化与反序列化全场景单元测试")
class JsonUtilTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class DemoUser {
        private Long id;
        private String name;
        private LocalDateTime createTime;
        private LocalDate birthday;
        private LocalTime loginTime;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class GenericPayload<T> {
        private String code;
        private T data;
    }

    @Test
    @DisplayName("测试基础对象序列化与 Java 8 日期格式化")
    void testToJsonString() {
        DemoUser user = new DemoUser(
                1234567890123456L,
                "Ateng",
                LocalDateTime.of(2026, 10, 17, 10, 30, 0),
                LocalDate.of(2026, 10, 17),
                LocalTime.of(10, 30, 0)
        );

        String json = JsonUtil.toJsonString(user);
        assertNotNull(json);
        // 默认保留 Long 原生数值
        assertTrue(json.contains("\"id\":1234567890123456"));
        assertTrue(json.contains("\"name\":\"Ateng\""));
        assertTrue(json.contains("\"createTime\":\"2026-10-17 10:30:00\""));
        assertTrue(json.contains("\"birthday\":\"2026-10-17\""));
        assertTrue(json.contains("\"loginTime\":\"10:30:00\""));
    }

    @Test
    @DisplayName("测试 Long 转 String 序列化（防前端失真）")
    void testToJsonStringWithLongToString() {
        DemoUser user = new DemoUser(
                9007199254740995L,
                "Ateng",
                LocalDateTime.of(2026, 10, 17, 10, 30, 0),
                LocalDate.of(2026, 10, 17),
                LocalTime.of(10, 30, 0)
        );

        String json = JsonUtil.toJsonStringWithLongToString(user);
        assertNotNull(json);
        // 验证 Long 序列化为 String
        assertTrue(json.contains("\"id\":\"9007199254740995\""));
    }

    @Test
    @DisplayName("测试美化 JSON 序列化")
    void testToPrettyJsonString() {
        DemoUser user = new DemoUser(1L, "Ateng", null, null, null);
        String pretty = JsonUtil.toPrettyJsonString(user);
        assertNotNull(pretty);
        assertTrue(pretty.contains("\n"));
        assertTrue(pretty.contains("  \"id\" : 1"));
    }

    @Test
    @DisplayName("测试反序列化为简单对象")
    void testParseObject() {
        String json = "{\"id\":1001,\"name\":\"Ateng\",\"createTime\":\"2026-10-17 10:30:00\",\"extraField\":\"ignored\"}";
        DemoUser user = JsonUtil.parseObject(json, DemoUser.class);
        assertNotNull(user);
        assertEquals(1001L, user.getId());
        assertEquals("Ateng", user.getName());
        assertEquals(LocalDateTime.of(2026, 10, 17, 10, 30, 0), user.getCreateTime());
    }

    @Test
    @DisplayName("测试基于 TypeReference 反序列化泛型对象")
    void testParseObjectTypeReference() {
        String json = "{\"code\":\"200\",\"data\":{\"id\":1002,\"name\":\"泛型测试\"}}";
        GenericPayload<DemoUser> payload = JsonUtil.parseObject(json, new TypeReference<GenericPayload<DemoUser>>() {});
        assertNotNull(payload);
        assertEquals("200", payload.getCode());
        assertNotNull(payload.getData());
        assertEquals(1002L, payload.getData().getId());
        assertEquals("泛型测试", payload.getData().getName());
    }

    @Test
    @DisplayName("测试反序列化为 List 列表")
    void testParseList() {
        String json = "[{\"id\":1,\"name\":\"User1\"},{\"id\":2,\"name\":\"User2\"}]";
        List<DemoUser> users = JsonUtil.parseList(json, DemoUser.class);
        assertNotNull(users);
        assertEquals(2, users.size());
        assertEquals(1L, users.get(0).getId());
        assertEquals("User2", users.get(1).getName());
    }

    @Test
    @DisplayName("测试反序列化为 Map")
    void testParseMap() {
        String json = "{\"key1\":\"value1\",\"key2\":\"value2\"}";
        Map<String, String> map = JsonUtil.parseMap(json, String.class, String.class);
        assertNotNull(map);
        assertEquals("value1", map.get("key1"));
        assertEquals("value2", map.get("key2"));

        Map<String, Object> genericMap = JsonUtil.parseMap(json);
        assertNotNull(genericMap);
        assertEquals("value1", genericMap.get("key1"));
    }

    @Test
    @DisplayName("测试 readTree 与 JsonNode 读取")
    void testReadTree() {
        String json = "{\"user\":{\"id\":888,\"active\":true}}";
        JsonNode root = JsonUtil.readTree(json);
        assertNotNull(root);
        assertEquals(888, root.path("user").path("id").asLong());
        assertTrue(root.path("user").path("active").asBoolean());
    }

    @Test
    @DisplayName("测试 convertValue 类型转换")
    void testConvertValue() {
        Map<String, Object> map = Map.of("id", 999L, "name", "Converter");
        DemoUser user = JsonUtil.convertValue(map, DemoUser.class);
        assertNotNull(user);
        assertEquals(999L, user.getId());
        assertEquals("Converter", user.getName());
    }

    @Test
    @DisplayName("测试边界空值安全与空集合处理")
    void testNullAndEmptySafety() {
        assertNull(JsonUtil.toJsonString(null));
        assertNull(JsonUtil.parseObject((String) null, DemoUser.class));
        assertNull(JsonUtil.parseObject("", DemoUser.class));
        assertNull(JsonUtil.parseObject("   ", DemoUser.class));
        assertTrue(JsonUtil.parseList(null, DemoUser.class).isEmpty());
        assertTrue(JsonUtil.parseList("", DemoUser.class).isEmpty());
        assertTrue(JsonUtil.parseMap(null).isEmpty());
        assertTrue(JsonUtil.parseMap("").isEmpty());
        assertNull(JsonUtil.readTree(null));
        assertNull(JsonUtil.readTree(""));
    }

    @Test
    @DisplayName("测试 init 静态钩子挂载定制 ObjectMapper")
    void testInitCustomObjectMapper() {
        ObjectMapper current = JsonUtil.getObjectMapper();
        assertNotNull(current);

        ObjectMapper custom = new ObjectMapper();
        JsonUtil.init(custom);
        assertSame(custom, JsonUtil.getObjectMapper());

        // 还原回默认实例
        JsonUtil.init(JsonUtil.createDefaultObjectMapper());
    }
}
