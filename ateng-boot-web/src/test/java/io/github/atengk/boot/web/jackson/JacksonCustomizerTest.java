package io.github.atengk.boot.web.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.web.properties.WebProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Jackson 定制器单元测试
 *
 * @author Ateng
 * @since 2026-10-15
 */
@DisplayName("Jackson 定制器测试")
class JacksonCustomizerTest {

    static class SampleModel {
        private Long snowflakeId;
        private long primitiveLong;
        private BigInteger bigInteger;
        private LocalDateTime dateTime;
        private LocalDate date;
        private LocalTime time;

        public Long getSnowflakeId() { return snowflakeId; }
        public void setSnowflakeId(Long snowflakeId) { this.snowflakeId = snowflakeId; }
        public long getPrimitiveLong() { return primitiveLong; }
        public void setPrimitiveLong(long primitiveLong) { this.primitiveLong = primitiveLong; }
        public BigInteger getBigInteger() { return bigInteger; }
        public void setBigInteger(BigInteger bigInteger) { this.bigInteger = bigInteger; }
        public LocalDateTime getDateTime() { return dateTime; }
        public void setDateTime(LocalDateTime dateTime) { this.dateTime = dateTime; }
        public LocalDate getDate() { return date; }
        public void setDate(LocalDate date) { this.date = date; }
        public LocalTime getTime() { return time; }
        public void setTime(LocalTime time) { this.time = time; }
    }

    private ObjectMapper createConfiguredMapper(boolean longToString) {
        WebProperties properties = new WebProperties();
        properties.setLongToString(longToString);

        JacksonCustomizer customizer = new JacksonCustomizer(properties);
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        customizer.customize(builder);
        return builder.build();
    }

    @Test
    @DisplayName("长整型与 BigInteger 正确转为 String 序列化防前端精度丢失")
    void shouldSerializeLongAndBigIntegerAsString() throws Exception {
        ObjectMapper mapper = createConfiguredMapper(true);

        SampleModel model = new SampleModel();
        model.setSnowflakeId(1846123456789012345L);
        model.setPrimitiveLong(987654321012345678L);
        model.setBigInteger(new BigInteger("123456789012345678901234567890"));

        String json = mapper.writeValueAsString(model);
        JsonNode node = mapper.readTree(json);

        assertTrue(node.get("snowflakeId").isTextual());
        assertEquals("1846123456789012345", node.get("snowflakeId").asText());

        assertTrue(node.get("primitiveLong").isTextual());
        assertEquals("987654321012345678", node.get("primitiveLong").asText());

        assertTrue(node.get("bigInteger").isTextual());
        assertEquals("123456789012345678901234567890", node.get("bigInteger").asText());
    }

    @Test
    @DisplayName("Java 8 日期时间全量标准化序列化与反序列化验证")
    void shouldFormatJava8DatesCorrectly() throws Exception {
        ObjectMapper mapper = createConfiguredMapper(true);

        SampleModel model = new SampleModel();
        model.setDateTime(LocalDateTime.of(2026, 10, 15, 14, 30, 45));
        model.setDate(LocalDate.of(2026, 10, 15));
        model.setTime(LocalTime.of(14, 30, 45));

        String json = mapper.writeValueAsString(model);
        JsonNode node = mapper.readTree(json);

        assertEquals("2026-10-15 14:30:45", node.get("dateTime").asText());
        assertEquals("2026-10-15", node.get("date").asText());
        assertEquals("14:30:45", node.get("time").asText());

        // 验证反序列化对称还原
        SampleModel deserialized = mapper.readValue(json, SampleModel.class);
        assertEquals(LocalDateTime.of(2026, 10, 15, 14, 30, 45), deserialized.getDateTime());
        assertEquals(LocalDate.of(2026, 10, 15), deserialized.getDate());
        assertEquals(LocalTime.of(14, 30, 45), deserialized.getTime());
    }

    @Test
    @DisplayName("配置关闭 longToString 时保留原始数值序列化")
    void shouldKeepNumericWhenLongToStringIsDisabled() throws Exception {
        ObjectMapper mapper = createConfiguredMapper(false);

        SampleModel model = new SampleModel();
        model.setSnowflakeId(123456789L);

        String json = mapper.writeValueAsString(model);
        JsonNode node = mapper.readTree(json);

        assertTrue(node.get("snowflakeId").isNumber());
        assertEquals(123456789L, node.get("snowflakeId").asLong());
    }
}
