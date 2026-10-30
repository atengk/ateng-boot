package io.github.atengk.boot.openapi.converter;

import com.fasterxml.jackson.databind.JavaType;
import io.github.atengk.boot.core.annotation.FieldSensitive;
import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.enums.SensitiveType;
import io.github.atengk.boot.core.validation.ValidList;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 接口契约模型治理增强器
 * <p>
 * 基于 SpringDoc / Swagger {@link ModelConverter} 机制，在 OpenAPI AST 构建期对领域模型进行三维度强契约保真：
 * 1. <b>{@link IBaseEnum} 强类型契约保真</b>：自动探测通用枚举，根据真实 {@code code} 映射强类型 Schema（Integer/String）、注入 enum 取值、Markdown 选项字典与首项示例；
 * 2. <b>{@link ValidList} 批量入参契约保真</b>：拦截泛型包装器，100% 转换为原生 Array JSON Schema {@code [ { item } ]}，消除对象属性包装歧义；
 * 3. <b>{@link FieldSensitive} 敏感脱敏安全提示</b>：自动为脱敏字段注入掩码警示前缀（如 {@code [脱敏字段: 手机号码掩码]}）与示例脱敏数据；
 * 4. 实现 {@link DisposableBean}，容器销毁时对称注销全局静态表中的转换器实例，保障生命周期闭环。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
public class OpenApiModelConverter implements ModelConverter, DisposableBean {

    private record SensitiveMeta(String label, String sample) {}

    private record EnumMetadata(
            List<Object> enumCodes,
            Object firstExample,
            Class<?> codeType,
            String markdownDict
    ) {}

    private static final Map<SensitiveType, SensitiveMeta> SENSITIVE_META_MAP = Map.of(
            SensitiveType.CHINESE_NAME, new SensitiveMeta("中文姓名", "张*三"),
            SensitiveType.MOBILE_PHONE, new SensitiveMeta("手机号码", "138****5678"),
            SensitiveType.FIXED_PHONE, new SensitiveMeta("固定电话", "010-******78"),
            SensitiveType.ID_CARD, new SensitiveMeta("身份证号", "110101********2345"),
            SensitiveType.EMAIL, new SensitiveMeta("电子邮箱", "z******n@example.com"),
            SensitiveType.BANK_CARD, new SensitiveMeta("银行卡号", "622202*********0123"),
            SensitiveType.PASSWORD, new SensitiveMeta("密码", "******"),
            SensitiveType.CAR_LICENSE, new SensitiveMeta("车牌号码", "京A***88"),
            SensitiveType.ADDRESS, new SensitiveMeta("国内地址", "北京市海淀区*********")
    );

    public OpenApiModelConverter() {
        registerIfAbsent();
    }

    /**
     * 将当前实例注册到全局 ModelConverters 注册表（防重注册）
     */
    public final void registerIfAbsent() {
        List<ModelConverter> converters = ModelConverters.getInstance().getConverters();
        if (converters != null && !converters.contains(this)) {
            ModelConverters.getInstance().addConverter(this);
        }
    }

    @Override
    public void destroy() {
        ModelConverters.getInstance().removeConverter(this);
    }

    @Override
    public Schema<?> resolve(AnnotatedType aType, ModelConverterContext context, Iterator<ModelConverter> chain) {
        if (aType == null) {
            return null;
        }

        Class<?> rawClass = getRawClass(aType.getType());

        // 1. ValidList<E> 批量入参原生数组契约保真
        if (rawClass != null && ValidList.class.isAssignableFrom(rawClass)) {
            Type itemType = extractListItemType(aType.getType());
            Schema<?> itemSchema = (itemType != null && context != null)
                    ? context.resolve(new AnnotatedType(itemType).jsonViewAnnotation(aType.getJsonViewAnnotation()))
                    : new Schema<>().type("object");

            ArraySchema arraySchema = new ArraySchema();
            arraySchema.setItems(itemSchema);

            String desc = extractDescriptionFromAnnotations(aType.getCtxAnnotations());
            if (StringUtils.hasText(desc)) {
                arraySchema.setDescription(desc);
            }
            return arraySchema;
        }

        // 2. IBaseEnum 强类型 Schema 与选项字典构建
        if (rawClass != null && rawClass.isEnum() && IBaseEnum.class.isAssignableFrom(rawClass)) {
            return buildEnumSchema(rawClass, aType.getCtxAnnotations());
        }

        // 3. 委派默认转换链处理
        Schema<?> schema = (chain != null && chain.hasNext()) ? chain.next().resolve(aType, context, chain) : null;
        if (schema == null) {
            return null;
        }

        // 4. 属性级 @FieldSensitive 脱敏注解增强
        FieldSensitive sensitive = findAnnotation(aType.getCtxAnnotations(), FieldSensitive.class);
        if (sensitive != null) {
            applySensitiveBadge(schema, sensitive);
        }

        // 5. 复合实体模型属性递归保真增强（双层防线）
        if (rawClass != null && schema.getProperties() != null) {
            enhanceModelProperties(rawClass, schema.getProperties());
        }

        return schema;
    }

