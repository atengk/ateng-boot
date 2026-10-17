package io.github.atengk.boot.redis.serializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.constant.DatePattern;
import io.github.atengk.boot.core.util.JsonUtil;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * 安全的 Redis 纯净 JSON 序列化器工厂
 * 统一构建符合企业规范的 Jackson2JsonRedisSerializer，彻底杜绝 @class 污染
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class RedisSerializerFactory {

    public static final String DEFAULT_DATE_TIME_FORMAT = DatePattern.NORM_DATETIME_PATTERN;
    public static final String DEFAULT_DATE_FORMAT = DatePattern.NORM_DATE_PATTERN;
    public static final String DEFAULT_TIME_FORMAT = DatePattern.NORM_TIME_PATTERN;
    public static final String DEFAULT_TIME_ZONE = DatePattern.DEFAULT_TIME_ZONE;

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
        return JsonUtil.createLongToStringObjectMapper();
    }
}

