package io.github.atengk.boot.redis.serializer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/**
 * 安全的 Redis 纯净 JSON 序列化器工厂
 * 统一构建符合企业规范的 Jackson2JsonRedisSerializer，彻底杜绝 @class 污染
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class RedisSerializerFactory {

    public static final String DEFAULT_DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
    public static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd";
    public static final String DEFAULT_TIME_FORMAT = "HH:mm:ss";
    public static final String DEFAULT_TIME_ZONE = "Asia/Shanghai";

    private RedisSerializerFactory() {
    }

    /**
     * 构建预配置好的纯净 JSON 序列化器
     *
     * @return Redis 序列化器
     */
    public static RedisSerializer<Object> createJsonSerializer() {
        ObjectMapper objectMapper = createSafeObjectMapper();
        return new Jackson2JsonRedisSerializer<>(objectMapper, Object.class);
    }

    /**
     * 创建安全、纯净无 @class 污染的 ObjectMapper
     *
     * @return 预配置的 ObjectMapper
     */
    public static ObjectMapper createSafeObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();

        // 1. 忽略未知属性，防反序列化由于新增字段报错
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // 2. 统一东八区时区
        objectMapper.setTimeZone(TimeZone.getTimeZone(DEFAULT_TIME_ZONE));

        // 3. 注册 Java 8 时间模块与格式化器
        JavaTimeModule javaTimeModule = new JavaTimeModule();
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DEFAULT_DATE_TIME_FORMAT);
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(DEFAULT_DATE_FORMAT);
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern(DEFAULT_TIME_FORMAT);

        javaTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(dateTimeFormatter));
        javaTimeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(dateTimeFormatter));
        javaTimeModule.addSerializer(LocalDate.class, new LocalDateSerializer(dateFormatter));
        javaTimeModule.addDeserializer(LocalDate.class, new LocalDateDeserializer(dateFormatter));
        javaTimeModule.addSerializer(LocalTime.class, new LocalTimeSerializer(timeFormatter));
        javaTimeModule.addDeserializer(LocalTime.class, new LocalTimeDeserializer(timeFormatter));

        // 4. 长整型转字符串防前端精度丢失
        javaTimeModule.addSerializer(Long.class, ToStringSerializer.instance);
        javaTimeModule.addSerializer(Long.TYPE, ToStringSerializer.instance);
        javaTimeModule.addSerializer(BigInteger.class, ToStringSerializer.instance);

        objectMapper.registerModule(javaTimeModule);

        // 严禁调用 activateDefaultTyping，杜绝 @class 注入
        return objectMapper;
    }
}
