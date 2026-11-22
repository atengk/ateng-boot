package io.github.atengk.boot.core.jackson;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.io.IOException;

/**
 * 上下文感知的长整型转字符串 Jackson 序列化器
 * 默认将 Long、long 与 BigInteger 序列化为 JSON string（防前端 53 位精度丢失）；
 * 当字段或类型声明 {@code @JsonFormat(shape = JsonFormat.Shape.NUMBER)} 或 {@code NUMBER_INT} 时，
 * 自动让位给 {@link NumericLongSerializer} 输出 JSON number
 *
 * @author Ateng
 * @since 2026-11-22
 */
public class ContextualLongToStringSerializer extends StdSerializer<Object> implements ContextualSerializer {

    private static final long serialVersionUID = 1L;

    /**
     * 共享无状态单例实例
     */
    public static final ContextualLongToStringSerializer INSTANCE = new ContextualLongToStringSerializer();

    public ContextualLongToStringSerializer() {
        super(Object.class);
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        ToStringSerializer.instance.serialize(value, gen, serializers);
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property) throws JsonMappingException {
        if (property != null) {
            Class<?> rawType = property.getType() != null ? property.getType().getRawClass() : handledType();
            JsonFormat.Value format = findFormatOverrides(prov, property, rawType);
            if (format != null) {
                JsonFormat.Shape shape = format.getShape();
                if (shape == JsonFormat.Shape.NUMBER || shape == JsonFormat.Shape.NUMBER_INT) {
                    return NumericLongSerializer.INSTANCE;
                }
            }
        }
        return this;
    }
}
