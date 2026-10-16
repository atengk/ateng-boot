package io.github.atengk.boot.mybatisplus.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import io.github.atengk.boot.mybatisplus.annotation.FieldSensitive;
import io.github.atengk.boot.mybatisplus.enums.SensitiveType;

import java.io.IOException;

/**
 * 敏感数据出库动态脱敏 Jackson 序列化器
 * 基于 ContextualSerializer 动态感知字段级 @FieldSensitive 注解配置，在写出 JSON 时执行动态掩码
 * 绝不修改 Java 内存实体的字段值，彻底消除内存脏数据与误写回风险
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class SensitiveJsonSerializer extends JsonSerializer<String> implements ContextualSerializer {

    private final SensitiveType type;
    private final int startInclude;
    private final int endExclude;
    private final char maskChar;

    public SensitiveJsonSerializer() {
        this(SensitiveType.CUSTOM, 0, 0, '*');
    }

    public SensitiveJsonSerializer(SensitiveType type, int startInclude, int endExclude, char maskChar) {
        this.type = type != null ? type : SensitiveType.CUSTOM;
        this.startInclude = startInclude;
        this.endExclude = endExclude;
        this.maskChar = maskChar;
    }

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeString(mask(value));
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property) throws JsonMappingException {
        if (property == null) {
            return prov.findNullValueSerializer(null);
        }
        FieldSensitive sensitive = property.getAnnotation(FieldSensitive.class);
        if (sensitive == null) {
            sensitive = property.getContextAnnotation(FieldSensitive.class);
        }
        if (sensitive != null) {
            return new SensitiveJsonSerializer(
                    sensitive.value(),
                    sensitive.startInclude(),
                    sensitive.endExclude(),
                    sensitive.maskChar()
            );
        }
        return prov.findValueSerializer(property.getType(), property);
    }

    /**
     * 根据配置的脱敏类型调用 SensitiveType 执行动态掩码转换
     *
     * @param value 原始真实明文字符串
     * @return 掩码后的字符串
     */
    public String mask(String value) {
        return type.mask(value, startInclude, endExclude, maskChar);
    }
}
