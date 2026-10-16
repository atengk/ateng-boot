package io.github.atengk.boot.redis.serializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 纯净安全序列化工厂单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("RedisSerializerFactory 单元测试")
class RedisSerializerFactoryTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class UserDemo {
        private Long id;
        private BigInteger bigId;
        private String username;
        private LocalDateTime createTime;
        private LocalDate birthDate;
        private LocalTime checkinTime;
    }

    @Test
    @DisplayName("验证序列化产物为纯净 JSON 且无 @class 多态元数据污染")
    void shouldSerializeToCleanJsonWithoutAtClass() {
        RedisSerializer<Object> serializer = RedisSerializerFactory.createJsonSerializer();

        UserDemo user = new UserDemo(
                1234567890123456789L,
                new BigInteger("9876543210987654321"),
                "Ateng",
                LocalDateTime.of(2026, 10, 16, 12, 30, 45),
                LocalDate.of(2026, 10, 16),
                LocalTime.of(12, 30, 45)
        );

        byte[] bytes = serializer.serialize(user);
        assertThat(bytes).isNotNull();

        String json = new String(bytes, StandardCharsets.UTF_8);

        // 1. 严格杜绝 @class 注入
        assertThat(json).doesNotContain("@class");

        // 2. Long 与 BigInteger 转换为字符串防前端失真
        assertThat(json).contains("\"id\":\"1234567890123456789\"");
        assertThat(json).contains("\"bigId\":\"9876543210987654321\"");

        // 3. Java 8 时间符合标准化格式
        assertThat(json).contains("\"createTime\":\"2026-10-16 12:30:45\"");
        assertThat(json).contains("\"birthDate\":\"2026-10-16\"");
        assertThat(json).contains("\"checkinTime\":\"12:30:45\"");
    }

    @Test
    @DisplayName("验证反序列化到具体对象成功且兼容未知属性")
    void shouldDeserializeSuccessfullyWithSafeObjectMapper() throws Exception {
        ObjectMapper objectMapper = RedisSerializerFactory.createSafeObjectMapper();

        String json = "{\"id\":\"1001\",\"bigId\":\"2002\",\"username\":\"Alice\",\"createTime\":\"2026-10-16 08:00:00\",\"birthDate\":\"2026-10-16\",\"checkinTime\":\"08:00:00\",\"extraField\":\"ignored\"}";

        UserDemo user = objectMapper.readValue(json, UserDemo.class);

        assertThat(user).isNotNull();
        assertThat(user.getId()).isEqualTo(1001L);
        assertThat(user.getBigId()).isEqualTo(new BigInteger("2002"));
        assertThat(user.getUsername()).isEqualTo("Alice");
        assertThat(user.getCreateTime()).isEqualTo(LocalDateTime.of(2026, 10, 16, 8, 0, 0));
        assertThat(user.getBirthDate()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(user.getCheckinTime()).isEqualTo(LocalTime.of(8, 0, 0));
    }
}