    /**
     * 构建强类型枚举 Schema
     */
    @SuppressWarnings("unchecked")
    private Schema<?> buildEnumSchema(Class<?> rawClass, Annotation[] ctxAnnotations) {
        Class<? extends IBaseEnum<?>> enumClass = (Class<? extends IBaseEnum<?>>) rawClass;
        IBaseEnum<?>[] enumConstants = enumClass.getEnumConstants();
        EnumMetadata metadata = resolveEnumMetadata(enumConstants);

        Schema<?> enumSchema;
        Class<?> codeType = metadata.codeType();

        if (Number.class.isAssignableFrom(codeType)) {
            IntegerSchema intSchema = new IntegerSchema();
            if (Long.class.isAssignableFrom(codeType)) {
                intSchema.setFormat("int64");
            } else {
                intSchema.setFormat("int32");
            }
            enumSchema = intSchema;
        } else if (Boolean.class.isAssignableFrom(codeType)) {
            enumSchema = new BooleanSchema();
        } else {
            enumSchema = new StringSchema();
        }

        setEnumValues(enumSchema, metadata.enumCodes());
        enumSchema.setExample(metadata.firstExample());

        String existingDesc = extractDescriptionFromAnnotations(ctxAnnotations);
        String dict = metadata.markdownDict();
        if (StringUtils.hasText(existingDesc)) {
            enumSchema.setDescription(existingDesc.trim() + "\n\n" + dict);
        } else {
            enumSchema.setDescription(dict);
        }

        return enumSchema;
    }

    /**
     * 对实体模型的所有声明字段进行枚举与脱敏增强
     */
    private void enhanceModelProperties(Class<?> rawClass, Map<String, Schema> properties) {
        for (Field field : getAllFields(rawClass)) {
            String fieldName = field.getName();
            Schema<?> propSchema = properties.get(fieldName);
            if (propSchema == null) {
                continue;
            }

            // 处理 @FieldSensitive 脱敏
            FieldSensitive sensitive = field.getAnnotation(FieldSensitive.class);
            if (sensitive != null) {
                applySensitiveBadge(propSchema, sensitive);
            }

            // 处理 IBaseEnum 枚举
            Class<?> fieldType = field.getType();
            if (fieldType.isEnum() && IBaseEnum.class.isAssignableFrom(fieldType)) {
                enhanceEnumPropertySchema(propSchema, fieldType);
            }
        }
    }

    /**
     * 对实体属性中的枚举 Schema 进行增强
     */
    @SuppressWarnings("unchecked")
    private void enhanceEnumPropertySchema(Schema<?> propSchema, Class<?> enumClass) {
        IBaseEnum<?>[] enumConstants = ((Class<? extends IBaseEnum<?>>) enumClass).getEnumConstants();
        if (enumConstants == null || enumConstants.length == 0) {
            return;
        }

        EnumMetadata metadata = resolveEnumMetadata(enumConstants);

        setEnumValues(propSchema, metadata.enumCodes());
        if (propSchema.getExample() == null) {
            propSchema.setExample(metadata.firstExample());
        }
        Class<?> codeType = metadata.codeType();
        if (Number.class.isAssignableFrom(codeType)) {
            propSchema.setType("integer");
            if (Long.class.isAssignableFrom(codeType)) {
                propSchema.setFormat("int64");
            } else {
                propSchema.setFormat("int32");
            }
        }

        String currentDesc = propSchema.getDescription();
        String dict = metadata.markdownDict();
        if (StringUtils.hasText(currentDesc)) {
            if (!currentDesc.contains(dict)) {
                propSchema.setDescription(currentDesc.trim() + "\n\n" + dict);
            }
        } else {
            propSchema.setDescription(dict);
        }
    }

