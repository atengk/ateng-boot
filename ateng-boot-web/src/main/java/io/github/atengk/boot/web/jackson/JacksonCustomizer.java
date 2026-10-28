package io.github.atengk.boot.web.jackson;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.module.SimpleDeserializers;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import io.github.atengk.boot.core.constant.DatePattern;
import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.web.properties.WebProperties;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/**
 * Jackson 序列化与反序列化增强定制器
 * 实现长整型数值防前端失真转字符串序列化，以及 Java 8 全量日期东八区标准化格式控制
 *
 * @author Ateng
 * @since 2026-10-15
 */
public class JacksonCustomizer implements Jackson2ObjectMapperBuilderCustomizer {

    public static final String DEFAULT_DATE_TIME_FORMAT = DatePattern.NORM_DATETIME_PATTERN;
    public static final String DEFAULT_DATE_FORMAT = DatePattern.NORM_DATE_PATTERN;
    public static final String DEFAULT_TIME_FORMAT = DatePattern.NORM_TIME_PATTERN;
    public static final String DEFAULT_TIME_ZONE = DatePattern.DEFAULT_TIME_ZONE;

    private final WebProperties webProperties;

    public JacksonCustomizer(WebProperties webProperties) {
        this.webProperties = webProperties != null ? webProperties : new WebProperties();
    }

    @Override
    public void customize(Jackson2ObjectMapperBuilder builder) {
        // 1. 统一全局默认时区为东八区
        builder.timeZone(TimeZone.getTimeZone(DEFAULT_TIME_ZONE));

        // 2. 长整型防精度丢失定制（默认启用）
        if (webProperties.isLongToString()) {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
            builder.serializerByType(BigInteger.class, ToStringSerializer.instance);
        }

        // 3. Java 8 核心时间类型标准化格式化与解析
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DEFAULT_DATE_TIME_FORMAT);
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(DEFAULT_DATE_FORMAT);
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern(DEFAULT_TIME_FORMAT);

        builder.serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(dateTimeFormatter));
        builder.deserializerByType(LocalDateTime.class, new LocalDateTimeDeserializer(dateTimeFormatter));

        builder.serializerByType(LocalDate.class, new LocalDateSerializer(dateFormatter));
        builder.deserializerByType(LocalDate.class, new LocalDateDeserializer(dateFormatter));

        builder.serializerByType(LocalTime.class, new LocalTimeSerializer(timeFormatter));
        builder.deserializerByType(LocalTime.class, new LocalTimeDeserializer(timeFormatter));

        // 4. 强类型通用枚举 BaseEnumDeserializer 自动发现与注册
        SimpleModule baseEnumModule = new SimpleModule("AtengBaseEnumModule");
        baseEnumModule.setDeserializers(new SimpleDeserializers() {
            @Override
            public JsonDeserializer<?> findEnumDeserializer(Class<?> type,
                                                          DeserializationConfig config,
                                                          BeanDescription beanDesc) throws JsonMappingException {
                Class<?> targetClass = BaseEnumDeserializer.resolveTargetEnumClass(type);
                if (targetClass != null && IBaseEnum.class.isAssignableFrom(targetClass)) {
                    return new BaseEnumDeserializer(targetClass);
                }
                return super.findEnumDeserializer(type, config, beanDesc);
            }

            @Override
            public JsonDeserializer<?> findBeanDeserializer(JavaType type,
                                                          DeserializationConfig config,
                                                          BeanDescription beanDesc) throws JsonMappingException {
                Class<?> targetClass = BaseEnumDeserializer.resolveTargetEnumClass(type.getRawClass());
                if (targetClass != null && IBaseEnum.class.isAssignableFrom(targetClass) && targetClass.isEnum()) {
                    return new BaseEnumDeserializer(targetClass);
                }
                return super.findBeanDeserializer(type, config, beanDesc);
            }
        });
        builder.modulesToInstall(modules -> modules.add(baseEnumModule));
    }
}
