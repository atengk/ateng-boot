package io.github.atengk.boot.core.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.NumberSerializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;
import java.math.BigInteger;

/**
 * 数值保真长整型 Jackson 序列化器
 * 始终将 Long、long 及 BigInteger 等数值按 JSON number 原样输出，用于覆盖全局 longToString 策略
 *
 * @author Ateng
 * @since 2026-11-22
 */
public class NumericLongSerializer extends StdSerializer<Number> {

    private static final long serialVersionUID = 1L;

    /**
     * 共享无状态单例实例
     */
    public static final NumericLongSerializer INSTANCE = new NumericLongSerializer();

    /**
     * 公开无参构造方法，供 Jackson @JsonSerialize(using = NumericLongSerializer.class) 反射实例化
     */
    public NumericLongSerializer() {
        super(Number.class);
    }

    @Override
    public void serialize(Number value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            gen.writeNumber(value.longValue());
            return;
        }
        if (value instanceof BigInteger bigInteger) {
            gen.writeNumber(bigInteger);
            return;
        }
        NumberSerializer.instance.serialize(value, gen, serializers);
    }
}
