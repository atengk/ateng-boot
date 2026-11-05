package io.github.atengk.boot.ai.sanitizer;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.atengk.boot.core.util.JsonUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JsonSchemaGenerator 原生 JSON Schema 生成器单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
class JsonSchemaGeneratorTest {

    static class SimpleUserDto {
        private String username;
        private int age;
        private boolean active;
        private BigDecimal balance;
        private List<String> tags;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public BigDecimal getBalance() { return balance; }
        public void setBalance(BigDecimal balance) { this.balance = balance; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
    }

    enum OrderStatus {
        PENDING, PAID, SHIPPED
    }

    static class OrderDto {
        private String orderId;
        private OrderStatus status;

        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }
        public OrderStatus getStatus() { return status; }
        public void setStatus(OrderStatus status) { this.status = status; }
    }

    @Test
    @DisplayName("基本数据类型生成：String, int, boolean, BigDecimal, List 生成标准 Schema")
    void testBasicTypeSchemaGeneration() {
        String schemaJson = JsonSchemaGenerator.generateSchema(SimpleUserDto.class);

        assertThat(schemaJson).isNotEmpty();
        JsonNode root = JsonUtil.readTree(schemaJson);

        assertThat(root.get("type").asText()).isEqualTo("object");
        JsonNode properties = root.get("properties");
        assertThat(properties).isNotNull();

        assertThat(properties.get("username").get("type").asText()).isEqualTo("string");
        assertThat(properties.get("age").get("type").asText()).isEqualTo("integer");
        assertThat(properties.get("active").get("type").asText()).isEqualTo("boolean");
        assertThat(properties.get("balance").get("type").asText()).isEqualTo("number");

        JsonNode tagsNode = properties.get("tags");
        assertThat(tagsNode.get("type").asText()).isEqualTo("array");
        assertThat(tagsNode.get("items").get("type").asText()).isEqualTo("string");

        // 验证 required 包含核心字段
        JsonNode required = root.get("required");
        assertThat(required.isArray()).isTrue();
        List<String> reqFields = JsonUtil.parseList(required.toString(), String.class);
        assertThat(reqFields).contains("username", "age", "active");
    }

    @Test
    @DisplayName("枚举类型生成：生成带 enum 候选值的 String Schema")
    void testEnumSchemaGeneration() {
        String schemaJson = JsonSchemaGenerator.generateSchema(OrderDto.class);

        JsonNode root = JsonUtil.readTree(schemaJson);
        JsonNode statusNode = root.get("properties").get("status");
        assertThat(statusNode.get("type").asText()).isEqualTo("string");
        assertThat(statusNode.has("enum")).isTrue();

        List<String> enumValues = JsonUtil.parseList(statusNode.get("enum").toString(), String.class);
        assertThat(enumValues).containsExactly("PENDING", "PAID", "SHIPPED");
    }

    @Test
    @DisplayName("空值防卫：null 入参安全生成空对象 Schema，不崩溃")
    void testNullClassDefense() {
        String schemaJson = JsonSchemaGenerator.generateSchema(null);
        assertThat(schemaJson).isEqualTo("{\"type\":\"object\"}");
    }
}