    /**
     * 解析 IBaseEnum 数组中的取值清单、首项示例与 Markdown 字典
     */
    private EnumMetadata resolveEnumMetadata(IBaseEnum<?>[] enumConstants) {
        if (enumConstants == null || enumConstants.length == 0) {
            return new EnumMetadata(Collections.emptyList(), null, String.class, "");
        }
        List<Object> enumCodes = new ArrayList<>(enumConstants.length);
        Object firstExample = null;
        Class<?> codeType = String.class;

        StringBuilder descBuilder = new StringBuilder();
        descBuilder.append("**枚举选项**：\n");

        for (IBaseEnum<?> constant : enumConstants) {
            Object code = constant.getCode();
            if (code != null) {
                if (firstExample == null) {
                    firstExample = code;
                    codeType = code.getClass();
                }
                enumCodes.add(code);
            }
            String label = constant.getLabel();
            String enumName = ((Enum<?>) constant).name();
            descBuilder.append("- `").append(code).append("`: ")
                    .append(label != null ? label : "")
                    .append(" (").append(enumName).append(")\n");
        }
        return new EnumMetadata(enumCodes, firstExample, codeType, descBuilder.toString().trim());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void setEnumValues(Schema<?> schema, List<?> values) {
        if (schema != null) {
            ((Schema) schema).setEnum(values);
        }
    }

    /**
     * 为 Schema 属性追加脱敏警示标签与脱敏示例值
     */
    private void applySensitiveBadge(Schema<?> propSchema, FieldSensitive sensitiveAnn) {
        if (propSchema == null) {
            return;
        }
        SensitiveType type = (sensitiveAnn != null) ? sensitiveAnn.value() : SensitiveType.CUSTOM;
        SensitiveMeta meta = SENSITIVE_META_MAP.get(type);
        String label = (meta != null) ? meta.label : "通用";
        String sample = (meta != null) ? meta.sample : "******";

        if (type == SensitiveType.KEEP_HEAD_TAIL && sensitiveAnn != null) {
            label = "保留首尾(前" + sensitiveAnn.keepHead() + "后" + sensitiveAnn.keepTail() + ")";
        }

        String badge = "[脱敏字段: " + label + "掩码]";
        String currentDesc = propSchema.getDescription();
        if (!StringUtils.hasText(currentDesc)) {
            propSchema.setDescription(badge);
        } else if (!currentDesc.contains(badge)) {
            propSchema.setDescription(badge + " " + currentDesc);
        }

        if (propSchema.getExample() == null) {
            propSchema.setExample(sample);
        }
    }

    private String extractDescriptionFromAnnotations(Annotation[] annotations) {
        if (annotations != null) {
            for (Annotation ann : annotations) {
                if (ann instanceof io.swagger.v3.oas.annotations.media.Schema schemaAnn) {
                    if (StringUtils.hasText(schemaAnn.description())) {
                        return schemaAnn.description();
                    }
                }
            }
        }
        return null;
    }

    private Class<?> getRawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof JavaType javaType) {
            return javaType.getRawClass();
        }
        if (type instanceof ParameterizedType pType && pType.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        return null;
    }

    private Type extractListItemType(Type type) {
        if (type instanceof ParameterizedType pType) {
            Type[] args = pType.getActualTypeArguments();
            if (args != null && args.length > 0) {
                return args[0];
            }
        }
        if (type instanceof JavaType javaType) {
            if (javaType.hasGenericTypes()) {
                return javaType.getBindings().getBoundType(0);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private <A extends Annotation> A findAnnotation(Annotation[] annotations, Class<A> annotationClass) {
        if (annotations == null || annotations.length == 0) {
            return null;
        }
        for (Annotation ann : annotations) {
            if (annotationClass.isInstance(ann)) {
                return (A) ann;
            }
        }
        return null;
    }

    private List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }
}
